package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.source.codec.image.GraphicCodec
import world.gregs.voidps.cache.source.codec.model.ModelCodec
import world.gregs.voidps.cache.source.codec.image.TextureImageCodecs
import world.gregs.voidps.cache.type.data.AchievementType
import world.gregs.voidps.cache.type.data.AnimStateMachineType
import world.gregs.voidps.cache.type.data.BASType
import world.gregs.voidps.cache.type.data.BillboardType
import world.gregs.voidps.cache.type.data.CutsceneOverlayType
import world.gregs.voidps.cache.type.data.ComponentType
import world.gregs.voidps.cache.type.data.ConfigGroup76Type
import world.gregs.voidps.cache.type.data.CursorType
import world.gregs.voidps.cache.type.data.CutsceneType
import world.gregs.voidps.cache.type.data.DbColumnIndexType
import world.gregs.voidps.cache.type.data.DefaultsGroup10Type
import world.gregs.voidps.cache.type.data.DefaultsGroup12Type
import world.gregs.voidps.cache.type.data.DefaultsGroup1Type
import world.gregs.voidps.cache.type.data.DefaultsGroup2Type
import world.gregs.voidps.cache.type.data.DefaultsGroup3Type
import world.gregs.voidps.cache.type.data.DefaultsGroup4Type
import world.gregs.voidps.cache.type.data.DefaultsGroup5Type
import world.gregs.voidps.cache.type.data.DefaultsGroup6Type
import world.gregs.voidps.cache.type.data.DefaultsGroup7Type
import world.gregs.voidps.cache.type.data.DefaultsGroup8Type
import world.gregs.voidps.cache.type.data.DefaultsGroup9Type
import world.gregs.voidps.cache.type.data.DbRowType
import world.gregs.voidps.cache.type.data.DbTableType
import world.gregs.voidps.cache.type.data.EffectAnimType
import world.gregs.voidps.cache.type.data.EnumType
import world.gregs.voidps.cache.type.data.FontType
import world.gregs.voidps.cache.type.data.FontMetricsType
import world.gregs.voidps.cache.type.data.HeadbarType
import world.gregs.voidps.cache.type.data.HuffmanType
import world.gregs.voidps.cache.type.data.HitmarkType
import world.gregs.voidps.cache.type.data.IDKType
import world.gregs.voidps.cache.type.data.InvType
import world.gregs.voidps.cache.type.data.LoadingScreenType
import world.gregs.voidps.cache.type.data.LocType
import world.gregs.voidps.cache.type.data.MapElementType
import world.gregs.voidps.cache.type.data.MapSceneType
import world.gregs.voidps.cache.type.data.MaterialType
import world.gregs.voidps.cache.type.data.NpcType
import world.gregs.voidps.cache.type.data.ObjType
import world.gregs.voidps.cache.type.data.OverlayType
import world.gregs.voidps.cache.type.data.ParticleProducerType
import world.gregs.voidps.cache.type.data.ParticleType
import world.gregs.voidps.cache.type.data.ParamType
import world.gregs.voidps.cache.type.data.ParticleSystemType
import world.gregs.voidps.cache.type.data.QuestType
import world.gregs.voidps.cache.type.data.QuickChatCatType
import world.gregs.voidps.cache.type.data.QuickChatPhraseType
import world.gregs.voidps.cache.type.data.SeqGroupType
import world.gregs.voidps.cache.type.data.SeqType
import world.gregs.voidps.cache.type.data.SpotAnimType
import world.gregs.voidps.cache.type.data.StructType
import world.gregs.voidps.cache.type.data.StylesheetType
import world.gregs.voidps.cache.type.data.UnderlayType
import world.gregs.voidps.cache.type.data.UiAnimCurveType
import world.gregs.voidps.cache.type.data.UiAnimType
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
import world.gregs.voidps.cache.type.data.WorldMapColoursType
import world.gregs.voidps.cache.type.data.WorldMapCoordsType
import world.gregs.voidps.cache.type.data.WorldMapType
import world.gregs.voidps.cache.type.decoder.AchievementDecoder
import world.gregs.voidps.cache.type.decoder.AnimStateMachineDecoder
import world.gregs.voidps.cache.type.decoder.BASDecoder
import world.gregs.voidps.cache.type.decoder.BillboardDecoder
import world.gregs.voidps.cache.type.decoder.ComponentDecoder
import world.gregs.voidps.cache.type.decoder.ConfigGroup76Decoder
import world.gregs.voidps.cache.type.decoder.CursorDecoder
import world.gregs.voidps.cache.type.decoder.CutsceneOverlayDecoder
import world.gregs.voidps.cache.type.decoder.CutsceneDecoder
import world.gregs.voidps.cache.type.decoder.DbColumnIndexDecoder
import world.gregs.voidps.cache.type.decoder.DefaultsGroup10Decoder
import world.gregs.voidps.cache.type.decoder.DefaultsGroup12Decoder
import world.gregs.voidps.cache.type.decoder.DefaultsGroup1Decoder
import world.gregs.voidps.cache.type.decoder.DefaultsGroup2Decoder
import world.gregs.voidps.cache.type.decoder.DefaultsGroup3Decoder
import world.gregs.voidps.cache.type.decoder.DefaultsGroup4Decoder
import world.gregs.voidps.cache.type.decoder.DefaultsGroup5Decoder
import world.gregs.voidps.cache.type.decoder.DefaultsGroup6Decoder
import world.gregs.voidps.cache.type.decoder.DefaultsGroup7Decoder
import world.gregs.voidps.cache.type.decoder.DefaultsGroup8Decoder
import world.gregs.voidps.cache.type.decoder.DefaultsGroup9Decoder
import world.gregs.voidps.cache.type.decoder.DbRowDecoder
import world.gregs.voidps.cache.type.decoder.DbTableDecoder
import world.gregs.voidps.cache.type.decoder.EffectAnimDecoder
import world.gregs.voidps.cache.type.decoder.EnumDecoder
import world.gregs.voidps.cache.type.decoder.FontDecoder
import world.gregs.voidps.cache.type.decoder.FontMetricsDecoder
import world.gregs.voidps.cache.type.decoder.HeadbarDecoder
import world.gregs.voidps.cache.type.decoder.HitmarkDecoder
import world.gregs.voidps.cache.type.decoder.HuffmanDecoder
import world.gregs.voidps.cache.type.decoder.IDKDecoder
import world.gregs.voidps.cache.type.decoder.InventoryDecoder
import world.gregs.voidps.cache.type.decoder.LoadingScreenDecoder
import world.gregs.voidps.cache.type.decoder.LocDecoder
import world.gregs.voidps.cache.type.decoder.MapElementDecoder
import world.gregs.voidps.cache.type.decoder.MapSceneDecoder
import world.gregs.voidps.cache.type.decoder.MaterialDecoder
import world.gregs.voidps.cache.type.decoder.NpcDecoder
import world.gregs.voidps.cache.type.decoder.ObjDecoder
import world.gregs.voidps.cache.type.decoder.OverlayDecoder
import world.gregs.voidps.cache.type.decoder.ParticleDecoder
import world.gregs.voidps.cache.type.decoder.ParticleProducerDecoder
import world.gregs.voidps.cache.type.decoder.ParticleSystemDecoder
import world.gregs.voidps.cache.type.decoder.ParamDecoder
import world.gregs.voidps.cache.type.decoder.QuestDecoder
import world.gregs.voidps.cache.type.decoder.QuickChatCatDecoder
import world.gregs.voidps.cache.type.decoder.QuickChatPhraseDecoder
import world.gregs.voidps.cache.type.decoder.SeqDecoder
import world.gregs.voidps.cache.type.decoder.SeqGroupDecoder
import world.gregs.voidps.cache.type.decoder.SpotAnimDecoder
import world.gregs.voidps.cache.type.decoder.StructDecoder
import world.gregs.voidps.cache.type.decoder.StylesheetDecoder
import world.gregs.voidps.cache.type.decoder.UnderlayDecoder
import world.gregs.voidps.cache.type.decoder.UiAnimCurveDecoder
import world.gregs.voidps.cache.type.decoder.UiAnimDecoder
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
import world.gregs.voidps.cache.type.decoder.WorldMapColoursDecoder
import world.gregs.voidps.cache.type.decoder.WorldMapCoordsDecoder
import world.gregs.voidps.cache.type.decoder.WorldMapDetailsDecoder
import world.gregs.voidps.cache.type.encoder.AchievementEncoder
import world.gregs.voidps.cache.type.encoder.AnimStateMachineEncoder
import world.gregs.voidps.cache.type.encoder.BASEncoder
import world.gregs.voidps.cache.type.encoder.BillboardEncoder
import world.gregs.voidps.cache.type.encoder.ComponentEncoder
import world.gregs.voidps.cache.type.encoder.ConfigGroup76Encoder
import world.gregs.voidps.cache.type.encoder.CursorEncoder
import world.gregs.voidps.cache.type.encoder.CutsceneOverlayEncoder
import world.gregs.voidps.cache.type.encoder.CutsceneEncoder
import world.gregs.voidps.cache.type.encoder.DbRowEncoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup10Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup12Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup1Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup2Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup3Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup4Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup5Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup6Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup7Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup8Encoder
import world.gregs.voidps.cache.type.encoder.DefaultsGroup9Encoder
import world.gregs.voidps.cache.type.encoder.DbTableIndexEncoder
import world.gregs.voidps.cache.type.encoder.DbTableEncoder
import world.gregs.voidps.cache.type.encoder.EffectAnimEncoder
import world.gregs.voidps.cache.type.encoder.EnumEncoder
import world.gregs.voidps.cache.type.encoder.FontEncoder
import world.gregs.voidps.cache.type.encoder.FontMetricsEncoder
import world.gregs.voidps.cache.type.encoder.HeadbarEncoder
import world.gregs.voidps.cache.type.encoder.HitmarkEncoder
import world.gregs.voidps.cache.type.encoder.HuffmanEncoder
import world.gregs.voidps.cache.type.encoder.IDKEncoder
import world.gregs.voidps.cache.type.encoder.InventoryEncoder
import world.gregs.voidps.cache.type.encoder.LoadingScreenEncoder
import world.gregs.voidps.cache.type.encoder.LocEncoder
import world.gregs.voidps.cache.type.encoder.MapElementEncoder
import world.gregs.voidps.cache.type.encoder.MapSceneEncoder
import world.gregs.voidps.cache.type.encoder.MaterialEncoder
import world.gregs.voidps.cache.type.encoder.NpcEncoder
import world.gregs.voidps.cache.type.encoder.ObjEncoder
import world.gregs.voidps.cache.type.encoder.OverlayEncoder
import world.gregs.voidps.cache.type.encoder.ParticleEncoder
import world.gregs.voidps.cache.type.encoder.ParticleProducerEncoder
import world.gregs.voidps.cache.type.encoder.ParticleSystemEncoder
import world.gregs.voidps.cache.type.encoder.ParamEncoder
import world.gregs.voidps.cache.type.encoder.QuestEncoder
import world.gregs.voidps.cache.type.encoder.QuickChatCatEncoder
import world.gregs.voidps.cache.type.encoder.QuickChatPhraseEncoder
import world.gregs.voidps.cache.type.encoder.SeqEncoder
import world.gregs.voidps.cache.type.encoder.SeqGroupEncoder
import world.gregs.voidps.cache.type.encoder.SpotAnimEncoder
import world.gregs.voidps.cache.type.encoder.StructEncoder
import world.gregs.voidps.cache.type.encoder.StylesheetEncoder
import world.gregs.voidps.cache.type.encoder.UnderlayEncoder
import world.gregs.voidps.cache.type.encoder.UiAnimCurveEncoder
import world.gregs.voidps.cache.type.encoder.UiAnimEncoder
import world.gregs.voidps.cache.type.encoder.VarBitEncoder
import world.gregs.voidps.cache.type.encoder.VarDomainEncoder
import world.gregs.voidps.cache.type.encoder.WaterEncoder
import world.gregs.voidps.cache.type.encoder.WorldAreaEncoder
import world.gregs.voidps.cache.type.encoder.WorldMapColoursEncoder
import world.gregs.voidps.cache.type.encoder.WorldMapCoordsEncoder
import world.gregs.voidps.cache.type.encoder.WorldMapDetailsEncoder

/**
 * The NXT cache's typed codecs beyond the raw ones: the image, definition and record codecs that
 * turn an index into files somebody can open.
 *
 * One list rather than registrations scattered across the codec classes, so that what the tree
 * holds as something other than bytes is readable in one place. Each entry is added as its codec
 * lands; an index absent from here is [RawCodec] until then.
 */
internal object NxtCodecs {

    fun register() {
        SourceCodecs.builtin(Index.MAPS, MapCodec)
        SourceCodecs.builtin(Index.ANIMATION_SKELETONS, AnimBaseCodec)
        SourceCodecs.builtin(Index.ANIMS_RT7, SkeletalAnimCodec)
        SourceCodecs.builtin(Index.ANIMS_KEYFRAMES, KeyframeAnimCodec)
        SourceCodecs.builtin(Index.MODELS_RT7, ModelCodec)
        SourceCodecs.builtin(Index.GRAPHICS, GraphicCodec)
        SourceCodecs.builtin(Index.LOADING_GRAPHICS, GraphicCodec)
        SourceCodecs.builtin(Index.LOADING_GRAPHICS_RAW, GraphicCodec)
        TextureImageCodecs.register()

        SourceCodecs.builtin(Index.INTERFACES, components())
        SourceCodecs.builtin(Index.FONT_METRICS, definitions("fontmetrics", DefinitionLayout.Packed(0), FontDecoder(), FontEncoder(), "fontmetrics") { FontType(it) })
        SourceCodecs.builtin(Index.TEXTURE_DEFINITIONS, definitions("material", DefinitionLayout.Packed(MATERIAL_BITS), MaterialDecoder(), MaterialEncoder(), "material") { MaterialType(it) })
        SourceCodecs.builtin(Index.BILLBOARDS, definitions("billboard", DefinitionLayout.Packed(BILLBOARD_BITS), BillboardDecoder(), BillboardEncoder(), null) { BillboardType(it) })
        SourceCodecs.builtin(Index.ACHIEVEMENT_DEF, definitions("achievement", DefinitionLayout.Packed(ACHIEVEMENT_BITS), AchievementDecoder(), AchievementEncoder(), "achievement") { AchievementType(it) })
        SourceCodecs.builtin(Index.DBTABLEINDEX, dbTableIndexes())
        SourceCodecs.builtin(Index.WORLD_MAP, worldMap())
        SourceCodecs.builtin(Index.QUICK_CHAT, quickChat())
        SourceCodecs.builtin(Index.WORLD_MAP_AREAS, WorldMapAreaCodec.AREAS)
        SourceCodecs.builtin(Index.WORLD_MAP_LABELS, WorldMapAreaCodec.COORDS)
        SourceCodecs.builtin(FontMetricsDecoder.INDEX, definitions("fontmetrics", DefinitionLayout.Packed(0), FontMetricsDecoder(), FontMetricsEncoder(), "fontmetrics") { FontMetricsType(it) })
        SourceCodecs.builtin(Index.PARTICLES, particles())
        SourceCodecs.builtin(Index.DEFAULTS, defaults())
        SourceCodecs.builtin(HuffmanDecoder.INDEX, definitions("huffman", DefinitionLayout.Packed(0), HuffmanDecoder(), HuffmanEncoder(), null) { HuffmanType(it) })
        SourceCodecs.builtin(StylesheetDecoder.INDEX, definitions("stylesheet", DefinitionLayout.Packed(0), StylesheetDecoder(), StylesheetEncoder(), "stylesheet") { StylesheetType(it) })
        SourceCodecs.builtin(ParticleSystemDecoder.INDEX, definitions("particlesystem", DefinitionLayout.Packed(0), ParticleSystemDecoder(), ParticleSystemEncoder(), null) { ParticleSystemType(it) })
        SourceCodecs.builtin(AnimStateMachineDecoder.INDEX, definitions("animstatemachine", DefinitionLayout.Packed(0), AnimStateMachineDecoder(), AnimStateMachineEncoder(), null) { AnimStateMachineType(it) })
        SourceCodecs.builtin(UiAnimDecoder.INDEX, uiAnims())
        SourceCodecs.builtin(CutsceneOverlayDecoder.INDEX, definitions("cutsceneoverlay", DefinitionLayout.Packed(0), CutsceneOverlayDecoder(), CutsceneOverlayEncoder(), null) { CutsceneOverlayType(it) })
        SourceCodecs.builtin(Index.GAME_TIPS, definitions("loadingscreen", DefinitionLayout.Packed(0), LoadingScreenDecoder(), LoadingScreenEncoder(), null) { LoadingScreenType(it) })
        SourceCodecs.builtin(Index.CUTSCENES, definitions("cutscene", DefinitionLayout.Packed(0), CutsceneDecoder(), CutsceneEncoder(), null) { CutsceneType(it) })

        SourceCodecs.builtin(Index.OBJECTS, definitions("loc", DefinitionLayout.Packed(8), LocDecoder(), LocEncoder(), "loc") { LocType(it) })
        SourceCodecs.builtin(Index.ENUMS, definitions("enum", DefinitionLayout.Packed(8), EnumDecoder(), EnumEncoder(), "enum") { EnumType(it) })
        SourceCodecs.builtin(Index.NPCS, definitions("npc", DefinitionLayout.Packed(7), NpcDecoder(), NpcEncoder(), "npc") { NpcType(it) })
        SourceCodecs.builtin(Index.ITEMS, definitions("obj", DefinitionLayout.Packed(8), ObjDecoder(), ObjEncoder(), "obj") { ObjType(it) })
        SourceCodecs.builtin(Index.ANIMATIONS, definitions("seq", DefinitionLayout.Packed(7), SeqDecoder(), SeqEncoder(), "seq") { SeqType(it) })
        SourceCodecs.builtin(Index.SPOTANIMS, definitions("spotanim", DefinitionLayout.Packed(8), SpotAnimDecoder(), SpotAnimEncoder(), null) { SpotAnimType(it) })
        SourceCodecs.builtin(Index.STRUCTS, definitions("struct", DefinitionLayout.Packed(5), StructDecoder(), StructEncoder(), "struct") { StructType(it) })

        config(Config.FLOOR_UNDERLAY, "underlay", UnderlayDecoder(), UnderlayEncoder(), null) { UnderlayType(it) }
        config(Config.IDENTITY_KIT, "idk", IDKDecoder(), IDKEncoder(), null) { IDKType(it) }
        config(Config.FLOOR_OVERLAY, "overlay", OverlayDecoder(), OverlayEncoder(), null) { OverlayType(it) }
        config(Config.INVENTORIES, "inv", InventoryDecoder(), InventoryEncoder(), "inv") { InvType(it) }
        config(Config.PARAMS, "param", ParamDecoder(), ParamEncoder(), "param") { ParamType(it) }
        config(Config.SEQ_GROUP, "seqgroup", SeqGroupDecoder(), SeqGroupEncoder(), null) { SeqGroupType(it) }
        config(Config.RENDER_ANIMATIONS, "bas", BASDecoder(), BASEncoder(), "bas") { BASType(it) }
        config(Config.CURSORS, "cursor", CursorDecoder(), CursorEncoder(), "cursor") { CursorType(it) }
        config(Config.MAP_SCENES, "mapscene", MapSceneDecoder(), MapSceneEncoder(), null) { MapSceneType(it) }
        config(Config.QUESTS, "quest", QuestDecoder(), QuestEncoder(), "quest") { QuestType(it) }
        config(Config.MAP_ELEMENTS, "mapelement", MapElementDecoder(), MapElementEncoder(), "mapelement") { MapElementType(it) }
        config(Config.DBTABLE, "dbtable", DbTableDecoder(), DbTableEncoder(), "dbtable") { DbTableType(it) }
        config(Config.DBROW, "dbrow", DbRowDecoder(), DbRowEncoder(), "dbrow") { DbRowType(it) }
        config(Config.HIT_SPLATS, "hitmark", HitmarkDecoder(), HitmarkEncoder(), "hitmark") { HitmarkType(it) }
        config(Config.HIT_BARS, "headbar", HeadbarDecoder(), HeadbarEncoder(), "headbar") { HeadbarType(it) }
        config(Config.VAR_BIT, "varbit", VarBitDecoder(), VarBitEncoder(), "varbit_player") { VarBitType(it) }
        config(Config.WATER, "water", WaterDecoder(), WaterEncoder(), null) { WaterType(it) }
        config(Config.WORLD_AREAS, "worldarea", WorldAreaDecoder(), WorldAreaEncoder(), null) { WorldAreaType(it) }
        config(EffectAnimDecoder.ARCHIVE, "effectanim", EffectAnimDecoder(), EffectAnimEncoder(), null) { EffectAnimType(it) }
        config(ConfigGroup76Decoder.ARCHIVE, "configgroup76", ConfigGroup76Decoder(), ConfigGroup76Encoder(), null) { ConfigGroup76Type(it) }

        config(Config.VAR_PLAYER, "varplayer", VarPlayerDecoder(), VarDomainEncoder(), "var_player") { VarPlayerType(it) }
        config(Config.VAR_NPC, "varnpc", VarNpcDecoder(), VarDomainEncoder(), "var_npc") { VarNpcType(it) }
        config(Config.VAR_CLIENT, "varclient", VarcDecoder(), VarDomainEncoder(), "var_client") { VarcType(it) }
        config(Config.VAR_WORLD, "varworld", VarWorldDecoder(), VarDomainEncoder(), null) { VarWorldType(it) }
        config(Config.VAR_MAP_SQUARE, "varmapsquare", VarMapSquareDecoder(), VarDomainEncoder(), null) { VarMapSquareType(it) }
        config(Config.VAR_OBJECT, "varobject", VarObjectDecoder(), VarDomainEncoder(), "var_object") { VarObjectType(it) }
        config(Config.VAR_CLAN, "varclan", VarClanDecoder(), VarDomainEncoder(), "var_clan") { VarClanType(it) }
        config(Config.VAR_CLAN_SETTINGS, "varclansetting", VarClanSettingDecoder(), VarDomainEncoder(), "var_clan_setting") { VarClanSettingType(it) }
        config(Config.VAR_CONTROLLER, "varcontroller", VarControllerDecoder(), VarDomainEncoder(), null) { VarControllerType(it) }
        config(Config.VAR_GLOBAL, "varglobal", VarGlobalDecoder(), VarDomainEncoder(), null) { VarGlobalType(it) }
        config(Config.VAR_GROUP, "vargroup", VarGroupDecoder(), VarDomainEncoder(), "var_player_group") { VarGroupType(it) }
    }


    /**
     * One interface per directory and one component per file, named through the component catalog,
     * whose entries are keyed by the interface and the component rather than by a single id.
     */
    private fun components() = DefinitionJsonCodec(
        id = "component",
        layout = DefinitionLayout.Nested(COMPONENT_BITS),
        create = { ComponentType(id = it) },
        decode = { definition, bytes -> ComponentDecoder().readLoop(definition, BufferReader(bytes)) },
        encode = { definition, writer -> with(ComponentEncoder()) { writer.encode(definition) } },
        catalogName = { archive, file -> SourceCodecs.context.gamevals.component(archive, file) }
    )

    /** A directory per table and a file per indexed column, named through the table's catalog entry. */
    private fun dbTableIndexes() = DefinitionJsonCodec(
        id = "dbtableindex",
        layout = DefinitionLayout.Nested(DBTABLE_COLUMN_BITS),
        create = { DbColumnIndexType(it) },
        decode = { definition, bytes -> DbColumnIndexDecoder().readLoop(definition, BufferReader(bytes)) },
        encode = { definition, writer -> with(DbTableIndexEncoder()) { writer.encode(definition) } },
        catalogName = { archive, _ -> SourceCodecs.context.gamevals.name("dbtable", archive) }
    )

    private fun worldMap() = ArchiveDirectoryCodec(
        id = "worldmapdata",
        name = { WORLD_MAP_NAMES.getValue(it) },
        archive = { name -> WORLD_MAP_NAMES.entries.firstOrNull { it.value == name }?.key ?: -1 },
        codec = { WORLD_MAP_CODECS.getValue(it) }
    )

    private fun particles() = ArchiveDirectoryCodec(
        id = "config_particle",
        name = { PARTICLE_NAMES.getValue(it) },
        archive = { name -> PARTICLE_NAMES.entries.firstOrNull { it.value == name }?.key ?: -1 },
        codec = { PARTICLE_CODECS.getValue(it) }
    )

    /**
     * One named file per archive rather than a directory each: every archive is one settings file
     * whose opcodes mean something different from every other's, so each has a type of its own.
     */
    private fun defaults() = ArchiveDirectoryCodec(
        id = "defaults",
        name = { "$DEFAULTS_PREFIX$it" },
        archive = { name -> name.removePrefix(DEFAULTS_PREFIX).toIntOrNull()?.takeIf { DEFAULTS_CODECS.containsKey(it) } ?: -1 },
        codec = { DEFAULTS_CODECS.getValue(it) },
        nested = false
    )

    private fun uiAnims() = NamedArchiveCodec(
        id = "uianim",
        names = UI_ANIM_NAMES,
        codecs = UI_ANIM_CODECS
    )

    private fun quickChat() = ArchiveDirectoryCodec(
        id = "quickchat",
        name = { QUICK_CHAT_NAMES.getValue(it) },
        archive = { name -> QUICK_CHAT_NAMES.entries.firstOrNull { it.value == name }?.key ?: -1 },
        codec = { QUICK_CHAT_CODECS.getValue(it) }
    )

    private fun <T : CacheType> defaults(
        group: Int,
        decoder: TypeDecoder<T>,
        encoder: TypeEncoder<T>,
        create: (Int) -> T
    ) = definitions("$DEFAULTS_PREFIX$group", NamedLayout("$DEFAULTS_PREFIX$group"), decoder, encoder, null, create)

    private fun <T : CacheType> config(
        archive: Int,
        id: String,
        decoder: TypeDecoder<T>,
        encoder: TypeEncoder<T>,
        gameval: String?,
        create: (Int) -> T
    ) = ConfigCodec.register(archive, definitions(id, DefinitionLayout.Flat, decoder, encoder, gameval, create))

    /**
     * Decoding goes through the decoder's read path and nothing else: `changeValues` derives values
     * that are not in the file, and a definition re-encoded after it no longer matches its own bytes.
     */
    private fun <T : CacheType> definitions(
        id: String,
        layout: DefinitionLayout,
        decoder: TypeDecoder<T>,
        encoder: TypeEncoder<T>,
        gameval: String?,
        create: (Int) -> T
    ) = DefinitionJsonCodec(
        id = id,
        layout = layout,
        create = create,
        decode = { definition, bytes -> decoder.readLoop(definition, BufferReader(bytes)) },
        encode = { definition, writer -> with(encoder) { writer.encode(definition) } },
        gameval = gameval
    )

    /** The interface id packing [world.gregs.voidps.cache.type.data.InterfaceType.pack] uses. */
    private const val COMPONENT_BITS = 16

    /** Enough to hold every material id of one archive, which is the whole index. */
    private const val MATERIAL_BITS = 15

    private const val BILLBOARD_BITS = 10

    private const val ACHIEVEMENT_BITS = 7

    private const val DBTABLE_COLUMN_BITS = 8

    private val WORLD_MAP_NAMES = mapOf(0 to "details", 1 to "coords", 2 to "image", 3 to "colours", 4 to "composite")

    private val WORLD_MAP_CODECS: Map<Int, SourceCodec> = mapOf(
        0 to definitions("worldmapdetails", DefinitionLayout.Flat, WorldMapDetailsDecoder(), WorldMapDetailsEncoder(), null) { WorldMapType(it) },
        1 to definitions("worldmapcoords", DefinitionLayout.Flat, WorldMapCoordsDecoder(), WorldMapCoordsEncoder(), null) { WorldMapCoordsType(it) },
        2 to WorldMapImageCodec(),
        3 to definitions("worldmapcolours", DefinitionLayout.Flat, WorldMapColoursDecoder(), WorldMapColoursEncoder(), null) { WorldMapColoursType(it) },
        4 to WorldMapCompositeCodec()
    )

    /** Nothing in the client names these archives, so the tree numbers them rather than invent names. */
    private const val DEFAULTS_PREFIX = "group"

    private val DEFAULTS_CODECS: Map<Int, SourceCodec> = mapOf(
        1 to defaults(1, DefaultsGroup1Decoder(), DefaultsGroup1Encoder()) { DefaultsGroup1Type(it) },
        2 to defaults(2, DefaultsGroup2Decoder(), DefaultsGroup2Encoder()) { DefaultsGroup2Type(it) },
        3 to defaults(3, DefaultsGroup3Decoder(), DefaultsGroup3Encoder()) { DefaultsGroup3Type(it) },
        4 to defaults(4, DefaultsGroup4Decoder(), DefaultsGroup4Encoder()) { DefaultsGroup4Type(it) },
        5 to defaults(5, DefaultsGroup5Decoder(), DefaultsGroup5Encoder()) { DefaultsGroup5Type(it) },
        6 to defaults(6, DefaultsGroup6Decoder(), DefaultsGroup6Encoder()) { DefaultsGroup6Type(it) },
        7 to defaults(7, DefaultsGroup7Decoder(), DefaultsGroup7Encoder()) { DefaultsGroup7Type(it) },
        8 to defaults(8, DefaultsGroup8Decoder(), DefaultsGroup8Encoder()) { DefaultsGroup8Type(it) },
        9 to defaults(9, DefaultsGroup9Decoder(), DefaultsGroup9Encoder()) { DefaultsGroup9Type(it) },
        10 to defaults(10, DefaultsGroup10Decoder(), DefaultsGroup10Encoder()) { DefaultsGroup10Type(it) },
        12 to defaults(12, DefaultsGroup12Decoder(), DefaultsGroup12Encoder()) { DefaultsGroup12Type(it) }
    )

    private val PARTICLE_NAMES = mapOf(0 to "producer", 1 to "particle")

    private val PARTICLE_CODECS: Map<Int, SourceCodec> = mapOf(
        0 to definitions("producer", DefinitionLayout.Flat, ParticleProducerDecoder(), ParticleProducerEncoder(), null) { ParticleProducerType(it) },
        1 to definitions("particle", DefinitionLayout.Flat, ParticleDecoder(), ParticleEncoder(), null) { ParticleType(it) }
    )

    private val UI_ANIM_NAMES = mapOf(UiAnimDecoder.CURVE_ARCHIVE to "ui_anim_curve", UiAnimDecoder.ANIM_ARCHIVE to "ui_anim")

    private val UI_ANIM_CODECS: Map<Int, SourceCodec> = mapOf(
        UiAnimDecoder.CURVE_ARCHIVE to definitions("ui_anim_curve", DefinitionLayout.Flat, UiAnimCurveDecoder(), UiAnimCurveEncoder(), "ui_anim_curve") { UiAnimCurveType(it) },
        UiAnimDecoder.ANIM_ARCHIVE to definitions("ui_anim", DefinitionLayout.Flat, UiAnimDecoder(), UiAnimEncoder(), "ui_anim") { UiAnimType(it) }
    )

    private val QUICK_CHAT_NAMES = mapOf(0 to "quickchatcat", 1 to "quickchatphrase")

    private val QUICK_CHAT_CODECS: Map<Int, SourceCodec> = mapOf(
        0 to definitions("quickchatcat", DefinitionLayout.Flat, QuickChatCatDecoder(), QuickChatCatEncoder(), null) { QuickChatCatType(it) },
        1 to definitions("quickchatphrase", DefinitionLayout.Flat, QuickChatPhraseDecoder(), QuickChatPhraseEncoder(), null) { QuickChatPhraseType(it) }
    )
}
