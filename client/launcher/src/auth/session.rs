use anyhow::{anyhow, Context, Result};

use super::types::SessionResponse;

const AUTH_API: &str = "https://auth.jagex.com/game-session/v1";

pub async fn create_session(client: &reqwest::Client, id_token: &str) -> Result<String> {
    let body = serde_json::json!({ "idToken": id_token });

    let resp = client
        .post(format!("{}/sessions", AUTH_API))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .json(&body)
        .send()
        .await
        .context("Session creation request failed")?;

    if !resp.status().is_success() {
        let status = resp.status();
        let body = resp.text().await.unwrap_or_default();
        return Err(anyhow!("Session creation failed: {} - {}", status, body));
    }

    let session: SessionResponse = resp
        .json()
        .await
        .context("Failed to parse session response")?;
    Ok(session.session_id)
}
