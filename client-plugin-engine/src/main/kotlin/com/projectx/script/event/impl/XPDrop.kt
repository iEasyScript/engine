package com.projectx.script.event.impl

import org.projectx.core.game.skill.Skill
import com.projectx.script.event.Event

class XPDrop(val skill: Skill, val gainedXp: Int) : Event