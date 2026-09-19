#include "projectx_vulkan.h"

#include <windows.h>

#include <cstring>
#include <vector>

#include "imgui.h"
#include "imgui_impl_vulkan.h"

void imgui_log_message(const char *format, ...);

namespace projectx::vulkan {
namespace {

// The client resolves Vulkan through volk, so nothing here is linked against vulkan-1: every call goes
// through the loader DLL the client already has mapped. Handles the client created through that loader
// dispatch correctly through its exports.
#define PROJECTX_VULKAN_FUNCTIONS(X) \
    X(vkGetSwapchainImagesKHR) \
    X(vkGetPhysicalDeviceSurfaceFormatsKHR) \
    X(vkCreateRenderPass) \
    X(vkDestroyRenderPass) \
    X(vkCreateImageView) \
    X(vkDestroyImageView) \
    X(vkCreateFramebuffer) \
    X(vkDestroyFramebuffer) \
    X(vkCreateCommandPool) \
    X(vkDestroyCommandPool) \
    X(vkAllocateCommandBuffers) \
    X(vkFreeCommandBuffers) \
    X(vkBeginCommandBuffer) \
    X(vkEndCommandBuffer) \
    X(vkResetCommandBuffer) \
    X(vkCmdBeginRenderPass) \
    X(vkCmdEndRenderPass) \
    X(vkCreateSemaphore) \
    X(vkDestroySemaphore) \
    X(vkCreateFence) \
    X(vkDestroyFence) \
    X(vkWaitForFences) \
    X(vkResetFences) \
    X(vkQueueSubmit) \
    X(vkDeviceWaitIdle)

struct Api {
#define PROJECTX_DECLARE(name) PFN_##name name = nullptr;
    PROJECTX_VULKAN_FUNCTIONS(PROJECTX_DECLARE)
#undef PROJECTX_DECLARE
};

Api vk;
HMODULE g_loader = nullptr;

VkInstance g_instance = VK_NULL_HANDLE;
VkPhysicalDevice g_physical_device = VK_NULL_HANDLE;
VkDevice g_device = VK_NULL_HANDLE;
VkQueue g_queue = VK_NULL_HANDLE;
uint32_t g_queue_family = 0;
int64_t g_surface_from_swapchain = 0;
int64_t g_width_offset = 0;
int64_t g_height_offset = 0;
bool g_offsets_known = false;
bool g_ready = false;

VkCommandPool g_command_pool = VK_NULL_HANDLE;

/// How many overlay frames may be in flight at once.
///
/// Deliberately fixed, and deliberately NOT the client's swapchain image count. The overlay owns its own
/// command buffers, fences and semaphores, and builds one framebuffer per swapchain image separately, so
/// nothing here has to agree with the client. What must agree is the slot ring and the ImGui backend's
/// vertex/index buffer ring, and keeping both at this constant makes them equal by construction.
///
/// Sizing them from the swapchain instead is what went wrong before: both were fixed at init from
/// whichever count the first present happened to show, and the client recreates its swapchain (2 images
/// then 3, on a resize) without the backend's ImageCount ever following - which cannot be changed after
/// ImGui_ImplVulkan_Init in any case.
///
/// The fence for a slot is waited on before that slot records again, so a submission this many frames old
/// has completed. That is exactly the guarantee the backend's texture-free heuristic needs, since it frees
/// a texture once UnusedFrames reaches ImageCount.
constexpr uint32_t OVERLAY_FRAMES_IN_FLIGHT = 3;

// One slot per ImGui vertex/index buffer set. The backend rotates through ImageCount buffer sets, one
// per drawn frame, so each slot's fence is waited on before the buffers it recorded against are reused.
struct Slot {
    VkCommandBuffer commands = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkSemaphore done = VK_NULL_HANDLE;
};
std::vector<Slot> g_slots;
uint32_t g_slot = 0;

struct Target {
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkExtent2D extent{};
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkRenderPass render_pass = VK_NULL_HANDLE;
    std::vector<VkImageView> views;
    std::vector<VkFramebuffer> framebuffers;
};
Target g_target;

struct Frame {
    bool recorded = false;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    uint32_t image = 0;
    VkExtent2D extent{};
    uint32_t wait_count = 0;
    const VkSemaphore *waits = nullptr;
    VkSemaphore signalled = VK_NULL_HANDLE;
};
Frame g_frame;
const VkPresentInfoKHR *g_present = nullptr;
VkPresentInfoKHR g_patched{};

/// Decodes the present being made: which swapchain image it shows, and - through the client object
/// pSwapchains points into - that swapchain's surface and extent.
void read_frame() {
    g_frame = Frame{};
    const VkPresentInfoKHR *info = g_present;
    if (!info || info->sType != VK_STRUCTURE_TYPE_PRESENT_INFO_KHR || info->swapchainCount != 1 ||
        !info->pSwapchains || !info->pImageIndices) {
        return;
    }
    const char *swapchain_object = (const char *)info->pSwapchains;
    g_frame.swapchain = info->pSwapchains[0];
    g_frame.image = info->pImageIndices[0];
    g_frame.wait_count = info->waitSemaphoreCount;
    g_frame.waits = info->pWaitSemaphores;
    std::memcpy(&g_frame.surface, swapchain_object + g_surface_from_swapchain, sizeof(VkSurfaceKHR));
    std::memcpy(&g_frame.extent.width, swapchain_object + g_width_offset, sizeof(uint32_t));
    std::memcpy(&g_frame.extent.height, swapchain_object + g_height_offset, sizeof(uint32_t));
    g_frame.recorded = g_frame.swapchain != VK_NULL_HANDLE && g_frame.surface != VK_NULL_HANDLE &&
                       g_frame.extent.width > 0 && g_frame.extent.height > 0;
}

bool check(VkResult result, const char *what) {
    if (result == VK_SUCCESS) return true;
    imgui_log_message("[Vulkan] %s failed: %d\n", what, (int)result);
    return false;
}

void imgui_check(VkResult result) {
    if (result < 0) imgui_log_message("[Vulkan] ImGui backend call failed: %d\n", (int)result);
}

bool load_functions() {
    g_loader = GetModuleHandleW(L"vulkan-1.dll");
    if (!g_loader) g_loader = LoadLibraryW(L"vulkan-1.dll");
    if (!g_loader) {
        imgui_log_message("[Vulkan] vulkan-1.dll is not available\n");
        return false;
    }
#define PROJECTX_RESOLVE(name) \
    vk.name = (decltype(vk.name))GetProcAddress(g_loader, #name); \
    if (!vk.name) { imgui_log_message("[Vulkan] vulkan-1.dll does not export " #name "\n"); return false; }
    PROJECTX_VULKAN_FUNCTIONS(PROJECTX_RESOLVE)
#undef PROJECTX_RESOLVE
    return true;
}

void destroy_framebuffers() {
    for (VkFramebuffer framebuffer : g_target.framebuffers) vk.vkDestroyFramebuffer(g_device, framebuffer, nullptr);
    for (VkImageView view : g_target.views) vk.vkDestroyImageView(g_device, view, nullptr);
    g_target.framebuffers.clear();
    g_target.views.clear();
}

void wait_for_slots() {
    std::vector<VkFence> fences;
    for (const Slot &slot : g_slots) fences.push_back(slot.fence);
    if (!fences.empty()) vk.vkWaitForFences(g_device, (uint32_t)fences.size(), fences.data(), VK_TRUE, UINT64_MAX);
}

bool surface_format(VkSurfaceKHR surface, VkFormat *format) {
    uint32_t count = 0;
    if (!check(vk.vkGetPhysicalDeviceSurfaceFormatsKHR(g_physical_device, surface, &count, nullptr), "vkGetPhysicalDeviceSurfaceFormatsKHR")) return false;
    if (count == 0) return false;
    std::vector<VkSurfaceFormatKHR> formats(count);
    VkResult result = vk.vkGetPhysicalDeviceSurfaceFormatsKHR(g_physical_device, surface, &count, formats.data());
    if (result != VK_SUCCESS && result != VK_INCOMPLETE) return check(result, "vkGetPhysicalDeviceSurfaceFormatsKHR");
    // The client builds its swapchain from the first format the surface reports, so this is the
    // format of the images being presented.
    *format = formats[0].format;
    return true;
}

bool create_render_pass(VkFormat format) {
    VkAttachmentDescription color{};
    color.format = format;
    color.samples = VK_SAMPLE_COUNT_1_BIT;
    color.loadOp = VK_ATTACHMENT_LOAD_OP_LOAD;
    color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    // The client leaves the image ready to present; the overlay draws over it and leaves it that way.
    color.initialLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    color.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

    VkAttachmentReference reference{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &reference;

    VkRenderPassCreateInfo info{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    info.attachmentCount = 1;
    info.pAttachments = &color;
    info.subpassCount = 1;
    info.pSubpasses = &subpass;
    return check(vk.vkCreateRenderPass(g_device, &info, nullptr, &g_target.render_pass), "vkCreateRenderPass");
}

bool create_framebuffers() {
    uint32_t count = 0;
    if (!check(vk.vkGetSwapchainImagesKHR(g_device, g_frame.swapchain, &count, nullptr), "vkGetSwapchainImagesKHR")) return false;
    std::vector<VkImage> images(count);
    if (!check(vk.vkGetSwapchainImagesKHR(g_device, g_frame.swapchain, &count, images.data()), "vkGetSwapchainImagesKHR")) return false;

    for (VkImage image : images) {
        VkImageViewCreateInfo view_info{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        view_info.image = image;
        view_info.viewType = VK_IMAGE_VIEW_TYPE_2D;
        view_info.format = g_target.format;
        view_info.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VkImageView view = VK_NULL_HANDLE;
        if (!check(vk.vkCreateImageView(g_device, &view_info, nullptr, &view), "vkCreateImageView")) return false;
        g_target.views.push_back(view);

        VkFramebufferCreateInfo framebuffer_info{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        framebuffer_info.renderPass = g_target.render_pass;
        framebuffer_info.attachmentCount = 1;
        framebuffer_info.pAttachments = &view;
        framebuffer_info.width = g_frame.extent.width;
        framebuffer_info.height = g_frame.extent.height;
        framebuffer_info.layers = 1;
        VkFramebuffer framebuffer = VK_NULL_HANDLE;
        if (!check(vk.vkCreateFramebuffer(g_device, &framebuffer_info, nullptr, &framebuffer), "vkCreateFramebuffer")) return false;
        g_target.framebuffers.push_back(framebuffer);
    }
    return true;
}

/// Builds render pass, views and framebuffers for the swapchain this present shows, whenever the client
/// has created a new one or resized it.
bool ensure_target() {
    if (!g_frame.recorded) return false;
    if (g_target.swapchain == g_frame.swapchain && g_target.extent.width == g_frame.extent.width &&
        g_target.extent.height == g_frame.extent.height && !g_target.framebuffers.empty()) {
        return g_frame.image < g_target.framebuffers.size();
    }

    wait_for_slots();
    destroy_framebuffers();
    g_target.swapchain = VK_NULL_HANDLE;

    VkFormat format = VK_FORMAT_UNDEFINED;
    if (!surface_format(g_frame.surface, &format)) return false;
    if (g_target.render_pass == VK_NULL_HANDLE || format != g_target.format) {
        if (g_target.render_pass != VK_NULL_HANDLE) vk.vkDestroyRenderPass(g_device, g_target.render_pass, nullptr);
        g_target.render_pass = VK_NULL_HANDLE;
        g_target.format = format;
        if (!create_render_pass(format)) return false;
        if (g_ready) {
            ImGui_ImplVulkan_PipelineInfo pipeline{};
            pipeline.RenderPass = g_target.render_pass;
            ImGui_ImplVulkan_CreateMainPipeline(&pipeline);
        }
    }

    g_target.extent = g_frame.extent;
    if (!create_framebuffers()) {
        destroy_framebuffers();
        return false;
    }
    g_target.swapchain = g_frame.swapchain;
    imgui_log_message("[Vulkan] Overlay target: %u images, %ux%u, format %d\n",
                      (unsigned)g_target.framebuffers.size(), g_frame.extent.width, g_frame.extent.height, (int)format);
    return g_frame.image < g_target.framebuffers.size();
}

bool create_slots(uint32_t count) {
    VkCommandPoolCreateInfo pool_info{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pool_info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool_info.queueFamilyIndex = g_queue_family;
    if (!check(vk.vkCreateCommandPool(g_device, &pool_info, nullptr, &g_command_pool), "vkCreateCommandPool")) return false;

    g_slots.resize(count);
    for (Slot &slot : g_slots) {
        VkCommandBufferAllocateInfo allocate{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        allocate.commandPool = g_command_pool;
        allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocate.commandBufferCount = 1;
        if (!check(vk.vkAllocateCommandBuffers(g_device, &allocate, &slot.commands), "vkAllocateCommandBuffers")) return false;

        VkFenceCreateInfo fence_info{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fence_info.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (!check(vk.vkCreateFence(g_device, &fence_info, nullptr, &slot.fence), "vkCreateFence")) return false;

        VkSemaphoreCreateInfo semaphore_info{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        if (!check(vk.vkCreateSemaphore(g_device, &semaphore_info, nullptr, &slot.done), "vkCreateSemaphore")) return false;
    }
    return true;
}

PFN_vkVoidFunction load_for_imgui(const char *name, void *) {
    return (PFN_vkVoidFunction)GetProcAddress(g_loader, name);
}

} // namespace

bool init(void *instance, void *physical_device, void *device, void *queue, uint32_t queue_family,
          uint32_t api_version, int64_t surface_from_swapchain, int64_t width_offset, int64_t height_offset) {
    if (g_ready) return true;
    if (!instance || !physical_device || !device || !queue) {
        imgui_log_message("[Vulkan] init: the client's Vulkan device is not created yet\n");
        return false;
    }
    if (!load_functions()) return false;

    g_instance = (VkInstance)instance;
    g_physical_device = (VkPhysicalDevice)physical_device;
    g_device = (VkDevice)device;
    g_queue = (VkQueue)queue;
    g_queue_family = queue_family;
    g_surface_from_swapchain = surface_from_swapchain;
    g_width_offset = width_offset;
    g_height_offset = height_offset;
    g_offsets_known = true;

    // begin_frame already ran for this present, but could not decode it before the offsets were known.
    read_frame();
    if (!g_frame.recorded) {
        imgui_log_message("[Vulkan] init: no present has been recorded to size the overlay for\n");
        return false;
    }
    if (!ensure_target()) {
        imgui_log_message("[Vulkan] init: could not build a render target for the client's swapchain\n");
        return false;
    }

    if (!create_slots(OVERLAY_FRAMES_IN_FLIGHT)) return false;

    if (!ImGui_ImplVulkan_LoadFunctions(api_version, load_for_imgui)) {
        imgui_log_message("[Vulkan] ImGui_ImplVulkan_LoadFunctions failed\n");
        return false;
    }

    ImGui_ImplVulkan_InitInfo info{};
    info.ApiVersion = api_version;
    info.Instance = g_instance;
    info.PhysicalDevice = g_physical_device;
    info.Device = g_device;
    info.QueueFamily = g_queue_family;
    info.Queue = g_queue;
    info.DescriptorPoolSize = 1024;
    info.MinImageCount = OVERLAY_FRAMES_IN_FLIGHT;
    info.ImageCount = OVERLAY_FRAMES_IN_FLIGHT;
    info.PipelineInfoMain.RenderPass = g_target.render_pass;
    info.CheckVkResultFn = imgui_check;
    if (!ImGui_ImplVulkan_Init(&info)) {
        imgui_log_message("[Vulkan] ImGui_ImplVulkan_Init failed\n");
        return false;
    }

    g_ready = true;
    imgui_log_message("[Vulkan] Overlay bound to the client's device (queue family %u, API 0x%x)\n", queue_family, api_version);
    return true;
}

bool ready() { return g_ready; }

void begin_frame(void *present_info) {
    g_present = (const VkPresentInfoKHR *)present_info;
    if (g_offsets_known) {
        read_frame();
    } else {
        g_frame = Frame{};
    }
}

bool frame_extent(uint32_t *width, uint32_t *height) {
    if (!g_frame.recorded || g_frame.extent.width == 0 || g_frame.extent.height == 0) return false;
    *width = g_frame.extent.width;
    *height = g_frame.extent.height;
    return true;
}

void new_frame() { ImGui_ImplVulkan_NewFrame(); }

void render(ImDrawData *draw_data) {
    if (!g_ready || !draw_data || !ensure_target()) return;
    if (draw_data->DisplaySize.x * draw_data->FramebufferScale.x <= 0.5f ||
        draw_data->DisplaySize.y * draw_data->FramebufferScale.y <= 0.5f) {
        return;
    }

    // Mirrors the backend's buffer rotation, which advances once per frame it actually draws.
    g_slot = (g_slot + 1) % (uint32_t)g_slots.size();
    Slot &slot = g_slots[g_slot];
    if (!check(vk.vkWaitForFences(g_device, 1, &slot.fence, VK_TRUE, UINT64_MAX), "vkWaitForFences")) return;
    vk.vkResetFences(g_device, 1, &slot.fence);
    vk.vkResetCommandBuffer(slot.commands, 0);

    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!check(vk.vkBeginCommandBuffer(slot.commands, &begin), "vkBeginCommandBuffer")) return;

    VkRenderPassBeginInfo pass{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    pass.renderPass = g_target.render_pass;
    pass.framebuffer = g_target.framebuffers[g_frame.image];
    pass.renderArea.extent = g_target.extent;
    vk.vkCmdBeginRenderPass(slot.commands, &pass, VK_SUBPASS_CONTENTS_INLINE);
    ImGui_ImplVulkan_RenderDrawData(draw_data, slot.commands);
    vk.vkCmdEndRenderPass(slot.commands);
    if (!check(vk.vkEndCommandBuffer(slot.commands), "vkEndCommandBuffer")) return;

    // Wait on what the client's present was going to wait on, so the overlay lands on its finished
    // image, and hand the present our semaphore in its place.
    std::vector<VkPipelineStageFlags> stages(g_frame.wait_count, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = g_frame.wait_count;
    submit.pWaitSemaphores = g_frame.waits;
    submit.pWaitDstStageMask = stages.empty() ? nullptr : stages.data();
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &slot.commands;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &slot.done;
    if (!check(vk.vkQueueSubmit(g_queue, 1, &submit, slot.fence), "vkQueueSubmit")) return;
    g_frame.signalled = slot.done;
}

void *end_frame(void *present_info) {
    g_present = nullptr;
    if (g_frame.signalled == VK_NULL_HANDLE) return present_info;
    g_patched = *(const VkPresentInfoKHR *)present_info;
    g_patched.waitSemaphoreCount = 1;
    g_patched.pWaitSemaphores = &g_frame.signalled;
    return &g_patched;
}

void shutdown() {
    if (!g_ready) return;
    vk.vkDeviceWaitIdle(g_device);
    ImGui_ImplVulkan_Shutdown();
    destroy_framebuffers();
    if (g_target.render_pass != VK_NULL_HANDLE) vk.vkDestroyRenderPass(g_device, g_target.render_pass, nullptr);
    for (Slot &slot : g_slots) {
        vk.vkDestroySemaphore(g_device, slot.done, nullptr);
        vk.vkDestroyFence(g_device, slot.fence, nullptr);
    }
    g_slots.clear();
    if (g_command_pool != VK_NULL_HANDLE) vk.vkDestroyCommandPool(g_device, g_command_pool, nullptr);
    g_command_pool = VK_NULL_HANDLE;
    g_target = Target{};
    g_ready = false;
}

} // namespace projectx::vulkan
