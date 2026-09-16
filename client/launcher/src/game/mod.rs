pub mod control;
/// Debian package extraction — only the Linux launcher ships as a `.deb`.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
pub mod deb;
pub mod inject;
/// Legacy shared libraries the client needs and modern hosts no longer ship.
#[cfg(target_os = "linux")]
pub mod nativedeps;
pub mod process;
/// Which renderer build of the client to launch (Vulkan vs OpenGL).
pub mod renderer;
pub mod rs3;
