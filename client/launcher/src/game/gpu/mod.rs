//! Puts the Linux client on a real GPU under XWayland: `EGL_PLATFORM=x11` with a matching 32-bit ARGB visual so
//! NVIDIA doesn't fall back to llvmpipe, and PRIME offload to the dGPU on hybrid laptops.
//! `PROJECTX_NO_EGL_X11=1` skips the EGL pair on X servers without DRI3/Present 1.2.

pub mod policy;
#[cfg(target_os = "linux")]
mod probe;
mod sysfs;

#[cfg(target_os = "linux")]
pub use linux::apply_launch_env;

#[cfg(target_os = "linux")]
mod linux {
    use super::policy::{self, Gpu, LaunchFacts};
    use super::{probe, sysfs};
    use std::env;
    use std::path::Path;
    use std::process::Command;
    use std::sync::OnceLock;

    const OPT_OUT: &str = "PROJECTX_NO_EGL_X11";

    fn user_env(name: &str) -> Option<String> {
        env::var_os(name).map(|value| value.to_string_lossy().into_owned())
    }

    fn opted_out() -> bool {
        user_env(OPT_OUT).is_some_and(|v| !matches!(v.trim(), "" | "0" | "false"))
    }

    fn inventory() -> &'static [Gpu] {
        static GPUS: OnceLock<Vec<Gpu>> = OnceLock::new();
        GPUS.get_or_init(|| {
            let mut gpus = sysfs::scan(Path::new(sysfs::PCI_DEVICES));
            match probe::vulkan_devices() {
                Some(reported) => policy::apply_vulkan_kinds(&mut gpus, &reported),
                None => log::info!(
                    "GPU: no Vulkan driver answered; GPU types come from PCI facts only."
                ),
            }
            for gpu in &gpus {
                log::info!("GPU: found {gpu}");
            }
            gpus
        })
    }

    pub fn apply_launch_env(cmd: &mut Command) {
        let wayland = policy::is_wayland_session(
            user_env("WAYLAND_DISPLAY").as_deref(),
            user_env("XDG_SESSION_TYPE").as_deref(),
        );
        if !wayland {
            return;
        }

        let egl_x11_opted_out = opted_out();
        let argb_visual = if egl_x11_opted_out {
            log::info!("GPU: {OPT_OUT} is set; leaving EGL on its default platform.");
            None
        } else {
            let visual = probe::argb_visual_id();
            match visual {
                Some(id) => {
                    log::info!("GPU: Wayland session; X display's ARGB visual is 0x{id:x}.")
                }
                None => log::warn!(
                    "GPU: Wayland session, but the X display has no 32-bit ARGB visual; \
                     leaving EGL on its default platform."
                ),
            }
            visual
        };

        let gpus = inventory();
        let gpu = policy::pick(gpus);
        match gpu {
            Some(gpu) => log::info!("GPU: rendering on {gpu}"),
            None => log::info!(
                "GPU: keeping the system default GPU ({} found).",
                gpus.len()
            ),
        }

        let plan = policy::launch_env(
            &LaunchFacts {
                wayland,
                egl_x11_opted_out,
                argb_visual,
                gpu,
            },
            user_env,
        );
        for (name, value) in &plan.set {
            log::info!("GPU: setting {name}={value}");
            cmd.env(name, value);
        }
        for name in &plan.kept_from_user {
            log::info!("GPU: keeping the user's {name}");
        }
    }
}
