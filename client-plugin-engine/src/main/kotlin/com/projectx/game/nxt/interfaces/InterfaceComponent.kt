package com.projectx.game.nxt.interfaces
import com.projectx.game.memory.atLeast

import com.projectx.game.nxt.extent
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getOrNull
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readByte
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.readLong
import com.projectx.game.memory.NativeAccess.readShort
import com.projectx.game.memory.NativeAccess.toMemorySegment
import com.projectx.game.memory.NativeAccess.toShared
import com.projectx.game.hooks.impl.InterfaceComponentRectCapture
import com.projectx.game.memory.eastl.EastlString
import com.projectx.game.nxt.OChildSlot
import com.projectx.game.nxt.OComponentPage
import com.projectx.game.nxt.OInterfaceComponent
import com.projectx.game.nxt.OInterfaceComponent.ITEM_ID
import com.projectx.game.nxt.OInterfaceComponent.PARENT_REL_X
import com.projectx.game.nxt.OInterfaceComponent.PARENT_REL_Y
import com.projectx.game.nxt.OInterfaceComponent.SCREEN_HEIGHT
import com.projectx.game.nxt.OInterfaceComponent.SCREEN_WIDTH
import com.projectx.game.nxt.OInterfaceComponent.STACK_SIZE
import com.projectx.game.nxt.types.Vector
import com.projectx.game.platform.Platform
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.invoke.MethodHandle

class InterfaceComponent(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OInterfaceComponent.extent)
    val interfaceId: Int
        get() = ptr.readShort(OInterfaceComponent.INTERFACE_ID).toInt()
    val componentId: Int
        get() = ptr.readShort(OInterfaceComponent.COMPONENT_ID).toInt()
    // Graphic ids are unsigned 16-bit; a raw signed read sign-extends ids above 0x7FFF into negatives.
    // Read unsigned, but keep the client's 0xFFFF "no graphic" value as -1 so sentinel checks still hold.
    val graphicId: Int
        get() {
            val raw = ptr.readShort(OInterfaceComponent.GRAPHIC_ID).toInt() and 0xFFFF
            return if (raw == 0xFFFF) -1 else raw
        }
    val slotId: Int
        get() = ptr.readShort(OInterfaceComponent.SLOT_ID).toInt()
    /** Component id of the layer this component sits in, or -1 when it hangs off the interface root. */
    val parentLayerId: Int
        get() {
            val raw = ptr.readShort(OInterfaceComponent.PARENT_LAYER_ID).toInt() and 0xFFFF
            return if (raw == 0xFFFF) -1 else raw
        }
    /**
     * The FLAGS bit 0x20 false-negatives for some dialog interfaces (the game leaves it
     * unset even while the component is on screen). Non-zero width/height after the
     * layout pass is the reliable signal we previously used.
     */
    val visible: Boolean
        get() = ptr.readInt(SCREEN_WIDTH) != 0 || ptr.readInt(SCREEN_HEIGHT) != 0
    /** Parent-local X. NOT screen-absolute - see [screenRect] for the live screen position. */
    val parentRelX: Int
        get() = ptr.readInt(PARENT_REL_X)
    /** Parent-local Y. NOT screen-absolute - see [screenRect] for the live screen position. */
    val parentRelY: Int
        get() = ptr.readInt(PARENT_REL_Y)
    val screenWidth: Int
        get() = ptr.readInt(SCREEN_WIDTH)
    val screenHeight: Int
        get() = ptr.readInt(SCREEN_HEIGHT)
    /** Absolute screen rect captured during the most recent draw pass, or null if not laid out this frame. */
    val screenRect: ScreenRect?
        get() = InterfaceComponentRectCapture.lookup(ptr)
    val xOriginOffset: Int
        get() = ptr.readInt(OInterfaceComponent.X_ORIGIN_OFFSET)
    val yOriginOffset: Int
        get() = ptr.readInt(OInterfaceComponent.Y_ORIGIN_OFFSET)
    val rawWidth: Int
        get() = ptr.readInt(OInterfaceComponent.RAW_WIDTH)
    val rawHeight: Int
        get() = ptr.readInt(OInterfaceComponent.RAW_HEIGHT)
    val parent: InterfaceComponent?
        get() = ptr.deref(OInterfaceComponent.PARENT_SHAREDPTR + 0x8L, OInterfaceComponent.extent).getOrNull?.let { InterfaceComponent(it) }
    val text: String
        get() = EastlString(ptr.asSlice(OInterfaceComponent.TEXT, EASTL_STRING_SIZE)).toString()
    val itemId
        get() = ptr.readInt(ITEM_ID)
    val stackSize
        get() = ptr.readInt(STACK_SIZE)
    /** The client's own kind for this component, answered by its class. Windows client only. */
    val kind: Int
        get() = ComponentKind.of(ptr)

    /**
     * Whether this component is a layer, the only kind that owns child slots. Every other kind keeps its own fields in
     * those bytes, so reading children off one walks garbage pointers.
     */
    val isLayer: Boolean
        get() = Platform.current != Platform.WINDOWS ||
            kind.let { it == OInterfaceComponent.LAYER_KIND || it == OInterfaceComponent.PAGED_LAYER_KIND }

    /**
     * Static children come from the interface file, dynamic ones from `cc_create` at runtime, and a
     * paged layer redirects both to its active page. The client's own draw and layout paths walk them
     * in that order, so the concatenation matches what is on screen.
     */
    val slotChildren: List<InterfaceComponent>
        get() {
            if (!isLayer) return emptyList()
            val pages = ptr.readLong(OInterfaceComponent.PAGES)
            val pagesEnd = ptr.readLong(OInterfaceComponent.PAGES_END)
            val base = if (pages == 0L || pagesEnd <= pages) {
                ptr.address() to false
            } else {
                val page = ptr.readByte(OInterfaceComponent.ACTIVE_PAGE).toLong() and 0xFF
                if (page >= (pagesEnd - pages) / OInterfaceComponent.PAGE_STRIDE) return emptyList()
                (pages + page * OInterfaceComponent.PAGE_STRIDE) to true
            }
            val (addr, paged) = base
            val static = if (paged) OComponentPage.STATIC_CHILDREN else OInterfaceComponent.STATIC_CHILDREN
            val dynamic = if (paged) OComponentPage.DYNAMIC_CHILDREN else OInterfaceComponent.DYNAMIC_CHILDREN
            return walkChildVector(addr, static) + walkChildVector(addr, dynamic)
        }

    // The vector header is inline at the offset, not a pointer to one. Skip removed slots and null
    // pointers; treat begin==0 / end==0 / end<begin as an empty (uninitialized or torn-down) vector.
    private fun walkChildVector(base: Long, vectorOffset: Long): List<InterfaceComponent> {
        val vecPtr = (base + vectorOffset).toMemorySegment(0x18L)
        val begin = vecPtr.readLong(Vector.OVector.VECTOR_BEGIN)
        val end = vecPtr.readLong(Vector.OVector.VECTOR_END)
        if (begin == 0L || end == 0L || end < begin) return emptyList()
        val stride = OChildSlot.SIZE
        val out = mutableListOf<InterfaceComponent>()
        var slotAddr = begin
        while (slotAddr + stride <= end) {
            val slot = slotAddr.toMemorySegment(stride)
            if (slot.readByte(OChildSlot.REMOVED) == 0.toByte()) {
                val compAddr = slot.readLong(OChildSlot.COMPONENT)
                if (compAddr != 0L) out += InterfaceComponent(compAddr.toMemorySegment(OInterfaceComponent.extent))
            }
            slotAddr += stride
        }
        return out
    }
}

/** An inline eastl::string: 23 short-string bytes plus the size byte. */
private const val EASTL_STRING_SIZE = 0x18L

private object ComponentKind {
    private val getterCall: MethodHandle by lazy {
        Linker.nativeLinker().downcallHandle(FunctionDescriptor.of(JAVA_BYTE, ADDRESS))
    }

    fun of(component: MemorySegment): Int {
        val vtable = component.readLong(0L)
        val getter = vtable.toMemorySegment(OInterfaceComponent.KIND_GETTER + 8).readLong(OInterfaceComponent.KIND_GETTER)
        val kind = getterCall.invokeExact(MemorySegment.ofAddress(getter), component) as Byte
        return kind.toInt() and 0xFF
    }
}
