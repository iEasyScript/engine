package com.projectx.script.impl.devin

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.ConfigurableScript
import com.projectx.script.EnumConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.ScriptExecutor
import com.projectx.script.api.clickKey
import com.projectx.script.api.continueDialogueContaining
import com.projectx.script.api.dialogueOptionVisible
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.isDialogOpen
import com.projectx.script.api.varps
import com.projectx.util.gaussian

private const val BSLAY_TARGET_VARBIT = 22901
private const val BSLAY_KILLS_LEFT_VARBIT = 22902
private const val CHOOSE_TASK_INTERFACE = 1761
private const val CHOOSE_TASK_CLICK_LAYER = 0

private const val GRIM_GEM = "Grim gem"
private const val ACCEPT_OPTION = "certainly can"
private const val MAX_DIALOGUE_ADVANCES = 12

enum class ReaperBoss(val targetId: Int, val bossName: String) {
    ARAXXI(1, "Araxxi"),
    BARROWS_BROTHERS(2, "The Barrows Brothers"),
    BARROWS_RISE_OF_THE_SIX(3, "The Barrows: Rise of the Six"),
    CHAOS_ELEMENTAL(4, "Chaos Elemental"),
    COMMANDER_ZILYANA(5, "Commander Zilyana"),
    CORPOREAL_BEAST(6, "Corporeal Beast"),
    DAGANNOTH_KINGS(7, "Dagannoth Kings"),
    GENERAL_GRAARDOR(8, "General Graardor"),
    GIANT_MOLE(9, "Giant Mole"),
    HAR_AKEN(10, "Har-Aken"),
    KALPHITE_KING(11, "Kalphite King"),
    KALPHITE_QUEEN(12, "Kalphite Queen"),
    KING_BLACK_DRAGON(13, "King Black Dragon"),
    KREEARRA(14, "Kree'arra"),
    KRIL_TSUTSAROTH(15, "K'ril Tsutsaroth"),
    LEGIONES(16, "Legiones"),
    NEX(17, "Nex"),
    QUEEN_BLACK_DRAGON(18, "Queen Black Dragon"),
    TZTOK_JAD(19, "TzTok-Jad"),
    VORAGO(20, "Vorago"),
    BEASTMASTER_DURZAG(21, "Beastmaster Durzag"),
    YAKAMARU(22, "Yakamaru"),
    TWIN_FURIES(23, "The Twin Furies"),
    VINDICTA_AND_GORVEK(24, "Vindicta & Gorvek"),
    GREGOROVIC(25, "Gregorovic"),
    HELWYR(26, "Helwyr"),
    TELOS(27, "Telos"),
    NEX_ANGEL_OF_DEATH(28, "Nex, Angel of Death"),
    THE_MAGISTER(29, "The Magister"),
    SOLAK(30, "Solak, Guardian of the Grove"),
    SANCTUM_GUARDIAN(31, "The Sanctum Guardian"),
    MASUTA_THE_ASCENDED(32, "Masuta the Ascended"),
    SEIRYU(33, "Seiryu the Azure Serpent"),
    ASTELLARN(34, "Astellarn"),
    VERAK_LITH(35, "Verak Lith"),
    BLACK_STONE_DRAGON(36, "Black Stone Dragon"),
    CRASSIAN_LEVIATHAN(37, "Crassian Leviathan"),
    TARAKET_THE_NECROMANCER(38, "Taraket the Necromancer"),
    THE_AMBASSADOR(39, "The Ambassador"),
    RAKSHA(40, "Raksha, the Shadow Colossus"),
    REX_MATRIARCHS(41, "Rex Matriarchs"),
    KERAPAC(42, "Kerapac, the bound"),
    ARCH_GLACOR(43, "Arch-Glacor"),
    CROESUS(44, "Croesus"),
    TZKAL_ZUK(45, "TzKal-Zuk"),
    ZAMORAK(46, "Zamorak, Lord of Chaos"),
    HERMOD(47, "Hermod, the Spirit of War"),
    RASIAL(48, "Rasial, the First Necromancer"),
    ZEMOUREGAL_AND_VORKATH(49, "Zemouregal & Vorkath"),
    VERMYX(50, "Vermyx, Brood Mother"),
    KEZALAM(51, "Kezalam, the Wanderer"),
    NAKATRA(52, "Nakatra, Devourer Eternal"),
    GATE_OF_ELIDINIS(53, "The Gate of Elidinis"),
    AMASCUT(54, "Amascut, the Devourer"),
    FLESH_HATCHER_MHEKARNAHZ(55, "Flesh-hatcher Mhekarnahz"),
    IVAR(56, "Ivar, King of Bones"),
    SILVERQUILL(57, "Silverquill, the Dreadhog");

    val chooseTaskSlot get() = targetId - 1

    override fun toString() = bossName
}

@ScriptDescription(
    name = "Reaper Assignment",
    version = "1.0.0",
    author = "Devin",
    description = "Reassigns a finished Reaper task through the Grim gem, picking the configured boss from the task list, then accepts it."
)
class ReaperAssignment : Script(), ConfigurableScript {
    private val targetBoss = EnumConfigItem(
        name = "Target boss",
        description = "Which Reaper boss to pick from the assignment list.",
        enumValues = ReaperBoss.entries.toTypedArray(),
        initialValue = ReaperBoss.IVAR
    )

    private var missingGemReported = false

    override suspend fun loop() {
        if (isDialogOpen() || varps.getVarBit(BSLAY_KILLS_LEFT_VARBIT) != 0) return delay(1400, 500)
        if (!inventory.hasItem(GRIM_GEM)) {
            if (!missingGemReported) {
                println("Reaper Assignment: no $GRIM_GEM to reassign with.")
                missingGemReported = true
            }
            return delay(6000, 2200)
        }
        missingGemReported = false

        ScriptExecutor.pauseOthers(this)
        try {
            negotiateAssignment()
        } finally {
            ScriptExecutor.resumeOthers(this)
        }
        delay(2100, 800)
    }

    private suspend fun negotiateAssignment() {
        // "Activate" is only Death's generic check-in; "Assignment" is what rolls a new task at 0 kills left.
        if (!inventory.clickItem(GRIM_GEM, "Assignment")) return

        delayUntil(gaussian(6000L, 1500L)) { interfaces.isOpen(CHOOSE_TASK_INTERFACE) }
        if (!interfaces.isOpen(CHOOSE_TASK_INTERFACE)) {
            println("Reaper Assignment: task picker never opened after the Assignment click, aborting.")
            return
        }
        delay(788, 214)

        val boss = targetBoss.value
        if (!IFSlot(CHOOSE_TASK_INTERFACE, CHOOSE_TASK_CLICK_LAYER).dialogueContinue(boss.chooseTaskSlot)) {
            println("Reaper Assignment: task picker click layer is missing, aborting.")
            return
        }
        delayUntil(gaussian(6000L, 1500L)) { isDialogOpen() }

        if (!tapThroughDialogue { dialogueOptionVisible(ACCEPT_OPTION) }) return
        val assigned = varps.getVarBit(BSLAY_TARGET_VARBIT)
        if (assigned != boss.targetId) {
            println("Reaper Assignment: picked slot ${boss.chooseTaskSlot} for ${boss.bossName} but was assigned target $assigned, expected ${boss.targetId}; stopping.")
            return stop()
        }
        acceptAssignment()
    }

    private suspend fun acceptAssignment() {
        if (!continueDialogueContaining(ACCEPT_OPTION)) return
        delayUntil(gaussian(6000L, 1500L)) { varps.getVarBit(BSLAY_KILLS_LEFT_VARBIT) > 0 }
        tapThroughDialogue { !isDialogOpen() }
    }

    private suspend fun tapThroughDialogue(reached: () -> Boolean): Boolean {
        repeat(MAX_DIALOGUE_ADVANCES) {
            if (reached()) return true
            if (!isDialogOpen()) return false
            clickKey(' ')
            waitThenDelayUntil(gaussian(400L, 90L), gaussian(4200L, 900L)) { reached() || !isDialogOpen() }
        }
        return reached()
    }

    override fun onStop() {
        ScriptExecutor.resumeOthers(this)
        super.onStop()
    }
}
