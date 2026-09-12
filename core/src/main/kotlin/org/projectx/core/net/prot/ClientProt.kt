package org.projectx.core.net.prot

import kotlinx.serialization.Serializable

/** Marker interface for all client-to-server packets. */
interface ClientProt

@JvmInline
value class Ping(val dummy: Int = 0) : ClientProt

/** Client signals it has finished building the scene from REBUILD_NORMAL. */
@JvmInline
value class MapBuildComplete(val dummy: Int = 0) : ClientProt

/** Client closed the modal window it had open. */
@JvmInline
value class CloseModal(val dummy: Int = 0) : ClientProt

/** Client abandoned a paused dialogue without answering it. */
@JvmInline
value class AbortPDialog(val dummy: Int = 0) : ClientProt

/**
 * Client's window geometry, sent on resize and at login. [mode] is the display mode the client is
 * running (the fixed/resizable choice), [flag] the extra display setting it reports alongside.
 */
data class WindowStatus(val mode: Int, val width: Int, val height: Int, val flag: Int) : ClientProt

data class RequestWorldList(val worldlistVersion: Int) : ClientProt

@Serializable data class FriendListAdd(val displayName: String) : ClientProt
@Serializable data class FriendListDel(val displayName: String) : ClientProt
@Serializable data class IgnoreListAdd(val displayName: String) : ClientProt

/** Public chat message. */
@Serializable data class MessagePublicSend(val color: Int, val effect: Int, val message: ByteArray) : ClientProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessagePublicSend) return false
        return color == other.color && effect == other.effect && message.contentEquals(other.message)
    }
    override fun hashCode(): Int = 31 * (31 * color + effect) + message.contentHashCode()
}

/** Send a private message to another player. */
@Serializable data class MessagePrivateSend(val toDisplayName: String, val message: ByteArray) : ClientProt {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessagePrivateSend) return false
        return toDisplayName == other.toDisplayName && message.contentEquals(other.message)
    }
    override fun hashCode(): Int = 31 * toDisplayName.hashCode() + message.contentHashCode()
}

/** Kick a user from a clan/friends channel. */
@Serializable data class ClanChannelKickUser(val username: String) : ClientProt

/**
 * Interface component CLICK. [buttonId] is the option index. interfaceHash packs the interface id
 * (ushr 16) and component id (and 0xFFFF). slotId/itemId identify the clicked sub-element.
 */
data class IfButton(val buttonId: Int, val interfaceHash: Int, val slotId: Int, val itemId: Int) : ClientProt

/**
 * A native dropdown changed its selection. The client sends the entry the player picked with
 * [committed] false, then the dropdown's settled value with [committed] true; only the second one
 * is the value the widget will show.
 */
data class IfDropdownSelect(val interfaceHash: Int, val slot: Int, val value: Int, val committed: Boolean) : ClientProt

/** Typed number from the count dialog. */
data class ResumePCountDialog(val value: Int) : ClientProt

/** Typed display name from the name dialog. */
data class ResumePNameDialog(val name: String) : ClientProt

/** Console `::command` text, already trimmed client-side. */
data class ClientCheat(val command: String) : ClientProt

data class MoveGameClick(val destX: Int, val destY: Int, val run: Boolean) : ClientProt
data class MoveMinimapClick(val destX: Int, val destY: Int, val run: Boolean) : ClientProt

/** EVENT_MOUSE_CLICK — one drained click from the client's click ring; input telemetry. */
data class EventMouseClick(val x: Int, val y: Int, val rightButton: Boolean, val timeDelta: Int) : ClientProt

/** EVENT_MOUSE_MOVE — the client's mouse-move history batch; input telemetry, body discarded. */
@JvmInline
value class EventMouseMove(val dummy: Int = 0) : ClientProt

/** EVENT_APPLET_FOCUS — sent on window keyboard-focus change; carries the new focus state. */
@JvmInline
value class EventAppletFocus(val focused: Boolean) : ClientProt

data class EventCameraPosition(val yaw: Int, val pitch: Int) : ClientProt

/** OPLOC1-6 — clicked menu [option] on scenery/loc [locId] at tile ([x], [y]). */
data class OpLoc(val option: Int, val locId: Int, val x: Int, val y: Int, val run: Boolean) : ClientProt

/** OPNPC1-6 — clicked menu [option] on npc [npcIndex]. */
data class OpNpc(val option: Int, val npcIndex: Int, val run: Boolean) : ClientProt

/** OPOBJ1-6 — clicked menu [option] on ground-item [objId] at tile ([x], [y]). */
data class OpObj(val option: Int, val objId: Int, val x: Int, val y: Int, val run: Boolean) : ClientProt

/** OPPLAYER1-10 — clicked menu [option] on player [playerIndex]. */
data class OpPlayer(val option: Int, val playerIndex: Int, val run: Boolean) : ClientProt

/** OPNPCT — use the interface component ([interfaceHash]/[slot]/[item]) on npc [npcIndex]. */
data class IfOnNpc(val interfaceHash: Int, val slot: Int, val item: Int, val npcIndex: Int, val run: Boolean) : ClientProt

/** OPLOCT — use the interface component on loc [locId] at tile ([x], [y]). */
data class IfOnLoc(val interfaceHash: Int, val slot: Int, val item: Int, val x: Int, val y: Int, val locId: Int, val run: Boolean) : ClientProt

/** OPTILET — use the interface component on the ground tile ([x], [y]). */
data class IfOnTile(val interfaceHash: Int, val slot: Int, val item: Int, val x: Int, val y: Int) : ClientProt

/** OPOBJT — use the interface component on ground-item [objId] at tile ([x], [y]). */
data class IfOnObj(val interfaceHash: Int, val slot: Int, val item: Int, val x: Int, val y: Int, val objId: Int, val run: Boolean) : ClientProt

/** IF_BUTTONT — use interface component ([interfaceHash]/[slot]/[item]) on target component ([targetHash]/[targetSlot]/[targetItem]). */
data class IfOnComponent(val interfaceHash: Int, val slot: Int, val item: Int, val targetHash: Int, val targetSlot: Int, val targetItem: Int) : ClientProt

/** IF_BUTTOND — drag interface component ([fromHash]/[fromSlot]/[fromItem]) onto ([toHash]/[toSlot]/[toItem]). Slot/item are -1 when absent. */
data class IfDrag(
    val fromHash: Int, val fromSlot: Int, val fromItem: Int,
    val toHash: Int, val toSlot: Int, val toItem: Int,
) : ClientProt {
    override fun toString(): String =
        "IfDrag(from=${fromHash ushr 16}:${fromHash and 0xFFFF} slot=$fromSlot item=$fromItem" +
            " -> to=${toHash ushr 16}:${toHash and 0xFFFF} slot=$toSlot item=$toItem)"
}

/** STORE_SERVERPERM_VARCS — the client's changed SERVERPERMANENT var_client values ([entries]: id -> value) for the server to persist and replay on next login. */
data class StoreServerpermVarcs(val entries: Map<Int, Int>) : ClientProt

/** MESSAGE_FILTER — sets a client chat filter/mode ([setting] selector, [value] mode). */
data class MessageFilter(val setting: Int, val value: Int) : ClientProt

/** CHAT_SETFILTER (op114) — the public/private/trade chat filter modes (0=on, 1=friends, 2=off). */
@Serializable data class ChatSetFilter(val publicFilter: Int, val privateFilter: Int, val tradeFilter: Int) : ClientProt

/** A quickchat phrase send; the phrase-id + param encoding is not yet decoded. */
data class MessageQuickChat(val length: Int) : ClientProt

/** Private message whose whole body is XTEA-encrypted with the per-session key. */
class MessagePrivateEncrypted(val encrypted: ByteArray) : ClientProt

/** A batch of key press/release events; input telemetry. */
data class EventKeyboard(val events: List<KeyPress>) : ClientProt

data class KeyPress(val keyCode: Int, val timeDelta: Int)

/** Catch-all for opcodes we haven't implemented handlers for yet. Carries opcode for logging. */
data class UnhandledClientProt(val opcode: Int, val name: String, val size: Int) : ClientProt
