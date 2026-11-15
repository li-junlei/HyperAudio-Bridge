use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::path::Path;

const RUN_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";
const RUN_NAME: &str = "HyperAudioBridge";

#[derive(Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Preferences {
    pub show_tray: bool,
}
impl Default for Preferences {
    fn default() -> Self {
        Self { show_tray: true }
    }
}
impl Preferences {
    pub fn load() -> Result<Self> {
        let path = crate::identity::directory().join("preferences.json");
        match std::fs::read(path) {
            Ok(bytes) => Ok(serde_json::from_slice(&bytes)?),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Self::default()),
            Err(e) => Err(e.into()),
        }
    }
    pub fn save(&self) -> Result<()> {
        let dir = crate::identity::directory();
        std::fs::create_dir_all(&dir)?;
        std::fs::write(
            dir.join("preferences.json"),
            serde_json::to_vec_pretty(self)?,
        )?;
        Ok(())
    }
}
fn startup_command(exe: &Path) -> String {
    format!("\"{}\" --background", exe.display())
}
pub fn startup_enabled() -> bool {
    windows_registry::CURRENT_USER
        .open(RUN_KEY)
        .and_then(|key| key.get_string(RUN_NAME))
        .is_ok_and(|command| {
            std::env::current_exe().is_ok_and(|exe| command == startup_command(&exe))
        })
}
fn set_startup_at(key_path: &str, enabled: bool, exe: &Path) -> Result<()> {
    let key = windows_registry::CURRENT_USER.create(key_path)?;
    if enabled {
        key.set_string(RUN_NAME, startup_command(exe))?;
    } else {
        match key.remove_value(RUN_NAME) {
            Ok(()) => (),
            Err(e) if e.code().0 as u32 == 0x80070002 => (),
            Err(e) => return Err(e.into()),
        }
    }
    Ok(())
}
pub fn set_startup(enabled: bool) -> Result<()> {
    set_startup_at(RUN_KEY, enabled, &std::env::current_exe()?)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn tray_preferences_default_and_roundtrip() {
        assert!(serde_json::from_str::<Preferences>("{}").unwrap().show_tray);
        let prefs = Preferences { show_tray: false };
        let restored: Preferences =
            serde_json::from_slice(&serde_json::to_vec(&prefs).unwrap()).unwrap();
        assert!(!restored.show_tray);
    }
    #[test]
    fn startup_registry_roundtrip_isolated_key() {
        let path = format!(
            r"Software\HyperAudioBridgeTests\Startup-{}",
            std::process::id()
        );
        let exe = Path::new(r"C:\Programs With Spaces\HyperAudio Bridge\hyperaudio.exe");
        set_startup_at(&path, true, exe).unwrap();
        let key = windows_registry::CURRENT_USER.open(&path).unwrap();
        assert_eq!(
            key.get_string(RUN_NAME).unwrap(),
            r#""C:\Programs With Spaces\HyperAudio Bridge\hyperaudio.exe" --background"#
        );
        set_startup_at(&path, false, exe).unwrap();
        assert!(key.get_string(RUN_NAME).is_err());
        set_startup_at(&path, false, exe).unwrap();
        drop(key);
        windows_registry::CURRENT_USER.remove_tree(path).unwrap();
    }
}
