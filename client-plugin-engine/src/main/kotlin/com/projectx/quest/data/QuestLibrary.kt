package com.projectx.quest.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import io.github.classgraph.ClassGraph
import org.projectx.core.ProjectPaths
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.lang.reflect.Type
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object QuestLibrary {
    private val gson by lazy {
        GsonBuilder()
            .registerTypeAdapter(QuestAction::class.java, QuestActionDeserializer())
            .registerTypeAdapter(QuestCondition::class.java, QuestConditionDeserializer())
            .registerTypeAdapter(QuestAction::class.java, QuestActionSerializer())
            .registerTypeAdapter(QuestCondition::class.java, QuestConditionSerializer())
            .setPrettyPrinting()
            .create()
    }

    /** Classpath location of the per-quest JSON files bundled into the injected jar. */
    private const val QUEST_DIR_RESOURCE = "quest-data/quests"

    private val BUNDLE_CANDIDATES: List<String> = listOfNotNull(
        System.getenv("PROJECTX_QUEST_DATA"),
        ProjectPaths.root?.let { File(it, "client-plugin-engine/src/main/resources/quest-data/quests.json").path },
    )

    private val PER_QUEST_DIR_CANDIDATES: List<String> = listOfNotNull(
        System.getenv("PROJECTX_QUEST_DIR"),
        ProjectPaths.root?.let { File(it, "client-plugin-engine/src/main/resources/quest-data/quests").path },
        File(ProjectPaths.home, "quest-data/quests").path,
    )

    @Volatile var lastLoadMs: Long = System.currentTimeMillis()
        private set
    @Volatile var lastLoadSource: String = "classpath"
        private set
    @Volatile var lastSavedPath: String? = null
        private set

    @Volatile private var cached: List<Quest> = emptyList()
    @Volatile private var bySlug: Map<String, Quest> = emptyMap()
    @Volatile private var byName: Map<String, Quest> = emptyMap()

    private val warmStarted = AtomicBoolean(false)

    /** True while the background load is in flight, so callers can render a placeholder. */
    @Volatile var loading: Boolean = false
        private set

    val quests: List<Quest> get() = cached.also { warm() }

    fun bySlug(slug: String): Quest? = bySlug[slug].also { warm() }
    fun byName(name: String): Quest? = byName[name.lowercase()].also { warm() }

    /**
     * Parses the library on a background thread, once. Deliberately NOT done in this object's
     * initializer: a reader that arrives mid-load would then block on the class-init monitor —
     * the very stall this avoids — instead of seeing an empty list for a frame or two.
     */
    fun warm() {
        if (warmStarted.get() || !warmStarted.compareAndSet(false, true)) return
        loading = true
        thread(isDaemon = true, name = "projectx-quest-load") {
            try {
                reload()
            } finally {
                loading = false
            }
        }
    }

    /**
     * Re-reads quests from disk. Per-quest JSON files in `quest-data/quests/`
     * take precedence over entries in the legacy bundle `quests.json`.
     */
    @Synchronized
    fun reload(): Int {
        cached = load()
        bySlug = cached.associateBy { it.slug }
        byName = cached.associateBy { it.name.lowercase() }
        lastLoadMs = System.currentTimeMillis()
        return cached.size
    }

    /**
     * Returns the writable per-quest file path for [slug] — picks the first
     * existing per-quest directory candidate, or creates the first writable
     * one. Used by the editor to persist edits.
     */
    fun perQuestFile(slug: String): File {
        val existingDir = PER_QUEST_DIR_CANDIDATES.map { File(it) }.firstOrNull { it.isDirectory }
        val dir = existingDir ?: PER_QUEST_DIR_CANDIDATES.map { File(it) }
            .first { runCatching { it.mkdirs() }.isSuccess && it.isDirectory }
        return File(dir, "$slug.json")
    }

    /**
     * Writes [quest] to its per-quest JSON file and reloads the library.
     * Returns the file written, or null if write failed.
     */
    @Synchronized
    fun saveQuest(quest: Quest): File? {
        return try {
            val f = perQuestFile(quest.slug)
            f.parentFile?.mkdirs()
            f.writeText(gson.toJson(quest))
            lastSavedPath = f.absolutePath
            reload()
            f
        } catch (t: Throwable) {
            println("[QuestLibrary] saveQuest(${quest.slug}) failed: ${t.message}")
            t.printStackTrace()
            null
        }
    }

    /**
     * One-time helper: writes every currently-loaded quest to its own file in
     * the per-quest directory, suitable for committing to git. Skips quests
     * whose per-quest file already exists. Returns the number of files written.
     */
    @Synchronized
    fun splitBundleToFiles(overwrite: Boolean = false): Int {
        var written = 0
        for (q in cached) {
            val f = perQuestFile(q.slug)
            if (!overwrite && f.exists()) continue
            try {
                f.parentFile?.mkdirs()
                f.writeText(gson.toJson(q))
                written++
            } catch (t: Throwable) {
                println("[QuestLibrary] split failed for ${q.slug}: ${t.message}")
            }
        }
        if (written > 0) reload()
        return written
    }

    private fun load(): List<Quest> {
        val combined = LinkedHashMap<String, Quest>()
        val sources = mutableListOf<String>()

        // Bundle first (baseline)
        val (bundleStream, bundleSource) = openBundleStream()
        if (bundleStream != null) {
            runCatching {
                val reader = bundleStream.bufferedReader()
                val bundle: JsonObject = gson.fromJson(reader, JsonObject::class.java)
                val arr = bundle.getAsJsonArray("quests")
                if (arr != null) {
                    arr.mapNotNull { gson.fromJson<Quest>(it, Quest::class.java) }
                        .forEach { combined[it.slug] = it }
                    sources += bundleSource
                }
            }.onFailure { println("[QuestLibrary] Failed to load bundle from $bundleSource: ${it.message}") }
        }

        // The per-quest directory is the one editable copy, so when it resolves it is the sole
        // source: scanning the classpath too would parse the very files it was built from, and
        // every entry that produced was then overwritten by slug below.
        val perQuestDir = PER_QUEST_DIR_CANDIDATES.map { File(it) }.firstOrNull { it.isDirectory }
        if (perQuestDir == null) {
            val classpathCount = loadClasspathQuests(combined)
            if (classpathCount > 0) sources += "classpath:/$QUEST_DIR_RESOURCE ($classpathCount quests)"
        } else {
            val questFiles = perQuestDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
            var loaded = 0
            for (f in questFiles) {
                runCatching {
                    val q: Quest = gson.fromJson(f.bufferedReader(), Quest::class.java)
                    combined[q.slug] = q
                    loaded++
                }.onFailure { println("[QuestLibrary] Skipped ${f.name}: ${it.message}") }
            }
            if (loaded > 0) sources += "${perQuestDir.absolutePath} ($loaded quests)"
        }

        lastLoadSource = if (sources.isEmpty()) "(missing)" else sources.joinToString("; ")
        if (combined.isEmpty()) {
            println("[QuestLibrary] WARN: no quest data found in bundle or per-quest dir.")
        }
        return combined.values.sortedBy { it.name.lowercase() }
    }

    private fun openBundleStream(): Pair<InputStream?, String> {
        for (path in BUNDLE_CANDIDATES) {
            val f = File(path)
            if (f.isFile && f.canRead()) return FileInputStream(f) to path
        }
        return QuestLibrary::class.java.getResourceAsStream("/quest-data/quests.json") to "classpath"
    }

    private fun loadClasspathQuests(combined: MutableMap<String, Quest>): Int {
        var count = 0
        runCatching {
            ClassGraph().acceptPaths(QUEST_DIR_RESOURCE).scan().use { scan ->
                for (res in scan.getResourcesWithExtension("json")) {
                    runCatching {
                        val q: Quest = gson.fromJson(res.contentAsString, Quest::class.java)
                        combined[q.slug] = q
                        count++
                    }.onFailure { println("[QuestLibrary] Skipped classpath quest ${res.path}: ${it.message}") }
                }
            }
        }.onFailure { println("[QuestLibrary] classpath quest scan failed: ${it.message}") }
        return count
    }
}

private class QuestActionDeserializer : JsonDeserializer<QuestAction> {
    override fun deserialize(json: JsonElement, typeOfT: Type, ctx: JsonDeserializationContext): QuestAction {
        val o = json.asJsonObject
        return when (o.get("t").asString) {
            "direction" -> {
                val x = o.dbl("x")
                val y = o.dbl("y")
                val explicit = o.get("instance")?.takeIf { !it.isJsonNull }?.asBoolean
                val inferred = (kotlin.math.abs(x) < 250.0 && kotlin.math.abs(y) < 250.0)
                QuestAction.Direction(
                    x = x,
                    heightFine = o.dbl("heightFine"),
                    y = y,
                    instance = explicit ?: inferred,
                    tile = o.boolOpt("tile"),
                    distance = o.intOpt("distance", -1),
                )
            }
            "modelHighlight" -> QuestAction.ModelHighlight(
                kind = o.get("kind").asString,
                typeId = o.intOpt("typeId", o.intOpt("id", -1)),
                modelIds = o.intList("modelIds"),
                candidateNpcTypeIds = o.intList("candidateNpcTypeIds"),
                candidateObjectTypeIds = o.intList("candidateObjectTypeIds"),
                displayName = o.get("displayName").asString,
                priority = o.get("priority")?.takeIf { !it.isJsonNull }?.asString,
                instance = o.boolOpt("instance"),
                distance = o.intOpt("distance", -1),
                atLocation = o.locationOpt("atLocation"),
            )
            "conversationHighlight" -> QuestAction.ConversationHighlight(o.get("text").asString)
            "inventoryHighlight" -> QuestAction.InventoryHighlight(
                itemId = o.get("itemId").asInt,
                modelIds = o.intList("modelIds"),
                candidateItemTypeIds = o.intList("candidateItemTypeIds"),
                displayName = o.get("displayName").asString,
            )
            "interfaceComponentHighlight" -> QuestAction.InterfaceComponentHighlight(
                interfaceId = o.get("interfaceId").asInt,
                componentId = o.get("componentId").asInt,
                slotId = o.intOpt("slotId", -1),
                label = o.get("label")?.takeIf { !it.isJsonNull }?.asString ?: "",
                color = o.get("color")?.takeIf { !it.isJsonNull }?.asInt,
            )
            "continueConversation" -> QuestAction.ContinueConversation
            "resetInstance" -> QuestAction.ResetInstance
            "pathGuide" -> {
                val arr = o.getAsJsonArray("waypoints") ?: return QuestAction.PathGuide(emptyList())
                val pts = arr.map {
                    val wp = it.asJsonObject
                    val x = wp.get("x").asDouble
                    val y = wp.get("y").asDouble
                    val h = wp.get("heightFine")?.takeIf { e -> !e.isJsonNull }?.asDouble ?: 0.0
                    QuestAction.PathGuide.Waypoint(x, h, y)
                }
                val explicit = o.get("instance")?.takeIf { !it.isJsonNull }?.asBoolean
                val inferred = pts.isNotEmpty() && pts.all {
                    kotlin.math.abs(it.x) < 250.0 && kotlin.math.abs(it.y) < 250.0
                }
                QuestAction.PathGuide(pts, instance = explicit ?: inferred)
            }
            "textHint" -> QuestAction.TextHint(o.get("text").asString)
            "unknown" -> QuestAction.Unknown(o.get("name").asString)
            else -> QuestAction.Unknown(o.get("t").asString)
        }
    }
}

private class QuestConditionDeserializer : JsonDeserializer<QuestCondition> {
    override fun deserialize(json: JsonElement, typeOfT: Type, ctx: JsonDeserializationContext): QuestCondition {
        val o = json.asJsonObject
        return when (o.get("t").asString) {
            "distanceTo" -> QuestCondition.DistanceTo(o.dbl("x"), o.dbl("heightFine"), o.dbl("y"), o.get("range").asInt, o.boolOpt("instance"))
            "distanceFrom" -> QuestCondition.DistanceFrom(o.dbl("x"), o.dbl("heightFine"), o.dbl("y"), o.get("range").asInt, o.boolOpt("instance"))
            "distanceToWithHeight" -> QuestCondition.DistanceToWithHeight(
                o.dbl("x"), o.dbl("heightFine"), o.dbl("y"),
                (o.get("range") ?: o.get("plane"))?.asInt ?: 1,
                o.boolOpt("instance"),
            )
            "distanceFromWithHeight" -> QuestCondition.DistanceFromWithHeight(
                o.dbl("x"), o.dbl("heightFine"), o.dbl("y"), o.get("range").asInt, o.boolOpt("instance"),
            )
            "notInInstance" -> QuestCondition.NotInInstance
            "changedInstance" -> QuestCondition.ChangedInstance
            "manual" -> QuestCondition.Manual
            "always" -> QuestCondition.Always
            "captureConversationState" -> QuestCondition.CaptureConversationState(o.get("pattern").asString, o.get("key").asString)
            "stateEquals" -> QuestCondition.StateEquals(o.get("key").asString, o.get("value").asString)
            "inventoryContains" -> QuestCondition.InventoryContains(
                itemId = o.get("itemId").asInt,
                displayName = o.get("displayName").asString,
                quantity = o.get("quantity").asInt,
                candidateItemTypeIds = o.intList("candidateItemTypeIds"),
            )
            "inventoryDoesNotContain" -> QuestCondition.InventoryDoesNotContain(
                itemId = o.get("itemId").asInt,
                displayName = o.get("displayName").asString,
                candidateItemTypeIds = o.intList("candidateItemTypeIds"),
            )
            "modelVisible" -> QuestCondition.ModelVisible(
                kind = o.get("kind").asString,
                typeId = o.intOpt("typeId", o.intOpt("id", -1)),
                modelIds = o.intList("modelIds"),
                candidateNpcTypeIds = o.intList("candidateNpcTypeIds"),
                candidateObjectTypeIds = o.intList("candidateObjectTypeIds"),
                displayName = o.get("displayName").asString,
                animated = o.boolOpt("animated"),
                instance = o.boolOpt("instance"),
                quantity = o.intOpt("quantity", -1),
                atLocation = o.locationOpt("atLocation"),
            )
            "modelNotVisible" -> QuestCondition.ModelNotVisible(
                kind = o.get("kind").asString,
                typeId = o.intOpt("typeId", o.intOpt("id", -1)),
                modelIds = o.intList("modelIds"),
                candidateNpcTypeIds = o.intList("candidateNpcTypeIds"),
                candidateObjectTypeIds = o.intList("candidateObjectTypeIds"),
                displayName = o.get("displayName").asString,
                instance = o.boolOpt("instance"),
                atLocation = o.locationOpt("atLocation"),
            )
            "questStarted" -> QuestCondition.QuestStarted
            "questComplete" -> QuestCondition.QuestComplete
            "questInterfaceOpen" -> QuestCondition.QuestInterfaceOpen
            "interfaceOpen" -> QuestCondition.InterfaceOpen(o.intOpt("interfaceId", -1))
            "npcNearTile" -> QuestCondition.NpcNearTile(
                typeId = o.intOpt("typeId", -1),
                displayName = o.get("displayName")?.takeIf { !it.isJsonNull }?.asString ?: "",
                tileX = o.intOpt("tileX", 0),
                tileY = o.intOpt("tileY", 0),
                plane = o.intOpt("plane", 0),
                distance = o.intOpt("distance", 2),
                instance = o.boolOpt("instance"),
                candidateNpcTypeIds = o.intList("candidateNpcTypeIds"),
            )
            "conversationActive" -> QuestCondition.ConversationActive
            "conversationInactive" -> QuestCondition.ConversationInactive
            "conversationText" -> QuestCondition.ConversationText(o.get("text").asString)
            "chatText" -> QuestCondition.ChatText(o.get("text").asString)
            "inInstance" -> QuestCondition.InInstance
            "inCombatWith" -> QuestCondition.InCombatWith(
                npcId = o.get("npcId").asInt,
                displayName = o.get("displayName")?.takeIf { !it.isJsonNull }?.asString ?: "",
            )
            "itemClicked" -> QuestCondition.ItemClicked(o.get("itemId").asInt)
            "generic" -> QuestCondition.Generic
            "unknown" -> QuestCondition.Unknown(o.get("name").asString)
            else -> QuestCondition.Unknown(o.get("t").asString)
        }
    }
}

private class QuestActionSerializer : JsonSerializer<QuestAction> {
    override fun serialize(src: QuestAction, type: Type, ctx: JsonSerializationContext): JsonElement {
        val o = JsonObject()
        when (src) {
            is QuestAction.Direction -> {
                o.addProperty("t", "direction")
                o.addProperty("x", src.x)
                o.addProperty("heightFine", src.heightFine)
                o.addProperty("y", src.y)
                o.addProperty("instance", src.instance)
                if (src.tile) o.addProperty("tile", true)
                if (src.distance >= 0) o.addProperty("distance", src.distance)
            }
            is QuestAction.ModelHighlight -> {
                o.addProperty("t", "modelHighlight")
                o.addProperty("kind", src.kind)
                if (src.typeId >= 0) o.addProperty("typeId", src.typeId)
                o.add("modelIds", ctx.serialize(src.modelIds))
                if (src.candidateNpcTypeIds.isNotEmpty()) o.add("candidateNpcTypeIds", ctx.serialize(src.candidateNpcTypeIds))
                if (src.candidateObjectTypeIds.isNotEmpty()) o.add("candidateObjectTypeIds", ctx.serialize(src.candidateObjectTypeIds))
                o.addProperty("displayName", src.displayName)
                src.priority?.let { o.addProperty("priority", it) }
                if (src.instance) o.addProperty("instance", true)
                if (src.distance >= 0) o.addProperty("distance", src.distance)
                src.atLocation?.let { o.add("atLocation", locationJson(it)) }
            }
            is QuestAction.ConversationHighlight -> {
                o.addProperty("t", "conversationHighlight")
                o.addProperty("text", src.text)
            }
            is QuestAction.InventoryHighlight -> {
                o.addProperty("t", "inventoryHighlight")
                o.addProperty("itemId", src.itemId)
                o.add("modelIds", ctx.serialize(src.modelIds))
                if (src.candidateItemTypeIds.isNotEmpty()) o.add("candidateItemTypeIds", ctx.serialize(src.candidateItemTypeIds))
                o.addProperty("displayName", src.displayName)
            }
            is QuestAction.InterfaceComponentHighlight -> {
                o.addProperty("t", "interfaceComponentHighlight")
                o.addProperty("interfaceId", src.interfaceId)
                o.addProperty("componentId", src.componentId)
                if (src.slotId >= 0) o.addProperty("slotId", src.slotId)
                if (src.label.isNotEmpty()) o.addProperty("label", src.label)
                src.color?.let { o.addProperty("color", it) }
            }
            QuestAction.ContinueConversation -> o.addProperty("t", "continueConversation")
            QuestAction.ResetInstance -> o.addProperty("t", "resetInstance")
            is QuestAction.PathGuide -> {
                o.addProperty("t", "pathGuide")
                val arr = com.google.gson.JsonArray()
                src.waypoints.forEach { wp ->
                    val w = JsonObject()
                    w.addProperty("x", wp.x)
                    w.addProperty("heightFine", wp.heightFine)
                    w.addProperty("y", wp.y)
                    arr.add(w)
                }
                o.add("waypoints", arr)
                if (src.instance) o.addProperty("instance", true)
            }
            is QuestAction.TextHint -> {
                o.addProperty("t", "textHint")
                o.addProperty("text", src.text)
            }
            is QuestAction.Unknown -> {
                o.addProperty("t", "unknown")
                o.addProperty("name", src.name)
            }
        }
        return o
    }
}

private class QuestConditionSerializer : JsonSerializer<QuestCondition> {
    override fun serialize(src: QuestCondition, type: Type, ctx: JsonSerializationContext): JsonElement {
        val o = JsonObject()
        when (src) {
            is QuestCondition.DistanceTo -> { o.addProperty("t", "distanceTo"); distanceFields(o, src.x, src.heightFine, src.y, src.range, src.instance) }
            is QuestCondition.DistanceFrom -> { o.addProperty("t", "distanceFrom"); distanceFields(o, src.x, src.heightFine, src.y, src.range, src.instance) }
            is QuestCondition.DistanceToWithHeight -> { o.addProperty("t", "distanceToWithHeight"); distanceFields(o, src.x, src.heightFine, src.y, src.range, src.instance) }
            is QuestCondition.DistanceFromWithHeight -> { o.addProperty("t", "distanceFromWithHeight"); distanceFields(o, src.x, src.heightFine, src.y, src.range, src.instance) }
            QuestCondition.NotInInstance -> o.addProperty("t", "notInInstance")
            QuestCondition.ChangedInstance -> o.addProperty("t", "changedInstance")
            QuestCondition.Manual -> o.addProperty("t", "manual")
            QuestCondition.Always -> o.addProperty("t", "always")
            is QuestCondition.CaptureConversationState -> {
                o.addProperty("t", "captureConversationState")
                o.addProperty("pattern", src.pattern); o.addProperty("key", src.key)
            }
            is QuestCondition.StateEquals -> {
                o.addProperty("t", "stateEquals")
                o.addProperty("key", src.key); o.addProperty("value", src.value)
            }
            is QuestCondition.InventoryContains -> {
                o.addProperty("t", "inventoryContains")
                o.addProperty("itemId", src.itemId)
                o.addProperty("displayName", src.displayName)
                o.addProperty("quantity", src.quantity)
                if (src.candidateItemTypeIds.isNotEmpty()) o.add("candidateItemTypeIds", ctx.serialize(src.candidateItemTypeIds))
            }
            is QuestCondition.InventoryDoesNotContain -> {
                o.addProperty("t", "inventoryDoesNotContain")
                o.addProperty("itemId", src.itemId)
                o.addProperty("displayName", src.displayName)
                if (src.candidateItemTypeIds.isNotEmpty()) o.add("candidateItemTypeIds", ctx.serialize(src.candidateItemTypeIds))
            }
            is QuestCondition.ModelVisible -> {
                o.addProperty("t", "modelVisible")
                modelTargetFields(o, src.kind, src.typeId, src.modelIds, src.candidateNpcTypeIds, src.candidateObjectTypeIds, src.displayName, src.instance, src.atLocation, ctx)
                if (src.animated) o.addProperty("animated", true)
                if (src.quantity > 0) o.addProperty("quantity", src.quantity)
            }
            is QuestCondition.ModelNotVisible -> {
                o.addProperty("t", "modelNotVisible")
                modelTargetFields(o, src.kind, src.typeId, src.modelIds, src.candidateNpcTypeIds, src.candidateObjectTypeIds, src.displayName, src.instance, src.atLocation, ctx)
            }
            QuestCondition.QuestStarted -> o.addProperty("t", "questStarted")
            QuestCondition.QuestComplete -> o.addProperty("t", "questComplete")
            QuestCondition.QuestInterfaceOpen -> o.addProperty("t", "questInterfaceOpen")
            is QuestCondition.InterfaceOpen -> { o.addProperty("t", "interfaceOpen"); o.addProperty("interfaceId", src.interfaceId) }
            is QuestCondition.NpcNearTile -> {
                o.addProperty("t", "npcNearTile")
                o.addProperty("typeId", src.typeId)
                o.addProperty("displayName", src.displayName)
                o.addProperty("tileX", src.tileX)
                o.addProperty("tileY", src.tileY)
                o.addProperty("plane", src.plane)
                o.addProperty("distance", src.distance)
                if (src.instance) o.addProperty("instance", true)
                if (src.candidateNpcTypeIds.isNotEmpty()) o.add("candidateNpcTypeIds", ctx.serialize(src.candidateNpcTypeIds))
            }
            QuestCondition.ConversationActive -> o.addProperty("t", "conversationActive")
            QuestCondition.ConversationInactive -> o.addProperty("t", "conversationInactive")
            is QuestCondition.ConversationText -> { o.addProperty("t", "conversationText"); o.addProperty("text", src.text) }
            is QuestCondition.ChatText -> { o.addProperty("t", "chatText"); o.addProperty("text", src.text) }
            QuestCondition.InInstance -> o.addProperty("t", "inInstance")
            is QuestCondition.InCombatWith -> {
                o.addProperty("t", "inCombatWith"); o.addProperty("npcId", src.npcId); o.addProperty("displayName", src.displayName)
            }
            is QuestCondition.ItemClicked -> { o.addProperty("t", "itemClicked"); o.addProperty("itemId", src.itemId) }
            QuestCondition.Generic -> o.addProperty("t", "generic")
            is QuestCondition.Unknown -> { o.addProperty("t", "unknown"); o.addProperty("name", src.name) }
        }
        return o
    }

    private fun distanceFields(o: JsonObject, x: Double, h: Double, y: Double, range: Int, instance: Boolean) {
        o.addProperty("x", x); o.addProperty("heightFine", h); o.addProperty("y", y)
        o.addProperty("range", range)
        if (instance) o.addProperty("instance", true)
    }

    private fun modelTargetFields(
        o: JsonObject,
        kind: String,
        typeId: Int,
        modelIds: List<Int>,
        candidateNpcTypeIds: List<Int>,
        candidateObjectTypeIds: List<Int>,
        displayName: String,
        instance: Boolean,
        atLocation: WorldLocation?,
        ctx: JsonSerializationContext,
    ) {
        o.addProperty("kind", kind)
        if (typeId >= 0) o.addProperty("typeId", typeId)
        o.add("modelIds", ctx.serialize(modelIds))
        if (candidateNpcTypeIds.isNotEmpty()) o.add("candidateNpcTypeIds", ctx.serialize(candidateNpcTypeIds))
        if (candidateObjectTypeIds.isNotEmpty()) o.add("candidateObjectTypeIds", ctx.serialize(candidateObjectTypeIds))
        o.addProperty("displayName", displayName)
        if (instance) o.addProperty("instance", true)
        atLocation?.let { o.add("atLocation", locationJson(it)) }
    }
}

private fun locationJson(loc: WorldLocation): JsonObject {
    val o = JsonObject()
    o.addProperty("x", loc.x); o.addProperty("heightFine", loc.heightFine); o.addProperty("y", loc.y)
    return o
}

private fun JsonObject.dbl(key: String): Double = this.get(key).asDouble
private fun JsonObject.boolOpt(key: String): Boolean =
    this.get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: false
private fun JsonObject.intOpt(key: String, default: Int): Int =
    this.get(key)?.takeIf { !it.isJsonNull }?.asInt ?: default
private fun JsonObject.intList(key: String): List<Int> {
    val arr = this.getAsJsonArray(key) ?: return emptyList()
    return arr.map { it.asInt }
}
private fun JsonObject.locationOpt(key: String): WorldLocation? {
    val obj = this.get(key)?.takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject ?: return null
    val x = obj.get("x")?.asDouble ?: return null
    val h = obj.get("heightFine")?.asDouble ?: 0.0
    val y = obj.get("y")?.asDouble ?: return null
    return WorldLocation(x, h, y)
}
