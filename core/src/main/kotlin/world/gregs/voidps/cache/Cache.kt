package world.gregs.voidps.cache

import org.projectx.core.EnvVars
import org.projectx.core.Logger.logInfo
import world.gregs.voidps.cache.type.data.*
import world.gregs.voidps.cache.type.decoder.*
import world.gregs.voidps.cache.secure.Huffman
import world.gregs.voidps.cache.sqlite.SQLiteCache
import world.gregs.voidps.gameval.Gameval
import java.nio.file.Path

interface Cache {
    val versionTable: ByteArray

    fun indexCount(): Int

    fun indices(): IntArray

    fun indexCrcs(): IntArray

    fun sector(index: Int, archive: Int): ByteArray?

    fun sectorSize(index: Int, archive: Int): Int = sector(index, archive)?.size ?: -1

    fun exists(index: Int, archive: Int): Boolean = sectorSize(index, archive) != -1

    fun archives(index: Int): IntArray

    fun archiveCount(index: Int): Int

    fun lastArchiveId(indexId: Int): Int

    fun archiveId(index: Int, hash: Int): Int

    fun archiveId(index: Int, name: String): Int = archiveId(index, name.hashCode())

    fun files(index: Int, archive: Int): IntArray

    fun fileCount(indexId: Int, archiveId: Int): Int

    fun lastFileId(indexId: Int, archive: Int): Int

    fun data(index: Int, archive: Int, file: Int = 0, xtea: IntArray? = null): ByteArray?

    fun data(index: Int, name: String, xtea: IntArray? = null) = data(index, archiveId(index, name), xtea = xtea)

    fun write(index: Int, archive: Int, file: Int, data: ByteArray, xteas: IntArray? = null)

    fun write(index: Int, archive: String, data: ByteArray, xteas: IntArray? = null)

    fun update(): Boolean

    fun close()

    companion object {
        private val lock = Any()

        @Volatile
        private var instance: Cache? = null

        /**
         * Whether the process has a cache of its own yet.
         *
         * A cache build reads config records out of the cache its source tree came from, which is
         * not this one, and must not touch this one: [get] holds [lock] for the whole build, so a
         * build that asks for the process cache waits on itself.
         */
        @JvmStatic
        val initialised: Boolean
            get() = instance != null

        @JvmStatic
        fun init(path: Path) = init(path, readOnly = false)

        @JvmStatic
        fun init(path: Path, readOnly: Boolean) {
            if (instance != null) {
                return
            }
            synchronized(lock) {
                if (instance == null) {
                    instance = SQLiteCache.load(path, readOnly = readOnly)
                }
            }
        }

        @JvmStatic
        fun init(cache: Cache) {
            if (instance != null) {
                return
            }
            synchronized(lock) {
                if (instance == null) {
                    instance = cache
                }
            }
        }

        @JvmStatic
        fun get(): Cache {
            instance?.let { return it }
            return synchronized(lock) {
                instance ?: SQLiteCache.load(readOnly = true).also { instance = it }
            }
        }

        @JvmStatic
        fun warm() {
            checkNotNull(instance) { "Cache.init(path) must be called before Cache.warm()" }
            val start = System.currentTimeMillis()
            check(varbits.isNotEmpty()) { "No varbit definitions decoded - cache is empty or corrupt" }
            checkNotNull(varbit(19924)) { "varbit 19924 (toplevel_v2_slim_mode) failed to decode" }
            obj(0); npc(0); loc(0); struct(0); enum(0); param(0); interfaceDef(0)
            check(varPlayers.isNotEmpty()) { "No var-player definitions decoded - cache is empty or corrupt" }
            varPlayer(0); varNpc(0); varGroup(0); varClan(0); varClanSetting(0)
            mapScene(0); overlay(0); underlay(0); material(0); quickChatCat(0); quickChatPhrase(0)
            checkNotNull(achievement(2)) { "achievement 2 (Cook's Assistant) failed to decode" }
            check(dbTableIndex(4)?.rowIds?.isNotEmpty() == true) { "db table index 4 row-id list failed to decode" }
            logInfo("Cache warmed in ${System.currentTimeMillis() - start}ms")
        }

        private val _huffman: Huffman by lazy {
            Huffman().load(get().data(Index.HUFFMAN, 1)!!)
        }

        @JvmStatic val huffman: Huffman get() = _huffman

        @JvmStatic val varbits: Array<VarBitType> by lazy {
            VarBitDecoder().load(get())
        }

        @JvmStatic val varcs: Array<VarcType> by lazy {
            VarcDecoder().load(get())
        }

        @JvmStatic val locs: Array<LocType> by lazy {
            LocDecoder(EnvVars.members).load(get())
        }

        @JvmStatic val objs: Array<ObjType> by lazy {
            ObjDecoder().load(get())
        }

        @JvmStatic val npcs: Array<NpcType> by lazy {
            NpcDecoder(EnvVars.members).load(get())
        }

        @JvmStatic val animations: Array<SeqType> by lazy {
            SeqDecoder().load(get())
        }

        @JvmStatic val enums: Array<EnumType> by lazy {
            EnumDecoder().load(get())
        }

        @JvmStatic val structs: Array<StructType> by lazy {
            StructDecoder().load(get())
        }

        @JvmStatic val spotAnims: Array<SpotAnimType> by lazy {
            SpotAnimDecoder().load(get())
        }

        @JvmStatic val bas: Array<BASType> by lazy {
            BASDecoder().load(get())
        }

        @JvmStatic val interfaces: Array<InterfaceType> by lazy {
            InterfaceDecoder().load(get())
        }

        @JvmStatic val fonts: Array<FontType> by lazy {
            FontDecoder().load(get())
        }

        @JvmStatic val params: Array<ParamType> by lazy {
            ParamDecoder().load(get())
        }

        @JvmStatic val invs: Array<InvType> by lazy {
            InventoryDecoder().load(get())
        }

        @JvmStatic val quests: Array<QuestType> by lazy {
            QuestDecoder().load(get())
        }

        @JvmStatic val worldAreas: Array<WorldAreaType> by lazy {
            WorldAreaDecoder().load(get())
        }

        @JvmStatic val varPlayers: Array<VarPlayerType> by lazy {
            VarPlayerDecoder().load(get())
        }

        @JvmStatic val varNpcs: Array<VarNpcType> by lazy {
            VarNpcDecoder().load(get())
        }

        @JvmStatic val varWorlds: Array<VarWorldType> by lazy {
            VarWorldDecoder().load(get())
        }

        @JvmStatic val varMapSquares: Array<VarMapSquareType> by lazy {
            VarMapSquareDecoder().load(get())
        }

        @JvmStatic val varObjects: Array<VarObjectType> by lazy {
            VarObjectDecoder().load(get())
        }

        @JvmStatic val varClans: Array<VarClanType> by lazy {
            VarClanDecoder().load(get())
        }

        @JvmStatic val varClanSettings: Array<VarClanSettingType> by lazy {
            VarClanSettingDecoder().load(get())
        }

        @JvmStatic val varControllers: Array<VarControllerType> by lazy {
            VarControllerDecoder().load(get())
        }

        @JvmStatic val varGlobals: Array<VarGlobalType> by lazy {
            VarGlobalDecoder().load(get())
        }

        @JvmStatic val varGroups: Array<VarGroupType> by lazy {
            VarGroupDecoder().load(get())
        }

        @JvmStatic val mapScenes: Array<MapSceneType> by lazy {
            MapSceneDecoder().load(get())
        }

        @JvmStatic val overlays: Array<OverlayType> by lazy {
            OverlayDecoder().load(get())
        }

        @JvmStatic val underlays: Array<UnderlayType> by lazy {
            UnderlayDecoder().load(get())
        }

        @JvmStatic val materials: Array<MaterialType> by lazy {
            MaterialDecoder().load(get())
        }

        @JvmStatic val quickChatCats: Array<QuickChatCatType> by lazy {
            QuickChatCatDecoder().load(get())
        }

        @JvmStatic val quickChatPhrases: Array<QuickChatPhraseType> by lazy {
            QuickChatPhraseDecoder().load(get())
        }

        @JvmStatic val achievements: Array<AchievementType> by lazy {
            AchievementDecoder().load(get())
        }

        @JvmStatic val dbTableIndexes: Array<DbTableIndexType> by lazy {
            DbTableIndexDecoder().load(get())
        }

        private val objTypes by lazy {
            Types(ObjDecoder(), get()) { def ->
                intArrayOf(
                    def.notedTemplateId, def.noteId,
                    def.lendTemplateId, def.lendId,
                    def.boundTemplateId, def.bindId,
                )
            }
        }
        private val npcTypes by lazy { Types(NpcDecoder(EnvVars.members), get()) }
        private val locTypes by lazy { Types(LocDecoder(EnvVars.members), get()) }
        private val enumTypes by lazy { Types(EnumDecoder(), get()) }
        private val structTypes by lazy { Types(StructDecoder(), get()) }
        private val varbitTypes by lazy { Types(VarBitDecoder(), get()) }
        private val varcTypes by lazy { Types(VarcDecoder(), get()) }
        private val animationTypes by lazy { Types(SeqDecoder(), get()) }
        private val basTypes by lazy { Types(BASDecoder(), get()) }
        private val spotAnimTypes by lazy { Types(SpotAnimDecoder(), get()) }
        private val interfaceTypes by lazy { Types(InterfaceDecoder(), get()) }
        private val fontTypes by lazy { Types(FontDecoder(), get()) }

        @JvmStatic fun obj(id: Int): ObjType? = objTypes.getOrNull(id)
        @JvmStatic fun npc(id: Int): NpcType? = npcTypes.getOrNull(id)
        @JvmStatic fun loc(id: Int): LocType? = locTypes.getOrNull(id)
        @JvmStatic fun enum(id: Int): EnumType? = enumTypes.getOrNull(id)
        @JvmStatic fun struct(id: Int): StructType? = structTypes.getOrNull(id)
        @JvmStatic fun varbit(id: Int): VarBitType? = varbitTypes.getOrNull(id)

        private val objectVarbitsByName: Map<String, VarBitType> by lazy {
            val defs = varbits.filter { it.id >= 0 && it.domainId.toInt() == VarDomain.OBJECT.id }.sortedBy { it.id }
            val names = Gameval.entries(Gameval.VARBIT_OBJECT).entries.sortedBy { it.key }.map { it.value }
            if (defs.size != names.size) {
                logInfo("object-varbit name map disabled: ${names.size} gameval names vs ${defs.size} defs")
                emptyMap()
            } else {
                names.zip(defs).toMap()
            }
        }

        @JvmStatic fun objectVarbit(name: String): VarBitType? = objectVarbitsByName[name]

        @JvmStatic fun varc(id: Int): VarcType? = varcTypes.getOrNull(id)
        @JvmStatic fun animation(id: Int): SeqType? = animationTypes.getOrNull(id)
        @JvmStatic fun bas(id: Int): BASType? = basTypes.getOrNull(id)
        @JvmStatic fun spotAnim(id: Int): SpotAnimType? = spotAnimTypes.getOrNull(id)
        @JvmStatic fun interfaceDef(id: Int): InterfaceType? = interfaceTypes.getOrNull(id)
        @JvmStatic fun font(id: Int): FontType? = fontTypes.getOrNull(id)

        private val paramTypes by lazy { Types(ParamDecoder(), get()) }
        private val cursorTypes by lazy { Types(CursorDecoder(), get()) }
        private val headbarTypes by lazy { Types(HeadbarDecoder(), get()) }
        private val hitmarkTypes by lazy { Types(HitmarkDecoder(), get()) }
        private val idkTypes by lazy { Types(IDKDecoder(), get()) }
        private val inventoryTypes by lazy { Types(InventoryDecoder(), get()) }
        private val questTypes by lazy { Types(QuestDecoder(), get()) }
        private val graphicTypes by lazy { Types(GraphicDecoder(), get()) }

        @JvmStatic fun param(id: Int): ParamType? = paramTypes.getOrNull(id)
        @JvmStatic fun cursor(id: Int): CursorType? = cursorTypes.getOrNull(id)
        @JvmStatic fun headbar(id: Int): HeadbarType? = headbarTypes.getOrNull(id)
        @JvmStatic fun hitmark(id: Int): HitmarkType? = hitmarkTypes.getOrNull(id)
        @JvmStatic fun idk(id: Int): IDKType? = idkTypes.getOrNull(id)
        @JvmStatic fun inv(id: Int): InvType? = inventoryTypes.getOrNull(id)
        @JvmStatic fun quest(id: Int): QuestType? = questTypes.getOrNull(id)
        @JvmStatic fun graphic(id: Int): GraphicType? = graphicTypes.getOrNull(id)
        @JvmStatic fun seq(id: Int): SeqType? = animation(id)

        private val varPlayerTypes by lazy { Types(VarPlayerDecoder(), get()) }
        private val varNpcTypes by lazy { Types(VarNpcDecoder(), get()) }
        private val varWorldTypes by lazy { Types(VarWorldDecoder(), get()) }
        private val varMapSquareTypes by lazy { Types(VarMapSquareDecoder(), get()) }
        private val varObjectTypes by lazy { Types(VarObjectDecoder(), get()) }
        private val varClanTypes by lazy { Types(VarClanDecoder(), get()) }
        private val varClanSettingTypes by lazy { Types(VarClanSettingDecoder(), get()) }
        private val varControllerTypes by lazy { Types(VarControllerDecoder(), get()) }
        private val varGlobalTypes by lazy { Types(VarGlobalDecoder(), get()) }
        private val varGroupTypes by lazy { Types(VarGroupDecoder(), get()) }

        @JvmStatic fun varPlayer(id: Int): VarPlayerType? = varPlayerTypes.getOrNull(id)
        @JvmStatic fun varNpc(id: Int): VarNpcType? = varNpcTypes.getOrNull(id)
        @JvmStatic fun varWorld(id: Int): VarWorldType? = varWorldTypes.getOrNull(id)
        @JvmStatic fun varMapSquare(id: Int): VarMapSquareType? = varMapSquareTypes.getOrNull(id)
        @JvmStatic fun varObject(id: Int): VarObjectType? = varObjectTypes.getOrNull(id)
        @JvmStatic fun varClan(id: Int): VarClanType? = varClanTypes.getOrNull(id)
        @JvmStatic fun varClanSetting(id: Int): VarClanSettingType? = varClanSettingTypes.getOrNull(id)
        @JvmStatic fun varController(id: Int): VarControllerType? = varControllerTypes.getOrNull(id)
        @JvmStatic fun varGlobal(id: Int): VarGlobalType? = varGlobalTypes.getOrNull(id)
        @JvmStatic fun varGroup(id: Int): VarGroupType? = varGroupTypes.getOrNull(id)

        private val mapSceneTypes by lazy { Types(MapSceneDecoder(), get()) }
        private val mapElementTypes by lazy { Types(MapElementDecoder(), get()) }
        private val overlayTypes by lazy { Types(OverlayDecoder(), get()) }
        private val underlayTypes by lazy { Types(UnderlayDecoder(), get()) }
        private val quickChatCatTypes by lazy { Types(QuickChatCatDecoder(), get()) }
        private val quickChatPhraseTypes by lazy { Types(QuickChatPhraseDecoder(), get()) }

        @JvmStatic fun mapScene(id: Int): MapSceneType? = mapSceneTypes.getOrNull(id)
        @JvmStatic fun mapElement(id: Int): MapElementType? = mapElementTypes.getOrNull(id)
        @JvmStatic fun overlay(id: Int): OverlayType? = overlayTypes.getOrNull(id)
        @JvmStatic fun underlay(id: Int): UnderlayType? = underlayTypes.getOrNull(id)
        @JvmStatic fun quickChatCat(id: Int): QuickChatCatType? = quickChatCatTypes.getOrNull(id)
        @JvmStatic fun quickChatPhrase(id: Int): QuickChatPhraseType? = quickChatPhraseTypes.getOrNull(id)
        @JvmStatic fun material(id: Int): MaterialType? = materials.getOrNull(id)

        private val achievementTypes by lazy { Types(AchievementDecoder(), get()) }
        private val dbTableIndexTypes by lazy { Types(DbTableIndexDecoder(), get()) }
        private val dbRowTypes by lazy { Types(DbRowDecoder(), get()) }
        private val dbTableTypes by lazy { Types(DbTableDecoder(), get()) }

        @JvmStatic fun achievement(id: Int): AchievementType? = achievementTypes.getOrNull(id)
        @JvmStatic fun dbTableIndex(id: Int): DbTableIndexType? = dbTableIndexTypes.getOrNull(id)
        @JvmStatic fun dbRow(id: Int): DbRowType? = dbRowTypes.getOrNull(id)
        @JvmStatic fun dbTable(id: Int): DbTableType? = dbTableTypes.getOrNull(id)

        /** Every decoded row belonging to [table], in the order the table index lists them. */
        @JvmStatic fun dbRows(table: Int): List<DbRowType> =
            dbTableIndex(table)?.rowIds?.asList()?.mapNotNull { dbRow(it) }?.filter { it.table == table } ?: emptyList()

        private val waterTypes by lazy { Types(WaterDecoder(), get()) }
        private val seqGroupTypes by lazy { Types(SeqGroupDecoder(), get()) }
        private val worldMapInfoTypes by lazy { Types(WorldMapInfoDecoder(), get()) }

        @JvmStatic fun water(id: Int): WaterType? = waterTypes.getOrNull(id)
        @JvmStatic fun seqGroup(id: Int): SeqGroupType? = seqGroupTypes.getOrNull(id)
        @JvmStatic fun worldMapInfo(id: Int): WorldMapInfoType? = worldMapInfoTypes.getOrNull(id)

        private val mapDecoder by lazy { MapDecoder() }

        @JvmStatic fun mapSquare(mapSquareId: Int): MapSquareType? = mapDecoder.decode(get(), mapSquareId)

        private val worldAreaByColour: Map<Int, Int> by lazy {
            val map = HashMap<Int, Int>()
            for (area in worldAreas) {
                if (area.id >= 0 && area.value >= 0) {
                    map.putIfAbsent(area.value, area.id)
                }
            }
            map
        }

        private const val WORLDMAP_SQUARE_ARCHIVE = 3
        private val worldmapSquareDecoder by lazy { WorldmapSquareDecoder(worldAreaByColour::get) }
        private val worldmapSquares = java.util.concurrent.ConcurrentHashMap<Int, WorldmapSquareType>()

        @JvmStatic
        fun worldmapSquare(mapSquareX: Int, mapSquareY: Int): WorldmapSquareType? {
            val id = (mapSquareX and 0x7f) or (mapSquareY shl 7)
            worldmapSquares[id]?.let { return it }
            return worldmapSquareDecoder.decode(get(), id)?.also { worldmapSquares[id] = it }
        }

        @JvmStatic
        fun worldAreaTypeAt(tileX: Int, tileY: Int): Int? {
            val square = worldmapSquare(tileX ushr 6, tileY ushr 6) ?: return null
            return square.areaAt(tileX ushr 3, tileY ushr 3)
        }

        private val worldAreaExtents: Map<Int, IntArray> by lazy {
            val extents = HashMap<Int, IntArray>()
            fun accumulate(id: Int, x: Int, y: Int) {
                val e = extents.getOrPut(id) { intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE) }
                if (x < e[0]) e[0] = x
                if (y < e[1]) e[1] = y
                if (x > e[2]) e[2] = x
                if (y > e[3]) e[3] = y
            }
            val cache = get()
            for (fileId in cache.files(Index.WORLD_MAP, WORLDMAP_SQUARE_ARCHIVE)) {
                val square = worldmapSquareDecoder.decode(cache, fileId) ?: continue
                val squareBaseX = (fileId and 0x7f) shl 6
                val squareBaseY = (fileId ushr 7) shl 6
                for (zoneX in 0 until 8) {
                    for (zoneY in 0 until 8) {
                        accumulate(square.areaAt(zoneX, zoneY), squareBaseX + (zoneX shl 3), squareBaseY + (zoneY shl 3))
                    }
                }
            }
            for (area in worldAreas) {
                if (area.id < 0) continue
                // op3 corner2 is a shared anchor (constant per area), not a rect corner — exclude from bounds.
                area.rects?.forEach { (corner1, _) -> if (corner1.plane >= 0) accumulate(area.id, corner1.x, corner1.y) }
                area.points?.forEach { (coord, _) -> if (coord.plane >= 0) accumulate(area.id, coord.x, coord.y) }
            }
            extents
        }

        /** REBUILD_NORMAL world corners for [id]: the map-square-snapped union of the area's worldmap
         * colour footprint and its op3/op4 coordinates. `[swX, swY, neX, neY]`, or null if unknown. */
        @JvmStatic
        fun worldAreaBounds(id: Int): IntArray? {
            val e = worldAreaExtents[id] ?: return null
            return intArrayOf((e[0] ushr 6) shl 6, (e[1] ushr 6) shl 6, ((e[2] ushr 6) shl 6) + 56, ((e[3] ushr 6) shl 6) + 56)
        }
    }
}
