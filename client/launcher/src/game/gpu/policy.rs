pub const EGL_PLATFORM: &str = "EGL_PLATFORM";
pub const SDL_VISUAL_ID: &str = "SDL_VIDEO_X11_WINDOW_VISUALID";
pub const NV_PRIME_RENDER_OFFLOAD: &str = "__NV_PRIME_RENDER_OFFLOAD";
pub const GLX_VENDOR_LIBRARY_NAME: &str = "__GLX_VENDOR_LIBRARY_NAME";
pub const VK_LAYER_NV_OPTIMUS: &str = "__VK_LAYER_NV_optimus";
pub const DRI_PRIME: &str = "DRI_PRIME";

const VENDOR_INTEL: u16 = 0x8086;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GpuKind {
    Integrated,
    Discrete,
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Gpu {
    pub address: String,
    pub vendor: u16,
    pub device: u16,
    pub driver: Option<String>,
    pub boot_vga: bool,
    pub kind: GpuKind,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Offload {
    NvidiaPrime,
    MesaPrime,
}

impl Gpu {
    fn offload(&self) -> Option<Offload> {
        match self.driver.as_deref()? {
            "nvidia" => Some(Offload::NvidiaPrime),
            "amdgpu" | "radeon" | "i915" | "xe" | "nouveau" => Some(Offload::MesaPrime),
            _ => None,
        }
    }

    fn bus(&self) -> Option<u8> {
        let bus = self.address.split(':').nth(1)?;
        u8::from_str_radix(bus, 16).ok()
    }

    /// Without a Vulkan answer only an Intel iGPU is recognisable: it always sits on root bus 00.
    fn is_integrated(&self) -> bool {
        match self.kind {
            GpuKind::Integrated => true,
            GpuKind::Discrete => false,
            GpuKind::Unknown => self.vendor == VENDOR_INTEL && self.bus() == Some(0),
        }
    }

    pub fn dri_prime_tag(&self) -> String {
        format!("pci-{}", self.address.replace([':', '.'], "_"))
    }
}

impl std::fmt::Display for Gpu {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let kind = match self.kind {
            GpuKind::Integrated => "integrated",
            GpuKind::Discrete => "discrete",
            GpuKind::Unknown => "type unknown",
        };
        write!(
            f,
            "{} [{:04x}:{:04x}] driver={} {}{}",
            self.address,
            self.vendor,
            self.device,
            self.driver.as_deref().unwrap_or("none"),
            kind,
            if self.boot_vga {
                ", drives the display"
            } else {
                ""
            }
        )
    }
}

/// Labels each GPU with the type its Vulkan driver reports. A vendor:device pair seen with
/// conflicting types stays unknown rather than guessing which card is which.
pub fn apply_vulkan_kinds(gpus: &mut [Gpu], reported: &[(u32, u32, GpuKind)]) {
    for gpu in gpus.iter_mut() {
        let mut kinds = reported
            .iter()
            .filter(|(vendor, device, _)| {
                *vendor == u32::from(gpu.vendor) && *device == u32::from(gpu.device)
            })
            .map(|(_, _, kind)| *kind);
        if let Some(first) = kinds.next() {
            if kinds.all(|kind| kind == first) {
                gpu.kind = first;
            }
        }
    }
}

/// The GPU the client should render on, or `None` to leave the system default alone.
pub fn pick(gpus: &[Gpu]) -> Option<&Gpu> {
    if gpus.len() < 2 {
        return None;
    }
    let display = gpus.iter().find(|g| g.boot_vga)?;
    if display.kind == GpuKind::Discrete {
        return None;
    }
    let candidates = || gpus.iter().filter(|g| !g.boot_vga && g.offload().is_some());
    if let Some(discrete) = candidates().find(|g| g.kind == GpuKind::Discrete) {
        return Some(discrete);
    }
    if !display.is_integrated() {
        return None;
    }
    candidates().find(|g| g.kind != GpuKind::Integrated)
}

/// NVIDIA's offload only works on the `EGL_PLATFORM=x11` path, so it needs that path in effect.
pub fn offload_env(gpu: &Gpu, egl_x11: bool) -> Vec<(&'static str, String)> {
    match gpu.offload() {
        Some(Offload::NvidiaPrime) if egl_x11 => vec![
            (NV_PRIME_RENDER_OFFLOAD, "1".to_string()),
            (GLX_VENDOR_LIBRARY_NAME, "nvidia".to_string()),
            (VK_LAYER_NV_OPTIMUS, "NVIDIA_only".to_string()),
        ],
        Some(Offload::MesaPrime) => vec![(DRI_PRIME, gpu.dri_prime_tag())],
        _ => Vec::new(),
    }
}

pub fn is_wayland_session(wayland_display: Option<&str>, session_type: Option<&str>) -> bool {
    wayland_display.is_some_and(|d| !d.is_empty())
        || session_type.is_some_and(|t| t.eq_ignore_ascii_case("wayland"))
}

#[derive(Debug, Default, PartialEq, Eq)]
pub struct LaunchEnv {
    pub set: Vec<(&'static str, String)>,
    pub kept_from_user: Vec<&'static str>,
    pub egl_x11: bool,
}

pub struct LaunchFacts<'a> {
    pub wayland: bool,
    pub egl_x11_opted_out: bool,
    pub argb_visual: Option<u64>,
    pub gpu: Option<&'a Gpu>,
}

/// `user` reads the launcher's own environment, which the client inherits; a variable present
/// there is never overridden.
pub fn launch_env(facts: &LaunchFacts<'_>, user: impl Fn(&str) -> Option<String>) -> LaunchEnv {
    let mut env = LaunchEnv::default();
    if !facts.wayland {
        return env;
    }

    let user_platform = user(EGL_PLATFORM);
    let user_visual = user(SDL_VISUAL_ID);
    if user_platform.is_some() || user_visual.is_some() {
        if user_platform.is_some() {
            env.kept_from_user.push(EGL_PLATFORM);
        }
        if user_visual.is_some() {
            env.kept_from_user.push(SDL_VISUAL_ID);
        }
        env.egl_x11 = user_platform.as_deref() == Some("x11") && user_visual.is_some();
    } else if let (false, Some(visual)) = (facts.egl_x11_opted_out, facts.argb_visual) {
        env.set.push((EGL_PLATFORM, "x11".to_string()));
        env.set.push((SDL_VISUAL_ID, format!("0x{visual:x}")));
        env.egl_x11 = true;
    }

    if let Some(gpu) = facts.gpu {
        for (name, value) in offload_env(gpu, env.egl_x11) {
            if user(name).is_some() {
                env.kept_from_user.push(name);
            } else {
                env.set.push((name, value));
            }
        }
    }
    env
}

#[cfg(test)]
mod tests {
    use super::*;

    const VENDOR_AMD: u16 = 0x1002;
    const VENDOR_NVIDIA: u16 = 0x10de;

    fn gpu(address: &str, vendor: u16, driver: &str, boot_vga: bool, kind: GpuKind) -> Gpu {
        Gpu {
            address: address.to_string(),
            vendor,
            device: 0x1234,
            driver: Some(driver.to_string()),
            boot_vga,
            kind,
        }
    }

    fn intel_igpu(kind: GpuKind) -> Gpu {
        gpu("0000:00:02.0", VENDOR_INTEL, "i915", true, kind)
    }

    fn amd_apu(kind: GpuKind) -> Gpu {
        gpu("0000:05:00.0", VENDOR_AMD, "amdgpu", true, kind)
    }

    fn nvidia_dgpu(kind: GpuKind) -> Gpu {
        gpu("0000:01:00.0", VENDOR_NVIDIA, "nvidia", false, kind)
    }

    fn no_user_env(_: &str) -> Option<String> {
        None
    }

    fn names(env: &LaunchEnv) -> Vec<&'static str> {
        env.set.iter().map(|(name, _)| *name).collect()
    }

    fn wayland_with<'a>(gpu: Option<&'a Gpu>, argb_visual: Option<u64>) -> LaunchFacts<'a> {
        LaunchFacts {
            wayland: true,
            egl_x11_opted_out: false,
            argb_visual,
            gpu,
        }
    }

    #[test]
    fn intel_with_nvidia_proprietary_offloads_to_nvidia() {
        let gpus = [
            intel_igpu(GpuKind::Integrated),
            nvidia_dgpu(GpuKind::Discrete),
        ];
        let chosen = pick(&gpus).unwrap();
        assert_eq!(chosen.vendor, VENDOR_NVIDIA);
        assert_eq!(
            offload_env(chosen, true),
            vec![
                (NV_PRIME_RENDER_OFFLOAD, "1".to_string()),
                (GLX_VENDOR_LIBRARY_NAME, "nvidia".to_string()),
                (VK_LAYER_NV_OPTIMUS, "NVIDIA_only".to_string()),
            ]
        );
    }

    #[test]
    fn nvidia_offload_needs_the_egl_x11_path() {
        let gpus = [
            intel_igpu(GpuKind::Integrated),
            nvidia_dgpu(GpuKind::Discrete),
        ];
        assert!(offload_env(pick(&gpus).unwrap(), false).is_empty());
    }

    #[test]
    fn intel_with_nouveau_uses_dri_prime() {
        let gpus = [
            intel_igpu(GpuKind::Integrated),
            gpu(
                "0000:01:00.0",
                VENDOR_NVIDIA,
                "nouveau",
                false,
                GpuKind::Discrete,
            ),
        ];
        assert_eq!(
            offload_env(pick(&gpus).unwrap(), false),
            vec![(DRI_PRIME, "pci-0000_01_00_0".to_string())]
        );
    }

    #[test]
    fn intel_with_amd_dgpu_uses_dri_prime() {
        let gpus = [
            intel_igpu(GpuKind::Integrated),
            gpu(
                "0000:03:00.0",
                VENDOR_AMD,
                "amdgpu",
                false,
                GpuKind::Discrete,
            ),
        ];
        assert_eq!(
            offload_env(pick(&gpus).unwrap(), true),
            vec![(DRI_PRIME, "pci-0000_03_00_0".to_string())]
        );
    }

    #[test]
    fn intel_with_intel_arc_uses_dri_prime() {
        let gpus = [
            intel_igpu(GpuKind::Integrated),
            gpu("0000:03:00.0", VENDOR_INTEL, "xe", false, GpuKind::Discrete),
        ];
        assert_eq!(
            offload_env(pick(&gpus).unwrap(), true),
            vec![(DRI_PRIME, "pci-0000_03_00_0".to_string())]
        );
    }

    #[test]
    fn amd_apu_with_nvidia_offloads_to_nvidia() {
        let gpus = [amd_apu(GpuKind::Integrated), nvidia_dgpu(GpuKind::Discrete)];
        assert_eq!(pick(&gpus).unwrap().vendor, VENDOR_NVIDIA);
    }

    #[test]
    fn amd_apu_with_amd_dgpu_uses_dri_prime() {
        let gpus = [
            amd_apu(GpuKind::Integrated),
            gpu(
                "0000:03:00.0",
                VENDOR_AMD,
                "amdgpu",
                false,
                GpuKind::Discrete,
            ),
        ];
        assert_eq!(
            offload_env(pick(&gpus).unwrap(), true),
            vec![(DRI_PRIME, "pci-0000_03_00_0".to_string())]
        );
    }

    #[test]
    fn a_desktop_whose_dgpu_drives_the_display_is_left_alone() {
        let gpus = [
            gpu(
                "0000:00:02.0",
                VENDOR_INTEL,
                "i915",
                false,
                GpuKind::Integrated,
            ),
            gpu(
                "0000:01:00.0",
                VENDOR_NVIDIA,
                "nvidia",
                true,
                GpuKind::Discrete,
            ),
        ];
        assert_eq!(pick(&gpus), None);
    }

    #[test]
    fn a_desktop_without_vulkan_whose_amd_dgpu_drives_the_display_is_left_alone() {
        let gpus = [
            gpu(
                "0000:00:02.0",
                VENDOR_INTEL,
                "i915",
                false,
                GpuKind::Unknown,
            ),
            gpu("0000:03:00.0", VENDOR_AMD, "amdgpu", true, GpuKind::Unknown),
        ];
        assert_eq!(pick(&gpus), None);
    }

    #[test]
    fn a_single_gpu_is_left_alone() {
        assert_eq!(pick(&[intel_igpu(GpuKind::Integrated)]), None);
        assert_eq!(pick(&[]), None);
    }

    #[test]
    fn without_vulkan_an_intel_igpu_on_bus_zero_falls_back_to_the_other_gpu() {
        let gpus = [intel_igpu(GpuKind::Unknown), nvidia_dgpu(GpuKind::Unknown)];
        assert_eq!(pick(&gpus).unwrap().vendor, VENDOR_NVIDIA);
    }

    #[test]
    fn without_vulkan_an_amd_apu_display_is_not_assumed_integrated() {
        let gpus = [amd_apu(GpuKind::Unknown), nvidia_dgpu(GpuKind::Unknown)];
        assert_eq!(pick(&gpus), None);
    }

    #[test]
    fn a_known_discrete_gpu_is_picked_even_when_the_display_gpu_has_no_vulkan_driver() {
        let gpus = [amd_apu(GpuKind::Unknown), nvidia_dgpu(GpuKind::Discrete)];
        assert_eq!(pick(&gpus).unwrap().vendor, VENDOR_NVIDIA);
    }

    #[test]
    fn a_gpu_bound_to_vfio_is_never_picked() {
        let gpus = [
            intel_igpu(GpuKind::Integrated),
            gpu(
                "0000:01:00.0",
                VENDOR_NVIDIA,
                "vfio-pci",
                false,
                GpuKind::Unknown,
            ),
        ];
        assert_eq!(pick(&gpus), None);
    }

    #[test]
    fn no_boot_vga_means_no_change() {
        let mut igpu = intel_igpu(GpuKind::Integrated);
        igpu.boot_vga = false;
        assert_eq!(pick(&[igpu, nvidia_dgpu(GpuKind::Discrete)]), None);
    }

    #[test]
    fn vulkan_kinds_are_matched_by_vendor_and_device() {
        let mut gpus = [intel_igpu(GpuKind::Unknown), nvidia_dgpu(GpuKind::Unknown)];
        gpus[1].device = 0x2560;
        apply_vulkan_kinds(
            &mut gpus,
            &[
                (u32::from(VENDOR_INTEL), 0x1234, GpuKind::Integrated),
                (u32::from(VENDOR_NVIDIA), 0x2560, GpuKind::Discrete),
                (0x10005, 0, GpuKind::Unknown),
            ],
        );
        assert_eq!(gpus[0].kind, GpuKind::Integrated);
        assert_eq!(gpus[1].kind, GpuKind::Discrete);
    }

    #[test]
    fn conflicting_vulkan_kinds_stay_unknown() {
        let mut gpus = [intel_igpu(GpuKind::Unknown)];
        apply_vulkan_kinds(
            &mut gpus,
            &[
                (u32::from(VENDOR_INTEL), 0x1234, GpuKind::Integrated),
                (u32::from(VENDOR_INTEL), 0x1234, GpuKind::Discrete),
            ],
        );
        assert_eq!(gpus[0].kind, GpuKind::Unknown);
    }

    #[test]
    fn wayland_is_detected_from_either_variable() {
        assert!(is_wayland_session(Some("wayland-0"), None));
        assert!(is_wayland_session(None, Some("wayland")));
        assert!(!is_wayland_session(Some(""), Some("x11")));
        assert!(!is_wayland_session(None, None));
    }

    #[test]
    fn an_x11_session_gets_nothing() {
        let gpus = [
            intel_igpu(GpuKind::Integrated),
            nvidia_dgpu(GpuKind::Discrete),
        ];
        let facts = LaunchFacts {
            wayland: false,
            ..wayland_with(pick(&gpus), Some(0x21))
        };
        assert_eq!(launch_env(&facts, no_user_env), LaunchEnv::default());
    }

    #[test]
    fn wayland_with_an_argb_visual_sets_the_egl_pair() {
        let env = launch_env(&wayland_with(None, Some(0x21)), no_user_env);
        assert_eq!(
            env.set,
            vec![
                (EGL_PLATFORM, "x11".to_string()),
                (SDL_VISUAL_ID, "0x21".to_string())
            ]
        );
        assert!(env.egl_x11);
    }

    #[test]
    fn without_an_argb_visual_neither_egl_variable_is_set() {
        let gpus = [
            intel_igpu(GpuKind::Integrated),
            nvidia_dgpu(GpuKind::Discrete),
        ];
        let env = launch_env(&wayland_with(pick(&gpus), None), no_user_env);
        assert!(env.set.is_empty());
        assert!(!env.egl_x11);
    }

    #[test]
    fn the_opt_out_drops_the_egl_pair_and_nvidia_offload_but_keeps_dri_prime() {
        let nvidia = [
            intel_igpu(GpuKind::Integrated),
            nvidia_dgpu(GpuKind::Discrete),
        ];
        let facts = LaunchFacts {
            egl_x11_opted_out: true,
            ..wayland_with(pick(&nvidia), Some(0x21))
        };
        assert!(launch_env(&facts, no_user_env).set.is_empty());

        let amd = [
            intel_igpu(GpuKind::Integrated),
            gpu(
                "0000:03:00.0",
                VENDOR_AMD,
                "amdgpu",
                false,
                GpuKind::Discrete,
            ),
        ];
        let facts = LaunchFacts {
            egl_x11_opted_out: true,
            ..wayland_with(pick(&amd), Some(0x21))
        };
        assert_eq!(names(&launch_env(&facts, no_user_env)), vec![DRI_PRIME]);
    }

    #[test]
    fn a_user_egl_platform_suppresses_both_egl_variables() {
        let user = |name: &str| (name == EGL_PLATFORM).then(|| "wayland".to_string());
        let env = launch_env(&wayland_with(None, Some(0x21)), user);
        assert!(env.set.is_empty());
        assert_eq!(env.kept_from_user, vec![EGL_PLATFORM]);
        assert!(!env.egl_x11);
    }

    #[test]
    fn a_user_egl_x11_pair_still_enables_nvidia_offload() {
        let gpus = [
            intel_igpu(GpuKind::Integrated),
            nvidia_dgpu(GpuKind::Discrete),
        ];
        let user = |name: &str| match name {
            EGL_PLATFORM => Some("x11".to_string()),
            SDL_VISUAL_ID => Some("0x5a".to_string()),
            _ => None,
        };
        let env = launch_env(&wayland_with(pick(&gpus), None), user);
        assert_eq!(
            names(&env),
            vec![
                NV_PRIME_RENDER_OFFLOAD,
                GLX_VENDOR_LIBRARY_NAME,
                VK_LAYER_NV_OPTIMUS
            ]
        );
    }

    #[test]
    fn a_user_dri_prime_is_never_overridden() {
        let gpus = [
            intel_igpu(GpuKind::Integrated),
            gpu(
                "0000:03:00.0",
                VENDOR_AMD,
                "amdgpu",
                false,
                GpuKind::Discrete,
            ),
        ];
        let user = |name: &str| (name == DRI_PRIME).then(|| "0".to_string());
        let env = launch_env(&wayland_with(pick(&gpus), Some(0x21)), user);
        assert_eq!(names(&env), vec![EGL_PLATFORM, SDL_VISUAL_ID]);
        assert_eq!(env.kept_from_user, vec![DRI_PRIME]);
    }

    #[test]
    fn a_hybrid_nvidia_laptop_on_wayland_gets_the_full_set() {
        let gpus = [
            intel_igpu(GpuKind::Integrated),
            nvidia_dgpu(GpuKind::Discrete),
        ];
        let env = launch_env(&wayland_with(pick(&gpus), Some(0x21)), no_user_env);
        assert_eq!(
            names(&env),
            vec![
                EGL_PLATFORM,
                SDL_VISUAL_ID,
                NV_PRIME_RENDER_OFFLOAD,
                GLX_VENDOR_LIBRARY_NAME,
                VK_LAYER_NV_OPTIMUS
            ]
        );
    }
}
