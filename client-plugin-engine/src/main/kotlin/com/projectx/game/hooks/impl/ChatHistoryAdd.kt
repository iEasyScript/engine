package com.projectx.game.hooks.impl

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.chat.CrownType
import com.projectx.game.chat.MessageType
import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.memory.NativeAccess.getOrNull
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.eastl.EastlString
import com.projectx.game.nxt.OFunctions
import com.projectx.quest.runtime.RecentChat
import com.projectx.script.ScriptExecutor
import com.projectx.script.event.impl.Chat
import java.lang.foreign.MemorySegment

object ChatHistoryAdd {
    @JvmStatic
    @Hook("CHATHISTORY_ADDCHAT_HOOKABLE")
    fun chatHistoryAddHook(chatHistoryPtr: MemorySegment, messageTypeId: Int, unkByte1: Byte, unkInt1: Int, formattedSenderNamePtr: MemorySegment, crownedSenderNamePtr: MemorySegment, cleanSenderNamePtr: MemorySegment, messagePtr: MemorySegment, crownPtr: MemorySegment, unkStringPtr: MemorySegment, unkInt2: Int): MemorySegment {
        var reassignedType = messageTypeId
        synchronized (Bootstrap.lock) {
            try {
                val messageType = MessageType.forId(messageTypeId)
                val formattedSenderName = formattedSenderNamePtr.getOrNull?.let { EastlString(it.reinterpret(24)).toString() }
                val crownedSenderName = crownedSenderNamePtr.getOrNull?.let { EastlString(it.reinterpret(24)).toString() }
                val cleanSenderName = cleanSenderNamePtr.getOrNull?.let { EastlString(it.reinterpret(24)).toString() }
                val message = messagePtr.getOrNull?.let { EastlString(it.reinterpret(24)).toString() }
                unkStringPtr.getOrNull?.let { EastlString(it.reinterpret(24)).toString() }
                val crown = CrownType.forId(crownPtr.getOrNull?.reinterpret(4)?.readInt() ?: 0)

                if (message == "Inventory full. To make more room, sell, drop or bank something.")
                    reassignedType = MessageType.FILTERABLE.id

                if (messageType != null)
                    ScriptExecutor.pushEvent(Chat(messageType, crown, cleanSenderName, crownedSenderName, formattedSenderName, message ?: ""))
                message?.let { RecentChat.record(it) }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        return HookManager.trampoline(::chatHistoryAddHook.name).invokeExact(chatHistoryPtr, reassignedType, unkByte1, unkInt1, formattedSenderNamePtr, crownedSenderNamePtr, cleanSenderNamePtr, messagePtr, crownPtr, unkStringPtr, unkInt2) as MemorySegment
    }
}