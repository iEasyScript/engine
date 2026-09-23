package com.projectx.ui.backend.native

import com.projectx.game.memory.NativeAccess
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiStyleVar
import com.projectx.ui.backend.native.StringAllocator.allocateString
import com.projectx.ui.compose.hud.DrawRecorder
import com.projectx.ui.compose.hud.Shape
import com.projectx.ui.compose.hud.TexturePixels
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

object NativeBridge {
    // Per-frame arena for transient string allocations to avoid leaking memory each frame
    private val frameArena = ThreadLocal<Arena?>()
    private var tabDebugLogged: Boolean = false
    private var tabItemDebugLogged: Boolean = false

    private val imguiInit by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_Init") {
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        }
    }

    private val imguiInitVulkan by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_InitVulkan") {
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
            )
        }
    }

    private val vulkanBeginFrame by lazy {
        NativeAccess.getFunction("ProjectX_Vulkan_BeginFrame") {
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        }
    }

    private val vulkanEndFrame by lazy {
        NativeAccess.getFunction("ProjectX_Vulkan_EndFrame") {
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        }
    }

    private val imguiShutdown by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_Shutdown") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiNewFrame by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_NewFrame") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiRender by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_Render") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiProcessEvent by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_ProcessEvent") {
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        }
    }

    private val imguiWantCaptureMouse by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_WantCaptureMouse") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN)
        }
    }

    private val imguiWantCaptureKeyboard by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_WantCaptureKeyboard") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN)
        }
    }

    private val imguiBegin by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_Begin") {
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT
            )
        }
    }

    private val imguiEnd by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_End") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiSetNextWindowPos by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_SetNextWindowPos") {
            FunctionDescriptor.ofVoid(
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT
            )
        }
    }

    private val imguiSetNextWindowSize by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_SetNextWindowSize") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT)
        }
    }

    private val imguiText by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_Text") {
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        }
    }

    private val imguiTextWrapped by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_TextWrapped") {
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        }
    }

    private val imguiButton by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_Button") {
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT
            )
        }
    }

    private val imguiCheckbox by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_Checkbox") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        }
    }

    private val imguiInputInt by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_InputInt") {
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT
            )
        }
    }

    private val imguiInputText by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_InputText") {
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT
            )
        }
    }

    private val imguiSliderFloat by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_SliderFloat") {
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT
            )
        }
    }

    private val imguiSliderInt by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_SliderInt") {
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT
            )
        }
    }

    private val imguiSeparator by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_Separator") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiSameLine by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_SameLine") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiNewLine by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_NewLine") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiSpacing by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_Spacing") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiIsItemHovered by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_IsItemHovered") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT)
        }
    }

    private val imguiIsItemClicked by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_IsItemClicked") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT)
        }
    }

    private val imguiGetDisplaySize by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetDisplaySize") {
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        }
    }

    fun init(sdlWindow: MemorySegment, glContext: MemorySegment) {
        imguiInit.invokeExact(sdlWindow, glContext)
    }

    /**
     * Binds the overlay to the client's own Vulkan device. The three offsets locate, from the
     * `VkPresentInfoKHR.pSwapchains` pointer, the surface and the swapchain's width and height.
     */
    fun initVulkan(
        window: MemorySegment,
        instance: MemorySegment,
        physicalDevice: MemorySegment,
        device: MemorySegment,
        queue: MemorySegment,
        queueFamily: Int,
        apiVersion: Int,
        surfaceFromSwapchain: Long,
        widthOffset: Long,
        heightOffset: Long,
    ) {
        imguiInitVulkan.invokeExact(
            window, instance, physicalDevice, device, queue,
            queueFamily, apiVersion, surfaceFromSwapchain, widthOffset, heightOffset,
        )
    }

    /** Records the image this present will show, so the overlay frame that follows draws onto it. */
    fun vulkanBeginFrame(presentInfo: MemorySegment) {
        vulkanBeginFrame.invokeExact(presentInfo)
    }

    /** The present info to hand to the client's vkQueuePresentKHR: waits on the overlay once it drew. */
    fun vulkanEndFrame(presentInfo: MemorySegment): MemorySegment =
        vulkanEndFrame.invokeExact(presentInfo) as MemorySegment

    fun shutdown() {
        frameArena.get()?.close()
        frameArena.set(null)
        imguiShutdown.invokeExact()
    }

    fun newFrame() {
        frameArena.get()?.close()
        frameArena.set(Arena.ofConfined())
        StringAllocator.newFrame()
        imguiNewFrame.invokeExact()
    }

    fun render() {
        imguiRender.invokeExact()
        frameArena.get()?.close()
        frameArena.set(null)
        StringAllocator.endFrame()
    }

    fun processEvent(event: MemorySegment) {
        imguiProcessEvent.invokeExact(event)
    }

    fun wantCaptureMouse(): Boolean {
        // Avoid NPEs if ImGui isn't ready; native returns false in that case
        return try { imguiWantCaptureMouse.invokeExact() as Boolean } catch (_: Throwable) { false }
    }

    fun wantCaptureKeyboard(): Boolean {
        return try { imguiWantCaptureKeyboard.invokeExact() as Boolean } catch (_: Throwable) { false }
    }

    fun begin(name: String, pOpen: MemorySegment? = null, flags: Int = 0): Boolean {
        val nameSegment = allocateString(name)
        return imguiBegin.invokeExact(nameSegment, pOpen ?: MemorySegment.NULL, flags) as Boolean
    }

    fun end() {
        imguiEnd.invokeExact()
    }

    fun setNextWindowPos(x: Float, y: Float, cond: Int = 0, pivotX: Float = 0f, pivotY: Float = 0f) {
        imguiSetNextWindowPos.invokeExact(x, y, cond, pivotX, pivotY)
    }

    fun setNextWindowSize(width: Float, height: Float, cond: Int = 0) {
        imguiSetNextWindowSize.invokeExact(width, height, cond)
    }

    fun text(text: String) {
        val textSegment = allocateString(text)
        imguiText.invokeExact(textSegment)
    }

    fun textWrapped(text: String) {
        val textSegment = allocateString(text)
        imguiTextWrapped.invokeExact(textSegment)
    }

    fun button(label: String, width: Float = 0f, height: Float = 0f): Boolean {
        val labelSegment = allocateString(label)
        return imguiButton.invokeExact(labelSegment, width, height) as Boolean
    }

    fun checkbox(label: String, value: MemorySegment): Boolean {
        val labelSegment = allocateString(label)
        return imguiCheckbox.invokeExact(labelSegment, value) as Boolean
    }

    fun inputInt(label: String, value: MemorySegment, step: Int = 1, stepFast: Int = 10, flags: Int = 0): Boolean {
        val labelSegment = allocateString(label)
        return imguiInputInt.invokeExact(labelSegment, value, step, stepFast, flags) as Boolean
    }

    fun inputText(label: String, buffer: MemorySegment, bufferSize: Int, flags: Int = 0): Boolean {
        val labelSegment = allocateString(label)
        return imguiInputText.invokeExact(labelSegment, buffer, bufferSize, flags) as Boolean
    }

    fun sliderFloat(label: String, value: MemorySegment, min: Float, max: Float, format: String = "%.3f", flags: Int = 0): Boolean {
        val labelSegment = allocateString(label)
        val formatSegment = allocateString(format)
        return imguiSliderFloat.invokeExact(labelSegment, value, min, max, formatSegment, flags) as Boolean
    }

    fun sliderInt(label: String, value: MemorySegment, min: Int, max: Int, format: String = "%d", flags: Int = 0): Boolean {
        val labelSegment = allocateString(label)
        val formatSegment = allocateString(format)
        return imguiSliderInt.invokeExact(labelSegment, value, min, max, formatSegment, flags) as Boolean
    }

    fun separator() {
        imguiSeparator.invokeExact()
    }

    fun sameLine(offsetFromStartX: Float = 0f, spacing: Float = -1f) {
        imguiSameLine.invokeExact(offsetFromStartX, spacing)
    }

    fun newLine() {
        imguiNewLine.invokeExact()
    }

    fun spacing() {
        imguiSpacing.invokeExact()
    }

    fun isItemHovered(flags: Int = 0): Boolean {
        return imguiIsItemHovered.invokeExact(flags) as Boolean
    }

    fun isItemClicked(mouseButton: Int = 0): Boolean {
        return imguiIsItemClicked.invokeExact(mouseButton) as Boolean
    }

    private val imguiTreeNode by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_TreeNode") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS)
        }
    }

    private val imguiTreeNodeEx by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_TreeNodeEx") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiSetClipboardText by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_SetClipboardText") {
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        }
    }

    /** False on a bootstrap built before the clipboard export existed. */
    val clipboardSupported: Boolean by lazy { runCatching { imguiSetClipboardText }.isSuccess }

    fun setClipboardText(text: String) {
        imguiSetClipboardText.invokeExact(allocateString(text))
    }

    private val imguiArrowButton by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_ArrowButton") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    /**
     * False against a bootstrap built before the arrow-button export existed. Probed rather than assumed
     * because a missing symbol throws on lookup, which would otherwise turn the button into a no-op and make
     * it silently disappear from the row.
     */
    val arrowButtonSupported: Boolean by lazy { runCatching { imguiArrowButton }.isSuccess }

    fun arrowButton(id: String, dir: Int): Boolean {
        val idSegment = allocateString(id)
        return imguiArrowButton.invokeExact(idSegment, dir) as Boolean
    }

    private val imguiTreePop by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_TreePop") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiCollapsingHeader by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_CollapsingHeader") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiBeginCombo by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_BeginCombo") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiEndCombo by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_EndCombo") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiCombo by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_Combo_StringList") {
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT
            )
        }
    }

    private val imguiSelectable by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_Selectable") {
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT
            )
        }
    }

    private val imguiSelectableWithBuffer by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_SelectableWithBuffer") {
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT
            )
        }
    }

    private val imguiBeginMenuBar by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_BeginMenuBar") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN)
        }
    }

    private val imguiEndMenuBar by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_EndMenuBar") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiBeginMenu by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_BeginMenu") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN)
        }
    }

    private val imguiEndMenu by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_EndMenu") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiMenuItem by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_MenuItem") {
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.JAVA_BOOLEAN
            )
        }
    }

    private val imguiBeginChild by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_BeginChild") {
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT
            )
        }
    }

    private val imguiEndChild by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_EndChild") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiColorEdit3 by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_ColorEdit3") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiColorEdit4 by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_ColorEdit4") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiColorPicker3 by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_ColorPicker3") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiColorPicker4 by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_ColorPicker4") {
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS
            )
        }
    }

    private val imguiBeginTooltip by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_BeginTooltip") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiEndTooltip by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_EndTooltip") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiSetTooltip by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_SetTooltip") {
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        }
    }

    private val imguiOpenPopup by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_OpenPopup") {
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiBeginPopup by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_BeginPopup") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiBeginPopupModal by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_BeginPopupModal") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiEndPopup by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_EndPopup") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiCloseCurrentPopup by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_CloseCurrentPopup") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiIsPopupOpen by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_IsPopupOpen") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiOpenPopupOnItemClick by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_OpenPopupOnItemClick") {
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiBeginPopupContextItem by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_BeginPopupContextItem") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiBeginPopupContextWindow by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_BeginPopupContextWindow") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiBeginPopupContextVoid by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_BeginPopupContextVoid") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiBeginTable by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_BeginTable") {
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT
            )
        }
    }

    private val imguiEndTable by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_EndTable") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiTableNextRow by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_TableNextRow") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiTableNextColumn by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_TableNextColumn") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN)
        }
    }

    private val imguiTableSetColumnIndex by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_TableSetColumnIndex") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT)
        }
    }

    private val imguiTableSetupColumn by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_TableSetupColumn") {
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_INT
            )
        }
    }

    private val imguiTableHeadersRow by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_TableHeadersRow") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiTableHeader by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_TableHeader") {
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        }
    }

    private val imguiGetWindowDrawList by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetWindowDrawList") {
            FunctionDescriptor.of(ValueLayout.ADDRESS)
        }
    }

    private val imguiGetBackgroundDrawList by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetBackgroundDrawList") {
            FunctionDescriptor.of(ValueLayout.ADDRESS)
        }
    }

    private val imguiGetForegroundDrawList by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetForegroundDrawList") {
            FunctionDescriptor.of(ValueLayout.ADDRESS)
        }
    }

    private val imguiDrawListAddLine by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_DrawList_AddLine") {
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT
            )
        }
    }

    private val imguiDrawListAddRect by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_DrawList_AddRect") {
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT
            )
        }
    }

    private val imguiDrawListAddRectFilled by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_DrawList_AddRectFilled") {
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_INT
            )
        }
    }

    private val imguiDrawListAddCircle by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_DrawList_AddCircle") {
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT
            )
        }
    }

    private val imguiDrawListAddCircleFilled by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_DrawList_AddCircleFilled") {
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT
            )
        }
    }

    private val imguiDrawListAddText by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_DrawList_AddText") {
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS
            )
        }
    }

    private val imguiDrawListAddImage by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_DrawList_AddImage") {
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_INT
            )
        }
    }

    private val imguiDrawListAddConvexPolyFilled by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_DrawList_AddConvexPolyFilled") {
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT
            )
        }
    }

    private val imguiDrawListAddPolyline by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_DrawList_AddPolyline") {
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT
            )
        }
    }

    private val imguiGetContentRegionAvail by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetContentRegionAvail") {
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        }
    }

    private val imguiGetScrollY by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetScrollY") {
            FunctionDescriptor.of(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiGetScrollMaxY by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetScrollMaxY") {
            FunctionDescriptor.of(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiSetScrollHereY by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_SetScrollHereY") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiCalcTextSize by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_CalcTextSize") {
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_BOOLEAN,
                ValueLayout.JAVA_FLOAT
            )
        }
    }

    private val imguiGetCursorPos by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetCursorPos") {
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        }
    }

    private val imguiSetCursorPos by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_SetCursorPos") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiGetCursorScreenPos by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetCursorScreenPos") {
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        }
    }

    private val imguiSetCursorScreenPos by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_SetCursorScreenPos") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiDummy by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_Dummy") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiProgressBar by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_ProgressBar") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.ADDRESS)
        }
    }

    private val imguiBeginListBox by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_BeginListBox") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiEndListBox by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_EndListBox") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiTreePush by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_TreePush") {
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        }
    }

    private val imguiBeginTabBar by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_BeginTabBar") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiEndTabBar by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_EndTabBar") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiBeginTabItem by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_BeginTabItem") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiEndTabItem by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_EndTabItem") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiTabItemButton by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_TabItemButton") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiImage by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_Image") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        }
    }

    private val imguiImageButton by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_ImageButton") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_LONG, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        }
    }

    private val imguiCreateTextureFromRGBA by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_CreateTextureFromRGBA") {
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        }
    }

    /** Absent from bootstraps built before it existed, so callers fall back to replacing the texture. */
    private val imguiUpdateTextureRGBA by lazy {
        runCatching {
            NativeAccess.getFunction("ProjectX_ImGui_UpdateTextureRGBA") {
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            }
        }.getOrNull()
    }

    private val imguiDestroyTexture by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_DestroyTexture") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG)
        }
    }

    private val imguiDragFloat by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_DragFloat") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiDragInt by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_DragInt") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiInputTextMultiline by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_InputTextMultiline") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT)
        }
    }

    private val imguiInputFloat2 by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_InputFloat2") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiInputFloat3 by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_InputFloat3") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiInputFloat4 by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_InputFloat4") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        }
    }

    private val imguiBeginGroup by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_BeginGroup") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiEndGroup by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_EndGroup") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiIndent by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_Indent") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiUnindent by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_Unindent") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiSetNextItemWidth by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_SetNextItemWidth") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiSetCursorPosX by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_SetCursorPosX") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiSetCursorPosY by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_SetCursorPosY") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiAlignTextToFramePadding by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_AlignTextToFramePadding") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiColumns by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_Columns") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN)
        }
    }

    private val imguiNextColumn by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_NextColumn") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiSetColumnWidth by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_SetColumnWidth") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiGetColumnWidth by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetColumnWidth") {
            FunctionDescriptor.of(ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT)
        }
    }

    private val imguiPushStyleVarFloat by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_PushStyleVarFloat") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiPushStyleVarVec2 by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_PushStyleVarVec2") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiPopStyleVar by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_PopStyleVar") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT)
        }
    }

    private val imguiPushStyleColor by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_PushStyleColor") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
        }
    }

    private val imguiPopStyleColor by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_PopStyleColor") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT)
        }
    }

    private val imguiPushItemWidth by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_PushItemWidth") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiPopItemWidth by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_PopItemWidth") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiGetItemRectMinX by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetItemRectMinX") {
            FunctionDescriptor.of(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiGetItemRectMinY by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetItemRectMinY") {
            FunctionDescriptor.of(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiGetItemRectMaxX by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetItemRectMaxX") {
            FunctionDescriptor.of(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiGetItemRectMaxY by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetItemRectMaxY") {
            FunctionDescriptor.of(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiGetItemRectSizeX by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetItemRectSizeX") {
            FunctionDescriptor.of(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiGetItemRectSizeY by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetItemRectSizeY") {
            FunctionDescriptor.of(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiIsItemActive by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_IsItemActive") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN)
        }
    }

    private val imguiIsItemFocused by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_IsItemFocused") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN)
        }
    }

    private val imguiIsItemVisible by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_IsItemVisible") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN)
        }
    }

    private val imguiGetWindowPosX by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetWindowPosX") {
            FunctionDescriptor.of(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiGetWindowPosY by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetWindowPosY") {
            FunctionDescriptor.of(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiGetWindowSizeX by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetWindowSizeX") {
            FunctionDescriptor.of(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiGetWindowSizeY by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetWindowSizeY") {
            FunctionDescriptor.of(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiGetMousePosX by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetMousePosX") {
            FunctionDescriptor.of(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiGetMousePosY by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetMousePosY") {
            FunctionDescriptor.of(ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiIsMouseDown by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_IsMouseDown") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT)
        }
    }

    private val imguiIsMouseClicked by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_IsMouseClicked") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT, ValueLayout.JAVA_BOOLEAN)
        }
    }

    private val imguiIsMouseDoubleClicked by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_IsMouseDoubleClicked") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT)
        }
    }

    fun getDisplaySize(): Pair<Float, Float> {
        Arena.ofConfined().use { arena ->
            val widthBuffer = arena.allocate(ValueLayout.JAVA_FLOAT)
            val heightBuffer = arena.allocate(ValueLayout.JAVA_FLOAT)
            
            imguiGetDisplaySize.invokeExact(widthBuffer, heightBuffer)
            
            val width = widthBuffer.get(ValueLayout.JAVA_FLOAT, 0)
            val height = heightBuffer.get(ValueLayout.JAVA_FLOAT, 0)
            
            return Pair(width, height)
        }
    }

    fun setNextWindowPosSafe(x: Float, y: Float, cond: Int = 0, pivotX: Float = 0f, pivotY: Float = 0f) {
        try {
            val (displayWidth, displayHeight) = getDisplaySize()
            
            val minVisible = 32f
            val safeX = x.coerceIn(-200f, displayWidth - minVisible)
            val safeY = y.coerceIn(0f, displayHeight - minVisible)
            
            val safePivotX = pivotX.coerceIn(0f, 1f)
            val safePivotY = pivotY.coerceIn(0f, 1f)
            
            setNextWindowPos(safeX, safeY, cond, safePivotX, safePivotY)
        } catch (e: Exception) {
            setNextWindowPos(100f, 100f, cond, 0f, 0f)
        }
    }

    fun setNextWindowSizeSafe(width: Float, height: Float, cond: Int = 0) {
        try {
            val (displayWidth, displayHeight) = getDisplaySize()
            
            val minWidth = 100f
            val minHeight = 50f
            val maxWidth = displayWidth * 0.95f
            val maxHeight = displayHeight * 0.95f
            
            val safeWidth = width.coerceIn(minWidth, maxWidth)
            val safeHeight = height.coerceIn(minHeight, maxHeight)
            
            setNextWindowSize(safeWidth, safeHeight, cond)
        } catch (e: Exception) {
            setNextWindowSize(400f, 300f, cond)
        }
    }

    fun treeNode(label: String): Boolean {
        val labelSegment = allocateString(label)
        return imguiTreeNode.invokeExact(labelSegment) as Boolean
    }
    
    fun treeNodeEx(label: String, flags: Int = 0): Boolean {
        val labelSegment = allocateString(label)
        return imguiTreeNodeEx.invokeExact(labelSegment, flags) as Boolean
    }
    
    fun treePop() {
        imguiTreePop.invokeExact()
    }
    
    fun collapsingHeader(label: String, flags: Int = 0): Boolean {
        val labelSegment = allocateString(label)
        return imguiCollapsingHeader.invokeExact(labelSegment, flags) as Boolean
    }

    fun beginCombo(label: String, previewValue: String? = null, flags: Int = 0): Boolean {
        val labelSegment = allocateString(label)
        val previewSegment = previewValue?.let { allocateString(it) } ?: MemorySegment.NULL
        return imguiBeginCombo.invokeExact(labelSegment, previewSegment, flags) as Boolean
    }
    
    fun endCombo() {
        imguiEndCombo.invokeExact()
    }

    fun combo(label: String, selectedIndex: MemorySegment, items: String, maxItemsShown: Int = -1): Boolean {
        if (items.split("\u0000").size < selectedIndex.get(ValueLayout.JAVA_INT, 0)) {
            return false
        }
        val labelSegment = allocateString(label)
        val itemsSegment = allocateString(items)
        return imguiCombo.invokeExact(labelSegment, selectedIndex, itemsSegment, maxItemsShown) as Boolean
    }

    fun selectable(label: String, selected: Boolean = false, flags: Int = 0, sizeX: Float = 0f, sizeY: Float = 0f): Boolean {
        val labelSegment = allocateString(label)
        return imguiSelectable.invokeExact(labelSegment, selected, flags, sizeX, sizeY) as Boolean
    }

    fun selectableWithBuffer(label: String, selectedBuffer: MemorySegment, flags: Int = 0, sizeX: Float = 0f, sizeY: Float = 0f): Boolean {
        val labelSegment = allocateString(label)
        return imguiSelectableWithBuffer.invokeExact(labelSegment, selectedBuffer, flags, sizeX, sizeY) as Boolean
    }

    fun beginMenuBar(): Boolean {
        return imguiBeginMenuBar.invokeExact() as Boolean
    }
    
    fun endMenuBar() {
        imguiEndMenuBar.invokeExact()
    }
    
    fun beginMenu(label: String, enabled: Boolean = true): Boolean {
        val labelSegment = allocateString(label)
        return imguiBeginMenu.invokeExact(labelSegment, enabled) as Boolean
    }
    
    fun endMenu() {
        imguiEndMenu.invokeExact()
    }
    
    fun menuItem(label: String, shortcut: String? = null, selected: Boolean = false, enabled: Boolean = true): Boolean {
        val labelSegment = allocateString(label)
        val shortcutSegment = shortcut?.let { allocateString(it) } ?: MemorySegment.NULL
        return imguiMenuItem.invokeExact(labelSegment, shortcutSegment, selected, enabled) as Boolean
    }

    // Note: flags are split between child-specific flags (ImGuiChildFlags_*) and window flags (ImGuiWindowFlags_*)
    fun beginChild(strId: String, sizeX: Float = 0f, sizeY: Float = 0f, childFlags: Int = 0, windowFlags: Int = 0): Boolean {
        val idSegment = allocateString(strId)
        return imguiBeginChild.invokeExact(idSegment, sizeX, sizeY, childFlags, windowFlags) as Boolean
    }
    
    fun endChild() {
        imguiEndChild.invokeExact()
    }

    fun colorEdit3(label: String, col: MemorySegment, flags: Int = 0): Boolean {
        val labelSegment = allocateString(label)
        return imguiColorEdit3.invokeExact(labelSegment, col, flags) as Boolean
    }
    
    fun colorEdit4(label: String, col: MemorySegment, flags: Int = 0): Boolean {
        val labelSegment = allocateString(label)
        return imguiColorEdit4.invokeExact(labelSegment, col, flags) as Boolean
    }
    
    fun colorPicker3(label: String, col: MemorySegment, flags: Int = 0): Boolean {
        val labelSegment = allocateString(label)
        return imguiColorPicker3.invokeExact(labelSegment, col, flags) as Boolean
    }
    
    fun colorPicker4(label: String, col: MemorySegment, flags: Int = 0, refCol: MemorySegment? = null): Boolean {
        val labelSegment = allocateString(label)
        return imguiColorPicker4.invokeExact(labelSegment, col, flags, refCol ?: MemorySegment.NULL) as Boolean
    }

    fun beginTooltip() {
        imguiBeginTooltip.invokeExact()
    }
    
    fun endTooltip() {
        imguiEndTooltip.invokeExact()
    }
    
    fun setTooltip(text: String) {
        val textSegment = allocateString(text)
        imguiSetTooltip.invokeExact(textSegment)
    }

    fun beginTable(strId: String, columns: Int, flags: Int = 0, outerSizeX: Float = 0f, outerSizeY: Float = 0f, innerWidth: Float = 0f): Boolean {
        val idSegment = allocateString(strId)
        return imguiBeginTable.invokeExact(idSegment, columns, flags, outerSizeX, outerSizeY, innerWidth) as Boolean
    }
    
    fun endTable() {
        imguiEndTable.invokeExact()
    }
    
    fun tableNextRow(rowFlags: Int = 0, minRowHeight: Float = 0f) {
        imguiTableNextRow.invokeExact(rowFlags, minRowHeight)
    }
    
    fun tableNextColumn(): Boolean {
        return imguiTableNextColumn.invokeExact() as Boolean
    }
    
    fun tableSetColumnIndex(columnN: Int): Boolean {
        return imguiTableSetColumnIndex.invokeExact(columnN) as Boolean
    }
    
    fun tableSetupColumn(label: String? = null, flags: Int = 0, initWidthOrWeight: Float = 0f, userId: Int = 0) {
        val labelSegment = label?.let { allocateString(it) } ?: MemorySegment.NULL
        imguiTableSetupColumn.invokeExact(labelSegment, flags, initWidthOrWeight, userId)
    }
    
    fun tableHeadersRow() {
        imguiTableHeadersRow.invokeExact()
    }
    
    fun tableHeader(label: String) {
        val labelSegment = allocateString(label)
        imguiTableHeader.invokeExact(labelSegment)
    }

    fun getWindowDrawList(): MemorySegment? {
        val result = imguiGetWindowDrawList.invokeExact() as MemorySegment
        return if (result == MemorySegment.NULL) null else result
    }
    
    fun getBackgroundDrawList(): MemorySegment? {
        val result = imguiGetBackgroundDrawList.invokeExact() as MemorySegment
        return if (result == MemorySegment.NULL) null else result
    }
    
    fun getForegroundDrawList(): MemorySegment? {
        val result = imguiGetForegroundDrawList.invokeExact() as MemorySegment
        return if (result == MemorySegment.NULL) null else result
    }
    
    fun drawListAddLine(drawList: MemorySegment, x1: Float, y1: Float, x2: Float, y2: Float, col: Int, thickness: Float = 1f) {
        if (DrawRecorder.isMarker(drawList)) return DrawRecorder.add(Shape.Line(x1, y1, x2, y2, col, thickness))
        imguiDrawListAddLine.invokeExact(drawList, x1, y1, x2, y2, col, thickness)
    }
    
    fun drawListAddRect(drawList: MemorySegment, x1: Float, y1: Float, x2: Float, y2: Float, col: Int, rounding: Float = 0f, flags: Int = 0, thickness: Float = 1f) {
        if (DrawRecorder.isMarker(drawList)) return DrawRecorder.add(Shape.Rect(x1, y1, x2, y2, col, rounding, thickness, filled = false))
        imguiDrawListAddRect.invokeExact(drawList, x1, y1, x2, y2, col, rounding, flags, thickness)
    }
    
    fun drawListAddRectFilled(drawList: MemorySegment, x1: Float, y1: Float, x2: Float, y2: Float, col: Int, rounding: Float = 0f, flags: Int = 0) {
        if (DrawRecorder.isMarker(drawList)) return DrawRecorder.add(Shape.Rect(x1, y1, x2, y2, col, rounding, 0f, filled = true))
        imguiDrawListAddRectFilled.invokeExact(drawList, x1, y1, x2, y2, col, rounding, flags)
    }
    
    fun drawListAddCircle(drawList: MemorySegment, centerX: Float, centerY: Float, radius: Float, col: Int, numSegments: Int = 0, thickness: Float = 1f) {
        if (DrawRecorder.isMarker(drawList)) return DrawRecorder.add(Shape.Circle(centerX, centerY, radius, col, thickness, filled = false))
        imguiDrawListAddCircle.invokeExact(drawList, centerX, centerY, radius, col, numSegments, thickness)
    }
    
    fun drawListAddCircleFilled(drawList: MemorySegment, centerX: Float, centerY: Float, radius: Float, col: Int, numSegments: Int = 0) {
        if (DrawRecorder.isMarker(drawList)) return DrawRecorder.add(Shape.Circle(centerX, centerY, radius, col, 0f, filled = true))
        imguiDrawListAddCircleFilled.invokeExact(drawList, centerX, centerY, radius, col, numSegments)
    }

    fun drawListAddText(drawList: MemorySegment, x: Float, y: Float, col: Int, text: String) {
        if (DrawRecorder.isMarker(drawList)) return DrawRecorder.add(Shape.Text(x, y, col, text))
        val textSegment = allocateString(text)
        imguiDrawListAddText.invokeExact(drawList, x, y, col, textSegment)
    }

    fun drawListAddImage(drawList: MemorySegment, texture: ImGuiTexture, x1: Float, y1: Float, x2: Float, y2: Float, col: Int = -1) {
        if (DrawRecorder.isMarker(drawList)) return DrawRecorder.add(Shape.Image(texture, x1, y1, x2, y2, col))
        val id = texture.id
        if (id != 0L) imguiDrawListAddImage.invokeExact(drawList, id, x1, y1, x2, y2, col)
    }

    fun drawListAddConvexPolyFilled(drawList: MemorySegment, points: FloatArray, col: Int) {
        if (DrawRecorder.isMarker(drawList)) return DrawRecorder.add(Shape.Poly(points.toList(), col, 0f, filled = true, closed = true))
        Arena.ofConfined().use { arena ->
            val pointsBuffer = arena.allocate(ValueLayout.JAVA_FLOAT, points.size.toLong())
            for (i in points.indices) {
                pointsBuffer.setAtIndex(ValueLayout.JAVA_FLOAT, i.toLong(), points[i])
            }
            val numPoints = points.size / 2
            imguiDrawListAddConvexPolyFilled.invokeExact(drawList, pointsBuffer, numPoints, col) as Unit
        }
    }

    fun drawListAddPolyline(drawList: MemorySegment, points: FloatArray, col: Int, flags: Int, thickness: Float) {
        if (DrawRecorder.isMarker(drawList)) return DrawRecorder.add(Shape.Poly(points.toList(), col, thickness, filled = false, closed = flags and 1 != 0))
        Arena.ofConfined().use { arena ->
            val pointsBuffer = arena.allocate(ValueLayout.JAVA_FLOAT, points.size.toLong())
            for (i in points.indices) {
                pointsBuffer.setAtIndex(ValueLayout.JAVA_FLOAT, i.toLong(), points[i])
            }
            val numPoints = points.size / 2
            imguiDrawListAddPolyline.invokeExact(drawList, pointsBuffer, numPoints, col, flags, thickness) as Unit
        }
    }

    fun getContentRegionAvail(): Pair<Float, Float> {
        Arena.ofConfined().use { arena ->
            val sizeXBuffer = arena.allocate(ValueLayout.JAVA_FLOAT)
            val sizeYBuffer = arena.allocate(ValueLayout.JAVA_FLOAT)
            
            imguiGetContentRegionAvail.invokeExact(sizeXBuffer, sizeYBuffer)
            
            val sizeX = sizeXBuffer.get(ValueLayout.JAVA_FLOAT, 0)
            val sizeY = sizeYBuffer.get(ValueLayout.JAVA_FLOAT, 0)
            
            return Pair(sizeX, sizeY)
        }
    }

    fun getScrollY(): Float = imguiGetScrollY.invokeExact() as Float
    fun getScrollMaxY(): Float = imguiGetScrollMaxY.invokeExact() as Float
    fun setScrollHereY(ratio: Float) { imguiSetScrollHereY.invokeExact(ratio) }
    
    fun calcTextSize(text: String, hideTextAfterDoubleHash: Boolean = false, wrapWidth: Float = -1f): Pair<Float, Float> {
        Arena.ofConfined().use { arena ->
            val textSegment = allocateString(text)
            val sizeXBuffer = arena.allocate(ValueLayout.JAVA_FLOAT)
            val sizeYBuffer = arena.allocate(ValueLayout.JAVA_FLOAT)
            
            imguiCalcTextSize.invokeExact(textSegment, sizeXBuffer, sizeYBuffer, hideTextAfterDoubleHash, wrapWidth)
            
            val sizeX = sizeXBuffer.get(ValueLayout.JAVA_FLOAT, 0)
            val sizeY = sizeYBuffer.get(ValueLayout.JAVA_FLOAT, 0)
            
            return Pair(sizeX, sizeY)
        }
    }
    
    fun getCursorPos(): Pair<Float, Float> {
        Arena.ofConfined().use { arena ->
            val posXBuffer = arena.allocate(ValueLayout.JAVA_FLOAT)
            val posYBuffer = arena.allocate(ValueLayout.JAVA_FLOAT)
            
            imguiGetCursorPos.invokeExact(posXBuffer, posYBuffer)
            
            val posX = posXBuffer.get(ValueLayout.JAVA_FLOAT, 0)
            val posY = posYBuffer.get(ValueLayout.JAVA_FLOAT, 0)
            
            return Pair(posX, posY)
        }
    }
    
    fun setCursorPos(posX: Float, posY: Float) {
        imguiSetCursorPos.invokeExact(posX, posY)
    }
    
    fun getCursorScreenPos(): Pair<Float, Float> {
        Arena.ofConfined().use { arena ->
            val posXBuffer = arena.allocate(ValueLayout.JAVA_FLOAT)
            val posYBuffer = arena.allocate(ValueLayout.JAVA_FLOAT)
            
            imguiGetCursorScreenPos.invokeExact(posXBuffer, posYBuffer)
            
            val posX = posXBuffer.get(ValueLayout.JAVA_FLOAT, 0)
            val posY = posYBuffer.get(ValueLayout.JAVA_FLOAT, 0)
            
            return Pair(posX, posY)
        }
    }
    
    fun setCursorScreenPos(posX: Float, posY: Float) {
        imguiSetCursorScreenPos.invokeExact(posX, posY)
    }

    fun openPopup(id: String, flags: Int = 0) {
        val idSegment = allocateString(id)
        imguiOpenPopup.invokeExact(idSegment, flags)
    }
    
    fun beginPopup(id: String, flags: Int = 0): Boolean {
        val idSegment = allocateString(id)
        return imguiBeginPopup.invokeExact(idSegment, flags) as Boolean
    }
    
    fun beginPopupModal(name: String, pOpen: MemorySegment? = null, flags: Int = 0): Boolean {
        val nameSegment = allocateString(name)
        return imguiBeginPopupModal.invokeExact(nameSegment, pOpen ?: MemorySegment.NULL, flags) as Boolean
    }
    
    fun endPopup() {
        imguiEndPopup.invokeExact()
    }
    
    fun closeCurrentPopup() {
        imguiCloseCurrentPopup.invokeExact()
    }
    
    fun isPopupOpen(id: String, flags: Int = 0): Boolean {
        val idSegment = allocateString(id)
        return imguiIsPopupOpen.invokeExact(idSegment, flags) as Boolean
    }
    
    fun openPopupOnItemClick(id: String? = null, flags: Int = 1) {
        val idSegment = id?.let { allocateString(it) } ?: MemorySegment.NULL
        imguiOpenPopupOnItemClick.invokeExact(idSegment, flags)
    }
    
    fun beginPopupContextItem(id: String? = null, flags: Int = 1): Boolean {
        val idSegment = id?.let { allocateString(it) } ?: MemorySegment.NULL
        return imguiBeginPopupContextItem.invokeExact(idSegment, flags) as Boolean
    }
    
    fun beginPopupContextWindow(id: String? = null, flags: Int = 1): Boolean {
        val idSegment = id?.let { allocateString(it) } ?: MemorySegment.NULL
        return imguiBeginPopupContextWindow.invokeExact(idSegment, flags) as Boolean
    }
    
    fun beginPopupContextVoid(id: String? = null, flags: Int = 1): Boolean {
        val idSegment = id?.let { allocateString(it) } ?: MemorySegment.NULL
        return imguiBeginPopupContextVoid.invokeExact(idSegment, flags) as Boolean
    }

    fun progressBar(fraction: Float, sizeX: Float = -1f, sizeY: Float = 0f, overlay: String = "") {
        val overlayPtr = if (overlay.isNotEmpty()) allocateString(overlay) else MemorySegment.NULL
        try {
            imguiProgressBar.invokeExact(fraction, sizeX, sizeY, overlayPtr)
        } catch (e: Throwable) {
        }
    }

    fun beginListBox(label: String, sizeX: Float = 0f, sizeY: Float = 0f): Boolean {
        val labelPtr = allocateString(label)
        return try {
            imguiBeginListBox.invokeExact(labelPtr, sizeX, sizeY) as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    fun endListBox() {
        try {
            imguiEndListBox.invokeExact()
        } catch (e: Throwable) {
        }
    }

    fun treePush(id: String) {
        val idPtr = allocateString(id)
        try {
            imguiTreePush.invokeExact(idPtr)
        } catch (e: Throwable) {
        }
    }

    fun beginTabBar(id: String, flags: Int = 0): Boolean {
        val idPtr = allocateString(id)
        return try {
            return imguiBeginTabBar.invokeExact(idPtr, flags) as Boolean
        } catch (e: Throwable) {
            println("[ImGui] BeginTabBar('$id') threw: ${e.message}")
            false
        }
    }

    fun endTabBar() {
        try {
            imguiEndTabBar.invokeExact()
        } catch (e: Throwable) {
        }
    }

    fun beginTabItem(label: String, pOpen: MemorySegment? = null, flags: Int = 0): Boolean {
        val labelPtr = allocateString(label)
        return try {
            return imguiBeginTabItem.invokeExact(labelPtr, (pOpen ?: MemorySegment.NULL), flags) as Boolean
        } catch (e: Throwable) {
            println("[ImGui] BeginTabItem('$label') threw: ${e.message}")
            false
        }
    }

    fun endTabItem() {
        try {
            imguiEndTabItem.invokeExact()
        } catch (e: Throwable) {
        }
    }

    fun tabItemButton(label: String, flags: Int = 0): Boolean {
        val labelPtr = allocateString(label)
        return try {
            imguiTabItemButton.invokeExact(labelPtr, flags) as Boolean
        } catch (e: Throwable) {
            false
        }
    }
    fun image(texture: ImGuiTexture, sizeX: Float, sizeY: Float, uv0X: Float = 0f, uv0Y: Float = 0f, uv1X: Float = 1f, uv1Y: Float = 1f, tintColor: Int = -1, borderColor: Int = 0) {
        try {
            val id = texture.id
            imguiImage.invokeExact(id, sizeX, sizeY, uv0X, uv0Y, uv1X, uv1Y, tintColor, borderColor)
        } catch (e: Throwable) {
        }
    }

    fun imageButton(texture: ImGuiTexture, sizeX: Float, sizeY: Float, uv0X: Float = 0f, uv0Y: Float = 0f, uv1X: Float = 1f, uv1Y: Float = 1f, framePadding: Int = -1, bgColor: Int = 0, tintColor: Int = -1): Boolean {
        return try {
            val id = texture.id
            imguiImageButton.invokeExact(id, sizeX, sizeY, uv0X, uv0Y, uv1X, uv1Y, framePadding, bgColor, tintColor) as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    fun loadTexture(pathOrResource: String): ImGuiTexture? = ImageHelper.loadTexture(pathOrResource)

    fun createTextureFromRGBA(pixels: ByteArray, width: Int, height: Int): ImGuiTexture? =
        createTransientTextureFromRGBA(pixels, width, height)?.also { TexturePixels.keep(it, pixels, width, height) }

    /** A texture only ImGui will draw, so its pixels are not kept for Compose - for the panels' own surfaces. */
    fun createTransientTextureFromRGBA(pixels: ByteArray, width: Int, height: Int): ImGuiTexture? {
        val id = createTextureFromRGBA_Raw(pixels, width, height)
        if (id == 0L) return null
        return ImGuiTexture(id, width, height)
    }

    private fun createTextureFromRGBA_Raw(pixels: ByteArray, width: Int, height: Int): Long {
        if (width <= 0 || height <= 0) return 0L
        val expectedSize = width * height * 4
        if (pixels.size < expectedSize) return 0L
        Arena.ofConfined().use { arena ->
            val seg = arena.allocate(expectedSize.toLong(), 1)
            seg.asByteBuffer().put(pixels, 0, expectedSize)
            return imguiCreateTextureFromRGBA.invokeExact(seg, width, height) as Long
        }
    }

    val canUpdateTextures: Boolean get() = imguiUpdateTextureRGBA != null

    /**
     * Overwrites every pixel of [texture] with the tightly packed RGBA at [pixels], keeping its handle. Render thread
     * only. False when the bootstrap cannot update in place or refused the update.
     */
    fun updateTextureFromRGBA(texture: ImGuiTexture, pixels: MemorySegment): Boolean {
        val update = imguiUpdateTextureRGBA ?: return false
        return update.invokeExact(texture.id, pixels, texture.width, texture.height) as Boolean
    }

    fun destroyTexture(texture: ImGuiTexture?) {
        if (texture == null) return
        destroyTextureById(texture.id)
    }

    internal fun destroyTextureById(textureId: Long) {
        if (textureId == 0L) return
        ImageHelper.forgetTextureById(textureId)
        imguiDestroyTexture.invokeExact(textureId)
    }

    fun dragFloat(label: String, value: MemorySegment, speed: Float = 1f, min: Float = 0f, max: Float = 0f, format: String = "%.3f", flags: Int = 0): Boolean {
        val labelPtr = allocateString(label)
        val formatPtr = allocateString(format)
        return try {
            imguiDragFloat.invokeExact(labelPtr, value, speed, min, max, formatPtr, flags) as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    fun dragInt(label: String, value: MemorySegment, speed: Float = 1f, min: Int = 0, max: Int = 0, format: String = "%d", flags: Int = 0): Boolean {
        val labelPtr = allocateString(label)
        val formatPtr = allocateString(format)
        return try {
            imguiDragInt.invokeExact(labelPtr, value, speed, min, max, formatPtr, flags) as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    fun inputTextMultiline(label: String, buffer: MemorySegment, bufferSize: Int, sizeX: Float = 0f, sizeY: Float = 0f, flags: Int = 0): Boolean {
        val labelPtr = allocateString(label)
        return try {
            imguiInputTextMultiline.invokeExact(labelPtr, buffer, bufferSize, sizeX, sizeY, flags) as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    fun inputFloat2(label: String, value: MemorySegment, format: String = "%.3f", flags: Int = 0): Boolean {
        val labelPtr = allocateString(label)
        val formatPtr = allocateString(format)
        return try {
            imguiInputFloat2.invokeExact(labelPtr, value, formatPtr, flags) as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    fun inputFloat3(label: String, value: MemorySegment, format: String = "%.3f", flags: Int = 0): Boolean {
        val labelPtr = allocateString(label)
        val formatPtr = allocateString(format)
        return try {
            imguiInputFloat3.invokeExact(labelPtr, value, formatPtr, flags) as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    fun inputFloat4(label: String, value: MemorySegment, format: String = "%.3f", flags: Int = 0): Boolean {
        val labelPtr = allocateString(label)
        val formatPtr = allocateString(format)
        return try {
            imguiInputFloat4.invokeExact(labelPtr, value, formatPtr, flags) as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    fun beginGroup() {
        try {
            imguiBeginGroup.invokeExact()
        } catch (e: Throwable) {
        }
    }

    fun endGroup() {
        try {
            imguiEndGroup.invokeExact()
        } catch (e: Throwable) {
        }
    }

    fun indent(indentWidth: Float = 0f) {
        try {
            imguiIndent.invokeExact(indentWidth)
        } catch (e: Throwable) {
        }
    }

    fun unindent(indentWidth: Float = 0f) {
        try {
            imguiUnindent.invokeExact(indentWidth)
        } catch (e: Throwable) {
        }
    }

    fun setNextItemWidth(itemWidth: Float) {
        try {
            imguiSetNextItemWidth.invokeExact(itemWidth)
        } catch (e: Throwable) {
        }
    }

    fun setCursorPosX(localX: Float) {
        try {
            imguiSetCursorPosX.invokeExact(localX)
        } catch (e: Throwable) {
        }
    }

    fun setCursorPosY(localY: Float) {
        try {
            imguiSetCursorPosY.invokeExact(localY)
        } catch (e: Throwable) {
        }
    }

    fun alignTextToFramePadding() {
        try {
            imguiAlignTextToFramePadding.invokeExact()
        } catch (e: Throwable) {
        }
    }

    fun columns(count: Int, id: String? = null, border: Boolean = true) {
        val idPtr = id?.let { allocateString(it) }
        try {
            imguiColumns.invokeExact(count, idPtr, border)
        } catch (e: Throwable) {
        }
    }

    fun nextColumn() {
        try {
            imguiNextColumn.invokeExact()
        } catch (e: Throwable) {
        }
    }

    fun setColumnWidth(columnIndex: Int, width: Float) {
        try {
            imguiSetColumnWidth.invokeExact(columnIndex, width)
        } catch (e: Throwable) {
        }
    }

    fun getColumnWidth(columnIndex: Int = -1): Float {
        return try {
            imguiGetColumnWidth.invokeExact(columnIndex) as Float
        } catch (e: Throwable) {
            0f
        }
    }

    fun pushStyleVarFloat(idx: ImGuiStyleVar, value: Float) {
        try {
            imguiPushStyleVarFloat.invokeExact(idx.ordinal, value)
        } catch (e: Throwable) {
        }
    }

    fun pushStyleVarVec2(idx: ImGuiStyleVar, x: Float, y: Float) {
        try {
            imguiPushStyleVarVec2.invokeExact(idx.ordinal, x, y)
        } catch (e: Throwable) {
        }
    }

    fun popStyleVar(count: Int = 1) {
        try {
            imguiPopStyleVar.invokeExact(count)
        } catch (e: Throwable) {
        }
    }

    fun pushStyleColor(idx: ImGuiCol, color: Int) {
        try {
            imguiPushStyleColor.invokeExact(idx.ordinal, color)
        } catch (e: Throwable) {
        }
    }

    fun popStyleColor(count: Int = 1) {
        try {
            imguiPopStyleColor.invokeExact(count)
        } catch (e: Throwable) {
        }
    }

    fun pushItemWidth(itemWidth: Float) {
        try {
            imguiPushItemWidth.invokeExact(itemWidth)
        } catch (e: Throwable) {
        }
    }

    fun popItemWidth() {
        try {
            imguiPopItemWidth.invokeExact()
        } catch (e: Throwable) {
        }
    }

    fun getItemRectMin(): Pair<Float, Float> {
        return try {
            val x = imguiGetItemRectMinX.invokeExact() as Float
            val y = imguiGetItemRectMinY.invokeExact() as Float
            Pair(x, y)
        } catch (e: Throwable) {
            Pair(0f, 0f)
        }
    }

    fun getItemRectMax(): Pair<Float, Float> {
        return try {
            val x = imguiGetItemRectMaxX.invokeExact() as Float
            val y = imguiGetItemRectMaxY.invokeExact() as Float
            Pair(x, y)
        } catch (e: Throwable) {
            Pair(0f, 0f)
        }
    }

    fun getItemRectSize(): Pair<Float, Float> {
        return try {
            val x = imguiGetItemRectSizeX.invokeExact() as Float
            val y = imguiGetItemRectSizeY.invokeExact() as Float
            Pair(x, y)
        } catch (e: Throwable) {
            Pair(0f, 0f)
        }
    }

    fun isItemActive(): Boolean {
        return try {
            imguiIsItemActive.invokeExact() as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    fun isItemFocused(): Boolean {
        return try {
            imguiIsItemFocused.invokeExact() as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    fun isItemVisible(): Boolean {
        return try {
            imguiIsItemVisible.invokeExact() as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    fun getWindowPos(): Pair<Float, Float> {
        return try {
            val x = imguiGetWindowPosX.invokeExact() as Float
            val y = imguiGetWindowPosY.invokeExact() as Float
            Pair(x, y)
        } catch (e: Throwable) {
            Pair(0f, 0f)
        }
    }

    fun getWindowSize(): Pair<Float, Float> {
        return try {
            val x = imguiGetWindowSizeX.invokeExact() as Float
            val y = imguiGetWindowSizeY.invokeExact() as Float
            Pair(x, y)
        } catch (e: Throwable) {
            Pair(0f, 0f)
        }
    }

    fun getMousePos(): Pair<Float, Float> {
        return try {
            val x = imguiGetMousePosX.invokeExact() as Float
            val y = imguiGetMousePosY.invokeExact() as Float
            Pair(x, y)
        } catch (e: Throwable) {
            Pair(0f, 0f)
        }
    }

    fun isMouseDown(button: Int): Boolean {
        return try {
            imguiIsMouseDown.invokeExact(button) as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    fun isMouseClicked(button: Int, repeat: Boolean = false): Boolean {
        return try {
            imguiIsMouseClicked.invokeExact(button, repeat) as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    fun isMouseDoubleClicked(button: Int): Boolean {
        return try {
            imguiIsMouseDoubleClicked.invokeExact(button) as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    private val imguiAddFontFromFile by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_AddFontFromFile") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiAddDefaultFont by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_AddDefaultFont") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_FLOAT)
        }
    }

    private val imguiPushFont by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_PushFont") {
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT)
        }
    }

    private val imguiPopFont by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_PopFont") {
            FunctionDescriptor.ofVoid()
        }
    }

    private val imguiGetFontCount by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_GetFontCount") {
            FunctionDescriptor.of(ValueLayout.JAVA_INT)
        }
    }

    private val imguiBuildFonts by lazy {
        NativeAccess.getFunction("ProjectX_ImGui_BuildFonts") {
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN)
        }
    }

    fun addFontFromFile(filename: String, sizePixels: Float): Boolean {
        return try {
            val filenameSegment = allocateString(filename)
            imguiAddFontFromFile.invokeExact(filenameSegment, sizePixels) as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    fun addDefaultFont(sizePixels: Float = 13.0f): Boolean {
        return try {
            imguiAddDefaultFont.invokeExact(sizePixels) as Boolean
        } catch (e: Throwable) {
            false
        }
    }

    fun pushFont(fontIndex: Int) {
        try {
            imguiPushFont.invokeExact(fontIndex)
        } catch (e: Throwable) {
        }
    }

    fun popFont() {
        try {
            imguiPopFont.invokeExact()
        } catch (e: Throwable) {
        }
    }

    fun getFontCount(): Int {
        return try {
            imguiGetFontCount.invokeExact() as Int
        } catch (e: Throwable) {
            0
        }
    }

    fun buildFonts(): Boolean {
        return try {
            imguiBuildFonts.invokeExact() as Boolean
        } catch (e: Throwable) {
            false
        }
    }
}
