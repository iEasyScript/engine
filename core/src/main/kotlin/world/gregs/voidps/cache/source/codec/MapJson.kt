package world.gregs.voidps.cache.source.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.source.SourceJson
import world.gregs.voidps.cache.type.data.MapSquareEffect
import world.gregs.voidps.cache.type.data.MapSquareEnvironmentFile
import world.gregs.voidps.cache.type.data.MapSquarePatch
import world.gregs.voidps.cache.type.data.MapSquarePointLight
import world.gregs.voidps.cache.type.data.MapSquareTerrain
import world.gregs.voidps.cache.type.data.MapSquareTerrainLevel
import world.gregs.voidps.cache.type.data.MapSquareEnvironment
import world.gregs.voidps.cache.type.data.MapSquareHdr
import world.gregs.voidps.cache.type.data.MapSquareLight
import world.gregs.voidps.cache.type.data.MapSquareLightGrid
import world.gregs.voidps.cache.type.data.MapSquareLightGridLevel
import world.gregs.voidps.cache.type.data.MapSquareLights
import world.gregs.voidps.cache.type.data.MapSquareNpcSpawn
import world.gregs.voidps.cache.type.data.MapSquareObject
import world.gregs.voidps.cache.type.data.MapSquareSkybox
import world.gregs.voidps.cache.type.data.MapSquareTiles
import world.gregs.voidps.cache.type.data.MapSquareTransform
import world.gregs.voidps.cache.type.data.MapSquareUnknown130
import world.gregs.voidps.cache.type.data.MapSquareUnknown3

/**
 * One map square file as editable JSON, and back.
 *
 * Writing is deterministic to the byte so re-unpacking an unchanged cache leaves the tree alone;
 * reading is tolerant of key order, whitespace and unknown keys, and a missing key reads as the
 * default the decoder would have left.
 *
 * **A tile is a token group and a row of 64 is a line.** A surface square is 16,384 tiles, which
 * cannot be an object each, so a tile's records are transcribed as tokens and one localX of 64
 * ascending localY is one line - the order the file itself stores them in. The token order is the
 * record order, which is what makes the line a transcription rather than a set of fields somebody
 * has to re-derive an order for:
 *
 * | token | record |
 * |---|---|
 * | `o<id>/<shape>/<rotation>` | overlay: a packed shape byte, then the id as a smart |
 * | `s<settings>` | the tile settings byte |
 * | `u<id>` | the underlay id, as a smart |
 * | `h<height>` | the tile height, as a short |
 * | `-` | a tile whose flag byte is 0 |
 */
internal object MapJson {

    fun writeTiles(tiles: MapSquareTiles, archive: Int): ByteArray {
        val out = StringBuilder(1 shl 16)
        out.append("{\n")
        region(out, archive)
        out.append("  \"levels\": ").append(tiles.levels).append(",\n")
        if (tiles.version != MapSquareTiles.NO_HEADER) {
            out.append("  \"version\": ").append(tiles.version).append(",\n")
        }
        out.append("  \"tiles\": [")
        for (level in 0 until tiles.levels) {
            out.append(if (level == 0) "\n" else ",\n").append("    [\n")
            for (x in 0 until MapSquareTiles.SIZE) {
                out.append("      \"")
                for (y in 0 until MapSquareTiles.SIZE) {
                    if (y != 0) {
                        out.append(' ')
                    }
                    tile(out, tiles, level, x, y)
                }
                out.append('"').append(if (x == MapSquareTiles.SIZE - 1) "\n" else ",\n")
            }
            out.append("    ]")
        }
        if (tiles.levels > 0) {
            out.append("\n  ")
        }
        out.append("]")
        environment(out, tiles)
        out.append("\n}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun readTiles(bytes: ByteArray, levels: Int): MapSquareTiles {
        val root = parse(bytes)
        val tiles = MapSquareTiles(root["levels"]?.jsonPrimitive?.int ?: levels)
        tiles.version = root["version"]?.jsonPrimitive?.int ?: MapSquareTiles.NO_HEADER
        val grid = root["tiles"]?.jsonArray ?: JsonArray(emptyList())
        require(grid.size == tiles.levels) { "\"levels\" says ${tiles.levels} but \"tiles\" holds ${grid.size}." }
        for ((level, rows) in grid.withIndex()) {
            val lines = rows.jsonArray
            require(lines.size == MapSquareTiles.SIZE) { "Level $level has ${lines.size} rows, not ${MapSquareTiles.SIZE}." }
            for ((x, row) in lines.withIndex()) {
                readRow(tiles, level, x, row.jsonPrimitive.content)
            }
        }
        val environment = root["environment"]?.jsonObject ?: return tiles
        tiles.environmentHead = ints(environment["head"]?.jsonArray)
        environment["effects"]?.jsonArray?.forEach { tiles.effects.add(readEffect(it.jsonObject)) }
        return tiles
    }

    fun writeLocs(locations: List<MapSquareObject>, archive: Int): ByteArray {
        val out = StringBuilder(locations.size * 80 + 64)
        out.append("{\n")
        region(out, archive)
        out.append("  \"locs\": [")
        for ((position, location) in locations.withIndex()) {
            out.append(if (position == 0) "\n" else ",\n")
            out.append("    {\"id\": ").append(location.id)
            out.append(", \"x\": ").append(location.localX)
            out.append(", \"y\": ").append(location.localY)
            out.append(", \"level\": ").append(location.plane)
            out.append(", \"shape\": ").append(location.shape)
            out.append(", \"rotation\": ").append(location.rotation)
            location.transform?.let {
                out.append(", \"transform\": ")
                transform(out, it)
            }
            out.append('}')
        }
        if (locations.isNotEmpty()) {
            out.append("\n  ")
        }
        out.append("]\n}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun readLocs(bytes: ByteArray): MutableList<MapSquareObject> {
        val locs = parse(bytes)["locs"]?.jsonArray ?: return ArrayList(0)
        val out = ArrayList<MapSquareObject>(locs.size)
        for (element in locs) {
            val loc = element.jsonObject
            out.add(
                MapSquareObject(
                    id = loc.getValue("id").jsonPrimitive.int,
                    localX = loc.getValue("x").jsonPrimitive.int,
                    localY = loc.getValue("y").jsonPrimitive.int,
                    plane = loc["level"]?.jsonPrimitive?.int ?: 0,
                    shape = loc["shape"]?.jsonPrimitive?.int ?: 0,
                    rotation = loc["rotation"]?.jsonPrimitive?.int ?: 0,
                    transform = loc["transform"]?.let { readTransform(it.jsonObject) }
                )
            )
        }
        return out
    }

    fun writeNpcs(spawns: List<MapSquareNpcSpawn>, archive: Int): ByteArray {
        val out = StringBuilder(spawns.size * 56 + 64)
        out.append("{\n")
        region(out, archive)
        out.append("  \"npcs\": [")
        for ((position, spawn) in spawns.withIndex()) {
            out.append(if (position == 0) "\n" else ",\n")
            out.append("    {\"npc\": ").append(spawn.id)
            out.append(", \"x\": ").append(spawn.localX)
            out.append(", \"y\": ").append(spawn.localY)
            out.append(", \"level\": ").append(spawn.level)
            out.append('}')
        }
        if (spawns.isNotEmpty()) {
            out.append("\n  ")
        }
        out.append("]\n}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun readNpcs(bytes: ByteArray): MutableList<MapSquareNpcSpawn> {
        val npcs = parse(bytes)["npcs"]?.jsonArray ?: return ArrayList(0)
        val out = ArrayList<MapSquareNpcSpawn>(npcs.size)
        for (element in npcs) {
            val spawn = element.jsonObject
            out.add(
                MapSquareNpcSpawn(
                    id = spawn.getValue("npc").jsonPrimitive.int,
                    localX = spawn.getValue("x").jsonPrimitive.int,
                    localY = spawn.getValue("y").jsonPrimitive.int,
                    level = spawn["level"]?.jsonPrimitive?.int ?: 0
                )
            )
        }
        return out
    }

    fun writeTerrain(terrain: MapSquareTerrain, archive: Int): ByteArray {
        val out = StringBuilder(1 shl 18)
        out.append("{\n")
        region(out, archive)
        if (terrain.version != MapSquareTiles.NO_HEADER) {
            out.append("  \"version\": ").append(terrain.version).append(",\n")
        }
        out.append("  \"levels\": [")
        for ((position, grid) in terrain.levels.withIndex()) {
            out.append(if (position == 0) "\n" else ",\n")
            out.append("    {\n      \"level\": ").append(grid.level).append(",\n      \"cells\": [\n")
            for (x in 0 until MapSquareTerrainLevel.SIZE) {
                out.append("        \"")
                for (y in 0 until MapSquareTerrainLevel.SIZE) {
                    if (y != 0) {
                        out.append(' ')
                    }
                    cell(out, grid, MapSquareTerrainLevel.index(x, y))
                }
                out.append('"').append(if (x == MapSquareTerrainLevel.SIZE - 1) "\n" else ",\n")
            }
            out.append("      ]\n    }")
        }
        if (terrain.levels.isNotEmpty()) {
            out.append("\n  ")
        }
        out.append("]\n}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun readTerrain(bytes: ByteArray): MapSquareTerrain {
        val root = parse(bytes)
        val terrain = MapSquareTerrain()
        terrain.version = root["version"]?.jsonPrimitive?.int ?: MapSquareTiles.NO_HEADER
        for (element in root["levels"]?.jsonArray ?: JsonArray(emptyList())) {
            val block = element.jsonObject
            val grid = MapSquareTerrainLevel(block.getValue("level").jsonPrimitive.int)
            val rows = block.getValue("cells").jsonArray
            require(rows.size == MapSquareTerrainLevel.SIZE) {
                "Level ${grid.level} has ${rows.size} rows, not ${MapSquareTerrainLevel.SIZE}."
            }
            for ((x, row) in rows.withIndex()) {
                readCells(grid, x, row.jsonPrimitive.content)
            }
            terrain.levels.add(grid)
        }
        return terrain
    }

    /** One cell's records as tokens, in the order the file stores them. */
    private fun cell(out: StringBuilder, grid: MapSquareTerrainLevel, index: Int) {
        val populated = grid.populated[index]
        val secondLayer = grid.secondLayer[index]
        if (populated && grid.settings[index] != 0) {
            out.append(SETTINGS).append(grid.settings[index]).append(',')
        }
        out.append(HEIGHT).append(grid.heights[index])
        if (secondLayer) {
            out.append(',').append(SECOND_HEIGHT).append(grid.secondHeights[index])
        }
        if (!populated) {
            return
        }
        val underlay = grid.underlayIds[index]
        out.append(',').append(UNDERLAY).append(underlay)
        if (underlay != MapSquareTerrainLevel.NONE) {
            out.append(',').append(COLOUR).append(grid.underlayColours[index])
        }
        val overlay = grid.overlayIds[index]
        out.append(',').append(OVERLAY).append(overlay)
        if (secondLayer) {
            out.append(',').append(SECOND_OVERLAY).append(grid.secondOverlayIds[index])
        }
        if (overlay != MapSquareTerrainLevel.NONE) {
            out.append(',').append(SHAPE).append(grid.overlayShapes[index])
                .append('/').append(grid.overlayRotations[index])
            if (secondLayer) {
                out.append(',').append(SECOND_UNDERLAY).append(grid.secondUnderlayIds[index])
            }
        }
    }

    private fun readCells(grid: MapSquareTerrainLevel, x: Int, row: String) {
        var y = 0
        for (group in row.splitToSequence(' ')) {
            require(y < MapSquareTerrainLevel.SIZE) { "Row $x has more than ${MapSquareTerrainLevel.SIZE} cells." }
            val index = MapSquareTerrainLevel.index(x, y)
            grid.underlayIds[index] = MapSquareTerrainLevel.NONE
            grid.overlayIds[index] = MapSquareTerrainLevel.NONE
            grid.secondOverlayIds[index] = MapSquareTerrainLevel.NONE
            grid.secondUnderlayIds[index] = MapSquareTerrainLevel.NONE
            for (token in group.split(',')) {
                readCellToken(grid, index, token)
            }
            y++
        }
        require(y == MapSquareTerrainLevel.SIZE) { "Row $x has $y cells, not ${MapSquareTerrainLevel.SIZE}." }
    }

    private fun readCellToken(grid: MapSquareTerrainLevel, index: Int, token: String) {
        val value = token.substring(1)
        when (token[0]) {
            SETTINGS -> grid.settings[index] = value.toInt()
            HEIGHT -> grid.heights[index] = value.toInt()
            SECOND_HEIGHT -> {
                grid.secondHeights[index] = value.toInt()
                grid.secondLayer[index] = true
            }
            UNDERLAY -> {
                grid.underlayIds[index] = value.toInt()
                grid.populated[index] = true
            }
            COLOUR -> grid.underlayColours[index] = value.toInt()
            OVERLAY -> {
                grid.overlayIds[index] = value.toInt()
                grid.populated[index] = true
            }
            SECOND_OVERLAY -> grid.secondOverlayIds[index] = value.toInt()
            SHAPE -> {
                val parts = value.split('/')
                grid.overlayShapes[index] = parts[0].toInt()
                grid.overlayRotations[index] = parts[1].toInt()
            }
            SECOND_UNDERLAY -> grid.secondUnderlayIds[index] = value.toInt()
            else -> throw IllegalArgumentException("Unknown terrain cell token '$token'.")
        }
    }

    fun writeEnvironmentFile(environment: MapSquareEnvironmentFile, archive: Int): ByteArray {
        val out = StringBuilder(2048)
        out.append("{\n")
        region(out, archive)
        out.append("  \"sunColour\": ").append(environment.sunColour).append(",\n")
        out.append("  \"sunPosition\": ").append(SourceJson.array(environment.sunPosition)).append(",\n")
        out.append("  \"sunAmbient\": ").append(environment.sunAmbient).append(",\n")
        out.append("  \"sunLight\": ").append(environment.sunLight).append(",\n")
        out.append("  \"sunBacklight\": ").append(environment.sunBacklight).append(",\n")
        out.append("  \"unknown5\": ").append(floats(environment.unknown5)).append(",\n")
        out.append("  \"fogColour\": ").append(environment.fogColour).append(",\n")
        out.append("  \"fogDepth\": ").append(environment.fogDepth).append(",\n")
        out.append("  \"fogEnabled\": ").append(environment.fogEnabled).append(",\n")
        out.append("  \"unknown9\": ").append(floats(environment.unknown9)).append(",\n")
        out.append("  \"unknown10\": ").append(environment.unknown10).append(",\n")
        out.append("  \"unknown11\": ").append(floats(environment.unknown11)).append(",\n")
        out.append("  \"unknown12\": ").append(floats(environment.unknown12)).append(",\n")
        out.append("  \"unknown13\": ").append(floats(environment.unknown13)).append(",\n")
        out.append("  \"unknown14\": ").append(environment.unknown14).append(",\n")
        out.append("  \"unknown15\": ").append(environment.unknown15).append(",\n")
        out.append("  \"unknown16\": ").append(float(environment.unknown16)).append(",\n")
        out.append("  \"unknown17\": ").append(environment.unknown17).append(",\n")
        out.append("  \"unknown18\": ").append(environment.unknown18).append(",\n")
        out.append("  \"unknown19\": ").append(floats(environment.unknown19)).append(",\n")
        out.append("  \"unknown20\": ").append(floats(environment.unknown20)).append(",\n")
        out.append("  \"unknown21\": ").append(environment.unknown21).append(",\n")
        out.append("  \"unknown22\": ").append(environment.unknown22).append(",\n")
        out.append("  \"unknown23\": ").append(environment.unknown23).append(",\n")
        out.append("  \"unknown24\": ").append(environment.unknown24).append(",\n")
        out.append("  \"unknown25\": ").append(float(environment.unknown25)).append(",\n")
        out.append("  \"unknown26\": ").append(environment.unknown26).append(",\n")
        out.append("  \"unknown27\": ").append(float(environment.unknown27)).append(",\n")
        out.append("  \"unknown28\": ").append(float(environment.unknown28)).append(",\n")
        out.append("  \"unknown29\": ").append(floats(environment.unknown29)).append("\n}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun readEnvironmentFile(bytes: ByteArray): MapSquareEnvironmentFile {
        val root = parse(bytes)
        return MapSquareEnvironmentFile(
            sunColour = root.getValue("sunColour").jsonPrimitive.int,
            sunPosition = ints(root["sunPosition"]?.jsonArray) ?: IntArray(MapSquareEnvironmentFile.VECTOR),
            sunAmbient = root.getValue("sunAmbient").jsonPrimitive.int,
            sunLight = root.getValue("sunLight").jsonPrimitive.int,
            sunBacklight = root.getValue("sunBacklight").jsonPrimitive.int,
            unknown5 = floats(root, "unknown5"),
            fogColour = root.getValue("fogColour").jsonPrimitive.int,
            fogDepth = root.getValue("fogDepth").jsonPrimitive.int,
            fogEnabled = root.getValue("fogEnabled").jsonPrimitive.int,
            unknown9 = floats(root, "unknown9"),
            unknown10 = root.getValue("unknown10").jsonPrimitive.int,
            unknown11 = floats(root, "unknown11"),
            unknown12 = floats(root, "unknown12"),
            unknown13 = floats(root, "unknown13"),
            unknown14 = root.getValue("unknown14").jsonPrimitive.int,
            unknown15 = root.getValue("unknown15").jsonPrimitive.int,
            unknown16 = float(root.getValue("unknown16")),
            unknown17 = root.getValue("unknown17").jsonPrimitive.int,
            unknown18 = root.getValue("unknown18").jsonPrimitive.int,
            unknown19 = floats(root, "unknown19"),
            unknown20 = floats(root, "unknown20"),
            unknown21 = root.getValue("unknown21").jsonPrimitive.int,
            unknown22 = root.getValue("unknown22").jsonPrimitive.int,
            unknown23 = root.getValue("unknown23").jsonPrimitive.int,
            unknown24 = root.getValue("unknown24").jsonPrimitive.int,
            unknown25 = float(root.getValue("unknown25")),
            unknown26 = root.getValue("unknown26").jsonPrimitive.int,
            unknown27 = float(root.getValue("unknown27")),
            unknown28 = float(root.getValue("unknown28")),
            unknown29 = floats(root, "unknown29")
        )
    }

    fun writePointLights(lights: List<MapSquarePointLight>, archive: Int): ByteArray {
        val out = StringBuilder(lights.size * 320 + 64)
        out.append("{\n")
        region(out, archive)
        out.append("  \"lights\": [")
        for ((position, light) in lights.withIndex()) {
            out.append(if (position == 0) "\n" else ",\n")
            out.append("    {\"flags\": ").append(light.flags)
            out.append(", \"x\": ").append(light.x)
            out.append(", \"z\": ").append(light.z)
            out.append(", \"height\": ").append(light.height)
            out.append(", \"radius\": ").append(light.radius)
            out.append(", \"ranges\": ").append(SourceJson.array(light.ranges))
            out.append(", \"colour\": ").append(light.colour)
            out.append(", \"packedType\": ").append(light.packedType)
            out.append(", \"unknown7\": ").append(light.unknown7)
            if (light.lightType != MapSquarePointLight.NO_LIGHT_TYPE) {
                out.append(", \"lightType\": ").append(light.lightType)
            }
            out.append(", \"unknown9\": ").append(floats(light.unknown9))
            out.append(", \"unknown10\": ").append(light.unknown10)
            out.append(", \"unknown11\": ").append(float(light.unknown11))
            out.append(", \"unknown12\": ").append(light.unknown12)
            out.append(", \"unknown13\": ").append(floats(light.unknown13))
            out.append(", \"unknown14\": ").append(SourceJson.array(light.unknown14))
            out.append(", \"unknown15\": ").append(light.unknown15).append('}')
        }
        if (lights.isNotEmpty()) {
            out.append("\n  ")
        }
        out.append("]\n}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun readPointLights(bytes: ByteArray): MutableList<MapSquarePointLight> {
        val lights = parse(bytes)["lights"]?.jsonArray ?: return ArrayList(0)
        return lights.mapTo(ArrayList(lights.size)) { element ->
            val light = element.jsonObject
            MapSquarePointLight(
                flags = light.getValue("flags").jsonPrimitive.int,
                x = light.getValue("x").jsonPrimitive.int,
                z = light.getValue("z").jsonPrimitive.int,
                height = light.getValue("height").jsonPrimitive.int,
                radius = light.getValue("radius").jsonPrimitive.int,
                ranges = ints(light["ranges"]?.jsonArray) ?: IntArray(0),
                colour = light.getValue("colour").jsonPrimitive.int,
                packedType = light.getValue("packedType").jsonPrimitive.int,
                unknown7 = light.getValue("unknown7").jsonPrimitive.int,
                lightType = light["lightType"]?.jsonPrimitive?.int ?: MapSquarePointLight.NO_LIGHT_TYPE,
                unknown9 = floats(light, "unknown9"),
                unknown10 = light.getValue("unknown10").jsonPrimitive.int,
                unknown11 = float(light.getValue("unknown11")),
                unknown12 = light.getValue("unknown12").jsonPrimitive.int,
                unknown13 = floats(light, "unknown13"),
                unknown14 = ints(light["unknown14"]?.jsonArray) ?: IntArray(3),
                unknown15 = light.getValue("unknown15").jsonPrimitive.int
            )
        }
    }

    fun writePatches(patches: List<MapSquarePatch>, archive: Int): ByteArray {
        val out = StringBuilder(patches.size * 160 + 64)
        out.append("{\n")
        region(out, archive)
        out.append("  \"patches\": [")
        for ((position, patch) in patches.withIndex()) {
            out.append(if (position == 0) "\n" else ",\n")
            out.append("    {\"x\": ").append(patch.positionX)
            out.append(", \"y\": ").append(patch.positionY)
            out.append(", \"extentX\": ").append(patch.extentX)
            out.append(", \"extentY\": ").append(patch.extentY)
            out.append(", \"unknown4\": ").append(patch.unknown4)
            out.append(", \"axis\": ").append(floats(patch.axis))
            out.append(", \"angle\": ").append(float(patch.angle))
            out.append(", \"unknown6\": ").append(patch.unknown6)
            out.append(", \"directionX\": ").append(patch.directionX)
            out.append(", \"directionY\": ").append(patch.directionY)
            out.append(", \"typeId\": ").append(patch.typeId).append('}')
        }
        if (patches.isNotEmpty()) {
            out.append("\n  ")
        }
        out.append("]\n}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun readPatches(bytes: ByteArray): MutableList<MapSquarePatch> {
        val patches = parse(bytes)["patches"]?.jsonArray ?: return ArrayList(0)
        return patches.mapTo(ArrayList(patches.size)) { element ->
            val patch = element.jsonObject
            MapSquarePatch(
                positionX = patch.getValue("x").jsonPrimitive.int,
                positionY = patch.getValue("y").jsonPrimitive.int,
                extentX = patch.getValue("extentX").jsonPrimitive.int,
                extentY = patch.getValue("extentY").jsonPrimitive.int,
                unknown4 = patch.getValue("unknown4").jsonPrimitive.int,
                axis = floats(patch, "axis"),
                angle = float(patch.getValue("angle")),
                unknown6 = patch.getValue("unknown6").jsonPrimitive.int,
                directionX = patch.getValue("directionX").jsonPrimitive.int,
                directionY = patch.getValue("directionY").jsonPrimitive.int,
                typeId = patch.getValue("typeId").jsonPrimitive.int
            )
        }
    }

    private fun floats(values: FloatArray): String = values.joinToString(", ", "[", "]") { float(it) }

    private fun floats(root: JsonObject, name: String): FloatArray {
        val values = root[name]?.jsonArray ?: return FloatArray(0)
        return FloatArray(values.size) { float(values[it]) }
    }

    /** A float as the shortest decimal that reads back to the same bits, or as its name otherwise. */
    private fun float(value: Float): String =
        if (value.isFinite()) value.toString() else SourceJson.quote(value.toString())

    private fun region(out: StringBuilder, archive: Int) {
        out.append("  \"region\": ").append(SourceJson.quote(MapSquare.directory(archive))).append(",\n")
    }

    private fun tile(out: StringBuilder, tiles: MapSquareTiles, level: Int, x: Int, y: Int) {
        val records = tiles.records[level][x][y]
        if (records == 0) {
            out.append(EMPTY)
            return
        }
        var written = false
        if (records and MapSquareTiles.OVERLAY != 0) {
            out.append(OVERLAY).append(tiles.overlayIds[level][x][y])
                .append('/').append(tiles.overlayPathShapes[level][x][y])
                .append('/').append(tiles.overlayRotations[level][x][y])
            written = true
        }
        if (records and MapSquareTiles.SETTINGS != 0) {
            if (written) out.append(',')
            out.append(SETTINGS).append(tiles.flags[level][x][y])
            written = true
        }
        if (records and MapSquareTiles.UNDERLAY != 0) {
            if (written) out.append(',')
            out.append(UNDERLAY).append(tiles.underlayIds[level][x][y])
            written = true
        }
        if (records and MapSquareTiles.HEIGHT != 0) {
            if (written) out.append(',')
            out.append(HEIGHT).append(tiles.heights[level][x][y])
        }
    }

    private fun readRow(tiles: MapSquareTiles, level: Int, x: Int, row: String) {
        var y = 0
        for (group in row.splitToSequence(' ')) {
            require(y < MapSquareTiles.SIZE) { "Level $level row $x has more than ${MapSquareTiles.SIZE} tiles." }
            if (group != EMPTY) {
                var records = 0
                for (token in group.split(',')) {
                    records = records or readToken(tiles, level, x, y, token)
                }
                tiles.records[level][x][y] = records
            }
            y++
        }
        require(y == MapSquareTiles.SIZE) { "Level $level row $x has $y tiles, not ${MapSquareTiles.SIZE}." }
    }

    private fun readToken(tiles: MapSquareTiles, level: Int, x: Int, y: Int, token: String): Int {
        val value = token.substring(1)
        return when (token[0]) {
            OVERLAY -> {
                val parts = value.split('/')
                tiles.overlayIds[level][x][y] = parts[0].toInt()
                tiles.overlayPathShapes[level][x][y] = parts[1].toInt()
                tiles.overlayRotations[level][x][y] = parts[2].toInt()
                MapSquareTiles.OVERLAY
            }
            SETTINGS -> {
                tiles.flags[level][x][y] = value.toInt()
                MapSquareTiles.SETTINGS
            }
            UNDERLAY -> {
                tiles.underlayIds[level][x][y] = value.toInt()
                MapSquareTiles.UNDERLAY
            }
            HEIGHT -> {
                tiles.heights[level][x][y] = value.toInt()
                MapSquareTiles.HEIGHT
            }
            else -> throw IllegalArgumentException("Unknown tile token '$token' at level $level, $x, $y.")
        }
    }

    private fun environment(out: StringBuilder, tiles: MapSquareTiles) {
        val head = tiles.environmentHead ?: return
        out.append(",\n  \"environment\": {\n")
        out.append("    \"head\": ").append(SourceJson.array(head)).append(",\n")
        out.append("    \"effects\": [")
        for ((position, effect) in tiles.effects.withIndex()) {
            out.append(if (position == 0) "\n" else ",\n").append("      ")
            effect(out, effect)
        }
        if (tiles.effects.isNotEmpty()) {
            out.append("\n    ")
        }
        out.append("]\n  }")
    }

    private fun effect(out: StringBuilder, effect: MapSquareEffect) {
        when (effect) {
            is MapSquareEnvironment -> {
                out.append("{\"type\": \"settings\"")
                effect.sunColour?.let { out.append(", \"sunColour\": ").append(it) }
                effect.sunAmbient?.let { out.append(", \"sunAmbient\": ").append(it) }
                effect.sunLight?.let { out.append(", \"sunLight\": ").append(it) }
                effect.sunBacklight?.let { out.append(", \"sunBacklight\": ").append(it) }
                effect.sunPosition?.let { out.append(", \"sunPosition\": ").append(SourceJson.array(it)) }
                effect.fogColour?.let { out.append(", \"fogColour\": ").append(it) }
                effect.fogDepth?.let { out.append(", \"fogDepth\": ").append(it) }
                effect.unknown7?.let { out.append(", \"unknown7\": ").append(it) }
                out.append('}')
            }
            is MapSquareLights -> {
                out.append("{\"type\": \"lights\", \"lights\": [")
                for ((position, light) in effect.lights.withIndex()) {
                    out.append(if (position == 0) "\n" else ",\n").append("        ")
                    light(out, light)
                }
                if (effect.lights.isNotEmpty()) {
                    out.append("\n      ")
                }
                out.append("]}")
            }
            is MapSquareHdr -> {
                out.append("{\"type\": \"hdr\", \"bloom\": ")
                float(out, effect.bloom)
                out.append(", \"brightpass\": ")
                float(out, effect.brightpass)
                out.append(", \"whitePoint\": ")
                float(out, effect.whitePoint)
                out.append('}')
            }
            is MapSquareUnknown3 -> {
                out.append("{\"type\": \"unknown3\", \"unknown0\": ").append(effect.unknown0).append(", \"unknown1\": ")
                float(out, effect.unknown1)
                out.append('}')
            }
            is MapSquareSkybox -> {
                out.append("{\"type\": \"skybox\", \"id\": ").append(effect.id)
                out.append(", \"x\": ").append(effect.x)
                out.append(", \"y\": ").append(effect.y)
                out.append(", \"z\": ").append(effect.z)
                out.append(", \"rotation\": ").append(effect.rotation).append('}')
            }
            is MapSquareLightGrid -> {
                out.append("{\"type\": \"lightGrid\", \"levels\": [")
                for ((position, level) in effect.levels.withIndex()) {
                    out.append(if (position == 0) "\n" else ",\n").append("        {\"mode\": ").append(level.mode)
                    level.samples?.let { out.append(", \"samples\": ").append(bytes(it)) }
                    out.append('}')
                }
                if (effect.levels.isNotEmpty()) {
                    out.append("\n      ")
                }
                out.append("]}")
            }
            is MapSquareUnknown130 -> out.append("{\"type\": \"unknown130\"}")
        }
    }

    private fun light(out: StringBuilder, light: MapSquareLight) {
        out.append("{\"packedLevel\": ").append(light.packedLevel)
        out.append(", \"x\": ").append(light.x)
        out.append(", \"z\": ").append(light.z)
        out.append(", \"heightOffset\": ").append(light.heightOffset)
        out.append(", \"packedRadius\": ").append(light.packedRadius)
        out.append(", \"ranges\": ").append(SourceJson.array(light.ranges))
        out.append(", \"colour\": ").append(light.colour)
        out.append(", \"packedType\": ").append(light.packedType)
        out.append(", \"unknown8\": ").append(light.unknown8)
        if (light.lightType != -1) {
            out.append(", \"lightType\": ").append(light.lightType)
        }
        out.append('}')
    }

    private fun transform(out: StringBuilder, transform: MapSquareTransform) {
        out.append('{')
        var written = false
        transform.rotation?.let {
            out.append("\"rotation\": ").append(SourceJson.array(it))
            written = true
        }
        written = field(out, written, "translateX", transform.translateX)
        written = field(out, written, "translateY", transform.translateY)
        written = field(out, written, "translateZ", transform.translateZ)
        written = field(out, written, "scale", transform.scale)
        written = field(out, written, "scaleX", transform.scaleX)
        written = field(out, written, "scaleY", transform.scaleY)
        field(out, written, "scaleZ", transform.scaleZ)
        out.append('}')
    }

    private fun field(out: StringBuilder, written: Boolean, name: String, value: Int?): Boolean {
        if (value == null) {
            return written
        }
        if (written) {
            out.append(", ")
        }
        out.append(SourceJson.quote(name)).append(": ").append(value)
        return true
    }

    private fun readTransform(root: JsonObject): MapSquareTransform = MapSquareTransform(
        rotation = ints(root["rotation"]?.jsonArray),
        translateX = root["translateX"]?.jsonPrimitive?.int,
        translateY = root["translateY"]?.jsonPrimitive?.int,
        translateZ = root["translateZ"]?.jsonPrimitive?.int,
        scale = root["scale"]?.jsonPrimitive?.int,
        scaleX = root["scaleX"]?.jsonPrimitive?.int,
        scaleY = root["scaleY"]?.jsonPrimitive?.int,
        scaleZ = root["scaleZ"]?.jsonPrimitive?.int
    )

    private fun readEffect(root: JsonObject): MapSquareEffect = when (val type = root.getValue("type").jsonPrimitive.content) {
        "settings" -> MapSquareEnvironment(
            sunColour = root["sunColour"]?.jsonPrimitive?.int,
            sunAmbient = root["sunAmbient"]?.jsonPrimitive?.int,
            sunLight = root["sunLight"]?.jsonPrimitive?.int,
            sunBacklight = root["sunBacklight"]?.jsonPrimitive?.int,
            sunPosition = ints(root["sunPosition"]?.jsonArray),
            fogColour = root["fogColour"]?.jsonPrimitive?.int,
            fogDepth = root["fogDepth"]?.jsonPrimitive?.int,
            unknown7 = root["unknown7"]?.jsonPrimitive?.int
        )
        "lights" -> MapSquareLights(
            root.getValue("lights").jsonArray.mapTo(ArrayList()) { element ->
                val light = element.jsonObject
                MapSquareLight(
                    packedLevel = light.getValue("packedLevel").jsonPrimitive.int,
                    x = light.getValue("x").jsonPrimitive.int,
                    z = light.getValue("z").jsonPrimitive.int,
                    heightOffset = light.getValue("heightOffset").jsonPrimitive.int,
                    packedRadius = light.getValue("packedRadius").jsonPrimitive.int,
                    ranges = ints(light["ranges"]?.jsonArray) ?: IntArray(0),
                    colour = light.getValue("colour").jsonPrimitive.int,
                    packedType = light.getValue("packedType").jsonPrimitive.int,
                    unknown8 = light.getValue("unknown8").jsonPrimitive.int,
                    lightType = light["lightType"]?.jsonPrimitive?.int ?: -1
                )
            }
        )
        "hdr" -> MapSquareHdr(
            float(root.getValue("bloom")),
            float(root.getValue("brightpass")),
            float(root.getValue("whitePoint"))
        )
        "unknown3" -> MapSquareUnknown3(root.getValue("unknown0").jsonPrimitive.int, float(root.getValue("unknown1")))
        "skybox" -> MapSquareSkybox(
            id = root.getValue("id").jsonPrimitive.int,
            x = root.getValue("x").jsonPrimitive.int,
            y = root.getValue("y").jsonPrimitive.int,
            z = root.getValue("z").jsonPrimitive.int,
            rotation = root.getValue("rotation").jsonPrimitive.int
        )
        "lightGrid" -> MapSquareLightGrid(
            root.getValue("levels").jsonArray.mapTo(ArrayList()) { element ->
                val level = element.jsonObject
                MapSquareLightGridLevel(
                    mode = level.getValue("mode").jsonPrimitive.int,
                    samples = level["samples"]?.jsonArray?.let { samples ->
                        ByteArray(samples.size) { samples[it].jsonPrimitive.int.toByte() }
                    }
                )
            }
        )
        "unknown130" -> MapSquareUnknown130()
        else -> throw IllegalArgumentException("Unknown map effect type '$type'.")
    }

    /**
     * A float as the shortest decimal that reads back to the same bits, or as its name when it has
     * no decimal form JSON can hold.
     */
    private fun float(out: StringBuilder, value: Float) {
        out.append(float(value))
    }

    private fun float(element: JsonElement): Float {
        val primitive = element.jsonPrimitive
        return if (primitive.isString) primitive.content.toFloat() else primitive.float
    }

    private fun bytes(values: ByteArray): String = values.joinToString(", ", "[", "]") { it.toString() }

    private fun ints(values: JsonArray?): IntArray? {
        if (values == null) {
            return null
        }
        return IntArray(values.size) { values[it].jsonPrimitive.int }
    }

    private fun parse(bytes: ByteArray): JsonObject =
        json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject

    private const val OVERLAY = 'o'
    private const val SETTINGS = 's'
    private const val UNDERLAY = 'u'
    private const val HEIGHT = 'h'
    private const val EMPTY = "-"

    private const val SECOND_HEIGHT = 'H'
    private const val COLOUR = 'c'
    private const val SECOND_OVERLAY = 'O'
    private const val SHAPE = 'p'
    private const val SECOND_UNDERLAY = 'U'

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
}
