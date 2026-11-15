use anyhow::{Context, Result, bail};
use serde_json::Value;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const PORT: u16 = 38471;
pub async fn read_json<R: AsyncRead + Unpin>(reader: &mut R) -> Result<Value> {
    let mut bytes = Vec::new();
    for _ in 0..4096 {
        let b = reader.read_u8().await?;
        if b == b'\n' {
            return Ok(serde_json::from_slice(&bytes)?);
        }
        bytes.push(b);
    }
    bail!("握手消息过长")
}
pub async fn write_json<W: AsyncWrite + Unpin>(writer: &mut W, value: &Value) -> Result<()> {
    writer.write_all(&serde_json::to_vec(value)?).await?;
    writer.write_all(b"\n").await?;
    writer.flush().await?;
    Ok(())
}
pub fn required(value: &Value, name: &str, max: usize) -> Result<String> {
    let text = value
        .get(name)
        .and_then(Value::as_str)
        .context("缺少字段")?;
    if text.trim().is_empty() || text.len() > max {
        bail!("无效的 {name}");
    }
    Ok(text.into())
}
pub async fn read_packet<R: AsyncRead + Unpin>(reader: &mut R, heartbeat: bool) -> Result<Vec<u8>> {
    let size = reader.read_u32().await? as usize;
    if size > 65536 || (size == 0 && !heartbeat) {
        bail!("无效的音频帧长度");
    }
    let mut data = vec![0; size];
    reader.read_exact(&mut data).await?;
    Ok(data)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn frame_boundaries() {
        assert!(
            read_packet(&mut &b"\x00\x01\x00\x01"[..], false)
                .await
                .is_err()
        );
        assert!(
            read_packet(&mut &b"\x00\x00\x00\x00"[..], false)
                .await
                .is_err()
        );
        assert!(
            read_packet(&mut &b"\x00\x00\x00\x00"[..], true)
                .await
                .unwrap()
                .is_empty()
        );
        assert!(
            read_packet(&mut &b"\x00\x00\x00\x03\x01"[..], false)
                .await
                .is_err()
        );
        assert_eq!(
            read_packet(&mut &b"\x00\x00\x00\x02\x01\x02"[..], false)
                .await
                .unwrap(),
            vec![1, 2]
        );
    }
    #[tokio::test]
    async fn bounded_json() {
        assert!(read_json(&mut &b"bad\n"[..]).await.is_err());
        assert!(read_json(&mut vec![b'x'; 4096].as_slice()).await.is_err());
        assert_eq!(
            read_json(&mut &b"{\"version\":1}\n"[..]).await.unwrap()["version"],
            1
        );
    }
}
