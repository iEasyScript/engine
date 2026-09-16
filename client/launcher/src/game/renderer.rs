//! Which renderer build of the NXT client to launch.
//!
//! Jagex ships build 950-1 on Windows twice: an OpenGL client tagged
//! `NXT-Windows-64` and a Vulkan-only client tagged `NXT-Windows-64-Vulkan`. They
//! share a build id and every engine struct layout, and differ in every code and
//! data address — which is why the engine carries a separate offset table per
//! renderer and `inject::guard` matches on the pair.
//!
//! The config server keys its client descriptor on `binaryType`, and the two
//! Windows builds sit on different values (verified against
//! `jav_config.ws?binaryType=N`: 2 and 10 return the same `download_name_0` with
//! different `download_crc_0`). Picking the renderer therefore happens here, at the
//! one point that decides which binary the Jagex launcher downloads.
//!
//! Linux and macOS have no Vulkan build, so they always resolve to OpenGL.

use serde::{Deserialize, Serialize};

/// A concrete renderer build, resolved and ready to key a `binaryType` off.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Renderer {
    Vulkan,
    OpenGl,
}

impl Renderer {
    /// The offset-table renderer tag this build carries, matching
    /// [`crate::game::inject::windows::Revision::renderer`] and the `-vulkan`
    /// suffix on an offset-table name.
    pub fn tag(self) -> &'static str {
        match self {
            Renderer::Vulkan => "vulkan",
            Renderer::OpenGl => "opengl",
        }
    }
}

impl std::fmt::Display for Renderer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Renderer::Vulkan => "Vulkan",
            Renderer::OpenGl => "OpenGL",
        })
    }
}

/// The user's renderer preference. `Auto` is the default and the only value the
/// launcher writes: it prefers Vulkan and falls back to OpenGL when the host or
/// the installed engine cannot support it. The explicit variants exist to pin a
/// renderer by hand in `config.json` when diagnosing a renderer-specific problem.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum RendererPref {
    Auto,
    Vulkan,
    OpenGl,
}

impl Default for RendererPref {
    fn default() -> Self {
        RendererPref::Auto
    }
}

/// Deserialize leniently, matching how `ServerMode` tolerates configs written by
/// other launcher builds: an unknown string falls back to `Auto` rather than
/// failing the whole config parse and resetting every other setting.
impl<'de> Deserialize<'de> for RendererPref {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        let raw = String::deserialize(deserializer)?;
        Ok(match raw.as_str() {
            "Vulkan" => RendererPref::Vulkan,
            // Spelled both ways in the wild; the serialized form is "OpenGl".
            "OpenGl" | "OpenGL" => RendererPref::OpenGl,
            "Auto" => RendererPref::Auto,
            other => {
                log::warn!("Unknown renderer {:?} in config; defaulting to Auto", other);
                RendererPref::Auto
            }
        })
    }
}

/// Resolve a preference into the build to actually launch.
///
/// `engine_has_vulkan_table` is whether the installed engine jar carries any
/// `-vulkan` offset table. Under `Auto` that gates the Vulkan choice: launching
/// the Vulkan client against an engine that can only describe the OpenGL one
/// means `inject::guard` refuses the injection and the user gets a client with no
/// overlay, which is a worse outcome than running OpenGL with a working engine.
/// Pass `true` when injection is off, since then no table is needed either way.
pub fn resolve(pref: RendererPref, engine_has_vulkan_table: bool) -> Renderer {
    if !cfg!(target_os = "windows") {
        // No Vulkan build exists off Windows. Log only when the user asked for one,
        // so the common case stays quiet.
        if pref == RendererPref::Vulkan {
            log::warn!("Vulkan was requested, but Jagex ships no Vulkan client for this OS; using OpenGL.");
        }
        return Renderer::OpenGl;
    }
    match pref {
        RendererPref::OpenGl => Renderer::OpenGl,
        RendererPref::Vulkan => Renderer::Vulkan,
        RendererPref::Auto => {
            if !engine_has_vulkan_table {
                log::info!(
                    "Renderer auto: the installed engine has no Vulkan offset table, so the \
                     Vulkan client could not be injected; using OpenGL."
                );
                return Renderer::OpenGl;
            }
            if !host_supports_vulkan() {
                log::info!("Renderer auto: no usable Vulkan loader on this host; using OpenGL.");
                return Renderer::OpenGl;
            }
            log::info!("Renderer auto: using Vulkan.");
            Renderer::Vulkan
        }
    }
}

/// Resolve a preference against the installed engine, for callers that have an
/// engine home rather than an answer about its offset tables.
///
/// `auto_inject` is whether this launch will inject the engine at all. With it
/// off nothing reads an offset table, so the engine has no say in the renderer.
/// `engine_home` is `None` when no engine is installed, which is the same case:
/// nothing will be injected, so nothing can veto Vulkan.
pub fn resolve_for(
    pref: RendererPref,
    engine_home: Option<&std::path::Path>,
    auto_inject: bool,
) -> Renderer {
    let covered = match (auto_inject, engine_home) {
        (true, Some(home)) => engine_has_vulkan_table(home),
        _ => true,
    };
    resolve(pref, covered)
}

#[cfg(any(all(unix, not(target_os = "macos")), windows))]
fn engine_has_vulkan_table(engine_home: &std::path::Path) -> bool {
    crate::game::inject::revision::engine_has_renderer(engine_home, Renderer::Vulkan.tag())
}

/// macOS has no injection support, and `resolve` never reaches this off Windows.
#[cfg(not(any(all(unix, not(target_os = "macos")), windows)))]
fn engine_has_vulkan_table(_engine_home: &std::path::Path) -> bool {
    false
}

/// Whether this host can run the Vulkan client at all.
///
/// The client links Vulkan through the loader (`vulkan-1.dll`) rather than a
/// driver directly, so the loader being present and exporting the entry points
/// the client resolves is what decides whether it can start. A machine with no
/// Vulkan-capable GPU has no loader installed — every driver that supports
/// Vulkan ships one — so a failed load is the signal to stay on OpenGL.
#[cfg(target_os = "windows")]
fn host_supports_vulkan() -> bool {
    use std::ffi::CString;
    use windows_sys::Win32::Foundation::FreeLibrary;
    use windows_sys::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryW};

    // LoadLibraryW rather than the ANSI form: the loader sits in System32 and is
    // found by name, but the W form is what the rest of this crate uses.
    let name: Vec<u16> = "vulkan-1.dll\0".encode_utf16().collect();
    let module = unsafe { LoadLibraryW(name.as_ptr()) };
    if module.is_null() {
        return false;
    }
    // vkGetInstanceProcAddr is the one entry point the loader must export; if it
    // is missing the DLL is not a usable Vulkan loader.
    let symbol = CString::new("vkGetInstanceProcAddr").expect("literal has no NUL");
    let found = unsafe { GetProcAddress(module, symbol.as_ptr() as *const u8) }.is_some();
    unsafe {
        FreeLibrary(module);
    }
    found
}

#[cfg(not(target_os = "windows"))]
fn host_supports_vulkan() -> bool {
    // Unreachable: `resolve` returns OpenGL off Windows before consulting this.
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_explicit_opengl_preference_is_honoured() {
        assert_eq!(resolve(RendererPref::OpenGl, true), Renderer::OpenGl);
    }

    #[test]
    fn auto_falls_back_to_opengl_without_a_vulkan_offset_table() {
        assert_eq!(resolve(RendererPref::Auto, false), Renderer::OpenGl);
    }

    #[test]
    fn off_windows_every_preference_resolves_to_opengl() {
        if cfg!(target_os = "windows") {
            return;
        }
        assert_eq!(resolve(RendererPref::Vulkan, true), Renderer::OpenGl);
        assert_eq!(resolve(RendererPref::Auto, true), Renderer::OpenGl);
    }

    #[test]
    fn an_unknown_preference_deserializes_to_auto() {
        let pref: RendererPref = serde_json::from_str("\"Metal\"").unwrap();
        assert_eq!(pref, RendererPref::Auto);
    }

    #[test]
    fn both_spellings_of_opengl_deserialize() {
        assert_eq!(
            serde_json::from_str::<RendererPref>("\"OpenGl\"").unwrap(),
            RendererPref::OpenGl
        );
        assert_eq!(
            serde_json::from_str::<RendererPref>("\"OpenGL\"").unwrap(),
            RendererPref::OpenGl
        );
    }
}
