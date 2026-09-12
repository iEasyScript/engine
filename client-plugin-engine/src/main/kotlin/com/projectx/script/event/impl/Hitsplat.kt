package com.projectx.script.event.impl

import com.projectx.game.nxt.entity.HitType
import com.projectx.script.event.Event

class Hitsplat(val typeId: Int, val type: HitType, val damage: Int, val target: Any) : Event