package com.projectx.ui.backend.dsl.scopes

import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.commands.*

@ImGuiDsl.ImGuiDsl
class PopupScope : LayoutScope {
    override val commands = mutableListOf<ImGuiCommand>()

    fun closeCurrentPopup() {
        commands.add(CloseCurrentPopupCommand())
    }
}

@ImGuiDsl.ImGuiDsl
class ModalScope : LayoutScope {
    override val commands = mutableListOf<ImGuiCommand>()

    fun closeCurrentPopup() {
        commands.add(CloseCurrentPopupCommand())
    }

    inline fun okCancelButtons(
        crossinline onOk: () -> Unit,
        crossinline onCancel: () -> Unit
    ) {
        button("OK") {
            onOk()
            closeCurrentPopup()
        }
        sameLine()
        button("Cancel") {
            onCancel()
            closeCurrentPopup()
        }
    }
}
