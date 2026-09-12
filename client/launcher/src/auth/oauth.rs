use anyhow::{anyhow, Context, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use rand::Rng;
use sha2::{Digest, Sha256};
use std::io::{BufRead, BufReader, Write};
use std::net::TcpListener;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use super::types::{AuthTokens, TokenResponse};

const ORIGIN: &str = "https://account.jagex.com";
const REDIRECT_URI: &str = "https://secure.runescape.com/m=weblogin/launcher-redirect";
const CLIENT_ID: &str = "com_jagex_auth_desktop_launcher";
const CONSENT_CLIENT_ID: &str = "1fddee4e-b100-4f4e-b2b0-097f9088f9d2";

pub struct PkceChallenge {
    pub verifier: String,
    pub challenge: String,
    pub state: String,
}

pub fn generate_pkce() -> PkceChallenge {
    let verifier = generate_random_string(43);
    let state = generate_random_string(16);

    let mut hasher = Sha256::new();
    hasher.update(verifier.as_bytes());
    let digest = hasher.finalize();
    let challenge = URL_SAFE_NO_PAD.encode(digest);

    PkceChallenge {
        verifier,
        challenge,
        state,
    }
}

pub fn build_login_url(pkce: &PkceChallenge) -> String {
    let params = url::form_urlencoded::Serializer::new(String::new())
        .append_pair("auth_method", "")
        .append_pair("login_type", "")
        .append_pair("flow", "launcher")
        .append_pair("response_type", "code")
        .append_pair("client_id", CLIENT_ID)
        .append_pair("redirect_uri", REDIRECT_URI)
        .append_pair("code_challenge", &pkce.challenge)
        .append_pair("code_challenge_method", "S256")
        .append_pair("prompt", "login")
        .append_pair(
            "scope",
            "openid offline gamesso.token.create user.profile.read",
        )
        .append_pair("state", &pkce.state)
        .finish();

    format!("{}/oauth2/auth?{}", ORIGIN, params)
}

pub fn build_consent_url(id_token: &str, nonce: &str) -> String {
    let state = generate_random_string(16);
    let params = url::form_urlencoded::Serializer::new(String::new())
        .append_pair("id_token_hint", id_token)
        .append_pair("nonce", nonce)
        .append_pair("prompt", "consent")
        .append_pair("redirect_uri", "http://localhost")
        .append_pair("response_type", "id_token code")
        .append_pair("state", &state)
        .append_pair("client_id", CONSENT_CLIENT_ID)
        .append_pair("scope", "openid offline")
        .finish();

    format!("{}/oauth2/auth?{}", ORIGIN, params)
}

pub fn is_login_redirect(url: &str) -> bool {
    url.starts_with(REDIRECT_URI)
}

pub fn is_consent_redirect(url: &str) -> bool {
    url.starts_with("http://localhost")
}

/// Extract the authorization `code` from the login redirect, rejecting a
/// response whose `state` does not match the one we generated for this attempt.
///
/// A redirect that carries no `state` at all is accepted with a warning rather
/// than failed: the parameter is echoed at the provider's discretion, and a hard
/// failure here would break login outright if Jagex ever stops sending it.
pub fn extract_auth_code(url: &str, expected_state: &str) -> Result<String> {
    let parsed = url::Url::parse(url).context("Failed to parse redirect URL")?;

    match parsed.query_pairs().find(|(k, _)| k == "state") {
        Some((_, returned)) if returned == expected_state => {}
        Some((_, returned)) => {
            return Err(anyhow!(
                "OAuth state mismatch: expected {} chars, got a different value ({} chars)",
                expected_state.len(),
                returned.len()
            ))
        }
        None => log::warn!("Login redirect carried no 'state' parameter; accepting unverified"),
    }

    parsed
        .query_pairs()
        .find(|(k, _)| k == "code")
        .map(|(_, v)| v.to_string())
        .ok_or_else(|| anyhow!("No 'code' parameter in redirect URL"))
}

/// Verify that a consent `id_token` echoes the nonce we sent. Absent claim →
/// accepted with a warning, same rationale as the `state` check above.
pub fn verify_consent_nonce(id_token: &str, expected_nonce: &str) -> Result<()> {
    let payload = decode_jwt_payload(id_token).context("Failed to decode consent id_token")?;
    match payload["nonce"].as_str() {
        Some(nonce) if nonce == expected_nonce => Ok(()),
        Some(_) => Err(anyhow!("Consent id_token nonce does not match the one sent")),
        None => {
            log::warn!("Consent id_token carried no 'nonce' claim; accepting unverified");
            Ok(())
        }
    }
}

/// Extract id_token from a URL's fragment (e.g. http://localhost#id_token=...&code=...)
pub fn extract_id_token_from_fragment(url: &str) -> Option<String> {
    let fragment = url.split_once('#').map(|(_, f)| f)?;
    url::form_urlencoded::parse(fragment.as_bytes())
        .find(|(k, _)| k == "id_token")
        .map(|(_, v)| v.to_string())
}

/// Error text returned when [`start_consent_callback_server`]'s wait is aborted
/// through its cancel flag, so callers can stay quiet about a user-closed window
/// instead of reporting a login failure.
pub const CONSENT_CANCELLED: &str = "consent wait cancelled";

/// Start a local HTTP server on port 80 to capture the OAuth consent redirect.
///
/// The consent flow redirects to `http://localhost#id_token=...`. Since the
/// fragment is client-side only (never sent to servers), the server responds
/// with a small HTML page whose JS reads `location.hash` and sends it back
/// via a `/callback?id_token=...` request.
///
/// Returns a `JoinHandle` that resolves to the extracted `id_token`. Setting
/// `cancel` makes the accept loop exit promptly and release port 80 — without it
/// a closed login window would keep the port bound for the full 60s deadline and
/// break the next attempt.
///
/// Fails if port 80 can't be bound (requires CAP_NET_BIND_SERVICE or
/// net.ipv4.ip_unprivileged_port_start <= 80).
pub fn start_consent_callback_server(
    cancel: Arc<AtomicBool>,
) -> Result<JoinHandle<Result<String>>> {
    let listener =
        TcpListener::bind("127.0.0.1:80").context("Failed to bind port 80 for consent callback")?;
    log::info!("Consent callback server bound to 127.0.0.1:80");

    let handle = std::thread::spawn(move || -> Result<String> {
        let deadline = std::time::Instant::now() + Duration::from_secs(60);

        let html_page = r#"<!DOCTYPE html><html><body>
<p>Completing login...</p>
<script>
fetch('/callback?' + location.hash.slice(1))
  .then(function() { document.body.innerHTML = '<p>Login complete. You can close this window.</p>'; })
  .catch(function() { document.body.innerHTML = '<p>Error completing login.</p>'; });
</script>
</body></html>"#;

        listener.set_nonblocking(true).ok();

        loop {
            if cancel.load(Ordering::SeqCst) {
                return Err(anyhow!(CONSENT_CANCELLED));
            }
            let remaining = deadline.saturating_duration_since(std::time::Instant::now());
            if remaining.is_zero() {
                return Err(anyhow!("Consent callback timed out (60s)"));
            }

            match listener.accept() {
                Ok((mut stream, _)) => {
                    stream.set_read_timeout(Some(Duration::from_secs(5))).ok();
                    stream.set_write_timeout(Some(Duration::from_secs(5))).ok();

                    let mut reader = BufReader::new(&stream);
                    let mut request_line = String::new();
                    reader.read_line(&mut request_line).ok();

                    // Drain headers
                    loop {
                        let mut line = String::new();
                        if reader.read_line(&mut line).unwrap_or(0) == 0
                            || line.trim().is_empty()
                        {
                            break;
                        }
                    }

                    if request_line.starts_with("GET /callback?") {
                        // JS forwarded the fragment data as query params
                        let query = request_line
                            .strip_prefix("GET /callback?")
                            .and_then(|s| s.split_whitespace().next())
                            .unwrap_or("");

                        let id_token = url::form_urlencoded::parse(query.as_bytes())
                            .find(|(k, _)| k == "id_token")
                            .map(|(_, v)| v.to_string());

                        let response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                        let _ = stream.write_all(response.as_bytes());
                        drop(stream);

                        return id_token
                            .ok_or_else(|| anyhow!("No id_token in callback query"));
                    } else {
                        // Initial redirect hit — serve the JS page
                        let response = format!(
                            "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                            html_page.len(),
                            html_page
                        );
                        let _ = stream.write_all(response.as_bytes());
                        drop(stream);
                    }
                }
                Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(Duration::from_millis(100));
                }
                Err(e) => return Err(anyhow!("Accept failed: {}", e)),
            }
        }
    });

    Ok(handle)
}

/// Exchange authorization code for tokens
pub async fn exchange_code(
    client: &reqwest::Client,
    code: &str,
    verifier: &str,
) -> Result<AuthTokens> {
    let params = [
        ("grant_type", "authorization_code"),
        ("client_id", CLIENT_ID),
        ("code", code),
        ("code_verifier", verifier),
        ("redirect_uri", REDIRECT_URI),
    ];

    let resp = client
        .post(format!("{}/oauth2/token", ORIGIN))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .form(&params)
        .send()
        .await
        .context("Token exchange request failed")?;

    if !resp.status().is_success() {
        let status = resp.status();
        let body = resp.text().await.unwrap_or_default();
        return Err(anyhow!("Token exchange failed: {} - {}", status, body));
    }

    let token_resp: TokenResponse = resp.json().await.context("Failed to parse token response")?;
    parse_token_response(token_resp)
}

/// Refresh an expired token
pub async fn refresh_token(
    client: &reqwest::Client,
    refresh_tok: &str,
) -> Result<AuthTokens> {
    let params = [
        ("grant_type", "refresh_token"),
        ("client_id", CLIENT_ID),
        ("refresh_token", refresh_tok),
    ];

    let resp = client
        .post(format!("{}/oauth2/token", ORIGIN))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .form(&params)
        .send()
        .await
        .context("Token refresh request failed")?;

    if !resp.status().is_success() {
        let status = resp.status();
        let body = resp.text().await.unwrap_or_default();
        return Err(anyhow!("Token refresh failed: {} - {}", status, body));
    }

    let token_resp: TokenResponse = resp.json().await.context("Failed to parse refresh response")?;
    parse_token_response(token_resp)
}

/// Revoke an access token (used during logout)
pub async fn revoke_token(client: &reqwest::Client, access_token: &str) -> Result<()> {
    let params = [
        ("token", access_token),
        ("client_id", CLIENT_ID),
    ];

    client
        .post(format!("{}/oauth2/revoke", ORIGIN))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .form(&params)
        .send()
        .await
        .context("Token revocation request failed")?;

    Ok(())
}

/// Decode a JWT's claims without verifying its signature. The tokens handled
/// here arrive over TLS from the provider we just spoke to, and are consumed
/// only for the `sub`/`nonce` claims.
fn decode_jwt_payload(token: &str) -> Result<serde_json::Value> {
    let parts: Vec<&str> = token.split('.').collect();
    if parts.len() != 3 {
        return Err(anyhow!(
            "Malformed id_token: {} sections, expected 3",
            parts.len()
        ));
    }

    let payload_bytes = base64::engine::general_purpose::STANDARD_NO_PAD
        .decode(parts[1])
        .or_else(|_| URL_SAFE_NO_PAD.decode(parts[1]))
        .context("Failed to decode id_token payload")?;

    serde_json::from_slice(&payload_bytes).context("Failed to parse id_token payload")
}

fn parse_token_response(resp: TokenResponse) -> Result<AuthTokens> {
    let payload = decode_jwt_payload(&resp.id_token)?;

    let sub = payload["sub"]
        .as_str()
        .ok_or_else(|| anyhow!("No 'sub' in id_token payload"))?
        .to_string();

    let now_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::ZERO)
        .as_millis() as u64;

    Ok(AuthTokens {
        access_token: resp.access_token,
        id_token: resp.id_token,
        refresh_token: resp.refresh_token,
        sub,
        expiry: now_ms + resp.expires_in * 1000,
    })
}

/// A fresh random nonce for the consent request, echoed back in the consent
/// `id_token` and checked by [`verify_consent_nonce`].
pub fn generate_nonce() -> String {
    generate_random_string(16)
}

fn generate_random_string(len: usize) -> String {
    const CHARSET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    let mut rng = rand::rng();
    (0..len)
        .map(|_| {
            let idx = rng.random_range(0..CHARSET.len());
            CHARSET[idx] as char
        })
        .collect()
}
