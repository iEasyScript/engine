use rust_embed::Embed;

/// The UI assets inlined into the main webview's HTML.
#[derive(Embed)]
#[folder = "ui/"]
#[include = "index.html"]
#[include = "style.css"]
#[include = "app.js"]
pub struct UiAssets;
