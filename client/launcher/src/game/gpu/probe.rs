use super::policy::GpuKind;
use std::ffi::{c_char, c_int, c_ulong, c_void, CStr};
use std::mem;
use std::ptr;

extern "C" {
    fn dlopen(filename: *const c_char, flags: c_int) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
}

const RTLD_NOW: c_int = 2;

/// Never dlclose'd: unloading GPU driver stacks (NVIDIA's especially) can crash at exit.
struct Library(*mut c_void);

impl Library {
    fn open(name: &CStr) -> Option<Self> {
        let handle = unsafe { dlopen(name.as_ptr(), RTLD_NOW) };
        (!handle.is_null()).then_some(Self(handle))
    }

    unsafe fn symbol<F: Copy>(&self, name: &CStr) -> Option<F> {
        let address = dlsym(self.0, name.as_ptr());
        (!address.is_null()).then(|| mem::transmute_copy(&address))
    }
}

#[repr(C)]
struct XVisualInfo {
    _visual: *mut c_void,
    visualid: c_ulong,
    _screen: c_int,
    _depth: c_int,
    _class: c_int,
    _red_mask: c_ulong,
    _green_mask: c_ulong,
    _blue_mask: c_ulong,
    _colormap_size: c_int,
    _bits_per_rgb: c_int,
}

type XOpenDisplay = unsafe extern "C" fn(*const c_char) -> *mut c_void;
type XDefaultScreen = unsafe extern "C" fn(*mut c_void) -> c_int;
type XMatchVisualInfo =
    unsafe extern "C" fn(*mut c_void, c_int, c_int, c_int, *mut XVisualInfo) -> c_int;
type XCloseDisplay = unsafe extern "C" fn(*mut c_void) -> c_int;

const TRUE_COLOR: c_int = 4;
const ARGB_DEPTH: c_int = 32;

/// The 32-bit TrueColor visual on `$DISPLAY`'s default screen.
pub fn argb_visual_id() -> Option<u64> {
    let x11 = Library::open(c"libX11.so.6")?;
    unsafe {
        let open: XOpenDisplay = x11.symbol(c"XOpenDisplay")?;
        let default_screen: XDefaultScreen = x11.symbol(c"XDefaultScreen")?;
        let match_visual: XMatchVisualInfo = x11.symbol(c"XMatchVisualInfo")?;
        let close: XCloseDisplay = x11.symbol(c"XCloseDisplay")?;

        let display = open(ptr::null());
        if display.is_null() {
            return None;
        }
        let mut info: XVisualInfo = mem::zeroed();
        let found = match_visual(
            display,
            default_screen(display),
            ARGB_DEPTH,
            TRUE_COLOR,
            &mut info,
        );
        close(display);
        (found != 0).then_some(info.visualid as u64)
    }
}

type VkInstance = *mut c_void;
type VkPhysicalDevice = *mut c_void;
type VkResult = i32;

#[repr(C)]
struct VkInstanceCreateInfo {
    s_type: i32,
    p_next: *const c_void,
    flags: u32,
    p_application_info: *const c_void,
    enabled_layer_count: u32,
    pp_enabled_layer_names: *const *const c_char,
    enabled_extension_count: u32,
    pp_enabled_extension_names: *const *const c_char,
}

/// Only the leading fields are read; the tail covers the name, limits and sparse properties.
#[repr(C, align(8))]
struct VkPhysicalDeviceProperties {
    _versions: [u32; 2],
    vendor_id: u32,
    device_id: u32,
    device_type: i32,
    _tail: [u8; 4096],
}

type GetInstanceProcAddr = unsafe extern "C" fn(VkInstance, *const c_char) -> *mut c_void;
type CreateInstance =
    unsafe extern "C" fn(*const VkInstanceCreateInfo, *const c_void, *mut VkInstance) -> VkResult;
type DestroyInstance = unsafe extern "C" fn(VkInstance, *const c_void);
type EnumeratePhysicalDevices =
    unsafe extern "C" fn(VkInstance, *mut u32, *mut VkPhysicalDevice) -> VkResult;
type GetPhysicalDeviceProperties =
    unsafe extern "C" fn(VkPhysicalDevice, *mut VkPhysicalDeviceProperties);

const VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO: i32 = 1;
const VK_SUCCESS: VkResult = 0;
const VK_INCOMPLETE: VkResult = 5;
const VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: i32 = 1;
const VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: i32 = 2;

unsafe fn proc_addr<F: Copy>(
    get: GetInstanceProcAddr,
    instance: VkInstance,
    name: &CStr,
) -> Option<F> {
    let address = get(instance, name.as_ptr());
    (!address.is_null()).then(|| mem::transmute_copy(&address))
}

/// `(vendor, device, kind)` for every physical device the Vulkan loader reports, or `None`
/// when there is no loader or no instance.
pub fn vulkan_devices() -> Option<Vec<(u32, u32, GpuKind)>> {
    let loader = Library::open(c"libvulkan.so.1")?;
    unsafe {
        let get: GetInstanceProcAddr = loader.symbol(c"vkGetInstanceProcAddr")?;
        let create: CreateInstance = proc_addr(get, ptr::null_mut(), c"vkCreateInstance")?;
        let info = VkInstanceCreateInfo {
            s_type: VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
            p_next: ptr::null(),
            flags: 0,
            p_application_info: ptr::null(),
            enabled_layer_count: 0,
            pp_enabled_layer_names: ptr::null(),
            enabled_extension_count: 0,
            pp_enabled_extension_names: ptr::null(),
        };
        let mut instance: VkInstance = ptr::null_mut();
        if create(&info, ptr::null(), &mut instance) != VK_SUCCESS || instance.is_null() {
            return None;
        }
        let devices = enumerate(get, instance);
        if let Some(destroy) = proc_addr::<DestroyInstance>(get, instance, c"vkDestroyInstance") {
            destroy(instance, ptr::null());
        }
        devices
    }
}

unsafe fn enumerate(
    get: GetInstanceProcAddr,
    instance: VkInstance,
) -> Option<Vec<(u32, u32, GpuKind)>> {
    let enumerate: EnumeratePhysicalDevices =
        proc_addr(get, instance, c"vkEnumeratePhysicalDevices")?;
    let properties: GetPhysicalDeviceProperties =
        proc_addr(get, instance, c"vkGetPhysicalDeviceProperties")?;

    let mut count = 0u32;
    if enumerate(instance, &mut count, ptr::null_mut()) != VK_SUCCESS {
        return None;
    }
    let mut handles: Vec<VkPhysicalDevice> = vec![ptr::null_mut(); count as usize];
    match enumerate(instance, &mut count, handles.as_mut_ptr()) {
        VK_SUCCESS | VK_INCOMPLETE => handles.truncate(count as usize),
        _ => return None,
    }

    Some(
        handles
            .into_iter()
            .map(|device| {
                let mut props: VkPhysicalDeviceProperties = mem::zeroed();
                properties(device, &mut props);
                let kind = match props.device_type {
                    VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU => GpuKind::Integrated,
                    VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU => GpuKind::Discrete,
                    _ => GpuKind::Unknown,
                };
                (props.vendor_id, props.device_id, kind)
            })
            .collect(),
    )
}
