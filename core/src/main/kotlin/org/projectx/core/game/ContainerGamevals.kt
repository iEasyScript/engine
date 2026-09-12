package org.projectx.core.game

fun Container.add(name: String, amount: Int = 1): Boolean = add(Obj(name, amount))

fun Container.set(slot: Int, name: String, amount: Int = 1) = set(slot, Obj(name, amount))
