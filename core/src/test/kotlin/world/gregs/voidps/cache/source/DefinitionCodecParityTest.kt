package world.gregs.voidps.cache.source

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.CacheFixture
import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.source.codec.DefinitionJson
import world.gregs.voidps.cache.sqlite.SQLiteCache
import world.gregs.voidps.cache.type.OpcodeOrdered
import world.gregs.voidps.cache.type.data.BASType
import world.gregs.voidps.cache.type.data.LocType
import world.gregs.voidps.cache.type.data.NpcType
import world.gregs.voidps.cache.type.data.ObjType
import world.gregs.voidps.cache.type.data.SeqType
import world.gregs.voidps.cache.type.data.CursorType
import world.gregs.voidps.cache.type.data.DbRowType
import world.gregs.voidps.cache.type.data.DbTableType
import world.gregs.voidps.cache.type.data.EnumType
import world.gregs.voidps.cache.type.data.HeadbarType
import world.gregs.voidps.cache.type.data.HitmarkType
import world.gregs.voidps.cache.type.data.IDKType
import world.gregs.voidps.cache.type.data.InvType
import world.gregs.voidps.cache.type.data.MapElementType
import world.gregs.voidps.cache.type.data.MapSceneType
import world.gregs.voidps.cache.type.data.OverlayType
import world.gregs.voidps.cache.type.data.ParamType
import world.gregs.voidps.cache.type.data.QuestType
import world.gregs.voidps.cache.type.data.SeqGroupType
import world.gregs.voidps.cache.type.data.SpotAnimType
import world.gregs.voidps.cache.type.data.StructType
import world.gregs.voidps.cache.type.data.UnderlayType
import world.gregs.voidps.cache.type.data.VarBitType
import world.gregs.voidps.cache.type.data.VarClanSettingType
import world.gregs.voidps.cache.type.data.VarClanType
import world.gregs.voidps.cache.type.data.VarControllerType
import world.gregs.voidps.cache.type.data.VarGlobalType
import world.gregs.voidps.cache.type.data.VarGroupType
import world.gregs.voidps.cache.type.data.VarMapSquareType
import world.gregs.voidps.cache.type.data.VarNpcType
import world.gregs.voidps.cache.type.data.VarObjectType
import world.gregs.voidps.cache.type.data.VarPlayerType
import world.gregs.voidps.cache.type.data.VarWorldType
import world.gregs.voidps.cache.type.data.VarcType
import world.gregs.voidps.cache.type.data.WaterType
import world.gregs.voidps.cache.type.data.WorldAreaType
import world.gregs.voidps.cache.type.decoder.BASDecoder
import world.gregs.voidps.cache.type.decoder.LocDecoder
import world.gregs.voidps.cache.type.decoder.NpcDecoder
import world.gregs.voidps.cache.type.decoder.ObjDecoder
import world.gregs.voidps.cache.type.decoder.SeqDecoder
import world.gregs.voidps.cache.type.decoder.CursorDecoder
import world.gregs.voidps.cache.type.decoder.DbRowDecoder
import world.gregs.voidps.cache.type.decoder.DbTableDecoder
import world.gregs.voidps.cache.type.decoder.EnumDecoder
import world.gregs.voidps.cache.type.decoder.HeadbarDecoder
import world.gregs.voidps.cache.type.decoder.HitmarkDecoder
import world.gregs.voidps.cache.type.decoder.IDKDecoder
import world.gregs.voidps.cache.type.decoder.InventoryDecoder
import world.gregs.voidps.cache.type.decoder.MapElementDecoder
import world.gregs.voidps.cache.type.decoder.MapSceneDecoder
import world.gregs.voidps.cache.type.decoder.OverlayDecoder
import world.gregs.voidps.cache.type.decoder.ParamDecoder
import world.gregs.voidps.cache.type.decoder.QuestDecoder
import world.gregs.voidps.cache.type.decoder.SeqGroupDecoder
import world.gregs.voidps.cache.type.decoder.SpotAnimDecoder
import world.gregs.voidps.cache.type.decoder.StructDecoder
import world.gregs.voidps.cache.type.decoder.UnderlayDecoder
import world.gregs.voidps.cache.type.decoder.VarBitDecoder
import world.gregs.voidps.cache.type.decoder.VarClanDecoder
import world.gregs.voidps.cache.type.decoder.VarClanSettingDecoder
import world.gregs.voidps.cache.type.decoder.VarControllerDecoder
import world.gregs.voidps.cache.type.decoder.VarGlobalDecoder
import world.gregs.voidps.cache.type.decoder.VarGroupDecoder
import world.gregs.voidps.cache.type.decoder.VarMapSquareDecoder
import world.gregs.voidps.cache.type.decoder.VarNpcDecoder
import world.gregs.voidps.cache.type.decoder.VarObjectDecoder
import world.gregs.voidps.cache.type.decoder.VarPlayerDecoder
import world.gregs.voidps.cache.type.decoder.VarWorldDecoder
import world.gregs.voidps.cache.type.decoder.VarcDecoder
import world.gregs.voidps.cache.type.decoder.WaterDecoder
import world.gregs.voidps.cache.type.decoder.WorldAreaDecoder
import world.gregs.voidps.cache.type.encoder.BASEncoder
import world.gregs.voidps.cache.type.encoder.LocEncoder
import world.gregs.voidps.cache.type.encoder.NpcEncoder
import world.gregs.voidps.cache.type.encoder.ObjEncoder
import world.gregs.voidps.cache.type.encoder.SeqEncoder
import world.gregs.voidps.cache.type.encoder.CursorEncoder
import world.gregs.voidps.cache.type.encoder.DbRowEncoder
import world.gregs.voidps.cache.type.encoder.DbTableEncoder
import world.gregs.voidps.cache.type.encoder.EnumEncoder
import world.gregs.voidps.cache.type.encoder.HeadbarEncoder
import world.gregs.voidps.cache.type.encoder.HitmarkEncoder
import world.gregs.voidps.cache.type.encoder.IDKEncoder
import world.gregs.voidps.cache.type.encoder.InventoryEncoder
import world.gregs.voidps.cache.type.encoder.MapElementEncoder
import world.gregs.voidps.cache.type.encoder.MapSceneEncoder
import world.gregs.voidps.cache.type.encoder.OverlayEncoder
import world.gregs.voidps.cache.type.encoder.ParamEncoder
import world.gregs.voidps.cache.type.encoder.QuestEncoder
import world.gregs.voidps.cache.type.encoder.SeqGroupEncoder
import world.gregs.voidps.cache.type.encoder.SpotAnimEncoder
import world.gregs.voidps.cache.type.encoder.StructEncoder
import world.gregs.voidps.cache.type.encoder.UnderlayEncoder
import world.gregs.voidps.cache.type.encoder.VarBitEncoder
import world.gregs.voidps.cache.type.encoder.VarDomainEncoder
import world.gregs.voidps.cache.type.encoder.WaterEncoder
import world.gregs.voidps.cache.type.encoder.WorldAreaEncoder
import kotlin.test.assertEquals

/**
 * Every shipped file of every editable definition type, decoded, written as JSON, read back and
 * re-encoded, compared against the bytes the cache holds.
 *
 * This is the measurement the source tree's parity guarantee rests on: a type whose identical count
 * equals its file count needs no `.pristine` sidecar for any of its files.
 */
class DefinitionCodecParityTest {

    private class Row(val name: String, val files: Int, val identical: Int, val order: Int, val shadow: Int, val first: String?)

    @Test
    fun `every definition file survives the json round trip`() {
        val dir = CacheFixture.resolveCacheDir()
        assumeTrue(dir != null, "no game cache on this machine — skipping")
        val cache = SQLiteCache.load(dir!!, readOnly = true)
        val rows = try {
            measureAll(cache)
        } finally {
            cache.close()
        }
        for (row in rows) {
            assertEquals(row.files, row.identical, "${row.name} lost ${row.files - row.identical} files: ${row.first}")
        }
    }

    private fun measureAll(cache: Cache): List<Row> {
        println(HEADER)
        return listOf(
            measure(cache, "obj", Index.ITEMS, null, 8, ObjDecoder(), ObjEncoder()) { ObjType(it) },
            measure(cache, "npc", Index.NPCS, null, 7, NpcDecoder(), NpcEncoder()) { NpcType(it) },
            measure(cache, "loc", Index.OBJECTS, null, 8, LocDecoder(), LocEncoder()) { LocType(it) },
            measure(cache, "seq", Index.ANIMATIONS, null, 7, SeqDecoder(), SeqEncoder()) { SeqType(it) },
            measure(cache, "spotanim", Index.SPOTANIMS, null, 8, SpotAnimDecoder(), SpotAnimEncoder()) { SpotAnimType(it) },
            measure(cache, "struct", Index.STRUCTS, null, 5, StructDecoder(), StructEncoder()) { StructType(it) },
            measure(cache, "enum", Index.ENUMS, null, 8, EnumDecoder(), EnumEncoder()) { EnumType(it) },
            config(cache, "param", Config.PARAMS, ParamDecoder(), ParamEncoder()) { ParamType(it) },
            config(cache, "inv", Config.INVENTORIES, InventoryDecoder(), InventoryEncoder()) { InvType(it) },
            config(cache, "varbit", Config.VAR_BIT, VarBitDecoder(), VarBitEncoder()) { VarBitType(it) },
            config(cache, "varplayer", Config.VAR_PLAYER, VarPlayerDecoder(), VarDomainEncoder()) { VarPlayerType(it) },
            config(cache, "varnpc", Config.VAR_NPC, VarNpcDecoder(), VarDomainEncoder()) { VarNpcType(it) },
            config(cache, "varclient", Config.VAR_CLIENT, VarcDecoder(), VarDomainEncoder()) { VarcType(it) },
            config(cache, "varworld", Config.VAR_WORLD, VarWorldDecoder(), VarDomainEncoder()) { VarWorldType(it) },
            config(cache, "varmapsquare", Config.VAR_MAP_SQUARE, VarMapSquareDecoder(), VarDomainEncoder()) { VarMapSquareType(it) },
            config(cache, "varobject", Config.VAR_OBJECT, VarObjectDecoder(), VarDomainEncoder()) { VarObjectType(it) },
            config(cache, "varclan", Config.VAR_CLAN, VarClanDecoder(), VarDomainEncoder()) { VarClanType(it) },
            config(cache, "varclansetting", Config.VAR_CLAN_SETTINGS, VarClanSettingDecoder(), VarDomainEncoder()) { VarClanSettingType(it) },
            config(cache, "varcontroller", Config.VAR_CONTROLLER, VarControllerDecoder(), VarDomainEncoder()) { VarControllerType(it) },
            config(cache, "varglobal", Config.VAR_GLOBAL, VarGlobalDecoder(), VarDomainEncoder()) { VarGlobalType(it) },
            config(cache, "vargroup", Config.VAR_GROUP, VarGroupDecoder(), VarDomainEncoder()) { VarGroupType(it) },
            config(cache, "underlay", Config.FLOOR_UNDERLAY, UnderlayDecoder(), UnderlayEncoder()) { UnderlayType(it) },
            config(cache, "overlay", Config.FLOOR_OVERLAY, OverlayDecoder(), OverlayEncoder()) { OverlayType(it) },
            config(cache, "idk", Config.IDENTITY_KIT, IDKDecoder(), IDKEncoder()) { IDKType(it) },
            config(cache, "mapscene", Config.MAP_SCENES, MapSceneDecoder(), MapSceneEncoder()) { MapSceneType(it) },
            config(cache, "mapelement", Config.MAP_ELEMENTS, MapElementDecoder(), MapElementEncoder()) { MapElementType(it) },
            config(cache, "quest", Config.QUESTS, QuestDecoder(), QuestEncoder()) { QuestType(it) },
            config(cache, "hitmark", Config.HIT_SPLATS, HitmarkDecoder(), HitmarkEncoder()) { HitmarkType(it) },
            config(cache, "headbar", Config.HIT_BARS, HeadbarDecoder(), HeadbarEncoder()) { HeadbarType(it) },
            config(cache, "cursor", Config.CURSORS, CursorDecoder(), CursorEncoder()) { CursorType(it) },
            config(cache, "dbtable", Config.DBTABLE, DbTableDecoder(), DbTableEncoder()) { DbTableType(it) },
            config(cache, "dbrow", Config.DBROW, DbRowDecoder(), DbRowEncoder()) { DbRowType(it) },
            config(cache, "bas", Config.RENDER_ANIMATIONS, BASDecoder(), BASEncoder()) { BASType(it) },
            config(cache, "worldarea", Config.WORLD_AREAS, WorldAreaDecoder(), WorldAreaEncoder()) { WorldAreaType(it) },
            config(cache, "water", Config.WATER, WaterDecoder(), WaterEncoder()) { WaterType(it) },
            config(cache, "seqgroup", Config.SEQ_GROUP, SeqGroupDecoder(), SeqGroupEncoder()) { SeqGroupType(it) }
        )
    }

    private fun <T : CacheType> config(
        cache: Cache,
        name: String,
        archive: Int,
        decoder: TypeDecoder<T>,
        encoder: TypeEncoder<T>,
        fresh: (Int) -> T
    ) = measure(cache, name, Index.CONFIGS, archive, 0, decoder, encoder, fresh)

    private fun <T : CacheType> measure(
        cache: Cache,
        name: String,
        index: Int,
        archive: Int?,
        bits: Int,
        decoder: TypeDecoder<T>,
        encoder: TypeEncoder<T>,
        fresh: (Int) -> T
    ): Row {
        val json = DefinitionJson.of(fresh(0)::class.java)
        val archives = if (archive != null) intArrayOf(archive) else cache.archives(index)
        var files = 0
        var identical = 0
        var order = 0
        var shadow = 0
        var first: String? = null
        for (group in archives) {
            for (file in cache.files(index, group)) {
                val data = cache.data(index, group, file) ?: continue
                files++
                val id = (group shl bits) or file
                val definition = fresh(id)
                decoder.readLoop(definition, BufferReader(data))
                val ordered = definition as OpcodeOrdered
                if (ordered.opcodeOrder != null) {
                    order++
                }
                if (ordered.shadowedPayloads != null) {
                    shadow++
                }
                val restored = fresh(id)
                json.read(json.write(definition), restored)
                val writer = BufferWriter(maxOf(data.size * 2, 64))
                with(encoder) { writer.encode(restored) }
                val encoded = writer.toArray()
                if (encoded.contentEquals(data)) {
                    identical++
                } else if (first == null) {
                    first = "$id ${data.size} bytes became ${encoded.size}, first difference at ${difference(data, encoded)}"
                }
            }
        }
        return Row(name, files, identical, order, shadow, first).also { println(line(it)) }
    }

    private fun difference(expected: ByteArray, actual: ByteArray): Int {
        for (index in 0 until minOf(expected.size, actual.size)) {
            if (expected[index] != actual[index]) {
                return index
            }
        }
        return minOf(expected.size, actual.size)
    }

    private fun line(row: Row): String = row.name.padEnd(15) +
        row.files.toString().padStart(8) +
        row.identical.toString().padStart(11) +
        row.order.toString().padStart(8) +
        row.shadow.toString().padStart(8)

    private companion object {
        const val HEADER = "type              files  identical   order  shadow"
    }
}
