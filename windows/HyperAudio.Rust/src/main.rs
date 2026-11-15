#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
mod audio;
mod backend;
mod identity;
mod protocol;
mod settings;
mod ui;
use windows::{
    Win32::{
        Foundation::{ERROR_ALREADY_EXISTS, GetLastError},
        System::Threading::CreateMutexW,
        UI::WindowsAndMessaging::{
            FindWindowW, IsIconic, MB_ICONERROR, MB_OK, MessageBoxW, SW_RESTORE, SW_SHOW,
            SetForegroundWindow, ShowWindow,
        },
    },
    core::w,
};
fn main() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    if std::env::args().any(|a| a == "--check-identity") {
        match identity::Identity::load()
            .and_then(|id| id.tls().map(|(_, pin)| (id.phones.len(), pin)))
        {
            Ok((phones, pin)) => println!("identity OK; pairedPhones={phones}; pin={pin}"),
            Err(e) => {
                eprintln!("{e:#}");
                std::process::exit(1);
            }
        }
        return;
    }
    let background = std::env::args().any(|a| a == "--background");
    unsafe {
        let _mutex = CreateMutexW(None, false, w!("Local\\HyperAudioBridge"));
        if GetLastError() == ERROR_ALREADY_EXISTS {
            if !background {
                // A shortcut must also restore an instance with its tray icon hidden.
                for _ in 0..20 {
                    if let Ok(hwnd) = FindWindowW(None, w!("HyperAudio Bridge")) {
                        let _ = ShowWindow(
                            hwnd,
                            if IsIconic(hwnd).as_bool() {
                                SW_RESTORE
                            } else {
                                SW_SHOW
                            },
                        );
                        let _ = SetForegroundWindow(hwnd);
                        break;
                    }
                    std::thread::sleep(std::time::Duration::from_millis(100));
                }
            }
            return;
        }
        match backend::Backend::start() {
            Ok(backend) => ui::run(backend, background),
            Err(e) => {
                let text: Vec<u16> = format!("{e:#}\0").encode_utf16().collect();
                let _ = MessageBoxW(
                    None,
                    windows::core::PCWSTR(text.as_ptr()),
                    w!("HyperAudio Bridge"),
                    MB_OK | MB_ICONERROR,
                );
            }
        }
    }
}
