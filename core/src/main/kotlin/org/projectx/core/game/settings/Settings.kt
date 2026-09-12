package org.projectx.core.game.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.projectx.core.EnvVars
import org.projectx.core.model.Vars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval
import java.io.File

/** How a settings row is drawn, which decides what the client sends when it is used. */
enum class Control { CHECKBOX, DROPDOWN, SLIDER, COLOUR, BUTTON, NUMBER_BOX, LABEL }

object Settings {
    /** Column of a `settings_all_categories` row holding that page's settings, in display order. */
    private const val PANE_SETTINGS_COLUMN = 2

    /** Column of a `settings_all_categories` row holding the page's title. */
    private const val PANE_TITLE_COLUMN = 0

    /** How far apart consecutive pages are numbered; a page's rows fill the space between. */
    private const val PANE_STRIDE = 256

    /**
     * The pane's rows are dynamic children, which the client numbers above the static ones, so the
     * page a click reports sits this far above that page's key in `settings_all_categories`.
     */
    private const val PANE_BASE = 15

    /** The value of a row's `control_type` param when the struct leaves it unset. */
    private const val CONTROL_CHECKBOX = 3

    /** Every `settings_var_reference` value is a var domain in its top byte over the var id. */
    private const val VAR_REFERENCE_DOMAIN_SHIFT = 24
    private const val VAR_REFERENCE_VARBIT = 1

    @Serializable
    private data class Model(val categories: List<String>, val settings: List<Setting>)

    private val model: Model by lazy {
        val file = File(EnvVars.dataPath, "settings/settings.json")
        require(file.isFile) { "settings file missing: ${file.absolutePath}" }
        Json { ignoreUnknownKeys = true }.decodeFromString(Model.serializer(), file.readText())
    }

    val categories: List<String> get() = model.categories
    val all: List<Setting> get() = model.settings
    val real: List<Setting> by lazy { all.filter { it.type != SettingType.SEPARATOR } }
    val byName: Map<String, Setting> by lazy { all.associateBy { it.name } }
    val byStruct: Map<Int, Setting> by lazy { all.associateBy { it.struct } }

    val byCategory: Map<String, Map<String, List<Setting>>> by lazy {
        categories.associateWith { cat ->
            real.filter { it.category == cat }.groupBy { it.subpage }
        }
    }

    fun find(name: String): Setting? = byName[name] ?: byName["settings_$name"]
    fun find(struct: Int): Setting? = byStruct[struct]

    /** How many pages the pane can show, which is how wide its category and cross-link lists are built. */
    val paneCount: Int get() = pageRows.size

    /** One slot range per page of the pane, for arming every row a page can hold. */
    val paneRanges: List<IntRange> by lazy {
        panes.map { (page, structs) -> paneSlot(page, 0)..paneSlot(page, structs.size) }
    }

    /**
     * The setting a pane click landed on. A row reports the page it belongs to and its index within
     * that page packed into one slot, and both come from the cache rather than from [model], so the
     * pane stays addressable when a page is reordered.
     */
    fun findBySlot(slot: Int): Setting? {
        val page = slot / PANE_STRIDE - PANE_BASE
        return panes[page]?.getOrNull(slot % PANE_STRIDE)?.let(::find)
    }

    /** The pane page titled [title], and the page that groups it, for pointing the pane at a page. */
    fun pageTitled(title: String): Pair<Int, Int>? {
        val page = pageRows.entries.firstOrNull { (_, row) -> Cache.dbRow(row)?.string(PANE_TITLE_COLUMN) == title }?.key
            ?: return null
        val master = pageRows.entries.firstOrNull { (_, row) ->
            val column = Cache.dbRow(row)?.column(PANE_SUBPAGES_COLUMN) ?: return@firstOrNull false
            (0 until column.tupleCount).any { column.int(it) == pageRows[page] }
        }?.key ?: return null
        return master to page
    }

    private const val PANE_SUBPAGES_COLUMN = 3

    /**
     * Each page of the settings pane against the setting structs it lists. The pages are the rows of
     * `settings_all_categories`; a page that only groups other pages carries no settings of its own.
     */
    private val panes: Map<Int, List<Int>> by lazy {
        val out = LinkedHashMap<Int, List<Int>>(pageRows.size)
        for ((page, row) in pageRows) {
            val column = Cache.dbRow(row)?.column(PANE_SETTINGS_COLUMN) ?: continue
            val structs = (0 until column.tupleCount).map { column.int(it) }
            if (structs.isNotEmpty()) out[page] = structs
        }
        out
    }

    private val pageRows: Map<Int, Int> by lazy {
        Cache.enum(Gameval.requireId(Gameval.ENUM, "settings_all_categories"))?.values.orEmpty()
            .mapNotNull { (page, row) -> (row as? Int)?.let { page to it } }
            .toMap()
    }

    private fun paneSlot(page: Int, index: Int) = (PANE_BASE + page) * PANE_STRIDE + index

    // -------------------------------------------------------------- row controls

    private fun param(name: String) = Gameval.requireId(Gameval.PARAM, name)

    private val controlType by lazy { param("control_type") }
    private val controlMin by lazy { param("control_min_val") }
    private val controlMax by lazy { param("control_max_val") }
    private val controlInverted by lazy { param("control_checkbox_inverted") }
    private val clientOnlyParam by lazy { param("settings_client_only") }
    private val varReference by lazy { param("settings_var_reference") }
    private val warningId by lazy { param("cws_id") }

    private fun structParam(setting: Setting, param: Int, default: Int): Int =
        Cache.struct(setting.struct)?.getIntValue(param, default) ?: default

    fun control(setting: Setting): Control = when (structParam(setting, controlType, CONTROL_CHECKBOX)) {
        1, 2, 11 -> Control.LABEL
        3 -> Control.CHECKBOX
        4, 5 -> Control.DROPDOWN
        6, 7, 8 -> Control.SLIDER
        9 -> Control.COLOUR
        10 -> Control.BUTTON
        1000, 1001, 1002 -> Control.NUMBER_BOX
        else -> Control.LABEL
    }

    /** A slider reports how far along it sits; its struct says where it starts and how far it runs. */
    fun sliderRange(setting: Setting): IntRange {
        val min = structParam(setting, controlMin, 0)
        return min..min + structParam(setting, controlMax, 0)
    }

    /** A row the client resolves entirely on its own, whose clicks the server only has to tolerate. */
    fun clientOnly(setting: Setting): Boolean = structParam(setting, clientOnlyParam, 0) == 1

    /** The checkbox draws the opposite of its var, so the var is toggled the same way either way. */
    fun inverted(setting: Setting): Boolean = structParam(setting, controlInverted, 0) == 1

    // ------------------------------------------------------------------ values

    private class Binding(val get: (Vars) -> Int?, val set: (Vars, Int) -> Boolean)

    private val bindings = HashMap<String, Binding>()

    private fun varId(setting: Setting): Int? {
        val domain = when (setting.domain) {
            VarDomain.VARBIT -> Gameval.VARBIT
            VarDomain.VARP -> Gameval.VAR_PLAYER
            null -> return null
        }
        return Gameval.id(domain, setting.backingVar!!)
    }

    private fun bindingOf(setting: Setting): Binding? = bindings.getOrPut(setting.name) {
        SettingBindings.resolve(setting) ?: referenceBinding(setting) ?: directBinding(setting) ?: return null
    }

    private fun directBinding(setting: Setting): Binding? {
        if (setting.readonly) return null
        val id = varId(setting) ?: return null
        return when (setting.domain) {
            VarDomain.VARBIT -> Binding({ it.getVarBit(id) }, { vars, value -> vars.setVarBit(id, value, forceSend = true, save = true); true })
            VarDomain.VARP -> Binding({ it.getVar(id) }, { vars, value -> vars.setVar(id, value, forceSend = true, save = true); true })
            null -> null
        }
    }

    private fun referenceBinding(setting: Setting): Binding? {
        val reference = structParam(setting, varReference, -1)
        if (reference == -1) return null
        val id = reference and ((1 shl VAR_REFERENCE_DOMAIN_SHIFT) - 1)
        return when (reference ushr VAR_REFERENCE_DOMAIN_SHIFT) {
            VAR_REFERENCE_VARBIT -> Binding({ it.getVarBit(id) }, { vars, value -> vars.setVarBit(id, value, forceSend = true, save = true); true })
            else -> Binding({ it.getVar(id) }, { vars, value -> vars.setVar(id, value, forceSend = true, save = true); true })
        }
    }

    /** Whether the server holds this setting's value, so a click on it must change a var. */
    fun bound(setting: Setting): Boolean = setting.type != SettingType.SEPARATOR && bindingOf(setting) != null

    fun get(vars: Vars, setting: Setting): Int? = bindingOf(setting)?.get?.invoke(vars)

    fun get(vars: Vars, name: String): Int? = find(name)?.let { get(vars, it) }

    fun set(vars: Vars, setting: Setting, value: Int): Boolean =
        bindingOf(setting)?.set?.invoke(vars, value) ?: false

    fun set(vars: Vars, name: String, value: Int): Boolean = find(name)?.let { set(vars, it, value) } ?: false

    fun apply(vars: Vars, struct: Int, value: Int): Boolean = find(struct)?.let { set(vars, it, value) } ?: false

    fun toggle(vars: Vars, setting: Setting): Boolean {
        val current = get(vars, setting) ?: return false
        return set(vars, setting, if (current == 0) 1 else 0)
    }

    fun toggle(vars: Vars, struct: Int): Boolean = find(struct)?.let { toggle(vars, it) } ?: false

    /**
     * The settings whose value is not a var of its own but a bit, a pair of vars, an enum lookup or
     * a warning-screen counter. Each one mirrors how the client's `settings_get_state` reads it.
     */
    private object SettingBindings {
        private val TEST_BIT = Regex("""testbit\(%(\w+), (\d+)\)""")
        private val LOG_BIT = Regex("""~script2590\((\d+)\)""")
        private val BEAM = Regex("""~script2407\((\d+)\)""")
        private val BAR_NAME = Regex("""~script17470\((\d+)\)""")
        private val CLAMPED = Regex("""~clamp\(%(\w+), 0, 1\)""")
        private val CAPPED = Regex("""min\(%(\w+), 1\)""")
        private val EITHER = Regex("""max\(%(\w+), %(\w+)\)""")

        private val BEAMS = mapOf(
            1 to "bslay_lootbeam_active_default", 2 to "bslay_lootbeam_active_rainbow",
            3 to "bslay_lootbeam_active_christmas", 4 to "bslay_lootbeam_active_beach",
            5 to "bslay_lootbeam_active_water", 6 to "bslay_lootbeam_active_grave",
            7 to "bslay_lootbeam_active_lovedup", 8 to "bslay_lootbeam_active_parcel",
            9 to "bslay_lootbeam_active_easter24", 10 to "bslay_lootbeam_active_snowman",
            11 to "bslay_lootbeam_active_frozen", 12 to "bslay_lootbeam_active_warped",
            13 to "bslay_lootbeam_active_good_pirate", 14 to "bslay_lootbeam_active_evil_pirate",
            15 to "bslay_lootbeam_active_occult",
        )

        val beamVars: Collection<String> get() = BEAMS.values

        /** The warning screens whose var is not named after their id, as the client's `cws_getvar` lists them. */
        private val WARNING_VARS = mapOf(
            37 to "cws_warning_38",
            38 to "cws_warning_39",
            39 to "cws_warning_40",
            41 to "dnd_summon_message_toggle",
            42 to "rand_dnd_broadcasts",
            43 to "warband_broadcast_toggle",
            44 to "chat_filter_worldevent",
            45 to "div_dnd_broadcast_toggle",
            47 to "ardragon_brimhaven_toggle",
            48 to "asc_warning_toggle",
            50 to "cws_skeletal_wyvern_toggle",
            51 to "cws_araxxor_toggle",
            54 to "cws_item_pickup_pvp_toggle",
        )

        private const val WARNING_SHOWN = 0
        private const val WARNING_DISMISSED = 7
        private const val WARNING_LAST_SHOWN = 6

        private const val COMBAT_MODE_MANUAL = 0
        private const val COMBAT_MODE_REVOLUTION = 1

        private const val TARGET_BOTH = 0
        private const val TARGET_PLAYERS = 1
        private const val TARGET_NPCS = 2

        fun resolve(setting: Setting): Binding? {
            val expr = setting.expr.orEmpty()
            TEST_BIT.matchEntire(expr)?.destructured?.let { (name, bit) -> return varpBit(name, bit.toInt()) }
            LOG_BIT.matchEntire(expr)?.destructured?.let { (bit) -> return varpBit("rs_plog_selector", bit.toInt()) }
            BEAM.matchEntire(expr)?.destructured?.let { (beam) -> return varbit(BEAMS.getValue(beam.toInt())) }
            BAR_NAME.matchEntire(expr)?.destructured?.let { (bar) -> return varbit("combatv2_actionbar_name_$bar") }
            CLAMPED.matchEntire(expr)?.destructured?.let { (name) -> return varbit(name) }
            CAPPED.matchEntire(expr)?.destructured?.let { (name) -> return varbit(name) }
            EITHER.matchEntire(expr)?.destructured?.let { (first, second) -> return either(first, second) }
            if (expr == "~script3873") return varbit("settings_chat_fontmetric")
            if (setting.name.startsWith("settings_doom_")) return warning(setting)
            return byName[setting.name]
        }

        private val byName: Map<String, Binding> by lazy {
            val out = HashMap<String, Binding>()
            for (name in listOf("settings_menu_one_button_gameplay", "settings_menu_one_button_gameplay_mobile")) {
                out[name] = varbit("option_mouse")
            }
            for (name in listOf("settings_windows_chatboxes", "settings_windows_chatboxes_mobile")) {
                out[name] = varbit("toplevel_v2_allow_window_clickthrough")
            }
            for (name in listOf("settings_combat_players", "settings_combat_players_mobile")) {
                out[name] = varbit("combatv2_player_attack_priority")
            }
            for (name in listOf("settings_combat_npcs", "settings_combat_npcs_mobile")) {
                out[name] = varbit("combatv2_npc_attack_priority")
            }
            for (name in listOf("settings_interfaces_slayercounter", "settings_interfaces_slayercounter_mobile")) {
                out[name] = Binding(
                    { it.getVarBit("slayer_toggle_slayer_counter") },
                    { vars, value -> vars.setVarBit("slayer_toggle_slayer_counter", if (value > 0) 1 else 0, forceSend = true, save = true); true },
                )
            }
            out["settings_legacy_skin"] = varbit("toplevel_v2_active_skinset")
            out["settings_windows_interface_style"] = varbit("toplevel_v2_legacy_interface")
            out["settings_interfaces_tutsys_hints"] = inverted("tutsys_disabled")
            out["settings_combat_mode_manual"] = combatMode(COMBAT_MODE_MANUAL)
            out["settings_combat_mode_revo"] = combatMode(COMBAT_MODE_REVOLUTION)
            out["settings_combat_mode_classic"] = varbit("toplevel_v2_legacy_combat")
            out["settings_cycle_target_pvp"] = targeting()
            out["settings_summoning_left_click_pet"] = enumIndexed("lore_selected_op1_pet", "lore_settings_pet")
            out["settings_summoning_left_click_legendary"] = enumIndexed("lore_selected_op1_legendary_pet", "lore_settings_legendary")
            out["settings_accessibility_highlight_mode"] = highlightMode()
            out["settings_accessibility_highlight_type"] = contrastAware("accessibility_highlight_type", "accessibility_high_contrast_highlight_type")
            out["settings_accessibility_highlight_active_player_colour"] = highlightColour("active_player")
            out["settings_accessibility_highlight_other_player_colour"] = highlightColour("other_player")
            out["settings_accessibility_highlight_npc_colour"] = highlightColour("npc")
            out["settings_accessibility_highlight_monster_colour"] = highlightColour("monster")
            out["settings_accessibility_highlight_interactables_colour"] = highlightColour("interactables")
            out["settings_accessibility_highlight_loot_colour"] = highlightColour("loot")
            out["settings_accessibility_highlight_pixel_size"] = contrastAwareVarp("accessibility_highlight_pixel_size", "accessibility_high_contrast_highlight_pixel_size")
            out
        }

        /** A var by name from whichever domain holds it; the client's scripts name both the same way. */
        private fun varbit(name: String): Binding {
            Gameval.id(Gameval.VARBIT, name)?.let { id ->
                return Binding({ it.getVarBit(id) }, { vars, value -> vars.setVarBit(id, value, forceSend = true, save = true); true })
            }
            val id = Gameval.requireId(Gameval.VAR_PLAYER, name)
            return Binding({ it.getVar(id) }, { vars, value -> vars.setVar(id, value, forceSend = true, save = true); true })
        }

        private fun inverted(name: String): Binding {
            val id = Gameval.requireId(Gameval.VARBIT, name)
            return Binding(
                { if (it.getVarBit(id) == 1) 0 else 1 },
                { vars, value -> vars.setVarBit(id, if (value == 0) 1 else 0, forceSend = true, save = true); true },
            )
        }

        private fun varpBit(name: String, bit: Int): Binding {
            val id = Gameval.requireId(Gameval.VAR_PLAYER, name)
            return Binding(
                { if (it.bitFlagged(id, bit)) 1 else 0 },
                { vars, value ->
                    val current = vars.getVar(id)
                    vars.setVar(id, if (value == 0) current and (1 shl bit).inv() else current or (1 shl bit), forceSend = true, save = true)
                    true
                },
            )
        }

        private fun either(first: String, second: String): Binding {
            val a = Gameval.requireId(Gameval.VARBIT, first)
            val b = Gameval.requireId(Gameval.VARBIT, second)
            return Binding(
                { maxOf(it.getVarBit(a), it.getVarBit(b)) },
                { vars, value -> vars.setVarBit(b, value, forceSend = true, save = true); true },
            )
        }

        private fun warning(setting: Setting): Binding? {
            val warning = structParam(setting, warningId, -1)
            if (warning == -1) return null
            val id = Gameval.id(Gameval.VARBIT, WARNING_VARS[warning] ?: "cws_warning_$warning") ?: return null
            return Binding(
                { if (it.getVarBit(id) <= WARNING_LAST_SHOWN) 1 else 0 },
                { vars, value ->
                    vars.setVarBit(id, if (value == 0) WARNING_DISMISSED else WARNING_SHOWN, forceSend = true, save = true)
                    true
                },
            )
        }

        private fun combatMode(mode: Int): Binding {
            val current = Gameval.requireId(Gameval.VARBIT, "combatv2_combat_mode")
            val legacy = Gameval.requireId(Gameval.VARBIT, "toplevel_v2_legacy_combat")
            return Binding(
                { if (it.getVarBit(legacy) == 0 && it.getVarBit(current) == mode) 1 else 0 },
                { vars, value ->
                    if (value == 0) return@Binding false
                    vars.setVarBit(legacy, 0, forceSend = true, save = true)
                    vars.setVarBit(current, mode, forceSend = true, save = true)
                    true
                },
            )
        }

        private fun targeting(): Binding {
            val players = Gameval.requireId(Gameval.VARBIT, "combatv2_targeting_target_players")
            val npcs = Gameval.requireId(Gameval.VARBIT, "combatv2_targeting_target_npcs")
            return Binding(
                {
                    when {
                        it.getVarBit(players) == 0 -> TARGET_NPCS
                        it.getVarBit(npcs) == 0 -> TARGET_PLAYERS
                        else -> TARGET_BOTH
                    }
                },
                { vars, value ->
                    vars.setVarBit(players, if (value == TARGET_NPCS) 0 else 1, forceSend = true, save = true)
                    vars.setVarBit(npcs, if (value == TARGET_PLAYERS) 0 else 1, forceSend = true, save = true)
                    true
                },
            )
        }

        /** A dropdown listing an enum's values, whose var holds the value while the row shows its index. */
        private fun enumIndexed(varbit: String, enum: String): Binding {
            val id = Gameval.requireId(Gameval.VARBIT, varbit)
            val values = Cache.enum(Gameval.requireId(Gameval.ENUM, enum))?.values.orEmpty()
                .mapNotNull { (index, value) -> (value as? Int)?.let { index to it } }
                .sortedBy { it.first }
            return Binding(
                { vars -> values.indexOfFirst { it.second == vars.getVarBit(id) }.coerceAtLeast(0) },
                { vars, index ->
                    val value = values.getOrNull(index)?.second ?: return@Binding false
                    vars.setVarBit(id, value, forceSend = true, save = true)
                    true
                },
            )
        }

        private val highContrast by lazy { Gameval.requireId(Gameval.VARBIT, "accessibility_high_contrast_enabled") }

        private fun contrastAware(normal: String, contrast: String): Binding {
            val plain = Gameval.requireId(Gameval.VARBIT, normal)
            val high = Gameval.requireId(Gameval.VARBIT, contrast)
            fun pick(vars: Vars) = if (vars.getVarBit(highContrast) == 1) high else plain
            return Binding({ it.getVarBit(pick(it)) }, { vars, value -> vars.setVarBit(pick(vars), value, forceSend = true, save = true); true })
        }

        private fun contrastAwareVarp(normal: String, contrast: String): Binding {
            val plain = Gameval.requireId(Gameval.VAR_PLAYER, normal)
            val high = Gameval.requireId(Gameval.VAR_PLAYER, contrast)
            fun pick(vars: Vars) = if (vars.getVarBit(highContrast) == 1) high else plain
            return Binding({ it.getVar(pick(it)) }, { vars, value -> vars.setVar(pick(vars), value, forceSend = true, save = true); true })
        }

        private fun highlightColour(entity: String) =
            contrastAware("accessibility_highlight_${entity}_colour", "accessibility_high_contrast_highlight_${entity}_colour")

        /** The mode dropdown lists modes in its own order, translated through a pair of enums. */
        private fun highlightMode(): Binding {
            val stored = contrastAware("accessibility_highlight_mode", "accessibility_high_contrast_highlight_mode")
            val toOption = enumMap("accessibility_highlight_mode_selected_mode_to_dropdown_option")
            val toMode = enumMap("accessibility_highlight_mode_dropdown_option_to_selected_mode")
            return Binding(
                { vars -> stored.get(vars)?.let { toOption[it] ?: it } },
                { vars, option -> stored.set(vars, toMode[option] ?: option) },
            )
        }

        private fun enumMap(name: String): Map<Int, Int> =
            Cache.enum(Gameval.requireId(Gameval.ENUM, name))?.values.orEmpty()
                .mapNotNull { (key, value) -> (value as? Int)?.let { key to it } }
                .toMap()
    }

    /** Every loot beam style the "all beams off" button clears. */
    val beamVars: Collection<String> get() = SettingBindings.beamVars
}
