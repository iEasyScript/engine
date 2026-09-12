use rust_embed::Embed;

/// The UI assets inlined into the main webview's HTML. The font licences ship in
/// the repo next to the `.woff2` files but are not needed at runtime, so they
/// stay out of the binary.
#[derive(Embed)]
#[folder = "ui/"]
#[exclude = "fonts/*.txt"]
pub struct UiAssets;
