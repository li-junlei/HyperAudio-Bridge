use anyhow::{Context, Result, bail};
use cpal::{
    FromSample, SizedSample,
    traits::{DeviceTrait, HostTrait, StreamTrait},
};
use opus_decoder::OpusDecoder;
use std::{
    collections::VecDeque,
    sync::{
        Arc, Condvar, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc,
    },
    thread,
    time::{Duration, Instant},
};

#[derive(Default)]
pub struct AudioStats {
    pub decoded: AtomicU64,
    pub rendered: AtomicU64,
    pub dropped_packets: AtomicU64,
    pub dropped_samples: AtomicU64,
    pub queued_packets: AtomicU64,
    pub buffered_samples: AtomicU64,
    pub max_decode_us: AtomicU64,
    pub max_queue_ms: AtomicU64,
    pub error: Mutex<Option<String>>,
}
// Keep the newest encoded packets even when decoding or device recovery is slow.
const MAX_PENDING_PACKETS: usize = 6;
const MAX_PACKET_AGE: Duration = Duration::from_millis(120);
const MAX_PCM_FRAMES: usize = 4800;
#[derive(Default)]
struct PacketQueue {
    packets: VecDeque<(Instant, Vec<u8>)>,
    discontinuity: bool,
    closed: bool,
}
impl PacketQueue {
    fn push(&mut self, packet: Vec<u8>, now: Instant) -> u64 {
        let mut dropped = 0;
        while self.packets.len() >= MAX_PENDING_PACKETS {
            self.packets.pop_front();
            dropped += 1;
        }
        self.discontinuity |= dropped > 0;
        self.packets.push_back((now, packet));
        dropped
    }
    fn pop(&mut self, now: Instant) -> (Option<(Instant, Vec<u8>, bool)>, u64) {
        let mut dropped = 0;
        while self
            .packets
            .front()
            .is_some_and(|(at, _)| now.saturating_duration_since(*at) > MAX_PACKET_AGE)
        {
            self.packets.pop_front();
            dropped += 1;
        }
        self.discontinuity |= dropped > 0;
        let packet = self
            .packets
            .pop_front()
            .map(|(at, bytes)| (at, bytes, std::mem::take(&mut self.discontinuity)));
        (packet, dropped)
    }
}
fn append_pcm(queue: &mut VecDeque<[f32; 2]>, samples: &[f32]) -> usize {
    queue.extend(samples.chunks_exact(2).map(|s| [s[0], s[1]]));
    let dropped = queue.len().saturating_sub(MAX_PCM_FRAMES);
    queue.drain(..dropped);
    dropped
}
pub struct Player {
    pending: Arc<(Mutex<PacketQueue>, Condvar)>,
    pub stats: Arc<AudioStats>,
}
impl Drop for Player {
    fn drop(&mut self) {
        let mut pending = self.pending.0.lock().unwrap();
        pending.closed = true;
        pending.packets.clear();
        self.pending.1.notify_one();
    }
}
impl Player {
    pub fn new() -> Result<Self> {
        let pending = Arc::new((Mutex::new(PacketQueue::default()), Condvar::new()));
        let worker_pending = pending.clone();
        let (ready_tx, ready_rx) = mpsc::sync_channel(1);
        let stats = Arc::new(AudioStats::default());
        let output_stats = stats.clone();
        thread::Builder::new()
            .name("audio-output".into())
            .spawn(move || {
                let mut decoder = OpusDecoder::new(48000, 2).expect("valid fixed Opus format");
                let mut samples = vec![0.; 5760 * 2];
                let host = cpal::default_host();
                let queue = Arc::new(Mutex::new(VecDeque::<[f32; 2]>::new()));
                let failed = Arc::new(AtomicBool::new(false));
                let mut current = String::new();
                let mut output = None;
                let mut next_check = std::time::Instant::now();
                let mut ready = Some(ready_tx);
                loop {
                    if std::time::Instant::now() >= next_check {
                        next_check = std::time::Instant::now() + Duration::from_secs(1);
                        let result = (|| -> Result<()> {
                            let device = host
                                .default_output_device()
                                .context("没有可用的音频输出设备")?;
                            let name = device.name()?;
                            if output.is_none()
                                || name != current
                                || failed.swap(false, Ordering::Relaxed)
                            {
                                output.take();
                                let config = device.default_output_config()?;
                                let stream = match config.sample_format() {
                                    cpal::SampleFormat::F32 => open::<f32>(
                                        &device,
                                        &config.into(),
                                        queue.clone(),
                                        output_stats.clone(),
                                        failed.clone(),
                                    )?,
                                    cpal::SampleFormat::I16 => open::<i16>(
                                        &device,
                                        &config.into(),
                                        queue.clone(),
                                        output_stats.clone(),
                                        failed.clone(),
                                    )?,
                                    cpal::SampleFormat::U16 => open::<u16>(
                                        &device,
                                        &config.into(),
                                        queue.clone(),
                                        output_stats.clone(),
                                        failed.clone(),
                                    )?,
                                    _ => bail!("输出设备格式不受支持"),
                                };
                                stream.play()?;
                                output = Some(stream);
                                current = name;
                                *output_stats.error.lock().unwrap() = None;
                            }
                            Ok(())
                        })();
                        if let Some(ready) = ready.take() {
                            let _ =
                                ready.send(result.as_ref().map(|_| ()).map_err(|e| e.to_string()));
                        }
                        if let Err(e) = result {
                            *output_stats.error.lock().unwrap() = Some(e.to_string());
                        }
                    }
                    let packet = {
                        let (mutex, wake) = &*worker_pending;
                        let mut pending = mutex.lock().unwrap();
                        if pending.packets.is_empty() && !pending.closed {
                            pending = wake
                                .wait_timeout(pending, Duration::from_millis(20))
                                .unwrap()
                                .0;
                        }
                        if pending.closed {
                            break;
                        }
                        let (packet, dropped) = pending.pop(Instant::now());
                        output_stats
                            .dropped_packets
                            .fetch_add(dropped, Ordering::Relaxed);
                        output_stats
                            .queued_packets
                            .store(pending.packets.len() as u64, Ordering::Relaxed);
                        packet
                    };
                    if let Some((received, packet, reset)) = packet {
                        output_stats
                            .max_queue_ms
                            .fetch_max(received.elapsed().as_millis() as u64, Ordering::Relaxed);
                        if reset {
                            decoder.reset();
                        }
                        let start = Instant::now();
                        let frames = match decoder.decode_float(&packet, &mut samples, false) {
                            Ok(frames) => frames,
                            Err(e) => {
                                *output_stats.error.lock().unwrap() =
                                    Some(format!("Opus 解码失败：{e}"));
                                worker_pending.0.lock().unwrap().closed = true;
                                break;
                            }
                        };
                        output_stats
                            .max_decode_us
                            .fetch_max(start.elapsed().as_micros() as u64, Ordering::Relaxed);
                        output_stats
                            .decoded
                            .fetch_add(frames as u64, Ordering::Relaxed);
                        if received.elapsed() > MAX_PACKET_AGE {
                            output_stats
                                .dropped_samples
                                .fetch_add(frames as u64, Ordering::Relaxed);
                            continue;
                        }
                        let mut pcm = queue.lock().unwrap();
                        let dropped = append_pcm(&mut pcm, &samples[..frames * 2]);
                        output_stats
                            .dropped_samples
                            .fetch_add(dropped as u64, Ordering::Relaxed);
                        output_stats
                            .buffered_samples
                            .store(pcm.len() as u64, Ordering::Relaxed);
                    }
                }
            })?;
        let player = Self { pending, stats };
        ready_rx
            .recv_timeout(Duration::from_secs(5))?
            .map_err(anyhow::Error::msg)?;
        Ok(player)
    }
    pub fn push(&mut self, packet: &[u8]) -> Result<()> {
        let mut pending = self.pending.0.lock().unwrap();
        if pending.closed {
            bail!("音频输出线程已退出");
        }
        let dropped = pending.push(packet.to_vec(), Instant::now());
        self.stats
            .dropped_packets
            .fetch_add(dropped, Ordering::Relaxed);
        self.stats
            .queued_packets
            .store(pending.packets.len() as u64, Ordering::Relaxed);
        self.pending.1.notify_one();
        Ok(())
    }
}
fn open<T: SizedSample + FromSample<f32>>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    queue: Arc<Mutex<VecDeque<[f32; 2]>>>,
    stats: Arc<AudioStats>,
    failed: Arc<AtomicBool>,
) -> Result<cpal::Stream> {
    let channels = config.channels as usize;
    let ratio = 48000. / config.sample_rate.0 as f64;
    let mut position = 0.;
    let stream = device.build_output_stream(
        config,
        move |data: &mut [T], _| {
            let mut frames = queue.lock().unwrap();
            for output in data.chunks_exact_mut(channels) {
                let first = frames.front().copied().unwrap_or([0., 0.]);
                let next = frames.get(1).copied().unwrap_or(first);
                let sample = [
                    first[0] + (next[0] - first[0]) * position as f32,
                    first[1] + (next[1] - first[1]) * position as f32,
                ];
                for (channel, out) in output.iter_mut().enumerate() {
                    let value = if channels == 1 {
                        (sample[0] + sample[1]) * 0.5
                    } else if channel < 2 {
                        sample[channel]
                    } else {
                        0.
                    };
                    *out = T::from_sample(value);
                }
                if frames.is_empty() {
                    position = 0.;
                    continue;
                }
                position += ratio;
                while position >= 1. {
                    if frames.pop_front().is_some() {
                        stats.rendered.fetch_add(1, Ordering::Relaxed);
                    }
                    position -= 1.;
                }
            }
            stats
                .buffered_samples
                .store(frames.len() as u64, Ordering::Relaxed);
        },
        move |_| {
            failed.store(true, Ordering::Relaxed);
        },
        None,
    )?;
    Ok(stream)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stalled_decoder_keeps_newest_packets_and_expires_old_audio() {
        let now = Instant::now();
        let mut queue = PacketQueue::default();
        let mut dropped = 0;
        // A 10-second burst must never become a 10-second playback queue.
        for index in 0..500u16 {
            dropped += queue.push(index.to_be_bytes().to_vec(), now);
        }
        assert_eq!(dropped, 494);
        let (packet, _) = queue.pop(now);
        let (_, bytes, reset) = packet.unwrap();
        assert_eq!(u16::from_be_bytes(bytes.try_into().unwrap()), 494);
        assert!(reset);
        let (packet, expired) = queue.pop(now + Duration::from_millis(121));
        assert!(packet.is_none());
        assert_eq!(expired, 5);
        queue.push(vec![42], now + Duration::from_secs(10));
        assert!(queue.pop(now + Duration::from_secs(10)).0.unwrap().2);
    }
    #[test]
    fn pcm_overflow_discards_oldest_and_retains_latest() {
        let mut queue = VecDeque::new();
        let samples: Vec<f32> = (0..6000).flat_map(|i| [i as f32; 2]).collect();
        assert_eq!(append_pcm(&mut queue, &samples), 1200);
        assert_eq!(queue.len(), MAX_PCM_FRAMES);
        assert_eq!(queue.front().unwrap()[0], 1200.);
        assert_eq!(queue.back().unwrap()[0], 5999.);
    }
    #[test]
    fn rust_decoder_matches_existing_receiver() {
        let packet = include_bytes!("../tests/fixtures/opus-20ms.bin");
        let reference = include_bytes!("../tests/fixtures/opus-20ms-reference.pcm");
        let expected: Vec<f64> = reference
            .chunks_exact(2)
            .map(|b| i16::from_le_bytes([b[0], b[1]]) as f64)
            .collect();
        let mut decoder = OpusDecoder::new(48000, 2).unwrap();
        let mut pcm = vec![0i16; 5760 * 2];
        let frames = decoder.decode(packet, &mut pcm, false).unwrap();
        assert_eq!(frames * 2, expected.len());
        let signal: f64 = expected.iter().map(|v| v * v).sum();
        let error: f64 = expected
            .iter()
            .zip(&pcm)
            .map(|(a, b)| (a - *b as f64).powi(2))
            .sum();
        assert!(signal > 1000.);
        assert!(
            (error / signal).sqrt() < 0.05,
            "decoder relative RMS error {}",
            (error / signal).sqrt()
        );
    }
}
