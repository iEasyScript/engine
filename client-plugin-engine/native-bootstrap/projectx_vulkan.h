#pragma once

#include <cstdint>

struct ImDrawData;

// The overlay's Vulkan renderer, used by the Vulkan build of the Windows client. The client owns the
// instance, device, queue and swapchain; the overlay borrows them from inside the client's own
// vkQueuePresentKHR call and draws onto the image about to be presented.
namespace projectx::vulkan {

/// Binds to the client's device. Must run on the render thread inside a present, after begin_frame()
/// recorded that present, because the render pass is built for the swapchain it presents.
/// `surface_from_swapchain`, `width_offset` and `height_offset` locate the client's surface and
/// swapchain extent relative to `VkPresentInfoKHR::pSwapchains`.
bool init(void *instance, void *physical_device, void *device, void *queue, uint32_t queue_family,
          uint32_t api_version, int64_t surface_from_swapchain, int64_t width_offset, int64_t height_offset);

bool ready();

/// Records which swapchain image this present shows. Called for every present, before the overlay frame.
void begin_frame(void *present_info);

/// Width and height of the swapchain being presented, or false when there is no usable target.
bool frame_extent(uint32_t *width, uint32_t *height);

void new_frame();

/// Draws [draw_data] onto the recorded image and submits it behind the client's own rendering.
void render(ImDrawData *draw_data);

/// The present info to hand to the client's vkQueuePresentKHR: [present_info] itself when nothing was
/// drawn, otherwise a copy that waits on the overlay's submission instead of the client's.
void *end_frame(void *present_info);

void shutdown();

} // namespace projectx::vulkan
