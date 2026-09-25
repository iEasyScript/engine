package com.projectx.game.nxt

import com.projectx.game.nxt.OffsetTable.count
import com.projectx.game.nxt.OffsetTable.offset
import com.projectx.game.nxt.OffsetTable.portPending
import com.projectx.game.platform.Platform.LINUX
import com.projectx.game.platform.Platform.WINDOWS
import com.projectx.game.platform.Renderer.VULKAN

// Values are not held here — they live per (platform, build) in the `offsets/` jar resources and
// are resolved through OffsetTable on first access. Field documentation lives alongside the values
// in those files; the Ghidra database remains the source of truth for how each was derived.

object OGlobal : OffsetObject {
    val CLIENT by offset()

    /** The clock the client stamps input samples with; the prot senders delta-encode against it. */
    val MONOTONIC_CLOCK_MS by offset()
}

object OInputGlobals : OffsetObject {
    val MOUSE_Y by offset()
    val MOUSE_X by offset()
    val LEFT_BUTTON_STATE by offset()
    val MIDDLE_BUTTON_STATE by offset()
    val RIGHT_BUTTON_STATE by offset()
}

object OFunctions : OffsetObject {
    val CONNECTIONMANAGER_TCPIN by offset()
    val SERVERCONNECTION_FLUSHCLIENTMESSAGES by offset()
    val CLIENT_SETMAINSTATE by offset()
    val STATTABLE_UPDATESTAT by offset()
    val CLIENT_MAINLOGIC by offset()
    val SERVERPROT_DECODE_UPDATE_INV_PARTIAL by offset()
    val SERVERPROT_DECODE_UPDATE_INV_FULL by offset()
    val CHATHISTORY_ADDCHAT_HOOKABLE by offset()
    val PLAYERVARDOMAIN_SETVARVALUEFROMSERVER by offset()
    val PLAYERVARDOMAIN_SETVARVALUE by offset()
    val CLIENTVARDOMAIN_SETVARVALUE by offset()
    val MINIMENU_DOACTION by offset()
    val INTERFACEMANAGER_IFBUTTONXINNER by offset()
    val PROJECTILELIST_ADD by offset()
    val ENTITY_ENTITY by offset()
    val ENTITY_ENTITY_DESTRUCT by offset()
    val INVENTORYMANAGER_GETINVENTORY by offset()
    val HITMARKSANDHEADBARS_ADDHEADBAR by offset()
    val HITMARKSANDHEADBARS_ADDHITMARK by offset()
    val INPUT_INPUT_ONKEYDOWNINNER by offset()
    val INPUT_INPUT_ONKEYUPINNER by offset()
    val SCRIPTRUNNER_EXECUTEHOOKINNER by offset()
    val SCRIPTRUNNER_EXECUTESCRIPT by offset()
    val SCRIPTRUNNER_GETCLIENTSCRIPTSTATE by offset()
    val SCRIPTRUNNER_CONTINUESCRIPT by offset()
    val SCRIPTRUNNER_GET_BY_ID by offset()
    val INPUT_INPUT_ONMOUSEMOTION by offset()
    val INPUT_INPUT_ONLEFTBUTTONDOWN by offset()
    val INPUT_INPUT_ONLEFTBUTTONUP by offset()
    val INPUT_INPUT_ONSCROLLWHEEL by offset()
    val INPUT_INPUT_ONKEYCHARINNER by offset()
    val EVENTLISTENERS_CALLLISTENERS_FF by offset(LINUX)
    val INPUT_INPUT_ONRIGHTBUTTONDOWN by offset(WINDOWS)
    val INPUT_INPUT_ONRIGHTBUTTONUP by offset(WINDOWS)
    val INPUT_INPUT_ONMIDDLEBUTTONUP by offset(WINDOWS)
    val STD_MTX_LOCK by offset(WINDOWS)
    val STD_MTX_UNLOCK by offset(WINDOWS)
    val EVENTLISTENERS_DISPATCHMOUSEEVENTWITHSOURCE by offset(WINDOWS)
    val TCPCONNECTIONMESSAGE_INIT by offset()
    val TCPCONNECTIONMESSAGE_INIT_INCOMING by offset()
    val SENDCLIENTMESSAGE by offset(LINUX)
    val REBUILD_NORMAL_HANDLER by offset()
    val BUILD_AREA_INIT by offset()
    val APPLY_LOC_CHANGE by offset()
    val INTERFACEMANAGER_DRAWCOMPONENTLIST by offset()
    val CLIENTPROT_SENDEVENTMOUSECLICK by offset()
    val CLIENTPROT_SENDNATIVEMOUSECLICK by offset()
    val CLIENTPROT_FLUSHMOUSEPROTSENDER by offset()
    val CLIENTPROT_FLUSHPERTICK by offset()
    val CLIENTERROR_REPORTERROR by offset()
    val CLIENTSTREAM_READ by offset()
    val CLIENTSTREAM_WRITE by offset()
}

object OClient : OffsetObject {
    val CLIENT_CYCLE by offset()
    val CLIENT_RENDER_CYCLE by offset()
    val SDL_MANAGER by offset(LINUX)
    val INPUT by offset()
    val BUILD_AREA_VECTOR by offset()
    val CAMERA by offset()
    val CONNECTION_MANAGER by offset()
    val HINTARROW_LIST by offset()
    val HINTTRAIL_LIST by offset()
    val INTERFACE_MANAGER by offset()
    val MAINLOGIC_MANAGER by offset()
    val MINIMENU by offset()
    val NPC_MANAGER by offset()
    val ITEMSTACK_LIST by offset()
    val PLAYER_MANAGER by offset()
    val PROJECTILE_LIST by offset()
    val SPOTANIM_MANAGER by offset()
    val INVENTORY_MANAGER by offset()
    val SCENE_MANAGER by offset()
    val MAIN_STATE by offset()
    val LOGGED_IN_PLAYER by offset()
    val PLAYER_VAR_DOMAIN by offset()
    val STOCKMARKET by portPending(WINDOWS)
    val WINDOW_FRAME by offset(WINDOWS)
}

object OWindowFrame : OffsetObject {
    val RENDER_VIEW by offset(WINDOWS)
}

object ORenderView : OffsetObject {
    val WINDOW_HANDLE by offset(WINDOWS)
}

object OVulkanGlobals : OffsetObject {
    val RENDER_DEVICE by offset(VULKAN)
    val QUEUE_PRESENT by offset(VULKAN)
}

object OVulkanRenderDevice : OffsetObject {
    val API_VERSION by offset(VULKAN)
    val INSTANCE by offset(VULKAN)
    val PHYSICAL_DEVICE by offset(VULKAN)
    val DEVICE by offset(VULKAN)
    val QUEUE by offset(VULKAN)
    val QUEUE_FAMILY by offset(VULKAN)
}

/** The object a window presents through: its surface, and the swapchain object embedded after it. */
object OVulkanPresentContext : OffsetObject {
    val SURFACE by offset(VULKAN)
    val SWAPCHAIN by offset(VULKAN)
}

object OVulkanSwapchain : OffsetObject {
    val HANDLE by offset(VULKAN)
    val WIDTH by offset(VULKAN)
    val HEIGHT by offset(VULKAN)
}

object OInput : OffsetObject {
    val GLOBAL_HANDLER by offset()
    val LAST_MOUSE_X by offset()
    val LAST_MOUSE_Y by offset()
    val CLIENT by offset()
    val CONSOLE_KEY_HELD by offset()
    val CONSOLE_KEY_VK by portPending(WINDOWS)
    val SIZE by offset()
}

object OStockMarket : OffsetObject {
    val OFFERS by portPending(WINDOWS)
    val OFFER_STRIDE by portPending(WINDOWS)
    val SLOTS_PER_GROUP by portPending(WINDOWS)
}

object OStockMarketOffer : OffsetObject {
    val STATUS by portPending(WINDOWS)
    val TYPE by portPending(WINDOWS)
    val ITEM_ID by portPending(WINDOWS)
    val PRICE by portPending(WINDOWS)
    val QUANTITY by portPending(WINDOWS)
    val COMPLETED_QUANTITY by portPending(WINDOWS)
    val COMPLETED_GOLD by portPending(WINDOWS)
}

object OInputState : OffsetObject {
    val KEY_DOWN_MAP_ROOT by offset()
    val KEY_NODE_RIGHT by offset()
    val KEY_NODE_LEFT by offset()
    val KEY_NODE_VK by offset()
    val KEY_NODE_PRESSED by offset()
}

object OInputHandler : OffsetObject {
    val LMOUSE_DOWN by offset()
    val LMOUSE_UP by offset()
    val MMOUSE_DOWN by offset()
    val MMOUSE_UP by offset()
    val RMOUSE_DOWN by offset()
    val RMOUSE_UP by offset()
    val MOUSE_MOVE by offset()
    val MOUSE_WHEEL by offset()
    val KEY_DOWN by offset()
    val KEY_UP by offset()
    val KEY_CHAR by offset()
    val SIZE by offset()
    val SLOT_BEGIN by offset(WINDOWS)
    val SLOT_END by offset(WINDOWS)
    val SLOT_MUTEX by offset(WINDOWS)
}

/** An `InputHandler` slot entry: MSVC `std::function`, invoked through its callable's vtable. */
object OInputListener : OffsetObject {
    val CALLABLE by offset(WINDOWS)
    val VTABLE_INVOKE by offset(WINDOWS)
}

object OSDLManager : OffsetObject {
    val WINDOW by offset(LINUX)
    val SDL_WINDOW by offset(LINUX)
    val WINDOW_FLAGS_BITFIELD by offset(LINUX)
}

object OSDLWindow : OffsetObject {
    val WINDOW_NAME by offset(LINUX)
    val WINDOW_DATA by offset(LINUX)
}

object OSDLWindowData

object OItemStackList : OffsetObject {
    val RB_TREE_BASE by offset()
}

object OItemStackNode : OffsetObject {
    val LEFT by offset()
    val RIGHT by offset()
    val PARENT by offset()
    val POS_PLANE by offset()
    val POS_X by offset()
    val POS_Y by offset()
    val ITEM_STACK by offset()
}

object OItemStack : OffsetObject {
    val ITEM_VECTOR by offset()
    val ITEM_VECTOR_ELEM_SIZE by offset()
    val ITEM_ID by offset()
    val ITEM_AMOUNT by offset()
}

object OProjectileList : OffsetObject {
    val SIZE by offset()
}

object OProjectile : OffsetObject {
    val ID by offset()
    val LOCKON_SERVER_INDEX by offset()
}

object OInterfaceManager : OffsetObject {
    val INTERFACE_LIST by offset()
    val KEY_EVENT_BEGIN by offset()
    val KEY_EVENT_END by offset()
}

object OInterfaceList : OffsetObject {
    val START_PTR by offset()
    val END_PTR by offset()
    val CAPACITY_PTR by offset()
    val PARENT_OFFSET by offset()
    val ENTRY_STRIDE by offset()
}

object OInterfaceParent : OffsetObject {
    val CHILD_ARRAY_BEGIN by offset()
    val CHILD_ARRAY_END by offset()
    val CHILD_SLOT_STRIDE by offset()
    val CHILD_SLOT_HIDDEN_FLAG by offset()
    val CHILD_SLOT_PTR by offset()
}

/** One entry of a component's child-slot vector. */
object OChildSlot : OffsetObject {
    val REMOVED by offset()
    val REF_COUNT by offset()
    val COMPONENT by offset()
    val SIZE by offset()
}

/**
 * One page of a paged layer or carousel. Each page mirrors both child vectors, so the
 * component's own vectors are only the children when the page array is empty.
 */
object OComponentPage : OffsetObject {
    val STATIC_CHILDREN by offset()
    val STATIC_CHILDREN_END by offset()
    val DYNAMIC_CHILDREN by offset()
    val DYNAMIC_CHILDREN_END by offset()
    val ENABLED by offset()
    val SIZE by offset()
}

object OInterfaceComponent : OffsetObject {
    val INTERFACE_ID by offset()
    val COMPONENT_ID by offset()
    val PARENT_LAYER_ID by offset()
    val SLOT_ID by offset()
    val PARENT_SHAREDPTR by offset()
    val FLAGS by offset()
    val X_ORIGIN_OFFSET by offset()
    val Y_ORIGIN_OFFSET by offset()
    val RAW_WIDTH by offset()
    val RAW_HEIGHT by offset()
    val PARENT_REL_X by offset()
    val PARENT_REL_Y by offset()
    val SCREEN_WIDTH by offset()
    val SCREEN_HEIGHT by offset()
    val TEXT by offset()
    val GRAPHIC_ID by offset()
    val ITEM_ID by offset()
    val STACK_SIZE by offset()
    val MODEL_ID by offset()
    val MODEL_OBJECT by offset()
    val STATIC_CHILDREN by offset()
    val STATIC_CHILDREN_END by offset()
    val DYNAMIC_CHILDREN by offset()
    val DYNAMIC_CHILDREN_END by offset()
    val PAGES by offset()
    val PAGES_END by offset()
    val PAGE_STRIDE by offset()
    val ACTIVE_PAGE by offset()
    val KIND_GETTER by offset(WINDOWS)
    val LAYER_KIND by count(WINDOWS)
    val PAGED_LAYER_KIND by count(WINDOWS)
}

object OLoggedInPlayer : OffsetObject {
    val PLAYER_RIGHTS by offset()
    val PLAYER_INDEX by offset()
    val PLAYER_NAME by offset()
    val TARGET_INDEX by offset()
    val TARGET_TYPE by offset()
}

object OPlayerManager : OffsetObject {
    val PLAYER_LIST by offset()
    val PLAYER_LIST_NODE_ENTITY by offset()
}

object ONPCManager : OffsetObject {
    val HASH_TABLE by offset()
    val LOCAL_NPC_INDICES by offset()
    val HASH_ELEMENT_SIZE by offset()
}

object OSceneManager : OffsetObject {
    val WORLD_ARRAY by offset()
    val CURRENT_WORLD_INDEX by offset()
    val WORLD_ARRAY_STRIDE by offset()
}

object OWorld : OffsetObject {
    val VIEW_MATRIX by offset()
    val PROJECTION_MATRIX by offset()
    val MAPSQUARE_X_OFFSET by offset()
    val MAPSQUARE_Y_OFFSET by offset()
    val MAPSQUARE_X_MAX by offset()
    val MAPSQUARE_Y_MAX by offset()
    val MAPSQUARES_VECTOR by offset()
    val ROOT_GRAPH_NODE by offset()
    val HEIGHT_MAP by offset()
    val LINK_MAP by offset()
}

object OHeightMap : OffsetObject {
    val GRID by offset()
    val CELL_STRIDE by offset()
    val CELL_CONTAINER by offset()
    val CELL_SENTINEL by offset()
    val EMPTY_CELL_SENTINEL_REL by offset()
}

object ORegionHeightContainer : OffsetObject {
    val PRIMARY by offset()
    val FALLBACK by offset()
    val READY_LO by offset()
    val READY_HI by offset()
    val NOT_LOADED_FLAG by offset()
    val PLANE_VEC_BEGIN by offset()
    val PLANE_VEC_END by offset()
    val PLANE_STRIDE by offset()
    val PLANE_ELEM by offset()
    val PLANE0_BRIDGE_GRID by offset()
    val VERTEX_COL_STRIDE by offset()
    val VERTEX_ROW_STRIDE by offset()
    val LINK_HEIGHT_PLANE_COUNT by offset()
    val LINK_HEIGHT_GRID by offset()
    val LINK_PLANE_STRIDE by offset()
    val LINK_COL_STRIDE by offset()
    val LINK_ROW_STRIDE by offset()
}

object OMapSquare : OffsetObject {
    val MAPSQUARE_X by offset()
    val MAPSQUARE_Y by offset()
    val COMPOSITE_DATA_COUNTER by offset()
    val COMPOSITE_DATA by offset()
    val LOCATION_CONTAINERS by offset()
    val LOCATION_CONTAINER_ENTRY_STRIDE by offset()
    val LOCATION_CONTAINER_ENTRY_PTR_OFFSET by offset()
}

object OLocationContainer : OffsetObject {
    val ENTRY_STRIDE by offset()
    val ENTRY_LOCATION_PTR_OFFSET by offset()
    val PRIMARY_LOCATIONS_BEGIN by offset()
    val PRIMARY_LOCATIONS_END by offset()
    val MULTI_LOCATIONS_BEGIN by offset()
    val MULTI_LOCATIONS_END by offset()
    val TERTIARY_LOCATIONS_BEGIN by offset()
    val TERTIARY_LOCATIONS_END by offset()
    val DISPATCH_SLOT_TABLE_START by offset()
    val DISPATCH_SLOT_STRIDE by offset()
    val DISPATCH_SLOT_COUNT by count()
}

object OLocation : OffsetObject {
    val RENDER_NODE_CTRL by offset()
    val RENDER_NODE by offset()
    val VISIBLE_TYPE by offset()
    val ORIGINAL_TYPE by offset()
    val SHAPE by offset()
    val ROTATION by offset()
    val POS_X by offset()
    val POS_Y by offset()
    val IS_DELETED by offset()
    val IS_HIDDEN by offset()
    val TYPE_ID by offset()
    val HIGHLIGHT_INTENSITY by offset()
    val HIGHLIGHT_CATEGORY by offset()
    val SHOW_AS_IMPORTANT by offset()
}

object OCombinedLocation : OffsetObject {
    val LOCATION_DATA_VECTOR by offset()
    val VECTOR_ELEMENT_LOC_SHAREDPTR by offset()
    val RENDER_MODEL by offset()
}

object OCombinedLocationSection : OffsetObject {
    val TYPE_SHAREDPTR by offset()
    val HIDDEN by offset()
    val SHAPE by offset()
    val ROTATION by offset()
    val POS_X by offset()
    val POS_Y by offset()
}

object OLocationType : OffsetObject {
    val ID by offset()
    val SIZE_X by offset()
    val SIZE_Y by offset()
}

object OSpotAnimManager : OffsetObject {
    val VTABLE by offset()
    val CLIENT_REF by offset()
    val FIRST_NODE by offset()
    val POOL_FREE_LIST_HEAD by offset()
    val POOL_BUMP_CURSOR by offset()
    val POOL_BUMP_LIMIT by offset()
    val POOL_ELEMENT_STRIDE by offset()
    val LIST_SIZE by offset()
}

object OSpotAnimNode : OffsetObject {
    val NEXT by offset()
    val VALUE by offset()
}

object OPlayerVarDomain : OffsetObject {
    val HASH_TABLE by offset()
    val VAR_ID by offset()
    val VAR_VALUE by offset()
}

object OClientVarDomain : OffsetObject {
    val HASH_TABLE by offset()
    val VAR_ID by offset()
    val VAR_VALUE by offset()
}

object OMiniMenuEntry : OffsetObject {
    val TARGET_STRING by offset()
    val ACTION_STRING by offset()
    val HIGHLIGHT_TYPE by offset()
    val ACTION by offset()
    val UNK_1 by offset()
    val ITEM_ID by offset()
    val PARAM_1 by offset()
    val PARAM_2 by offset()
    val PARAM_3 by offset()
    val TARGETED_ENTITY by offset()
}

object OMiniMenu : OffsetObject {
    val MENU_OPEN by offset()
}

object OMiniMenuAction : OffsetObject {
    val ACTION_VTABLE by offset()
    val CLIENT by offset()
    val VTABLE_ACTION_SEND_FUNCTION by offset()
    val ACTION_ID by offset()
}

/**
 * Two rings with identical entry layout: presses, then movement samples. Each is followed by its own cursor
 * pair — write first, read second, empty when equal.
 */
object OMainLogicManager : OffsetObject {
    val STAT_TABLE by offset()
    val CLIENT_VAR_DOMAIN by offset()
    val CLICK_BUFFER_BASE by offset()
    val CLICK_BUFFER_WRITE by offset()
    val CLICK_BUFFER_READ by offset()
    val CLICK_BUFFER_CAPACITY by count()
    val CLICK_ENTRY_SIZE by offset()
    val MOVE_BUFFER_BASE by offset()
    val MOVE_BUFFER_WRITE by offset()
    val MOVE_BUFFER_READ by offset()
    val MOVE_BUFFER_CAPACITY by count()
    val MOVE_ENTRY_SIZE by offset()
}

/** One entry in either ring. `BUTTON` is a button id for a press and -1 for a pure movement sample. */
object OMouseSample : OffsetObject {
    val BUTTON by offset()
    val X by offset()
    val Y by offset()
    val TIMESTAMP_MS by offset()
}

/** One staged key press. Only the low byte of [KEY_ID] and the timestamp delta reach the wire. */
object OKeyEvent : OffsetObject {
    val KEY_ID by offset()
    val CHAR_CODE by offset()
    val RAW_KEY_CODE by offset()
    val REPEAT by offset()
    val MODIFIER_MASK by offset()
    val TIMESTAMP_MS by offset()
    val SIZE by offset()
}

object OClientProt : OffsetObject {
    val CLIENT by offset()
    val EVENT_MOUSE_SENDER by offset()
    val NATIVE_MOUSE_SENDER by offset()
    val LAST_KEY_EVENT_TIME by offset()
}

/** Per-sender delta state. The movement stage clobbers everything here except the click timestamp. */
object OMouseProtSender : OffsetObject {
    val VTABLE by offset()
    val CLIENT by offset()
    val LAST_SENT_X by offset()
    val LAST_SENT_Y by offset()
    val LAST_MOVE_SAMPLE_TIME by offset()
    val LAST_CLICK_SAMPLE_TIME by offset()
}

object OStatTable : OffsetObject {
    val TABLE_SIZE by offset()
    val TABLE_BEGIN by offset()
    val ENTRY_SIZE by offset()
}

object OStat : OffsetObject {
    val INFO_PTR by offset()
    val XP_IN_TENTHS by offset()
    val EXPERIENCE by offset()
    val REAL_LEVEL by offset()
    val CURRENT_LEVEL by offset()
}

object OInventory : OffsetObject {
    val INVENTORY_ID by offset()
    val INVENTORY_ITEMS by offset()
    val OBJ_VAR_DOMAINS by offset()
    val OBJ_VAR_DOMAIN_STRIDE by offset()
    val OBJ_VAR_DOMAIN_FIELD by offset()
}

object OObjVarDomain : OffsetObject {
    val ELEMENT_SIZE by offset()
}

object OGraphNode : OffsetObject {
    val DIRECTION_X by offset()
    val DIRECTION_Y by offset()
    val DIRECTION_Z by offset()
    val BOUNDS_MIN_X by offset()
    val BOUNDS_MIN_Z by offset()
    val BOUNDS_MIN_Y by offset()
    val BOUNDS_MAX_X by offset()
    val BOUNDS_MAX_Z by offset()
    val BOUNDS_MAX_Y by offset()
    val SRC_BOUNDS_MIN_X by offset()
    val SRC_BOUNDS_MIN_Z by offset()
    val SRC_BOUNDS_MIN_Y by offset()
    val SRC_BOUNDS_MAX_X by offset()
    val SRC_BOUNDS_MAX_Z by offset()
    val SRC_BOUNDS_MAX_Y by offset()
    val LOCAL_X by offset()
    val LOCAL_Z by offset()
    val LOCAL_Y by offset()
    val DEPTH by offset()
    val SCENE_X by offset()
    val SCENE_Y by offset()
    val SCENE_Z by offset()
    val FLAGS by offset()
    val CHILDREN by offset()
    val ENTITY by offset()
    val FRAME_COUNT by offset()
}

object OEntity : OffsetObject {
    val GRAPH_NODE by offset()
    val ENTITY_TYPE by offset()
    val PICK_TYPE by offset()
    val SCREEN_X1 by offset()
    val SCREEN_Y1 by offset()
    val SCREEN_X2 by offset()
    val SCREEN_Y2 by offset()
    val LINE_RADIUS by offset()
    val SCREEN_CENTER_X by offset()
    val SCREEN_CENTER_Y by offset()
    val POINT_RADIUS by offset()
    val ENTITY_PLANE by offset()
    val SIZE by offset()
    val ANIMATION_ID by offset()
    val ANIM_IDS_BEGIN by offset()
    val ANIM_IDS_END by offset()
    val ANIMATION_SHARED_PTR by offset()
    val RENDER_MODEL by offset()
}

object ORenderModel : OffsetObject {
    val WORLD_TRANSFORM by offset()
    val MESH_COMPONENTS by offset()
    val HL_COLOR_R by offset()
    val HL_COLOR_G by offset()
    val HL_COLOR_B by offset()
    val HL_BASE_ALPHA by offset()
    val HL_STRENGTH by offset()
    val HL_FACTOR by offset()
    val HL_MODE by offset()
}

object OMeshComponent : OffsetObject {
    val MESH_DATA by offset()
    val BONE_COUNT by offset()
    val BONE_MATRICES by offset()
    val BONE_ANIM_CHECK by offset()
}

object OMeshData : OffsetObject {
    val VERTEX_SCALE by offset()
    val INDEX_BUFFER by offset()
    val INDEX_BUFFER_END by offset()
    val VERTEX_POSITIONS by offset()
    val SKINNING_DATA by offset()
    val SKINNED_VERTEX_DATA by offset()
    val VERTEX_WEIGHTS by offset()
}

object OAnimation : OffsetObject {
    val ID by offset()
    val CURRENT_FRAME by offset()
}

object ONPC : OffsetObject {
    val RENDER_ANIM by offset()
    val ID by offset()
    val TYPE_ID by offset()
    val CURRENT_HP by offset()
    val MAX_HP by offset()
    val HIDDEN_MENUOP_FLAGS by offset()
    val SHOW_AS_IMPORTANT by offset()
}


object OPathingEntity : OffsetObject {
    val SERVER_INDEX by offset()
    val NAME by offset()
    val INTERACTING_NPC_SID by offset()
    val ROUTE_WAYPOINT_MANAGER by offset()
    val WAYPOINT_COUNT by offset()
    val FINE_POS_X by offset()
    val FINE_POS_Y by offset()
    val FINE_POS_Z by offset()
    val MOVE_TARGET_X by offset()
    val MOVE_TARGET_Y by offset()
    val MOVE_TARGET_Z by offset()
    val HITMARKS_AND_HEADBARS by offset()
}

object OHitmarksAndHeadbars : OffsetObject {
    val HIT_VECTOR by offset()
    val HEADBAR_LINKEDLIST_VECTOR_START by offset()
    val HEADBAR_VECTOR_END by offset()
    val HEADBAR_STRIDE by offset()
}

object OHit : OffsetObject {
    val TYPE by offset()
    val DAMAGE by offset()
    val CLIENTCYCLE_CREATED by offset()
    val UNKNEG1_1 by offset()
    val UNKNEG1_2 by offset()
    val DURATION_CLIENTCYLES by offset()
    val STRIDE by offset()
}

object OHeadbar : OffsetObject {
    val TYPE_PTR by offset()
    val TYPE_ID by offset()
    val CLIENTCYCLE_CREATED by offset()
    val FROM_FILL by offset()
    val ZERO by offset()
    val TO_FILL by offset()
    val ZERO2 by offset()
    val DURATION_CLIENTCYCLES by offset()
}

object OHitmark

object OSpotAnim : OffsetObject {
    val ID by offset()
    val CREATED_CLIENTCYCLE by offset()
}

object OHintArrow : OffsetObject {
    val TARGET_ENTITY_SHARED_PTR by offset()
    val TARGET_POS_VEC3 by offset()
}

/**
 * The hint arrow/icon container: three parallel slot arrays, indexed by the packet's slot byte.
 * [DESCRIPTORS] is a plain POD array, so where a hint points is readable without touching an entity.
 */
object OHintArrowList : OffsetObject {
    val ARROW_SLOTS by offset()
    val POINTER_SLOTS by offset()
    val DESCRIPTORS by offset()
    val DESCRIPTOR_STRIDE by offset()
    val SLOT_COUNT by count()
}

object OHintArrowDescriptor : OffsetObject {
    val KIND by offset()
    val TARGET_INDEX by offset()
    val FINE_X by offset()
    val HEIGHT by offset()
    val FINE_Y by offset()
}

object OHintTrailList : OffsetObject {
    val SLOT_ARRAY by offset()
    val SLOT_COUNT by count()
}

/** One occupied slot of [OHintTrailList]: an Entity subclass carrying the hint's path and target. */
object OHintTrail : OffsetObject {
    val TARGET_ID by offset()
    val POINTS_BEGIN by offset()
    val POINTS_END by offset()
    val POINT_STRIDE by offset()
    val POINT_FINE_X by offset()
    val POINT_FINE_HEIGHT by offset()
    val POINT_FINE_Y by offset()
    val TARGET_ENTITY_SHARED_PTR by offset()
}

object OServerConnection : OffsetObject {
    val PENDING_LIST_HEAD by offset()
    val PENDING_COUNT by offset()
    val CLIENT_STREAM by offset()
    val CURRENT_OPCODE by offset()
    val RESOLVED_SIZE by offset()
    val ISAAC_PTR by offset()
    val PACKET_BASE by offset()
    val BUF_DATA by offset()
    val BUF_POS by offset()
}

object OConnectionManager : OffsetObject {
    val GAME_CONNECTION by offset()
    val LOGIN_CONNECTION by offset()
    val HANDLE_STATE by offset()
}

object OLocChangeRecord : OffsetObject {
    val RECORD by offset()
    val PLANE by offset()
    val TILE_X by offset()
    val TILE_Y by offset()
    val KIND by offset()
}

object OPendingMessageNode : OffsetObject {
    val MESSAGE by offset()
}

object OTcpConnectionMessage : OffsetObject {
    val PROT_PTR by offset()
    val PAYLOAD_LENGTH by offset()
    val FIXED_SIZE by offset()
    val BUF_DATA by offset()
    val BUF_WRITE_POS by offset()
}

object OVarInfo : OffsetObject {
    val TYPE_PTR by offset()
    val VAR_ID by offset()
}

/** No field of it is read yet; declared so the wrapper sizes its window from the table like every other object. */
object ORouteWaypointManager : OffsetObject
