package com.projectx.game.hooks.impl

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.HookManager
import com.projectx.game.hooks.SlotHook
import com.projectx.game.hooks.SupportedOn
import com.projectx.game.memory.NativeAccess
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.readLong
import com.projectx.game.nxt.OClient
import com.projectx.game.nxt.ORenderView
import com.projectx.game.nxt.OVulkanGlobals
import com.projectx.game.nxt.OVulkanPresentContext
import com.projectx.game.nxt.OVulkanRenderDevice
import com.projectx.game.nxt.OVulkanSwapchain
import com.projectx.game.nxt.OWindowFrame
import com.projectx.game.nxt.extent
import com.projectx.game.platform.Platform
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.backend.rendering.ImGuiRenderManager
import com.projectx.ui.backend.rendering.OverlayFrameDriver
import com.projectx.ui.backend.rendering.UiFrameProducer
import java.lang.foreign.MemorySegment

/**
 * Vulkan counterpart of [GdiSwapBuffers]. The Vulkan client calls vkQueuePresentKHR through volk's
 * function table rather than a fixed import, so the overlay replaces that table slot: each present
 * first draws the overlay onto the image being presented, then presents it.
 */
@SupportedOn(Platform.WINDOWS)
object VulkanQueuePresent {

    @JvmStatic
    @SlotHook("OVulkanGlobals.QUEUE_PRESENT")
    fun queuePresentHook(queue: MemorySegment, presentInfo: MemorySegment): Int {
        var presented = presentInfo
        // During teardown become a pure passthrough so the render thread never touches ImGui state
        // while it is being shut down.
        if (!Bootstrap.stopping) {
            NativeBridge.vulkanBeginFrame(presentInfo)
            OverlayFrameDriver.drawFrame(::initImGui)
            presented = NativeBridge.vulkanEndFrame(presentInfo)
        }
        return HookManager.slotOriginal(::queuePresentHook.name).invokeExact(queue, presented) as Int
    }

    /** Everything is read from the client's own objects on the render thread, inside its present call. */
    private fun initImGui() {
        try {
            val device = NativeAccess.BASE_ADDR.deref(OVulkanGlobals.RENDER_DEVICE, OVulkanRenderDevice.extent)
            val window = Bootstrap.client.ptr
                .deref(OClient.WINDOW_FRAME, OWindowFrame.extent)
                .deref(OWindowFrame.RENDER_VIEW, ORenderView.extent)
                .readLong(ORenderView.WINDOW_HANDLE)
            if (window == 0L) {
                println("[VulkanQueuePresent] The render view has no window yet; overlay input will not bind")
            }

            NativeBridge.initVulkan(
                window = MemorySegment.ofAddress(window),
                instance = MemorySegment.ofAddress(device.readLong(OVulkanRenderDevice.INSTANCE)),
                physicalDevice = MemorySegment.ofAddress(device.readLong(OVulkanRenderDevice.PHYSICAL_DEVICE)),
                device = MemorySegment.ofAddress(device.readLong(OVulkanRenderDevice.DEVICE)),
                queue = MemorySegment.ofAddress(device.readLong(OVulkanRenderDevice.QUEUE)),
                queueFamily = device.readInt(OVulkanRenderDevice.QUEUE_FAMILY),
                apiVersion = device.readInt(OVulkanRenderDevice.API_VERSION),
                surfaceFromSwapchain = OVulkanPresentContext.SURFACE - OVulkanPresentContext.SWAPCHAIN,
                widthOffset = OVulkanSwapchain.WIDTH,
                heightOffset = OVulkanSwapchain.HEIGHT,
            )

            ImGuiRenderManager.initialize()
            UiFrameProducer.enabled = true

            println("[VulkanQueuePresent] ImGui initialized successfully")
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
