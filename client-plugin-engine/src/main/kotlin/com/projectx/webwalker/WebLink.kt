package com.projectx.webwalker

import com.projectx.script.api.Lodestone
import world.gregs.voidps.type.Tile

/** What a route has to do to cross a [WebLink], which is what tells the walker how to perform it. */
enum class WebLinkKind {
    /** Click a scene object: stairs, a ladder, a shortcut, a cave entrance. */
    OBJECT,

    /** Open a door and step through it. */
    DOOR,

    /** Use an NPC: a ferryman, a charter crew, a carpet seller. */
    NPC,

    /** Use an item from the backpack on a scene object. */
    USE_ON,

    /** An item's own teleport, from the backpack or worn. Usable from anywhere. */
    ITEM,

    /** A button on an interface, such as a spell. Usable from anywhere. */
    INTERFACE,

    /** The Passage of the Abyss: its teleport, then the jewellery, then the place. Usable from anywhere. */
    POA,

    /** A lodestone teleport. Usable from anywhere. */
    LODESTONE,

    /** At a fairy ring, dial another ring's code. */
    FAIRY_RING,
    ;

    /** True for the kinds a route can take from wherever it starts, rather than from a place in the world. */
    val isGlobal: Boolean get() = this == ITEM || this == INTERFACE || this == POA || this == LODESTONE
}

/** A rectangle of tiles on one plane. Link endpoints are areas because a staircase lands you anywhere in a room. */
data class WebArea(
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
    val plane: Int,
) {
    val centreX: Int get() = (minX + maxX) / 2
    val centreY: Int get() = (minY + maxY) / 2

    fun contains(x: Int, y: Int, plane: Int): Boolean =
        plane == this.plane && x in minX..maxX && y in minY..maxY

    /** Every tile in the rectangle, nearest the centre first, so a resolver tries the middle of a room before a corner. */
    fun tilesFromCentre(): List<Tile> {
        val tiles = ArrayList<Tile>((maxX - minX + 1) * (maxY - minY + 1))
        for (x in minX..maxX) for (y in minY..maxY) tiles += Tile.of(x, y, plane)
        return tiles.sortedBy { maxOf(Math.abs(it.x - centreX), Math.abs(it.y - centreY)) }
    }

    override fun toString(): String =
        if (minX == maxX && minY == maxY) "$minX,$minY,$plane" else "$minX..$maxX,$minY..$maxY,$plane"
}

/**
 * A condition on using a link: a varbit or varp value, a skill level, or coins in the pouch.
 *
 * [key] is `varbit_<id>`, `varp_<id>`, `<skill>Level`, `coins`, or an opaque flag the converter carried over;
 * anything this engine cannot evaluate is treated as met, so an unrecognised gate costs a re-plan rather than
 * making a destination unreachable. See [WebLinkPermissions].
 */
data class WebRequirement(
    val about: String?,
    val key: String,
    val value: String,
    val comparison: String,
) {
    override fun toString(): String = "$key $comparison $value" + (about?.let { " ($it)" } ?: "")
}

/** Something done partway through a link, after its first action: a click on an interface, or another object. */
sealed interface WebStep

/**
 * One click on an interface, as part of getting through a link.
 *
 * Some ways through are not a click and a wait: the dig sites map opens a panel of destinations and goes
 * nowhere until one is picked, and which one decides where you come out. A dialogue's answer is text, so
 * [WebLink.choosing] can carry it, but a panel's is a component - an icon in a grid - and only its numbers
 * identify it. So those are carried literally.
 */
data class WebInterfaceStep(
    val interfaceId: Int,
    val componentId: Int,
    /** The slot within the component, or -1 when the component is clicked whole. */
    val slot: Int = -1,
    /**
     * Which of the component's options to fire, counting from one - or zero to continue a dialogue.
     *
     * The distinction is real rather than cosmetic: most of these steps land on a conversation, which is
     * advanced by its own opcode and not by picking an option off a component. Upstream records that as
     * click id zero, and firing it as option zero instead would click nothing.
     */
    val option: Int = 1,
) : WebStep {
    override fun toString(): String =
        "if($interfaceId,$componentId" + (if (slot >= 0) ",$slot" else "") + (if (option != 1) " op$option" else "") + ")"
}

/** Using another scene object partway through a link, as a trapdoor climbed down once it has been picked open. */
data class WebObjectStep(val objectId: Int, val action: String, val searchRadius: Int) : WebStep {
    override fun toString(): String = "$action $objectId"
}

/** The NPC or item a link uses: matched by [id], or when [id] is -1 by a name starting with [name], ignoring case. */
data class WebTarget(val id: Int, val name: String? = null) {
    fun matches(id: Int, name: String?): Boolean =
        if (this.id >= 0) id == this.id else this.name != null && name != null && name.startsWith(this.name, ignoreCase = true)

    override fun toString(): String = if (id >= 0) "$id" else "\"$name\""
}

/**
 * A step a route can take that walking cannot: up a staircase, down a ladder, through a shortcut or a door.
 *
 * Walk edges come from collision, so the pathfinder discovers them itself. A link cannot be derived that way -
 * nothing in the cache says where a staircase comes out - so links come from [WebLinks], which loads the curated
 * set converted from navpathService.
 *
 * [from] is where the player must stand and [to] is where they end up; the two may be on different planes, which
 * is the point. [cost] is in the pathfinder's own units, so it compares directly with walked tiles.
 */
data class WebLink(
    val kind: WebLinkKind,
    val from: WebArea,
    val to: WebArea,
    val cost: Int,
    /** The option to click, e.g. "Climb-up" or "Open". */
    val action: String,
    /** The scene object to click it on; the walker looks for it within [searchRadius] of the player. */
    val objectId: Int,
    val searchRadius: Int,
    val requirements: List<WebRequirement>,
) {
    /**
     * The destination to pick when using this link puts up a choice of where to go, or null when it does not.
     *
     * Kharid-et's fort entrance is the reason: clicking it opens "Choose destination." and the player stays
     * outside until that is answered, so a link through it has to carry the answer as well as the click.
     *
     * Set through [choosing] rather than the constructor so every existing call site keeps working, the same
     * reason `withAction` exists for config items. Two links that differ only by their answer also differ by
     * where they come out, which is what equality is built on.
     */
    var choice: String? = null
        private set

    /** This link, answering its object's "where to?" with the option whose text contains [destination]. */
    fun choosing(destination: String): WebLink = also { it.choice = destination }

    /**
     * The interface clicks that finish this link, in order, or empty when there are none.
     *
     * A panel of destinations is picked from by component rather than by text, so the clicks are kept
     * literally. They run after the object has been used and after any [choice], which is the order they
     * happen in: the thing is clicked, it puts something up, and that is answered.
     *
     * A builder for the same reason [choosing] is one - every existing `WebLink(...)` keeps compiling.
     */
    var steps: List<WebInterfaceStep> = emptyList()
        private set

    /** This link, finished by clicking [steps] in order once its object has been used. */
    fun clicking(vararg steps: WebInterfaceStep): WebLink = then(*steps)

    /**
     * Everything done after the link's first action, in order: interface clicks and other objects alike. A
     * trapdoor is picked and then climbed down; a dialogue is answered and then a vine chopped. [steps] is the
     * interface clicks alone, for callers that only know about those.
     */
    var chain: List<WebStep> = emptyList()
        private set

    /** This link, finished by [chain] in order once its first action has been done. */
    fun then(vararg chain: WebStep): WebLink = also {
        it.chain = chain.toList()
        it.steps = chain.filterIsInstance<WebInterfaceStep>()
    }

    /** The NPC an [WebLinkKind.NPC] link talks to, or the item an item-based link uses; null for objects. */
    var target: WebTarget? = null
        private set

    fun using(target: WebTarget): WebLink = also { it.target = target }

    /** The Passage of the Abyss menu to pick through after its teleport: the jewellery, then the place. */
    var menu: List<String> = emptyList()
        private set

    fun picking(vararg menu: String): WebLink = also { it.menu = menu.toList() }

    /** The lodestone a [WebLinkKind.LODESTONE] link teleports to. */
    var lodestone: Lodestone? = null
        private set

    fun toLodestone(lodestone: Lodestone): WebLink = also { it.lodestone = lodestone }

    /** The code a [WebLinkKind.FAIRY_RING] link dials, e.g. "ckr". */
    var fairyCode: String? = null
        private set

    fun dialling(code: String): WebLink = also { it.fairyCode = code.lowercase() }

    val isGlobal: Boolean get() = kind.isGlobal

    private val identity: List<Any?>
        get() = listOf(kind, from, to, cost, action, objectId, searchRadius, requirements, choice, chain, target, menu, lodestone, fairyCode)

    override fun equals(other: Any?): Boolean = this === other || other is WebLink && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()

    override fun toString(): String {
        val what = when (kind) {
            WebLinkKind.LODESTONE -> "${lodestone?.name}"
            WebLinkKind.FAIRY_RING -> "$action $objectId to ${fairyCode?.uppercase()}"
            WebLinkKind.NPC, WebLinkKind.ITEM -> "$action $target"
            WebLinkKind.POA -> "$action $target, ${menu.joinToString(" > ")}"
            WebLinkKind.USE_ON -> "$target on $objectId"
            WebLinkKind.INTERFACE -> "interface"
            else -> "$action $objectId"
        }
        val place = if (isGlobal) "-> $to" else "$from -> $to"
        return "$kind($what: $place" + (choice?.let { ", \"$it\"" } ?: "") +
            (if (chain.isEmpty()) "" else ", " + chain.joinToString(" then ")) + ")"
    }
}
