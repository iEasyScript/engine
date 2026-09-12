package com.projectx.script.event.impl

import com.projectx.game.chat.CrownType
import com.projectx.game.chat.MessageType
import com.projectx.script.event.Event

class Chat(
    val messageType: MessageType,
    val crown: CrownType,
    val cleanSenderName: String?,
    val crownedSenderName: String?,
    val formattedSenderName: String?,
    val message: String
) : Event