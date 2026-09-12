package com.projectx.script.event.impl

import com.projectx.game.nxt.DoActionOpcode
import com.projectx.script.event.Event

class ManualDoAction(val opcode: DoActionOpcode, val target: Any) : Event