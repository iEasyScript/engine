fn main() {
    stamp_version();

    #[cfg(windows)]
    embed_resource::compile("assets/projectx.rc", embed_resource::NONE)
        .manifest_optional()
        .expect("failed to embed assets/projectx.rc");
}

/// Bake in the version the release pipeline stamps every artifact with, so the
/// launcher can compare itself against the release it reads the plugin catalog
/// from.
///
/// Without `PROJECTX_VERSION` — a local `cargo build` — the crate version stands
/// in, suffixed so it reads as a pre-release. A bare `0.1.0` is a perfectly good
/// release number, and one that every release outranks, which had a developer's
/// own build prompting itself to download the release on every launch.
fn stamp_version() {
    println!("cargo:rerun-if-env-changed=PROJECTX_VERSION");
    let version = std::env::var("PROJECTX_VERSION")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| {
            let crate_version =
                std::env::var("CARGO_PKG_VERSION").unwrap_or_else(|_| "0.0.0".to_string());
            format!("{}-dev", crate_version)
        });
    println!("cargo:rustc-env=PROJECTX_LAUNCHER_VERSION={}", version);
}
