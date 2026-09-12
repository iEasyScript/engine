package world.gregs.voidps.cache

object Index {
    const val ANIMATION_FRAMES = 0
    const val ANIMATION_SKELETONS = 1 // bases
    const val CONFIGS = 2
    const val INTERFACES = 3
    const val SOUND_EFFECTS = 4
    const val MAPS = 5
    const val MUSIC = 6
    const val MODELS = 7
    const val GRAPHICS = 8
    const val TEXTURES = 9
    const val HUFFMAN = 10 // binary
    const val MUSIC_EFFECTS = 11
    const val CLIENT_SCRIPTS = 12
    const val FONT_METRICS = 13
    const val VORBIS = 14
    const val SOUND_EFFECTS_MIDI = 15
    const val OBJECTS = 16 // locations
    const val ENUMS = 17
    const val NPCS = 18
    const val ITEMS = 19
    const val ANIMATIONS = 20 // sequences
    const val SPOTANIMS = 21
    const val STRUCTS = 22
    const val WORLD_MAP = 23
    const val QUICK_CHAT = 24
    const val QUICK_CHAT_GLOBAL = 25
    const val TEXTURE_DEFINITIONS = 26 // materials
    const val PARTICLES = 27
    const val DEFAULTS = 28
    const val BILLBOARDS = 29
    const val NATIVE_LIBRARIES = 30 // DLLS
    const val SHADERS = 31
    const val LOADING_GRAPHICS = 32
    const val GAME_TIPS = 33 // loading screens
    const val LOADING_GRAPHICS_RAW = 34
    const val CUTSCENES = 35
    const val AUDIO_STREAMS = 40
    const val WORLD_MAP_AREAS = 41
    const val WORLD_MAP_LABELS = 42
    const val MODELS_RT7 = 47
    const val ANIMS_RT7 = 48
    const val DBTABLEINDEX = 49
    const val TEXTURES_DXT = 52
    const val TEXTURES_PNG = 53
    const val TEXTURES_PNG_MIPPED = 54
    const val TEXTURES_ETC = 55
    const val ANIMS_KEYFRAMES = 56
    const val ACHIEVEMENT_DEF = 57

    // Backwards compatibility alias
    @Deprecated("Use STRUCTS instead", ReplaceWith("STRUCTS"))
    const val VAR_BIT = 22
}
