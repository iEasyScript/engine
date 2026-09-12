package com.projectx.game.nxt.interfaces

/**
 * Upper bound on how many child slots a component vector may claim before it is treated as invalid.
 *
 * Slot vectors are read straight out of client memory, and the same offset on a component that is not a slot
 * parent holds unrelated bytes. Those decode to an arbitrary begin/end pair, so the only thing separating a
 * real vector from noise is whether its implied length is plausible. Interfaces top out in the low hundreds
 * of components; anything past this is noise, and walking it hangs the client and then faults.
 */
internal const val MAX_PLAUSIBLE_SLOTS = 4096L
