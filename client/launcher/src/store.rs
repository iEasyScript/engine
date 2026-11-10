//! Store sign-in, catalogue and paid-script installs. The token lives in ~/.projectx so the
//! injected engine, which decides whether a paid script may run, reads the same file.

use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

const DEFAULT_STORE: &str = "https://xclient.dev";

// Without it the site answers with OSRS, which is what every client from before the split means.
const GAME: &str = "rs3";

const PAIR_POLL_INTERVAL: Duration = Duration::from_secs(3);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

pub fn store_base() -> String {
    std::env::var("PROJECTX_STORE_URL").unwrap_or_else(|_| DEFAULT_STORE.to_string())
}

pub fn token_path() -> Result<PathBuf> {
    let dir = crate::plugins::home_dir()?.join(".projectx");
    fs::create_dir_all(&dir)?;
    Ok(dir.join("store-token.json"))
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StoredToken {
    pub token: String,
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub store: Option<String>,
}

pub fn read_token() -> Option<StoredToken> {
    let path = token_path().ok()?;
    let bytes = fs::read(path).ok()?;
    serde_json::from_slice(&bytes).ok()
}

pub fn write_token(token: &StoredToken) -> Result<()> {
    let path = token_path()?;
    let body = serde_json::to_vec_pretty(token)?;
    fs::write(&path, body).with_context(|| format!("Failed to write {}", path.display()))?;
    Ok(())
}

pub fn sign_out() -> Result<()> {
    let path = token_path()?;
    if path.exists() {
        fs::remove_file(&path)?;
    }
    Ok(())
}

pub fn device_label() -> String {
    let host = std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .ok()
        .filter(|h| !h.is_empty());
    match host {
        Some(host) => format!("Project X launcher ({})", host),
        None => "Project X launcher".to_string(),
    }
}

#[derive(Debug, Deserialize)]
pub struct PairStart {
    #[serde(rename = "userCode")]
    pub user_code: String,
    #[serde(rename = "deviceCode")]
    pub device_code: String,
    #[serde(rename = "verificationUrlComplete")]
    pub verification_url_complete: Option<String>,
    #[serde(rename = "verificationUrl")]
    pub verification_url: Option<String>,
}

impl PairStart {
    pub fn url(&self) -> String {
        self.verification_url_complete
            .clone()
            .or_else(|| self.verification_url.clone())
            .unwrap_or_else(|| format!("{}/pair", store_base()))
    }
}

pub async fn pair_start(client: &reqwest::Client, label: &str) -> Result<PairStart> {
    let url = format!("{}/api/v1/pair/start", store_base());
    let response = client
        .post(&url)
        .json(&serde_json::json!({ "label": label }))
        .timeout(REQUEST_TIMEOUT)
        .send()
        .await
        .with_context(|| format!("POST {} failed", url))?;
    if !response.status().is_success() {
        bail!("The store would not start a pairing ({})", response.status());
    }
    Ok(response.json().await.context("Could not read the pairing")?)
}

pub enum Poll {
    Pending,
    Approved(StoredToken),
    Expired,
    Refused(String),
}

pub async fn pair_poll(client: &reqwest::Client, device_code: &str) -> Result<Poll> {
    let url = format!("{}/api/v1/pair/poll", store_base());
    let response = client
        .post(&url)
        .json(&serde_json::json!({ "deviceCode": device_code }))
        .timeout(REQUEST_TIMEOUT)
        .send()
        .await
        .with_context(|| format!("POST {} failed", url))?;

    let status = response.status();
    if status == reqwest::StatusCode::ACCEPTED {
        return Ok(Poll::Pending);
    }
    if status == reqwest::StatusCode::GONE {
        return Ok(Poll::Expired);
    }
    if !status.is_success() {
        let body: serde_json::Value = response.json().await.unwrap_or_default();
        let message = body
            .get("message")
            .and_then(|m| m.as_str())
            .unwrap_or("The store refused this pairing")
            .to_string();
        return Ok(Poll::Refused(message));
    }

    let body: serde_json::Value = response.json().await.context("Could not read the token")?;
    let token = body
        .get("token")
        .and_then(|t| t.as_str())
        .context("The store approved the pairing but sent no token")?;
    Ok(Poll::Approved(StoredToken {
        token: token.to_string(),
        name: body
            .get("user")
            .and_then(|u| u.get("name"))
            .and_then(|n| n.as_str())
            .map(str::to_string),
        store: Some(store_base()),
    }))
}

pub async fn pair(
    client: &reqwest::Client,
    label: &str,
    show: impl Fn(&PairStart),
) -> Result<StoredToken> {
    let start = pair_start(client, label).await?;
    show(&start);
    loop {
        match pair_poll(client, &start.device_code).await? {
            Poll::Pending => tokio::time::sleep(PAIR_POLL_INTERVAL).await,
            Poll::Approved(token) => {
                write_token(&token)?;
                return Ok(token);
            }
            Poll::Expired => bail!("The pairing expired before it was approved. Try again."),
            Poll::Refused(message) => bail!(message),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct StorePlugin {
    #[serde(rename = "internalName")]
    pub internal_name: String,
    #[serde(default)]
    pub name: Option<String>,
    pub version: String,
    #[serde(rename = "downloadUrl")]
    pub download_url: Option<String>,
    #[serde(default)]
    pub paid: bool,
    #[serde(rename = "fileName")]
    pub file_name: Option<String>,
    #[serde(rename = "storeUrl")]
    pub store_url: Option<String>,
    #[serde(default)]
    pub sha256: Option<String>,
    /// Costs nothing; still claimed on the store before the client runs it.
    #[serde(default)]
    pub free: bool,
    /// The client only runs it for a player subscribed to it. A store older than free claims marks only `paid`.
    #[serde(default, rename = "requiresSubscription")]
    pub requires_subscription: bool,
    #[serde(default)]
    pub prices: Option<StorePrices>,
    #[serde(default, rename = "salePercent")]
    pub sale_percent: Option<u32>,
}

/// What each tier costs right now in X Tokens, sale included, as the store page quotes it.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct StorePrices {
    pub week: u32,
    pub fortnight: u32,
    pub month: u32,
}

impl StorePlugin {
    /// Listed in the Store tab: everything the client needs a subscription for.
    pub fn on_store(&self) -> bool {
        self.paid || self.requires_subscription
    }
}

pub async fn catalogue(client: &reqwest::Client) -> Result<Vec<StorePlugin>> {
    let url = format!("{}/api/v1/hub/plugins.json?game={}", store_base(), GAME);
    let mut request = client.get(&url).timeout(REQUEST_TIMEOUT);
    if let Some(stored) = read_token() {
        request = request.bearer_auth(stored.token);
    }
    let response = request
        .send()
        .await
        .with_context(|| format!("GET {} failed", url))?;
    if !response.status().is_success() {
        bail!("The store would not list its scripts ({})", response.status());
    }

    // A store older than the game split ignores ?game= and sends the OSRS list.
    let answered = response
        .headers()
        .get("x-projectx-game")
        .and_then(|v| v.to_str().ok())
        .map(str::to_string);
    if answered.as_deref() != Some(GAME) {
        bail!("This store does not serve RS3 scripts yet.");
    }

    Ok(response.json().await.context("Could not read the catalogue")?)
}

const ENCRYPTED_FORMAT: &str = "pxe1";
const ENCRYPTED_MAGIC: &[u8] = b"PXE1";
const ENCRYPTED_EXTENSION: &str = ".pxe";
const RECORD_SUFFIX: &str = ".json";
const FORMAT_HEADER: &str = "x-projectx-jar-format";
// Magic, a 12-byte nonce and a 16-byte tag.
const ENCRYPTED_MIN_LEN: usize = 4 + 12 + 16;

/// A paid script as the store sent it: a PXE1 blob only the engine can open, or a plain jar.
pub struct Downloaded {
    pub bytes: Vec<u8>,
    pub encrypted: bool,
}

fn is_encrypted_blob(bytes: &[u8]) -> bool {
    bytes.len() >= ENCRYPTED_MIN_LEN && bytes.starts_with(ENCRYPTED_MAGIC)
}

fn with_format(url: &str) -> String {
    let separator = if url.contains('?') { '&' } else { '?' };
    format!("{}{}format={}", url, separator, ENCRYPTED_FORMAT)
}

/// `encrypted` asks for the PXE1 build; the store sends free scripts plain whatever is asked.
pub async fn download(
    client: &reqwest::Client,
    plugin: &StorePlugin,
    encrypted: bool,
) -> Result<Downloaded> {
    let base = plugin.download_url.clone().unwrap_or_else(|| {
        format!(
            "{}/api/v1/hub/jars/{}/{}?game={}",
            store_base(),
            plugin.internal_name,
            plugin.version,
            GAME
        )
    });
    let url = if encrypted { with_format(&base) } else { base };
    let mut request = client.get(&url).timeout(crate::HTTP_DOWNLOAD_TIMEOUT);
    if let Some(stored) = read_token() {
        request = request.bearer_auth(stored.token);
    }
    let response = request
        .send()
        .await
        .with_context(|| format!("GET {} failed", url))?;

    if response.status() == reqwest::StatusCode::UNAUTHORIZED {
        bail!("Sign in to the store to download {}", plugin.internal_name);
    }
    if response.status() == reqwest::StatusCode::FORBIDDEN {
        bail!("You do not have access to {}", plugin.internal_name);
    }
    if !response.status().is_success() {
        bail!(
            "Downloading {} failed ({})",
            plugin.internal_name,
            response.status()
        );
    }
    let declared = response
        .headers()
        .get(FORMAT_HEADER)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|v| v.eq_ignore_ascii_case(ENCRYPTED_FORMAT));
    let bytes = response.bytes().await?.to_vec();
    let is_blob = is_encrypted_blob(&bytes);
    if declared && !is_blob {
        bail!("{} did not arrive as an encrypted build", plugin.internal_name);
    }
    if is_blob && !encrypted {
        bail!(
            "The store sent {} encrypted, which the installed engine cannot load",
            plugin.internal_name
        );
    }
    Ok(Downloaded { bytes, encrypted: is_blob })
}

#[derive(Debug, Clone, Deserialize)]
pub struct Entitlement {
    #[serde(rename = "internalName")]
    pub internal_name: String,
    #[serde(rename = "expiresAt")]
    pub expires_at: Option<String>,
    #[serde(default)]
    pub trial: bool,
    #[serde(default)]
    // A requested trial whose hour has not started; installable now.
    pub pending: bool,
}

#[derive(Debug, Deserialize)]
struct EntitlementsBody {
    #[serde(default)]
    entitlements: Vec<Entitlement>,
}

pub async fn entitlements(client: &reqwest::Client) -> Result<Vec<Entitlement>> {
    let Some(stored) = read_token() else {
        return Ok(Vec::new());
    };
    // peek: looking must not start a waiting trial's hour.
    let url = format!("{}/api/v1/entitlements?game={}&peek=1", store_base(), GAME);
    let response = client
        .get(&url)
        .bearer_auth(stored.token)
        .timeout(REQUEST_TIMEOUT)
        .send()
        .await
        .with_context(|| format!("GET {} failed", url))?;
    if response.status() == reqwest::StatusCode::UNAUTHORIZED {
        bail!("The store no longer accepts this launcher's sign-in. Sign in again.");
    }
    if !response.status().is_success() {
        bail!("The store would not say what you own ({})", response.status());
    }
    let body: EntitlementsBody = response.json().await.context("Could not read what you own")?;
    Ok(body.entitlements)
}

pub async fn install(client: &reqwest::Client, plugin: &StorePlugin) -> Result<PathBuf> {
    install_with(client, plugin, crate::engine::engine_reads_encrypted_scripts()).await
}

/// `encrypted` is whether the engine can load a `.pxe`; without it a paid script installs as a plain jar.
async fn install_with(
    client: &reqwest::Client,
    plugin: &StorePlugin,
    encrypted: bool,
) -> Result<PathBuf> {
    let fetched = download(client, plugin, encrypted).await?;
    let dir = crate::plugins::scripts_dir()?;

    let (file, kept) = if fetched.encrypted {
        let file = pxe_name(plugin);
        let record_file = record_name(&file);
        let record = EncryptedBuild {
            game: "RS3",
            internal_name: &plugin.internal_name,
            version: &plugin.version,
            sha256: plugin.sha256.as_deref().filter(|s| !s.is_empty()),
        };
        // The record goes first, so a `.pxe` is never on disk without the names its key and AAD need.
        write_atomically(&dir, &record_file, &serde_json::to_vec(&record)?)?;
        write_atomically(&dir, &file, &fetched.bytes)?;
        (file.clone(), vec![file, record_file])
    } else {
        if let Some(expected) = plugin.sha256.as_deref().filter(|s| !s.is_empty()) {
            let actual = crate::plugins::hex_digest(&fetched.bytes);
            if !actual.eq_ignore_ascii_case(expected) {
                bail!(
                    "Checksum mismatch for {} (expected {}, got {})",
                    plugin.internal_name,
                    expected,
                    actual
                );
            }
        }
        let file = jar_name(plugin);
        write_atomically(&dir, &file, &fetched.bytes)?;
        (file.clone(), vec![file])
    };

    remove_stale(&dir, plugin, &kept);
    Ok(dir.join(file))
}

/// Which script and release a `.pxe` holds, so the engine need not reverse the file name.
#[derive(Serialize)]
struct EncryptedBuild<'a> {
    game: &'a str,
    #[serde(rename = "internalName")]
    internal_name: &'a str,
    version: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    sha256: Option<&'a str>,
}

fn write_atomically(dir: &Path, file: &str, bytes: &[u8]) -> Result<()> {
    let target = dir.join(file);
    let temp = dir.join(format!(".{}.part", file));
    fs::write(&temp, bytes).with_context(|| format!("Failed to write {}", temp.display()))?;
    if let Err(e) = fs::rename(&temp, &target) {
        let _ = fs::remove_file(&temp);
        if e.kind() == std::io::ErrorKind::PermissionDenied {
            bail!(
                "Could not update {}: a running client is still using it. Close the RuneScape clients and try again.",
                file
            );
        }
        bail!("Failed to install {}: {}", target.display(), e);
    }
    Ok(())
}

// Two builds of one script put every class on the scan path twice.
fn remove_stale(dir: &Path, plugin: &StorePlugin, keep: &[String]) {
    for stale in stale_builds(dir, plugin, keep) {
        match fs::remove_file(dir.join(&stale)) {
            Ok(()) => log::info!("Removed superseded {}", stale),
            Err(e) => log::warn!("Could not remove superseded {}: {}", stale, e),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum InstallState {
    Missing,
    Current,
    Outdated,
}

pub fn install_state(plugin: &StorePlugin) -> InstallState {
    install_state_for(plugin, crate::engine::engine_reads_encrypted_scripts())
}

fn install_state_for(plugin: &StorePlugin, encrypted: bool) -> InstallState {
    let Ok(dir) = crate::plugins::scripts_dir() else {
        return InstallState::Missing;
    };
    if current_build(&dir, plugin, encrypted).is_some() {
        InstallState::Current
    } else if stale_builds(&dir, plugin, &[]).is_empty() {
        InstallState::Missing
    } else {
        InstallState::Outdated
    }
}

/// The files that make up the current release on disk, in the form the engine can load. A paid script's plain
/// jar does not count for an engine that reads `.pxe`, so it is replaced; a `.pxe` does not count for one that
/// cannot, so that engine gets a jar it can run.
fn current_build(dir: &Path, plugin: &StorePlugin, encrypted: bool) -> Option<Vec<String>> {
    let pxe = pxe_name(plugin);
    let record = record_name(&pxe);
    if encrypted && dir.join(&pxe).is_file() && dir.join(&record).is_file() {
        return Some(vec![pxe, record]);
    }
    let jar = jar_name(plugin);
    let plain_is_enough = !encrypted || plugin.free;
    (plain_is_enough && dir.join(&jar).is_file()).then(|| vec![jar])
}

// Versioned so an update is a new file, never a rewrite of a jar the engine may have scanned.
fn jar_name(plugin: &StorePlugin) -> String {
    format!("{}.jar", build_stem(plugin))
}

fn pxe_name(plugin: &StorePlugin) -> String {
    format!("{}{}", build_stem(plugin), ENCRYPTED_EXTENSION)
}

fn record_name(pxe: &str) -> String {
    format!("{}{}", pxe, RECORD_SUFFIX)
}

// Must match the engine's ScriptSync naming, so whichever of the two gets there first, the other sees it done.
fn build_stem(plugin: &StorePlugin) -> String {
    let safe = |s: &str| -> String {
        s.chars()
            .map(|c| if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '+' | '-') { c } else { '-' })
            .collect()
    };
    format!("{}-{}", safe(&plugin.internal_name), safe(&plugin.version).trim_start_matches('.'))
}

// A version starts with a digit, so `Agility-` does not claim `Agility-Course-1.0.jar`.
fn stale_builds(dir: &Path, plugin: &StorePlugin, keep: &[String]) -> Vec<String> {
    let prefix = format!("{}-", plugin.internal_name);
    let store_name = plugin.file_name.clone().unwrap_or_default();
    let record_extension = record_name(ENCRYPTED_EXTENSION);
    let Ok(entries) = fs::read_dir(dir) else {
        return Vec::new();
    };
    entries
        .filter_map(|e| e.ok())
        .filter_map(|e| e.file_name().into_string().ok())
        .filter(|f| !keep.contains(f))
        .filter(|f| f.ends_with(".jar") || f.ends_with(ENCRYPTED_EXTENSION) || f.ends_with(&record_extension))
        .filter(|f| {
            let versioned = f
                .strip_prefix(&prefix)
                .and_then(|rest| rest.chars().next())
                .is_some_and(|c| c.is_ascii_digit());
            versioned || (!store_name.is_empty() && *f == store_name)
        })
        .collect()
}

// Everything the account owns, at its current build; scripts whose access has lapsed are left alone.
pub async fn sync_owned(client: &reqwest::Client) -> Result<Vec<(String, Result<String>)>> {
    sync_owned_with(client, crate::engine::engine_reads_encrypted_scripts()).await
}

async fn sync_owned_with(
    client: &reqwest::Client,
    encrypted: bool,
) -> Result<Vec<(String, Result<String>)>> {
    if read_token().is_none() {
        return Ok(Vec::new());
    }
    let catalogue = catalogue(client).await?;
    let owned: std::collections::HashSet<String> = entitlements(client)
        .await?
        .into_iter()
        .map(|e| e.internal_name)
        .collect();
    let dir = crate::plugins::scripts_dir()?;

    let mut moved = Vec::new();
    for plugin in catalogue.iter().filter(|p| p.paid && owned.contains(&p.internal_name)) {
        let verb = match install_state_for(plugin, encrypted) {
            InstallState::Current => {
                // A plaintext jar an older launcher left beside the encrypted build would load twice.
                if let Some(kept) = current_build(&dir, plugin, encrypted) {
                    remove_stale(&dir, plugin, &kept);
                }
                continue;
            }
            InstallState::Missing => "Installed",
            InstallState::Outdated => "Updated",
        };
        let label = plugin.name.clone().unwrap_or_else(|| plugin.internal_name.clone());
        let outcome = install_with(client, plugin, encrypted)
            .await
            .map(|_| format!("{} {} {}", verb, label, plugin.version));
        moved.push((plugin.internal_name.clone(), outcome));
    }
    Ok(moved)
}

#[cfg(test)]
// The home-redirect guard is deliberately held across the awaits in a test: it is
// what stops two tests from racing on the process-wide home directory.
#[allow(clippy::await_holding_lock)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader, Read, Write};
    use std::net::{TcpListener, TcpStream};
    use std::sync::MutexGuard;

    struct Reply {
        status: &'static str,
        game_header: Option<&'static str>,
        format: Option<&'static str>,
        body: Vec<u8>,
    }

    /// Canned replies, served once each. Hand-rolled rather than mocked so a test
    /// exercises the real HTTP client, the real headers and the real JSON.
    struct FakeStore {
        port: u16,
        requests: std::sync::Arc<std::sync::Mutex<Vec<String>>>,
    }

    impl FakeStore {
        fn start(replies: Vec<Reply>) -> Self {
            Self::start_with(|_| replies)
        }

        fn start_with(build: impl FnOnce(u16) -> Vec<Reply>) -> Self {
            let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
            let port = listener.local_addr().unwrap().port();
            let replies = build(port);
            let requests = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
            let seen = std::sync::Arc::clone(&requests);

            std::thread::spawn(move || {
                for (reply, stream) in replies.into_iter().zip(listener.incoming()) {
                    let Ok(stream) = stream else { break };
                    Self::respond(stream, reply, &seen);
                }
            });

            FakeStore { port, requests }
        }

        /// The request lines served so far, e.g. `GET /jar?format=pxe1 HTTP/1.1`.
        fn requests(&self) -> Vec<String> {
            self.requests.lock().unwrap().clone()
        }

        fn respond(mut stream: TcpStream, reply: Reply, seen: &std::sync::Mutex<Vec<String>>) {
            let mut reader = BufReader::new(stream.try_clone().unwrap());
            let mut line = String::new();
            if reader.read_line(&mut line).is_err() {
                return;
            }
            seen.lock().unwrap().push(line.trim().to_string());
            // Drain the request so the client is not left writing into a closed pipe.
            let mut header = String::new();
            let mut length = 0usize;
            while reader.read_line(&mut header).map(|n| n > 2).unwrap_or(false) {
                if let Some(v) = header.to_lowercase().strip_prefix("content-length:") {
                    length = v.trim().parse().unwrap_or(0);
                }
                header.clear();
            }
            if length > 0 {
                let mut body = vec![0u8; length];
                let _ = reader.read_exact(&mut body);
            }

            let game = reply
                .game_header
                .map(|g| format!("X-ProjectX-Game: {}\r\n", g))
                .unwrap_or_default();
            let format = reply
                .format
                .map(|f| format!("X-ProjectX-Jar-Format: {}\r\n", f))
                .unwrap_or_default();
            let head = format!(
                "HTTP/1.1 {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\n{}{}Connection: close\r\n\r\n",
                reply.status,
                reply.body.len(),
                game,
                format,
            );
            let _ = stream.write_all(head.as_bytes());
            let _ = stream.write_all(&reply.body);
            let _ = stream.flush();
        }
    }

    fn json(status: &'static str, body: &str) -> Reply {
        Reply {
            status,
            game_header: None,
            format: None,
            body: body.as_bytes().to_vec(),
        }
    }

    fn bytes(body: Vec<u8>) -> Reply {
        Reply {
            status: "200 OK",
            game_header: None,
            format: None,
            body,
        }
    }

    /// What the store sends for a paid script asked for as pxe1: the blob, labelled.
    fn encrypted(body: Vec<u8>) -> Reply {
        Reply {
            status: "200 OK",
            game_header: None,
            format: Some("pxe1"),
            body,
        }
    }

    fn blob() -> Vec<u8> {
        let mut blob = b"PXE1".to_vec();
        blob.extend_from_slice(&[7u8; 12]);
        blob.extend_from_slice(b"ciphertext and its sixteen byte tag");
        blob
    }

    /// Point the home directory and the store at scratch values for one test.
    fn scratch(label: &str, port: u16) -> (MutexGuard<'static, ()>, PathBuf) {
        let guard = crate::plugins::tests::HOME_REDIRECT
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let home = std::env::temp_dir().join(format!("projectx-store-test-{}", label));
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&home).unwrap();
        *crate::plugins::tests::HOME_OVERRIDE
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(home.clone());
        std::env::set_var("PROJECTX_STORE_URL", format!("http://127.0.0.1:{}", port));
        (guard, home)
    }

    fn client() -> reqwest::Client {
        reqwest::Client::builder().build().unwrap()
    }

    fn gates(port: u16, sha: Option<String>) -> StorePlugin {
        StorePlugin {
            internal_name: "GatesOfElidinis".into(),
            name: None,
            version: "3.6.2".into(),
            download_url: Some(format!("http://127.0.0.1:{}/jar", port)),
            paid: true,
            file_name: None,
            store_url: None,
            sha256: sha,
            free: false,
            requires_subscription: true,
            prices: None,
            sale_percent: None,
        }
    }

    #[tokio::test]
    async fn a_pairing_waiting_for_approval_reports_itself_as_pending() {
        let server = FakeStore::start(vec![json("202 Accepted", r#"{"status":"pending"}"#)]);
        let (_g, _h) = scratch("pending", server.port);

        assert!(matches!(
            pair_poll(&client(), "device").await.unwrap(),
            Poll::Pending
        ));
    }

    #[tokio::test]
    async fn an_approved_pairing_hands_back_the_token_and_who_it_belongs_to() {
        let body = r#"{"status":"approved","token":"tok_abc","user":{"id":"u1","name":"Easy"}}"#;
        let server = FakeStore::start(vec![json("200 OK", body)]);
        let (_g, _h) = scratch("approved", server.port);

        let Poll::Approved(token) = pair_poll(&client(), "device").await.unwrap() else {
            panic!("expected an approved pairing");
        };
        assert_eq!(token.token, "tok_abc");
        assert_eq!(token.name.as_deref(), Some("Easy"));
    }

    /// The whole sign-in, as the Store panel runs it: ask for a pairing, show the
    /// user the code, wait out their approval, keep the token.
    #[tokio::test]
    async fn signing_in_shows_the_code_waits_for_approval_and_keeps_the_token() {
        let start = r#"{"userCode":"WXYZ-1234","deviceCode":"dev_secret",
            "verificationUrl":"https://xclient.dev/pair",
            "verificationUrlComplete":"https://xclient.dev/pair?code=WXYZ-1234",
            "pollSeconds":3}"#;
        let approved = r#"{"status":"approved","token":"tok_abc","user":{"name":"Easy"}}"#;
        let server = FakeStore::start(vec![
            json("200 OK", start),
            json("202 Accepted", r#"{"status":"pending"}"#),
            json("200 OK", approved),
        ]);
        let (_g, home) = scratch("pair", server.port);

        let shown = std::sync::Mutex::new(Vec::new());
        let token = pair(&client(), "Project X launcher (test)", |s| {
            shown.lock().unwrap().push((s.user_code.clone(), s.url()));
        })
        .await
        .unwrap();

        // The code goes up once, before any waiting, or the user has nothing to type.
        let shown = shown.into_inner().unwrap();
        assert_eq!(shown.len(), 1);
        assert_eq!(shown[0].0, "WXYZ-1234");
        assert_eq!(shown[0].1, "https://xclient.dev/pair?code=WXYZ-1234");

        assert_eq!(token.token, "tok_abc");
        // Persisted where the injected engine reads it, which is what signs
        // both the launcher and the running scripts in.
        assert_eq!(read_token().unwrap().token, "tok_abc");
        assert!(token_path().unwrap().ends_with("store-token.json"));
        let _ = fs::remove_dir_all(home);
    }

    #[tokio::test]
    async fn a_dead_pairing_is_expired_rather_than_an_error_to_retry() {
        let server = FakeStore::start(vec![json("410 Gone", r#"{"error":"expired"}"#)]);
        let (_g, _h) = scratch("expired", server.port);

        assert!(matches!(
            pair_poll(&client(), "device").await.unwrap(),
            Poll::Expired
        ));
    }

    #[tokio::test]
    async fn a_refusal_carries_the_reason_the_store_gave() {
        let body = r#"{"error":"banned","message":"This account cannot use the launcher."}"#;
        let server = FakeStore::start(vec![json("403 Forbidden", body)]);
        let (_g, _h) = scratch("refused", server.port);

        let Poll::Refused(message) = pair_poll(&client(), "device").await.unwrap() else {
            panic!("expected a refusal");
        };
        assert_eq!(message, "This account cannot use the launcher.");
    }

    /// Why the header exists: a store older than the two games ignores the game
    /// parameter and answers with the other one. Treating that as ours would list
    /// scripts this client cannot run.
    #[tokio::test]
    async fn a_catalogue_that_is_not_labelled_for_this_game_is_refused() {
        let plugins = r#"[{"internalName":"SomeRuneLitePlugin","version":"1.0","paid":true}]"#;
        let server = FakeStore::start(vec![json("200 OK", plugins)]);
        let (_g, _h) = scratch("unlabelled", server.port);

        let err = catalogue(&client()).await.unwrap_err();
        assert!(err.to_string().contains("does not serve RS3"), "{}", err);
    }

    #[tokio::test]
    async fn a_catalogue_labelled_for_the_other_game_is_refused_too() {
        let server = FakeStore::start(vec![Reply {
            status: "200 OK",
            game_header: Some("osrs"),
            format: None,
            body: b"[]".to_vec(),
        }]);
        let (_g, _h) = scratch("wrong-game", server.port);

        assert!(catalogue(&client()).await.is_err());
    }

    #[tokio::test]
    async fn a_catalogue_labelled_for_this_game_is_read() {
        let plugins = r#"[{"internalName":"GatesOfElidinis","name":"Gates of Elidinis",
            "version":"3.6.2","paid":true,"fileName":"gates-of-elidinis.jar",
            "storeUrl":"https://xclient.dev/rs3/store/gates-of-elidinis"}]"#;
        let server = FakeStore::start(vec![Reply {
            status: "200 OK",
            game_header: Some("rs3"),
            format: None,
            body: plugins.as_bytes().to_vec(),
        }]);
        let (_g, _h) = scratch("right-game", server.port);

        let list = catalogue(&client()).await.unwrap();
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].internal_name, "GatesOfElidinis");
        assert!(list[0].paid);
        assert_eq!(list[0].file_name.as_deref(), Some("gates-of-elidinis.jar"));
    }

    fn files_in(dir: &Path) -> Vec<String> {
        let mut names: Vec<String> = fs::read_dir(dir)
            .unwrap()
            .filter_map(|e| e.ok())
            .filter_map(|e| e.file_name().into_string().ok())
            .filter(|f| !f.starts_with('.'))
            .collect();
        names.sort();
        names
    }

    #[tokio::test]
    async fn a_paid_script_installs_encrypted_with_its_record_and_older_builds_go() {
        let server = FakeStore::start(vec![encrypted(blob())]);
        let (_g, home) = scratch("install", server.port);
        let dir = crate::plugins::scripts_dir().unwrap();
        fs::write(dir.join("GatesOfElidinis-3.6.1.jar"), b"old plaintext").unwrap();
        fs::write(dir.join("GatesOfElidinis-3.6.1.pxe"), b"old encrypted").unwrap();
        fs::write(dir.join("GatesOfElidinis-3.6.1.pxe.json"), b"{}").unwrap();
        fs::write(dir.join("GatesOfElidinis-3.6.2.jar"), b"plaintext of this very release").unwrap();

        let plugin = gates(server.port, Some("plaintextsha".into()));
        let written = install_with(&client(), &plugin, true).await.unwrap();

        assert!(written.ends_with("GatesOfElidinis-3.6.2.pxe"));
        assert_eq!(fs::read(&written).unwrap(), blob());
        let record: serde_json::Value =
            serde_json::from_slice(&fs::read(dir.join("GatesOfElidinis-3.6.2.pxe.json")).unwrap()).unwrap();
        assert_eq!(
            record,
            serde_json::json!({"game":"RS3","internalName":"GatesOfElidinis","version":"3.6.2","sha256":"plaintextsha"})
        );
        // Two builds of one script put every class on the engine scan path twice.
        assert_eq!(files_in(&dir), vec!["GatesOfElidinis-3.6.2.pxe", "GatesOfElidinis-3.6.2.pxe.json"]);
        assert_eq!(install_state_for(&plugin, true), InstallState::Current);
        assert_eq!(server.requests(), vec!["GET /jar?format=pxe1 HTTP/1.1"]);
        let _ = fs::remove_dir_all(home);
    }

    #[test]
    fn the_format_is_added_to_a_download_url_with_or_without_a_query() {
        assert_eq!(with_format("https://x/jars/A/1?game=rs3"), "https://x/jars/A/1?game=rs3&format=pxe1");
        assert_eq!(with_format("https://x/jars/A/1"), "https://x/jars/A/1?format=pxe1");
    }

    /// An engine from before encrypted scripts would find nothing it can load.
    #[tokio::test]
    async fn for_an_engine_that_cannot_read_them_a_paid_script_stays_a_plain_jar() {
        let jar = b"PK\x03\x04 pretend jar".to_vec();
        let server = FakeStore::start(vec![bytes(jar.clone())]);
        let (_g, home) = scratch("old-engine", server.port);
        let dir = crate::plugins::scripts_dir().unwrap();
        fs::write(dir.join("GatesOfElidinis-3.6.2.pxe"), b"encrypted").unwrap();
        fs::write(dir.join("GatesOfElidinis-3.6.2.pxe.json"), b"{}").unwrap();

        let plugin = gates(server.port, Some(crate::plugins::hex_digest(&jar)));
        assert_eq!(install_state_for(&plugin, false), InstallState::Outdated);
        let written = install_with(&client(), &plugin, false).await.unwrap();

        assert_eq!(fs::read(&written).unwrap(), jar);
        assert_eq!(files_in(&dir), vec!["GatesOfElidinis-3.6.2.jar"]);
        assert_eq!(server.requests(), vec!["GET /jar HTTP/1.1"]);
        let _ = fs::remove_dir_all(home);
    }

    #[tokio::test]
    async fn a_download_labelled_encrypted_that_is_not_is_not_installed() {
        let server = FakeStore::start(vec![encrypted(b"PK\x03\x04 a plain jar".to_vec())]);
        let (_g, home) = scratch("mislabelled", server.port);

        let plugin = gates(server.port, None);
        let err = install_with(&client(), &plugin, true).await.unwrap_err();

        assert!(err.to_string().contains("did not arrive as an encrypted build"), "{}", err);
        assert!(files_in(&crate::plugins::scripts_dir().unwrap()).is_empty());
        let _ = fs::remove_dir_all(home);
    }

    #[tokio::test]
    async fn a_store_that_sends_a_paid_script_plain_still_gets_it_installed() {
        let jar = b"PK\x03\x04 pretend jar".to_vec();
        let server = FakeStore::start(vec![bytes(jar.clone())]);
        let (_g, home) = scratch("plain-answer", server.port);
        let dir = crate::plugins::scripts_dir().unwrap();
        fs::write(dir.join("GatesOfElidinis-3.6.1.jar"), b"stale").unwrap();

        let plugin = gates(server.port, Some(crate::plugins::hex_digest(&jar)));
        let written = install_with(&client(), &plugin, true).await.unwrap();

        assert!(written.ends_with("GatesOfElidinis-3.6.2.jar"));
        assert_eq!(files_in(&dir), vec!["GatesOfElidinis-3.6.2.jar"]);
        let _ = fs::remove_dir_all(home);
    }

    #[tokio::test]
    async fn a_jar_that_does_not_match_its_checksum_is_not_installed() {
        let server = FakeStore::start(vec![bytes(b"not the jar you were promised".to_vec())]);
        let (_g, home) = scratch("checksum", server.port);

        let plugin = gates(server.port, Some(crate::plugins::hex_digest(b"the real jar")));
        let err = install_with(&client(), &plugin, false).await.unwrap_err();

        assert!(err.to_string().contains("Checksum mismatch"), "{}", err);
        assert_eq!(install_state_for(&plugin, false), InstallState::Missing);
        let _ = fs::remove_dir_all(home);
    }

    /// The store decides who may have a paid script, so its refusal is passed
    /// back as it stands rather than worked around.
    #[tokio::test]
    async fn a_script_the_account_does_not_own_reports_the_refusal() {
        let server = FakeStore::start(vec![json("403 Forbidden", r#"{"error":"not_entitled"}"#)]);
        let (_g, _h) = scratch("forbidden", server.port);

        let err = download(&client(), &gates(server.port, None), true).await.err().unwrap();
        assert!(err.to_string().contains("do not have access"), "{}", err);
    }

    #[tokio::test]
    async fn signing_out_forgets_the_token_on_this_machine() {
        let server = FakeStore::start(vec![]);
        let (_g, home) = scratch("signout", server.port);

        write_token(&StoredToken {
            token: "tok_abc".into(),
            name: Some("Easy".into()),
            store: None,
        })
        .unwrap();
        assert!(read_token().is_some());

        sign_out().unwrap();
        assert!(read_token().is_none());
        let _ = fs::remove_dir_all(home);
    }

    /// An update must be a new file, never a rewrite of one the engine may have
    /// scanned, so the version is in the name whatever the store suggests.
    #[test]
    fn the_jar_name_always_carries_the_version() {
        let mut plugin = gates(0, None);
        plugin.file_name = Some("gates-of-elidinis.jar".into());
        assert_eq!(jar_name(&plugin), "GatesOfElidinis-3.6.2.jar");

        plugin.file_name = None;
        assert_eq!(jar_name(&plugin), "GatesOfElidinis-3.6.2.jar");
        assert_eq!(pxe_name(&plugin), "GatesOfElidinis-3.6.2.pxe");
        assert_eq!(record_name(&pxe_name(&plugin)), "GatesOfElidinis-3.6.2.pxe.json");
    }

    #[test]
    fn a_hostile_name_or_version_cannot_leave_the_scripts_folder() {
        let mut plugin = gates(0, None);
        plugin.internal_name = "../../evil".into();
        plugin.version = "../1.0".into();
        for name in [jar_name(&plugin), pxe_name(&plugin)] {
            assert!(!name.contains('/') && !name.contains('\\'), "{}", name);
        }
    }

    #[test]
    fn a_script_whose_name_merely_begins_the_same_is_not_an_older_build() {
        let (_g, home) = scratch("prefix", 0);
        let dir = crate::plugins::scripts_dir().unwrap();
        fs::write(dir.join("Agility-Course-1.0.0.jar"), b"another script").unwrap();
        fs::write(dir.join("Agility-Course-1.0.0.pxe"), b"another script").unwrap();
        fs::write(dir.join("Agility-Course-1.0.0.pxe.json"), b"{}").unwrap();

        let mut agility = gates(0, None);
        agility.internal_name = "Agility".into();
        agility.version = "2.0.0".into();

        assert_eq!(install_state_for(&agility, true), InstallState::Missing);
        assert!(stale_builds(&dir, &agility, &[]).is_empty());
        let _ = fs::remove_dir_all(home);
    }

    #[test]
    fn install_state_tells_missing_from_current_from_outdated() {
        let (_g, home) = scratch("states", 0);
        let dir = crate::plugins::scripts_dir().unwrap();
        let plugin = gates(0, None);

        assert_eq!(install_state_for(&plugin, true), InstallState::Missing);

        fs::write(dir.join("GatesOfElidinis-3.6.1.pxe"), b"old").unwrap();
        assert_eq!(install_state_for(&plugin, true), InstallState::Outdated);

        // A plain jar of a paid script is replaced for an engine that reads encrypted builds.
        fs::write(dir.join("GatesOfElidinis-3.6.2.jar"), b"new, plain").unwrap();
        assert_eq!(install_state_for(&plugin, true), InstallState::Outdated);
        assert_eq!(install_state_for(&plugin, false), InstallState::Current);

        fs::write(dir.join("GatesOfElidinis-3.6.2.pxe"), b"new").unwrap();
        assert_eq!(install_state_for(&plugin, true), InstallState::Outdated, "no record yet");
        fs::write(dir.join("GatesOfElidinis-3.6.2.pxe.json"), b"{}").unwrap();
        assert_eq!(install_state_for(&plugin, true), InstallState::Current);
        let _ = fs::remove_dir_all(home);
    }

    #[test]
    fn a_free_scripts_plain_jar_is_current_either_way() {
        let (_g, home) = scratch("free-plain", 0);
        let dir = crate::plugins::scripts_dir().unwrap();
        let mut plugin = gates(0, None);
        plugin.free = true;

        fs::write(dir.join("GatesOfElidinis-3.6.2.jar"), b"free").unwrap();
        assert_eq!(install_state_for(&plugin, true), InstallState::Current);
        let _ = fs::remove_dir_all(home);
    }

    #[tokio::test]
    async fn installing_clears_out_a_build_saved_under_the_old_unversioned_name() {
        let server = FakeStore::start(vec![encrypted(blob())]);
        let (_g, home) = scratch("legacy-name", server.port);
        let dir = crate::plugins::scripts_dir().unwrap();
        fs::write(dir.join("gates-of-elidinis.jar"), b"from an older launcher").unwrap();

        let mut plugin = gates(server.port, None);
        plugin.file_name = Some("gates-of-elidinis.jar".into());
        install_with(&client(), &plugin, true).await.unwrap();

        assert_eq!(files_in(&dir), vec!["GatesOfElidinis-3.6.2.pxe", "GatesOfElidinis-3.6.2.pxe.json"]);
        let _ = fs::remove_dir_all(home);
    }

    /// The launcher looks; looking must not start a trial's hour, and a waiting
    /// trial has to come back as something it can install.
    #[tokio::test]
    async fn the_launcher_peeks_and_reads_a_waiting_trial() {
        let body = r#"{"entitlements":[{"internalName":"GatesOfElidinis","expiresAt":null,
            "daysRemaining":0,"trial":true,"pending":true}]}"#;
        let server = FakeStore::start(vec![json("200 OK", body)]);
        let (_g, home) = scratch("peek", server.port);
        write_token(&StoredToken { token: "tok".into(), name: None, store: None }).unwrap();

        let list = entitlements(&client()).await.unwrap();
        assert_eq!(list.len(), 1);
        assert!(list[0].trial && list[0].pending);
        assert!(list[0].expires_at.is_none());
        let _ = fs::remove_dir_all(home);
    }

    fn owned_gates_store(port: u16, sha: &str, download: Reply) -> Vec<Reply> {
        let catalogue = format!(
            r#"[{{"internalName":"GatesOfElidinis","name":"Gates of Elidinis","version":"3.6.2",
                "paid":true,"sha256":"{sha}","downloadUrl":"http://127.0.0.1:{port}/jar?game=rs3"}}]"#
        );
        vec![
            Reply { status: "200 OK", game_header: Some("rs3"), format: None, body: catalogue.into_bytes() },
            json("200 OK", r#"{"entitlements":[{"internalName":"GatesOfElidinis","trial":false}]}"#),
            download,
        ]
    }

    #[tokio::test]
    async fn an_owned_script_that_is_out_of_date_is_brought_up_to_date() {
        let server = FakeStore::start_with(|port| owned_gates_store(port, "sha", encrypted(blob())));
        let (_g, home) = scratch("auto-update", server.port);
        write_token(&StoredToken { token: "tok".into(), name: None, store: None }).unwrap();
        let dir = crate::plugins::scripts_dir().unwrap();
        fs::write(dir.join("GatesOfElidinis-3.6.1.jar"), b"old build").unwrap();

        let moved = sync_owned_with(&client(), true).await.unwrap();

        assert_eq!(moved.len(), 1);
        assert_eq!(moved[0].1.as_ref().unwrap(), "Updated Gates of Elidinis 3.6.2");
        assert_eq!(fs::read(dir.join("GatesOfElidinis-3.6.2.pxe")).unwrap(), blob());
        assert_eq!(files_in(&dir), vec!["GatesOfElidinis-3.6.2.pxe", "GatesOfElidinis-3.6.2.pxe.json"]);
        assert_eq!(server.requests()[2], "GET /jar?game=rs3&format=pxe1 HTTP/1.1");
        let _ = fs::remove_dir_all(home);
    }

    /// Buying a script is enough: it arrives without the player pressing Install.
    #[tokio::test]
    async fn a_bought_script_that_was_never_installed_is_installed() {
        let server = FakeStore::start_with(|port| owned_gates_store(port, "sha", encrypted(blob())));
        let (_g, home) = scratch("bought", server.port);
        write_token(&StoredToken { token: "tok".into(), name: None, store: None }).unwrap();

        let moved = sync_owned_with(&client(), true).await.unwrap();

        assert_eq!(moved.len(), 1);
        assert_eq!(moved[0].1.as_ref().unwrap(), "Installed Gates of Elidinis 3.6.2");
        let dir = crate::plugins::scripts_dir().unwrap();
        assert_eq!(fs::read(dir.join("GatesOfElidinis-3.6.2.pxe")).unwrap(), blob());
        let _ = fs::remove_dir_all(home);
    }

    #[tokio::test]
    async fn an_engine_that_cannot_read_encrypted_scripts_is_synced_plain_jars() {
        let jar = b"PK\x03\x04 version 3.6.2".to_vec();
        let sha = crate::plugins::hex_digest(&jar);
        let server = FakeStore::start_with(|port| owned_gates_store(port, &sha, bytes(jar.clone())));
        let (_g, home) = scratch("old-engine-sync", server.port);
        write_token(&StoredToken { token: "tok".into(), name: None, store: None }).unwrap();
        let dir = crate::plugins::scripts_dir().unwrap();
        fs::write(dir.join("GatesOfElidinis-3.6.2.pxe"), b"encrypted").unwrap();
        fs::write(dir.join("GatesOfElidinis-3.6.2.pxe.json"), b"{}").unwrap();

        let moved = sync_owned_with(&client(), false).await.unwrap();

        assert_eq!(moved[0].1.as_ref().unwrap(), "Updated Gates of Elidinis 3.6.2");
        assert_eq!(files_in(&dir), vec!["GatesOfElidinis-3.6.2.jar"]);
        assert_eq!(server.requests()[2], "GET /jar?game=rs3 HTTP/1.1");
        let _ = fs::remove_dir_all(home);
    }

    /// An older launcher writes the plain jar back beside the encrypted build; it would load twice.
    #[tokio::test]
    async fn a_plaintext_jar_beside_the_current_encrypted_build_is_removed_without_a_download() {
        let catalogue = r#"[{"internalName":"GatesOfElidinis","version":"3.6.2","paid":true}]"#;
        let server = FakeStore::start(vec![
            Reply { status: "200 OK", game_header: Some("rs3"), format: None, body: catalogue.as_bytes().to_vec() },
            json("200 OK", r#"{"entitlements":[{"internalName":"GatesOfElidinis","trial":false}]}"#),
        ]);
        let (_g, home) = scratch("leftover-plain", server.port);
        write_token(&StoredToken { token: "tok".into(), name: None, store: None }).unwrap();
        let dir = crate::plugins::scripts_dir().unwrap();
        fs::write(dir.join("GatesOfElidinis-3.6.2.pxe"), blob()).unwrap();
        fs::write(dir.join("GatesOfElidinis-3.6.2.pxe.json"), b"{}").unwrap();
        fs::write(dir.join("GatesOfElidinis-3.6.2.jar"), b"plaintext").unwrap();

        assert!(sync_owned_with(&client(), true).await.unwrap().is_empty());

        assert_eq!(files_in(&dir), vec!["GatesOfElidinis-3.6.2.pxe", "GatesOfElidinis-3.6.2.pxe.json"]);
        assert_eq!(server.requests().len(), 2);
        let _ = fs::remove_dir_all(home);
    }

    /// A script the account does not own never appears, listed or not.
    #[tokio::test]
    async fn a_claimed_free_community_script_is_listed_but_never_downloaded() {
        let catalogue = r#"[{"internalName":"HarvestHollow","version":"bundled","paid":false,"free":true,"requiresSubscription":true,"prices":{"week":0,"fortnight":0,"month":0},"storeUrl":"https://xclient.dev/rs3/store/harvest-hollow"}]"#;
        let server = FakeStore::start(vec![
            Reply { status: "200 OK", game_header: Some("rs3"), format: None, body: catalogue.as_bytes().to_vec() },
            json("200 OK", r#"{"entitlements":[{"internalName":"HarvestHollow","expiresAt":"2099-01-01T00:00:00Z","trial":false}]}"#),
        ]);
        let (_g, home) = scratch("claimed-free", server.port);
        write_token(&StoredToken { token: "tok".into(), name: None, store: None }).unwrap();

        assert!(sync_owned_with(&client(), true).await.unwrap().is_empty());
        let _ = fs::remove_dir_all(home);
    }

    #[tokio::test]
    async fn free_and_priced_store_scripts_are_both_on_the_store() {
        let listing = r#"[
            {"internalName":"HarvestHollow","version":"bundled","paid":false,"free":true,"requiresSubscription":true,"prices":{"week":0,"fortnight":0,"month":0}},
            {"internalName":"GatesOfElidinis","version":"3.6.2","paid":true,"requiresSubscription":true,"prices":{"week":90,"fortnight":160,"month":300},"salePercent":25},
            {"internalName":"OldPaid","version":"1.0.0","paid":true},
            {"internalName":"FreePlugin","version":"1.0.0","paid":false}
        ]"#;
        let server = FakeStore::start(vec![Reply {
            status: "200 OK",
            game_header: Some("rs3"),
            format: None,
            body: listing.as_bytes().to_vec(),
        }]);
        let (_g, home) = scratch("on-store", server.port);

        let list = catalogue(&client()).await.unwrap();
        let listed: Vec<&str> = list.iter().filter(|p| p.on_store()).map(|p| p.internal_name.as_str()).collect();
        assert_eq!(listed, vec!["HarvestHollow", "GatesOfElidinis", "OldPaid"]);
        let gates = list.iter().find(|p| p.internal_name == "GatesOfElidinis").unwrap();
        assert_eq!(gates.prices.as_ref().map(|p| (p.week, p.fortnight, p.month)), Some((90, 160, 300)));
        assert_eq!(gates.sale_percent, Some(25));
        let _ = fs::remove_dir_all(home);
    }

    #[tokio::test]
    async fn a_script_that_is_not_owned_is_not_installed() {
        let catalogue = r#"[{"internalName":"GatesOfElidinis","version":"3.6.2","paid":true}]"#;
        let server = FakeStore::start(vec![
            Reply { status: "200 OK", game_header: Some("rs3"), format: None, body: catalogue.as_bytes().to_vec() },
            json("200 OK", r#"{"entitlements":[]}"#),
        ]);
        let (_g, home) = scratch("not-owned", server.port);
        write_token(&StoredToken { token: "tok".into(), name: None, store: None }).unwrap();

        assert!(sync_owned_with(&client(), true).await.unwrap().is_empty());
        assert_eq!(install_state_for(&gates(server.port, None), true), InstallState::Missing);
        let _ = fs::remove_dir_all(home);
    }

    /// Access that has lapsed leaves the old build where it is: the store would
    /// refuse the download, and a failed update on every launch helps nobody.
    #[tokio::test]
    async fn a_script_no_longer_owned_is_not_updated() {
        let catalogue = r#"[{"internalName":"GatesOfElidinis","version":"3.6.2","paid":true}]"#;
        let server = FakeStore::start(vec![
            Reply { status: "200 OK", game_header: Some("rs3"), format: None, body: catalogue.as_bytes().to_vec() },
            json("200 OK", r#"{"entitlements":[]}"#),
        ]);
        let (_g, home) = scratch("lapsed", server.port);
        write_token(&StoredToken { token: "tok".into(), name: None, store: None }).unwrap();
        let dir = crate::plugins::scripts_dir().unwrap();
        fs::write(dir.join("GatesOfElidinis-3.6.1.jar"), b"old build").unwrap();

        assert!(sync_owned_with(&client(), true).await.unwrap().is_empty());
        assert!(dir.join("GatesOfElidinis-3.6.1.jar").exists());
        let _ = fs::remove_dir_all(home);
    }

    #[tokio::test]
    async fn signed_out_there_is_nothing_to_update() {
        let server = FakeStore::start(vec![]);
        let (_g, home) = scratch("signed-out", server.port);
        assert!(sync_owned_with(&client(), true).await.unwrap().is_empty());
        let _ = fs::remove_dir_all(home);
    }
}
