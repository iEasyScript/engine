use anyhow::{anyhow, Context, Result};

use super::types::{Account, User};

const API: &str = "https://api.jagex.com/v1";
const AUTH_API: &str = "https://auth.jagex.com/game-session/v1";

pub async fn get_user(client: &reqwest::Client, user_id: &str, access_token: &str) -> Result<User> {
    let resp = client
        .get(format!("{}/users/{}/displayName", API, user_id))
        .header("Authorization", format!("Bearer {}", access_token))
        .send()
        .await
        .context("User fetch request failed")?;

    if !resp.status().is_success() {
        let status = resp.status();
        return Err(anyhow!("User fetch failed: {}", status));
    }

    resp.json().await.context("Failed to parse user response")
}

pub async fn get_accounts(client: &reqwest::Client, session_id: &str) -> Result<Vec<Account>> {
    let resp = client
        .get(format!("{}/accounts", AUTH_API))
        .header("Accept", "application/json")
        .header("Authorization", format!("Bearer {}", session_id))
        .send()
        .await
        .context("Accounts fetch request failed")?;

    if !resp.status().is_success() {
        let status = resp.status();
        return Err(anyhow!("Accounts fetch failed: {}", status));
    }

    let body = resp.text().await.context("Failed to read accounts response body")?;
    // Do not log the body: it contains userHash and other account identifiers.
    log::debug!("Accounts response received ({} bytes)", body.len());
    serde_json::from_str(&body).context("Failed to parse accounts response")
}
