use anyhow::{Context, Result, bail};
use base64::{Engine, engine::general_purpose::STANDARD};
use p12_keystore::{
    Certificate, KeyStore, KeyStoreEntry, Pkcs12ImportPolicy, PrivateKey, PrivateKeyChain,
};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
    sync::Arc,
};
use windows::{
    Win32::{
        Foundation::{HLOCAL, LocalFree},
        Security::Cryptography::*,
    },
    core::PCWSTR,
};

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct Phone {
    pub name: String,
    pub token: String,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct Identity {
    pub id: String,
    pub certificate: String,
    pub phones: BTreeMap<String, Phone>,
    #[serde(skip)]
    path: PathBuf,
}

pub fn directory() -> PathBuf {
    PathBuf::from(std::env::var_os("LOCALAPPDATA").unwrap_or_default()).join("HyperAudio Bridge")
}
pub fn random_hex(bytes: usize) -> String {
    let mut data = vec![0; bytes];
    rand::thread_rng().fill_bytes(&mut data);
    data.iter().map(|b| format!("{b:02x}")).collect()
}
pub fn protect(data: &[u8], encrypt: bool) -> Result<Vec<u8>> {
    let input = CRYPT_INTEGER_BLOB {
        cbData: data.len().try_into()?,
        pbData: data.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB::default();
    unsafe {
        if encrypt {
            CryptProtectData(
                &input,
                PCWSTR::null(),
                None,
                None,
                None,
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )?;
        } else {
            CryptUnprotectData(
                &input,
                None,
                None,
                None,
                None,
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )?;
        }
        let bytes = std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec();
        let _ = LocalFree(Some(HLOCAL(output.pbData as _)));
        Ok(bytes)
    }
}
impl Identity {
    pub fn load() -> Result<Self> {
        Self::load_at(&directory().join("identity.dat"))
    }
    pub fn load_at(path: &Path) -> Result<Self> {
        if path.exists() {
            let mut identity: Self = serde_json::from_slice(&protect(&fs::read(path)?, false)?)
                .context("无法读取已有配对数据，原文件已保留")?;
            identity.path = path.into();
            return Ok(identity);
        }
        let rcgen::CertifiedKey { cert, key_pair } =
            rcgen::generate_simple_self_signed(vec!["HyperAudio Bridge".into()])?;
        let mut store = KeyStore::new();
        let chain = PrivateKeyChain::new(
            vec![1],
            PrivateKey::from_der(&key_pair.serialize_der())?,
            vec![Certificate::from_der(cert.der())?],
        );
        store.add_entry("HyperAudio", KeyStoreEntry::PrivateKeyChain(chain));
        let identity = Self {
            id: random_hex(16),
            certificate: STANDARD.encode(store.writer("").write()?),
            phones: BTreeMap::new(),
            path: path.into(),
        };
        identity.save_at(path)?;
        Ok(identity)
    }
    pub fn save(&self) -> Result<()> {
        self.save_at(&self.path)
    }
    pub fn save_at(&self, path: &Path) -> Result<()> {
        fs::create_dir_all(path.parent().context("无效的数据目录")?)?;
        let temp = path.with_extension("dat.tmp");
        let encrypted = protect(&serde_json::to_vec(self)?, true)?;
        use std::io::Write;
        let mut file = fs::File::create(&temp)?;
        file.write_all(&encrypted)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temp, path)?;
        Ok(())
    }
    pub fn tls(&self) -> Result<(Arc<rustls::ServerConfig>, String)> {
        let data = STANDARD.decode(&self.certificate)?;
        let store = match KeyStore::from_pkcs12(&data, "", Pkcs12ImportPolicy::Strict) {
            Ok(store) => store,
            // .NET's null-password PFX differs from an empty BMP password. Normalize only in memory.
            Err(_) => import_windows_pfx(&data)?,
        };
        let (_, chain) = store.private_key_chain().context("证书缺少私钥")?;
        if chain.certs().is_empty() {
            bail!("证书链为空");
        }
        let pin = Sha256::digest(chain.certs()[0].as_der())
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        let certs = chain
            .certs()
            .iter()
            .map(|c| rustls::pki_types::CertificateDer::from(c.as_der().to_vec()))
            .collect();
        let key = rustls::pki_types::PrivatePkcs8KeyDer::from(chain.key().as_der().to_vec()).into();
        let config = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(certs, key)?;
        Ok((Arc::new(config), pin))
    }
}

fn import_windows_pfx(data: &[u8]) -> Result<KeyStore> {
    let input = CRYPT_INTEGER_BLOB {
        cbData: data.len().try_into()?,
        pbData: data.as_ptr() as *mut u8,
    };
    unsafe {
        let store = PFXImportCertStore(
            &input,
            PCWSTR::null(),
            CRYPT_EXPORTABLE | PKCS12_NO_PERSIST_KEY,
        )?;
        let result = (|| -> Result<KeyStore> {
            let password = random_hex(24);
            let wide: Vec<u16> = password.encode_utf16().chain(Some(0)).collect();
            let mut output = CRYPT_INTEGER_BLOB::default();
            PFXExportCertStoreEx(
                store,
                &mut output,
                PCWSTR(wide.as_ptr()),
                std::ptr::null(),
                EXPORT_PRIVATE_KEYS,
            )?;
            let mut bytes = vec![0; output.cbData as usize];
            output.pbData = bytes.as_mut_ptr();
            PFXExportCertStoreEx(
                store,
                &mut output,
                PCWSTR(wide.as_ptr()),
                std::ptr::null(),
                EXPORT_PRIVATE_KEYS,
            )?;
            bytes.truncate(output.cbData as usize);
            Ok(KeyStore::from_pkcs12(
                &bytes,
                &password,
                Pkcs12ImportPolicy::Strict,
            )?)
        })();
        let _ = CertCloseStore(Some(store), 0);
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn legacy_dotnet_null_password_keeps_certificate() {
        let store =
            import_windows_pfx(include_bytes!("../tests/fixtures/legacy-null-password.pfx"))
                .unwrap();
        let (_, chain) = store.private_key_chain().unwrap();
        let actual: String = Sha256::digest(chain.certs()[0].as_der())
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        assert_eq!(
            actual,
            include_str!("../tests/fixtures/legacy-certificate.sha256").trim()
        );
        assert!(!chain.key().as_der().is_empty());
    }
    #[test]
    fn dpapi_and_identity_round_trip() {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let path = std::env::temp_dir().join(format!("hyperaudio-rust-{}.dat", random_hex(8)));
        let mut first = Identity::load_at(&path).unwrap();
        first.phones.insert(
            "test".into(),
            Phone {
                name: "phone".into(),
                token: random_hex(32),
            },
        );
        first.save_at(&path).unwrap();
        let second = Identity::load_at(&path).unwrap();
        assert_eq!(first.id, second.id);
        assert_eq!(first.tls().unwrap().1, second.tls().unwrap().1);
        assert_eq!(first.phones["test"].token, second.phones["test"].token);
        assert!(!fs::read(&path).unwrap().windows(5).any(|w| w == b"phone"));
        fs::remove_file(path).unwrap();
    }
}
