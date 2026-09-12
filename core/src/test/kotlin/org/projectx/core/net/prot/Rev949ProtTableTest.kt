package org.projectx.core.net.prot

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the rev949 codec to what the client binary actually declares.
 *
 * Both tables are emitted by the updater from the binaries themselves: opcodes and sizes come from
 * the 949-5 `ServerProt::RegisterAll` walk, and the name universe from the `jag::ServerProt` /
 * `jag::ClientProt` / `jag::ZoneProt` symbols in the unstripped reference client. A packet
 * registered under the wrong opcode shows up here as a size disagreement, which is how the
 * IF_CLOSESUB and OBJ_COUNT misassignments were found.
 */
class Rev949ProtTableTest {

    private val updater = File("re-resources/updater").takeIf { it.isDirectory }
        ?: File("../re-resources/updater").takeIf { it.isDirectory }

    private fun table(): List<Row>? {
        val file = File(updater ?: return null, "prot_949-5/prot_tables_949-5.csv")
        if (!file.isFile) return null
        return file.readLines().drop(1).mapNotNull { line ->
            val cells = line.split(',')
            if (cells.size < 3) null else Row(cells[0], cells[1].toInt(), cells[2].toInt())
        }
    }

    private fun officialNames(): Set<String>? {
        val file = File(updater ?: return null, "official_prot_names.json")
        if (!file.isFile) return null
        return Regex("\"([A-Z][A-Z0-9_]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()
    }

    private class Row(val direction: String, val opcode: Int, val size: Int)

    @Test
    fun `every server opcode declares the size the binary declares`() {
        val rows = table()
        assumeTrue(rows != null, "re-resources/updater not checked out — skipping")
        val codec = ProtRevisions.codec(949)!!
        val wrong = rows!!.filter { it.direction == "SERVER" }
            .mapNotNull { row ->
                val declared = codec.serverProtInfo[row.opcode]?.size?.toInt() ?: return@mapNotNull null
                if (declared == row.size) null else "op ${row.opcode} (${codec.serverProtName(row.opcode)}): codec $declared, binary ${row.size}"
            }
        assertEquals(emptyList(), wrong, "server prot sizes disagree with the 949-5 binary")
    }

    @Test
    fun `every registered encoder sits on an opcode of its own size`() {
        val rows = table()
        assumeTrue(rows != null, "re-resources/updater not checked out — skipping")
        val binary = rows!!.filter { it.direction == "SERVER" }.associate { it.opcode to it.size }
        val codec = ProtRevisions.codec(949)!!
        val wrong = codec.serverProts.entries.mapNotNull { (type, entry) ->
            if (entry.opcode == Codec.ZONE_ONLY_OPCODE) return@mapNotNull null
            val expected = binary[entry.opcode] ?: return@mapNotNull null
            if (entry.size.toInt() == expected) null else "${type.simpleName} @${entry.opcode}: encoder ${entry.size.toInt()}, binary $expected"
        }
        assertEquals(emptyList(), wrong, "encoders registered on an opcode the binary sizes differently")
    }

    @Test
    fun `named prots use canonical Jagex names`() {
        val official = officialNames()
        assumeTrue(official != null, "re-resources/updater not checked out — skipping")
        val codec = ProtRevisions.codec(949)!!
        val unattested = (codec.serverProtInfo.values + codec.clientProtInfo.values)
            .map { it.name }
            .filterNot { it.startsWith("UNKNOWN_") || it.startsWith("UNNAMED_") }
            .distinct()
            .filterNot { it in official!! }
            .filterNot { it in NOT_YET_IDENTIFIED }
            .sorted()
        assertEquals(emptyList(), unattested, "prot names the reference client's symbols do not attest")
    }

    private companion object {
        /**
         * Names our tables carry that the reference client does not attest. Each is either a packet
         * newer than the reference or one we have identified by behaviour without recovering its
         * Jagex name; both cases still need a canonical name. Anything not listed here fails the
         * test, so a new invented name cannot slip in unnoticed.
         */
        val NOT_YET_IDENTIFIED = setOf(
            "CHAT_SETFILTER", "CUTSCENE2D_PLAY", "DBFILTER_DEBUG", "IF_DRAG", "IF_SETOBJECT_LONG",
            "LOBBY_TICK_END", "LOC__ADD", "MAP_ANIM_ALT", "MAP_PROJANIM_ALT",
            "MAP_PROJANIM_HALFSQ_ALT", "MESSAGE_FILTER", "MESSAGE_PRIVATE_ENCRYPTED",
            "MESSAGE_QUICKCHAT", "MISC_NEW_945", "MISC_NEW_946", "NPC_SAY_SPECIFIC",
            "OBJ__ADD__BIG", "OPTILET", "PLAYER_ANIM_SPECIFIC", "PROJANIM_SPECIFIC_ALT",
            "REDUCE_NPC_ATTACK_PRIORITY", "SEND_NATIVE_MOUSE_CLICK", "SET__TARGET__MARKER",
            "SPOTANIM_SPECIFIC_ALT", "VARP__LONG",
        )
    }
}
