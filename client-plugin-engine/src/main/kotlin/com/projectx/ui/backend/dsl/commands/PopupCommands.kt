package com.projectx.ui.backend.dsl.commands

import com.projectx.ui.backend.native.NativeBridge
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicReference

data class OpenPopupCommand(val id: String, val flags: Int = 0) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.openPopup(id, flags)
    }
}

data class BeginPopupCommand(
    val id: String,
    val flags: Int = 0,
    val result: AtomicReference<Boolean>
) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        if (ImGuiExecState.isSkipping()) {
            ImGuiExecState.beginSkipped()
            result.set(false)
            return
        }
        val open = NativeBridge.beginPopup(id, flags)
        ImGuiExecState.begin(open)
        result.set(open)
    }
}

data class BeginPopupModalCommand(
    val name: String,
    val open: AtomicReference<Boolean>? = null,
    val flags: Int = 0,
    val result: AtomicReference<Boolean>
) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        if (ImGuiExecState.isSkipping()) {
            ImGuiExecState.beginSkipped()
            result.set(false)
            return
        }
        
        val popupOpen = if (open != null) {
            Arena.ofConfined().use { arena ->
                val buffer = arena.allocate(ValueLayout.JAVA_BOOLEAN)
                buffer.set(ValueLayout.JAVA_BOOLEAN, 0, open.get())
                val opened = NativeBridge.beginPopupModal(name, buffer, flags)
                open.set(buffer.get(ValueLayout.JAVA_BOOLEAN, 0))
                opened
            }
        } else {
            NativeBridge.beginPopupModal(name, null, flags)
        }
        
        ImGuiExecState.begin(popupOpen)
        result.set(popupOpen)
    }
}

data class EndPopupCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        ImGuiExecState.end {
            NativeBridge.endPopup()
        }
    }
}

data class CloseCurrentPopupCommand(val dummy: Unit = Unit) : ImGuiCommand() {
    override fun execute() {
        NativeBridge.closeCurrentPopup()
    }
}

data class BeginPopupContextItemCommand(
    val id: String? = null,
    val flags: Int = 1,
    val result: AtomicReference<Boolean>
) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        if (ImGuiExecState.isSkipping()) {
            ImGuiExecState.beginSkipped()
            result.set(false)
            return
        }
        val open = NativeBridge.beginPopupContextItem(id, flags)
        ImGuiExecState.begin(open)
        result.set(open)
    }
}

data class BeginPopupContextWindowCommand(
    val id: String? = null,
    val flags: Int = 1,
    val result: AtomicReference<Boolean>
) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        if (ImGuiExecState.isSkipping()) {
            ImGuiExecState.beginSkipped()
            result.set(false)
            return
        }
        val open = NativeBridge.beginPopupContextWindow(id, flags)
        ImGuiExecState.begin(open)
        result.set(open)
    }
}

data class BeginPopupContextVoidCommand(
    val id: String? = null,
    val flags: Int = 1,
    val result: AtomicReference<Boolean>
) : ImGuiCommand() {
    override fun isStructural(): Boolean = true
    override fun execute() {
        if (ImGuiExecState.isSkipping()) {
            ImGuiExecState.beginSkipped()
            result.set(false)
            return
        }
        val open = NativeBridge.beginPopupContextVoid(id, flags)
        ImGuiExecState.begin(open)
        result.set(open)
    }
}