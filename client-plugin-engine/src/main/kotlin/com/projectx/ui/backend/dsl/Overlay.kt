package com.projectx.ui.backend.dsl

import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.flags.ImGuiCond
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.ui.backend.native.ImGuiTexture
import org.projectx.core.game.skill.Skill
import java.util.function.Consumer
import java.util.function.IntConsumer
import java.util.function.Supplier

/**
 * The overlay DSL for Java scripts: `import static com.projectx.ui.backend.dsl.Overlay.*;` and call these from
 * `render()`.
 *
 * Kotlin builds overlay windows with lambdas that have a receiver and return Unit, which Java can only write by
 * returning `Unit.INSTANCE`, and window flags are a value class Java cannot pass. Each builder here takes a
 * [Consumer] of the scope instead, and flags as a plain int. Kotlin scripts keep using [ImGuiDsl] and the scope
 * extensions directly.
 *
 * ```java
 * @Override
 * public void render() {
 *     window("My Script", w -> {
 *         text(w, "Status: " + status);
 *         button(w, "Stop", this::stop);
 *     });
 * }
 * ```
 */
object Overlay {

    // ---- Window flags and conditions as plain ints; OR flags together ----

    @JvmField val WINDOW_NO_TITLE_BAR = WindowFlags.NoTitleBar.value
    @JvmField val WINDOW_NO_RESIZE = WindowFlags.NoResize.value
    @JvmField val WINDOW_NO_MOVE = WindowFlags.NoMove.value
    @JvmField val WINDOW_NO_SCROLLBAR = WindowFlags.NoScrollbar.value
    @JvmField val WINDOW_NO_COLLAPSE = WindowFlags.NoCollapse.value
    @JvmField val WINDOW_ALWAYS_AUTO_RESIZE = WindowFlags.AlwaysAutoResize.value
    @JvmField val WINDOW_NO_BACKGROUND = WindowFlags.NoBackground.value
    @JvmField val WINDOW_NO_SAVED_SETTINGS = WindowFlags.NoSavedSettings.value
    @JvmField val WINDOW_MENU_BAR = WindowFlags.MenuBar.value
    @JvmField val WINDOW_NO_DECORATION = WindowFlags.NoDecoration.value

    @JvmField val COND_ALWAYS = ImGuiCond.Always.value
    @JvmField val COND_ONCE = ImGuiCond.Once.value
    @JvmField val COND_FIRST_USE_EVER = ImGuiCond.FirstUseEver.value
    @JvmField val COND_APPEARING = ImGuiCond.Appearing.value

    // ---- Windows ----

    /** A window titled [title] whose content [content] adds. */
    @JvmStatic
    fun window(title: String, content: Consumer<WindowScope>) = window(title, 0, content)

    /** A window titled [title] with ImGui window [flags] (WindowFlags values ORed together). */
    @JvmStatic
    fun window(title: String, flags: Int, content: Consumer<WindowScope>) =
        ImGuiDsl.window(title, WindowFlags(flags)) { content.accept(this) }

    /** Positions the next window at ([x], [y]) on screen, every frame. */
    @JvmStatic
    fun setNextWindowPos(x: Float, y: Float) = ImGuiDsl.setNextWindowPos(x, y)

    /** Positions the next window at ([x], [y]) under an ImGuiCond [cond], such as first use only. */
    @JvmStatic
    fun setNextWindowPos(x: Float, y: Float, cond: Int) = ImGuiDsl.setNextWindowPos(x, y, ImGuiCond(cond))

    /** Sizes the next window, every frame. */
    @JvmStatic
    fun setNextWindowSize(width: Float, height: Float) = ImGuiDsl.setNextWindowSize(width, height)

    /** Sizes the next window under an ImGuiCond [cond], such as first use only. */
    @JvmStatic
    fun setNextWindowSize(width: Float, height: Float, cond: Int) = ImGuiDsl.setNextWindowSize(width, height, ImGuiCond(cond))

    /** Draws straight onto the game view, behind every window: tile highlights, text over the world, shapes. */
    @JvmStatic
    fun backgroundDrawList(content: Consumer<BackgroundDrawListScope>) = ImGuiDsl.backgroundDrawList { content.accept(this) }

    // ---- State that survives between frames ----

    /** A value kept under [key] across frames, created by [factory] the first time. */
    @JvmStatic
    fun <T> persistentState(key: String, factory: Supplier<ImGuiState<T>>): ImGuiState<T> =
        ImGuiDsl.persistentState(key) { factory.get() }

    @JvmStatic
    fun boolState(initialValue: Boolean): ImGuiState<Boolean> = com.projectx.ui.backend.dsl.boolState(initialValue)

    @JvmStatic
    fun intState(initialValue: Int): ImGuiState<Int> = com.projectx.ui.backend.dsl.intState(initialValue)

    @JvmStatic
    fun floatState(initialValue: Float): ImGuiState<Float> = com.projectx.ui.backend.dsl.floatState(initialValue)

    @JvmStatic
    fun stringState(initialValue: String): ImGuiState<String> = com.projectx.ui.backend.dsl.stringState(initialValue)

    // ---- Text and layout ----

    @JvmStatic
    fun text(scope: LayoutScope, text: String) = scope.text(text)

    @JvmStatic
    fun textWrapped(scope: LayoutScope, text: String) = scope.textWrapped(text)

    /** A titled group: accent text over a separator. */
    @JvmStatic
    fun section(scope: LayoutScope, title: String) = scope.section(title)

    @JvmStatic
    fun separator(scope: LayoutScope) = scope.separator()

    @JvmStatic
    fun sameLine(scope: LayoutScope) = scope.sameLine()

    @JvmStatic
    fun spacing(scope: LayoutScope) = scope.spacing()

    @JvmStatic
    fun newLine(scope: LayoutScope) = scope.newLine()

    /** A tooltip on the item added just before this. */
    @JvmStatic
    fun itemTooltip(scope: LayoutScope, text: String) = scope.itemTooltip(text)

    @JvmStatic
    fun progressBar(scope: LayoutScope, fraction: Float) = scope.progressBar(fraction)

    @JvmStatic
    fun progressBar(scope: LayoutScope, fraction: Float, overlayText: String) = scope.progressBar(fraction, overlay = overlayText)

    /** The player's level in [skill] and progress to the next. */
    @JvmStatic
    fun xpProgressBar(scope: LayoutScope, skill: Skill) = scope.xpProgressBar(skill)

    @JvmStatic
    fun image(scope: LayoutScope, texture: ImGuiTexture, width: Float, height: Float) = scope.image(texture, width, height)

    // ---- Controls ----

    @JvmStatic
    fun button(scope: LayoutScope, label: String, onClick: Runnable) = scope.button(label) { onClick.run() }

    @JvmStatic
    fun button(scope: LayoutScope, label: String, width: Float, height: Float, onClick: Runnable) =
        scope.button(label, width, height) { onClick.run() }

    @JvmStatic
    fun smallButton(scope: LayoutScope, label: String, onClick: Runnable) = scope.smallButton(label) { onClick.run() }

    /** A checkbox showing [checked]; [onChange] gets the new value when the user clicks it. */
    @JvmStatic
    fun checkbox(scope: LayoutScope, label: String, checked: Boolean, onChange: Consumer<Boolean>) =
        scope.checkbox(label, checked) { onChange.accept(it) }

    /** A checkbox bound to [state]. */
    @JvmStatic
    fun checkbox(scope: LayoutScope, label: String, state: ImGuiState<Boolean>) = scope.checkbox(label, state)

    /** A text field showing [value]; [onChange] gets the new text as the user types. */
    @JvmStatic
    fun inputText(scope: LayoutScope, label: String, value: String, onChange: Consumer<String>) =
        scope.inputText(label, value) { onChange.accept(it) }

    /** A number field showing [value]; [onChange] gets the new number. */
    @JvmStatic
    fun inputInt(scope: LayoutScope, label: String, value: Int, onChange: IntConsumer) =
        scope.inputInt(label, value) { onChange.accept(it) }

    @JvmStatic
    fun sliderInt(scope: LayoutScope, label: String, state: ImGuiState<Int>, min: Int, max: Int) = scope.sliderInt(label, state, min, max)

    @JvmStatic
    fun sliderFloat(scope: LayoutScope, label: String, state: ImGuiState<Float>, min: Float, max: Float) =
        scope.sliderFloat(label, state, min, max)

    /** A dropdown of [items] showing [selectedIndex]; [onChange] gets the index the user picks. */
    @JvmStatic
    fun combo(scope: LayoutScope, label: String, selectedIndex: Int, items: List<String>, onChange: IntConsumer) =
        scope.combo(label, selectedIndex, items) { onChange.accept(it) }

    /** A dropdown showing [preview] whose entries [content] adds; true while it is open. */
    @JvmStatic
    fun combo(scope: LayoutScope, label: String, preview: String, content: Consumer<ComboScope>): Boolean =
        scope.combo(label, preview) { content.accept(this) }

    @JvmStatic
    fun selectable(scope: LayoutScope, label: String, selected: Boolean, onClick: Runnable) =
        scope.selectable(label, selected) { onClick.run() }

    // ---- Containers ----

    /** Lays [content] out as one item, so [sameLine] treats it as a block. */
    @JvmStatic
    fun group(scope: LayoutScope, content: Consumer<ChildScope>) = scope.group { content.accept(this) }

    /** A scrolling region [width] by [height] (0 fills the space). */
    @JvmStatic
    fun child(scope: LayoutScope, id: String, width: Float, height: Float, content: Consumer<ChildScope>) =
        scope.child(id, width, height) { content.accept(this) }

    /** A header the user can fold away, with [content] indented under it. */
    @JvmStatic
    fun collapsingHeader(scope: LayoutScope, label: String, content: Consumer<ChildScope>) =
        scope.collapsingHeader(label) { content.accept(this) }

    /** An arrow and label whose [content] shows while open. */
    @JvmStatic
    fun treeNode(scope: LayoutScope, label: String, content: Consumer<ChildScope>) = scope.treeNode(label) { content.accept(this) }

    /** A table of [columns] columns; add rows with `nextRow` and cells with `nextColumn` on the table. */
    @JvmStatic
    fun table(scope: LayoutScope, id: String, columns: Int, content: Consumer<TableScope>): Boolean =
        scope.table(id, columns) { content.accept(this) }

    /** A two-column label and control grid; fill it with [row] and [valueRow]. */
    @JvmStatic
    fun properties(scope: LayoutScope, id: String, content: Consumer<TableScope>) = scope.properties(id) { content.accept(this) }

    /** One labelled row of a [properties] grid; [control] fills the value column. */
    @JvmStatic
    fun row(table: TableScope, label: String, control: Consumer<TableScope>) = table.row(label) { control.accept(this) }

    /** A read-only label and value row of a [properties] grid. */
    @JvmStatic
    fun valueRow(table: TableScope, label: String, value: String) = table.valueRow(label, value)

    /** A row of tabs; add each with [tabItem]. */
    @JvmStatic
    fun tabBar(scope: LayoutScope, id: String, content: Consumer<TabBarScope>): Boolean = scope.tabBar(id) { content.accept(this) }

    /** A tab whose [content] shows while it is selected; true while selected. */
    @JvmStatic
    fun tabItem(tabBar: TabBarScope, label: String, content: Consumer<ChildScope>): Boolean =
        tabBar.tabItem(label) { content.accept(this) }

    /** A list box [width] by [height] whose entries [content] adds. */
    @JvmStatic
    fun listBox(scope: LayoutScope, label: String, width: Float, height: Float, content: Consumer<ListBoxScope>): Boolean =
        scope.listBox(label, width, height) { content.accept(this) }

    /** [content] drawn with ImGui colour [colorIndex] set to [color]. */
    @JvmStatic
    fun styleColor(scope: LayoutScope, colorIndex: ImGuiCol, color: Int, content: Consumer<ChildScope>) =
        scope.styleColor(colorIndex, color) { content.accept(this) }

    /** [content] with controls [width] pixels wide. */
    @JvmStatic
    fun itemWidth(scope: LayoutScope, width: Float, content: Consumer<ChildScope>) = scope.itemWidth(width) { content.accept(this) }
}
