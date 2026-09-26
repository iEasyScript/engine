//! RuneScape's own avatar and hiscore data for a saved character.
//!
//! Both come from `secure.runescape.com`, and neither is fetched in the webview: the avatar
//! service answers a name nobody has with **HTTP 200 and a default silhouette** rather than a 404,
//! so the only way to tell "no avatar" from a real one is to compare the bytes with what a made-up
//! name returns. A page cannot do that cross-origin. Doing it here also keeps the disk cache and
//! the rate limiting in one place.

use anyhow::Result;
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::sync::OnceCell;

const AVATAR: &str = "https://secure.runescape.com/m=avatar-rs/{name}/chat.png";
const HISCORE: &str = "https://secure.runescape.com/m=hiscore/index_lite.ws?player={name}";
/// A name nobody can have, so whatever comes back is the "this account has no avatar" image.
const NOBODY: &str = "zz_no_such_player_zz";
/// Hiscores are rate limited and a total level does not move minute to minute.
const FRESH_FOR: Duration = Duration::from_secs(6 * 60 * 60);

static DEFAULT_AVATAR: OnceCell<Option<Vec<u8>>> = OnceCell::const_new();

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Character {
    /// The chathead as a data URI, or None when the account has no avatar on record.
    pub avatar: Option<String>,
    pub total: Option<u64>,
    pub rank: Option<u64>,
    pub xp: Option<u64>,
    #[serde(default)]
    fetched: u64,
}

impl Character {
    fn fresh(&self) -> bool {
        now().saturating_sub(self.fetched) < FRESH_FOR.as_secs()
    }
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

/// One file per character. The name is not a path, so anything awkward becomes an underscore.
fn cache_file(dir: &Path, name: &str) -> PathBuf {
    let safe: String = name
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' { c } else { '_' })
        .collect();
    dir.join("characters").join(format!("{}.json", safe.to_lowercase()))
}

async fn default_avatar(client: &reqwest::Client) -> Option<Vec<u8>> {
    DEFAULT_AVATAR
        .get_or_init(|| async { fetch_bytes(client, &url(AVATAR, NOBODY)).await.ok() })
        .await
        .clone()
}

fn url(template: &str, name: &str) -> String {
    template.replace("{name}", &urlencoding(name))
}

/// Percent-encodes the few characters a RuneScape name can hold that a URL cannot (a space).
fn urlencoding(name: &str) -> String {
    name.chars()
        .map(|c| match c {
            ' ' => "%20".to_string(),
            c if c.is_ascii_alphanumeric() || c == '-' || c == '_' => c.to_string(),
            c => format!("%{:02X}", c as u32 as u8),
        })
        .collect()
}

async fn fetch_bytes(client: &reqwest::Client, url: &str) -> Result<Vec<u8>> {
    let response = client.get(url).send().await?.error_for_status()?;
    Ok(response.bytes().await?.to_vec())
}

async fn avatar(client: &reqwest::Client, name: &str) -> Option<String> {
    let bytes = fetch_bytes(client, &url(AVATAR, name)).await.ok()?;
    if Some(&bytes) == default_avatar(client).await.as_ref() {
        return None;
    }
    Some(format!("data:image/png;base64,{}", BASE64.encode(&bytes)))
}

async fn hiscore(client: &reqwest::Client, name: &str) -> (Option<u64>, Option<u64>, Option<u64>) {
    let Ok(body) = fetch_bytes(client, &url(HISCORE, name)).await else {
        return (None, None, None);
    };
    let text = String::from_utf8_lossy(&body);
    let Some(overall) = text.lines().next() else {
        return (None, None, None);
    };
    let mut parts = overall.split(',').map(|p| p.trim().parse::<u64>().ok());
    let rank = parts.next().flatten();
    let total = parts.next().flatten();
    let xp = parts.next().flatten();
    (total, rank, xp)
}

/// Everything known about [name], from the cache when it is recent enough, otherwise from Jagex.
///
/// A character that is not on the hiscores, or has no avatar, is a normal answer rather than an
/// error: it caches as "nothing known" so the next paint does not ask again.
pub async fn load(client: &reqwest::Client, data_dir: &Path, name: &str) -> Character {
    let file = cache_file(data_dir, name);
    if let Ok(text) = tokio::fs::read_to_string(&file).await {
        if let Ok(cached) = serde_json::from_str::<Character>(&text) {
            if cached.fresh() {
                return cached;
            }
        }
    }

    let (total, rank, xp) = hiscore(client, name).await;
    let character = Character { avatar: avatar(client, name).await, total, rank, xp, fetched: now() };

    if let Some(parent) = file.parent() {
        let _ = tokio::fs::create_dir_all(parent).await;
    }
    if let Ok(text) = serde_json::to_string(&character) {
        let _ = tokio::fs::write(&file, text).await;
    }
    character
}
