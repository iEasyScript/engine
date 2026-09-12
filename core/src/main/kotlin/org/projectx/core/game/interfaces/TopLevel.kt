package org.projectx.core.game.interfaces

import org.projectx.core.Logger.logInfo
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.StructType
import world.gregs.voidps.gameval.Gameval

/**
 * The `toplevel_v2` window system, read from the cache rather than transcribed.
 *
 * Every panel the game frame can show is described by cache data: [Parent] windows come from
 * `toplevel_v2_parent_index_to_data`, their tabs from the `toplevel_v2_parent_tab_struct_*` params
 * on each parent, and the free-floating ribbon panels from `toplevel_v2_window_id_to_data`. The
 * server therefore never needs a hand-written table of which interface belongs on which tab - it
 * asks the cache, which is the same source the client builds the frame from.
 *
 * The one binding the cache does not carry is which varbit remembers a parent's last open tab;
 * that is resolved by gameval name in [LAST_TAB_VARBIT].
 */
object TopLevel {

    /** One tab of a [Parent], laid out across `toplevel_v2_parent:suboverlay_layer_1..n`. */
    class Tab(
        val index: Int,
        val caption: String,
        val layers: List<Int>,
        val hiddenIfLocked: Boolean,
        val hiddenIfMobile: Boolean,
        val hiddenIfLegacy: Boolean,
        val allowedInCombat: Boolean,
    )

    /** A ribbon category - the window opened into `toplevel_v2:parent_window_content`. */
    class Parent(
        val index: Int,
        val name: String,
        val windowInterface: Int,
        val bannerInterface: Int,
        val defaultTab: Int,
        val allowedInCombat: Boolean,
        val tabs: List<Tab>,
        val lastTabVarbit: String?,
    ) {
        fun tab(index: Int): Tab? = tabs.firstOrNull { it.index == index }
    }

    /**
     * A free-floating ribbon panel. The client owns its visibility, so the server's only interest is
     * [contentComponent] - where the panel's interface is placed when the frame is built - and the
     * [parentIndex]/[parentTab] the same content also lives under.
     */
    class Window(
        val id: Int,
        val name: String,
        val contentComponent: Int,
        val parentIndex: Int,
        val parentTab: Int,
    )

    private fun param(name: String) = Gameval.requireId(Gameval.PARAM, name)
    private fun enumOf(name: String) = Cache.enum(Gameval.requireId(Gameval.ENUM, name))

    /** How many `_1.._n` variants of a repeated param the cache actually defines. */
    private fun paramSeries(prefix: String): List<Int> =
        generateSequence(1) { it + 1 }
            .map { Gameval.id(Gameval.PARAM, "$prefix$it") }
            .takeWhile { it != null }
            .filterNotNull()
            .toList()

    private val TAB_STRUCT_PARAMS by lazy { paramSeries("toplevel_v2_parent_tab_struct_") }
    private val LAYER_PARAMS by lazy { paramSeries("toplevel_v2_parent_tab_suboverlay_") }

    /** The deepest tab layout any parent uses, which is how many suboverlay layers the frame needs. */
    val layerCount: Int get() = LAYER_PARAMS.size

    /** The most tabs any parent can carry, which is how wide the frame's tab bar is built. */
    val maxTabs: Int get() = TAB_STRUCT_PARAMS.size

    private val LAST_TAB_VARBIT: Map<Int, String> = mapOf(
        0 to "toplevel_v2_parent_last_parent_tab_id_hero",
        1 to "toplevel_v2_parent_last_parent_tab_id_customisations",
        2 to "toplevel_v2_parent_last_parent_tab_id_powers",
        3 to "toplevel_v2_parent_last_parent_tab_id_adventures",
        4 to "toplevel_v2_parent_last_parent_tab_id_social",
        5 to "toplevel_v2_parent_last_parent_tab_id_grand_exchange",
        7 to "toplevel_v2_parent_last_parent_tab_id_mtx",
        8 to "toplevel_v2_parent_last_parent_tab_id_telemetry",
        9 to "toplevel_v2_parent_last_parent_tab_id_settings",
        12 to "toplevel_v2_parent_last_parent_tab_id_league",
    )

    val parents: Map<Int, Parent> by lazy { loadParents() }

    val windows: Map<Int, Window> by lazy { loadWindows() }

    /**
     * Ribbon slot to parent index. The ribbon's category buttons are the fixed ordered set the cache
     * lists in `ribbon_windows_select_parent`; `ribbon_real_parent_id` turns each of those window
     * ids back into the parent it opens.
     */
    val ribbonParents: Map<Int, Int> by lazy { parentsOf("ribbon_windows_select_parent") }

    /**
     * Escape-menu slot to parent index. The menu lists the categories down its left edge from its own
     * ordered set, which is not the ribbon's, so a slot means a different category in each.
     */
    val escapeMenuParents: Map<Int, Int> by lazy { parentsOf("ribbon_windows_escapemenu") }

    private fun parentsOf(windows: String): Map<Int, Int> {
        val realParent = enumOf("ribbon_real_parent_id")?.values.orEmpty()
        return enumOf(windows)?.values.orEmpty()
            .mapNotNull { (slot, window) ->
                val parent = realParent[window as? Int ?: return@mapNotNull null] as? Int ?: return@mapNotNull null
                slot to parent
            }
            .toMap()
    }

    /** The window the parent overlay itself occupies, which is what closes it client-side. */
    val parentWindowId: Int by lazy {
        val content = Gameval.requireComponentHash("toplevel_v2:parent_window_content")
        windows.values.first { it.contentComponent == content }.id
    }

    val closeWindowScript: Int by lazy { Gameval.requireId(Gameval.CLIENTSCRIPT, "[clientscript,toplevel_v2_close_window]") }

    val openParentScript: Int by lazy { Gameval.requireId(Gameval.CLIENTSCRIPT, "[clientscript,toplevel_v2_open_parent]") }

    fun parent(index: Int): Parent? = parents[index]

    fun window(id: Int): Window? = windows[id]

    fun parentNamed(name: String): Parent? = parents.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** The parent whose banner overlay is [interfaceId] - banners are one per parent. */
    fun parentByBanner(interfaceId: Int): Parent? = parents.values.firstOrNull { it.bannerInterface == interfaceId }

    private fun loadParents(): Map<Int, Parent> {
        val nameParam = param("toplevel_v2_window_name")
        val windowInterfaceParam = param("toplevel_v2_window_interface")
        val bannerParam = param("toplevel_v2_parent_banner_suboverlay")
        val defaultTabParam = param("toplevel_v2_parent_tab_struct_default")
        val combatParam = param("toplevel_v2_parent_allowed_in_combat")

        val loaded = enumOf("toplevel_v2_parent_index_to_data")?.values.orEmpty().mapNotNull { (index, structId) ->
            val struct = Cache.struct(structId as? Int ?: return@mapNotNull null) ?: return@mapNotNull null
            // Positional: the tab a click reports is the position of its param, so a parent that
            // leaves one of the six undefined must leave a hole rather than close the list up.
            val tabStructs = TAB_STRUCT_PARAMS.map { struct.params?.get(it) as? Int }
            val defaultStruct = struct.params?.get(defaultTabParam) as? Int
            val tabs = tabStructs.mapIndexedNotNull { i, id ->
                id?.let { Cache.struct(it) }?.let { tab(i + 1, it) }
            }
            index to Parent(
                index = index,
                name = struct.getStringValue(nameParam) ?: "",
                windowInterface = struct.getIntValue(windowInterfaceParam, NONE),
                bannerInterface = struct.getIntValue(bannerParam, NONE),
                defaultTab = defaultStruct?.let { tabStructs.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 1,
                allowedInCombat = struct.getIntValue(combatParam) != 0,
                tabs = tabs,
                lastTabVarbit = LAST_TAB_VARBIT[index],
            )
        }.toMap()

        logInfo("TopLevel: ${loaded.size} parent windows, ${loaded.values.sumOf { it.tabs.size }} tabs, $layerCount suboverlay layers")
        return loaded
    }

    private fun tab(index: Int, struct: StructType): Tab = Tab(
        index = index,
        caption = struct.getStringValue(param("toplevel_v2_parent_suboverlay_tab_text")) ?: "",
        layers = LAYER_PARAMS.map { struct.params?.get(it) as? Int }.takeWhile { it != null }.filterNotNull(),
        hiddenIfLocked = struct.getIntValue(param("toplevel_v2_parent_tab_hidden_if_locked")) != 0,
        hiddenIfMobile = struct.getIntValue(param("toplevel_v2_parent_tab_hidden_if_mobile")) != 0,
        hiddenIfLegacy = struct.getIntValue(param("toplevel_v2_parent_tab_hidden_if_legacy")) != 0,
        allowedInCombat = struct.getIntValue(param("toplevel_v2_parent_tab_allowed_in_combat"), 1) != 0,
    )

    private fun loadWindows(): Map<Int, Window> {
        val nameParam = param("toplevel_v2_window_name")
        val contentParam = param("toplevel_v2_window_content_layer")
        val parentParam = param("toplevel_v2_window_primary_parent_id")
        val parentTabParam = param("toplevel_v2_window_primary_parent_tab_id")

        return enumOf("toplevel_v2_window_id_to_data")?.values.orEmpty().mapNotNull { (id, structId) ->
            val struct = Cache.struct(structId as? Int ?: return@mapNotNull null) ?: return@mapNotNull null
            id to Window(
                id = id,
                name = struct.getStringValue(nameParam) ?: "",
                contentComponent = struct.getIntValue(contentParam, NONE),
                parentIndex = struct.getIntValue(parentParam, NONE),
                parentTab = struct.getIntValue(parentTabParam, NONE),
            )
        }.toMap()
    }

    const val NONE = -1
}
