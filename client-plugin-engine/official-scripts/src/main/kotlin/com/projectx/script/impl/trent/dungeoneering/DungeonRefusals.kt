package com.projectx.script.impl.trent.dungeoneering

/**
 * The server tells us when an approach cannot work. A refusal arriving in reply to our own action is the
 * cheapest signal there is that retrying is pointless — far cheaper than counting failed attempts — and it is
 * the one thing every stuck loop this bot has produced had in common while nothing was listening for it.
 *
 * Two kinds matter and they must not be confused. A HARD refusal names a permanent obstacle: the door stays
 * shut however many times it is asked. A SOFT gate says the attempt may still succeed, and the Archaeology
 * door proves those are worth repeating — it opened for an under-levelled account. Soft is therefore checked
 * first, because a soft gate's wording contains a hard-sounding refusal.
 */
object DungeonRefusals {

    // A door that will open on its own once something in the room finishes. Retrying is exactly right here;
    // banning throws away a route that was seconds from opening.
    private val WAIT = listOf("until", "once the", "not yet")

    private val SOFT = listOf("can be attempted")

    /**
     * Refusals proven permanent, and ONLY those. The two classes are not symmetric: a missed WAIT or SOFT
     * costs a retry, while a wrongly-HARD message destroys a live route for the rest of the floor. So this
     * list holds observed wordings rather than plausible ones — an earlier version guessed at "is locked",
     * which is also how a charging monolith announces itself, and banned the room it was about to open.
     */
    private val HARD_PASSAGE = listOf(
        "unable to clear the vines",
        "unable to clear the wisp",
    )

    // "You can't reach that." answers for the TARGET, not the passage — the door may be fine and the item
    // simply walled off inside a puzzle. Its own channel, because re-firing against it is free to the server
    // and ruinous to us: one walled-off key drew 162 of these while the room's toxin did the killing.
    private val UNREACHABLE = listOf("can't reach that")

    @Volatile
    private var reachAt = 0L

    @Volatile
    private var reachText = ""

    @Volatile
    private var hardAt = 0L

    @Volatile
    private var hardText = ""

    @Volatile
    private var waitAt = 0L

    @Volatile
    private var waitText = ""

    fun observe(message: String) {
        val text = message.lowercase()
        if (SOFT.any { text.contains(it) }) return
        if (WAIT.any { text.contains(it) }) {
            waitAt = System.currentTimeMillis()
            waitText = message
            return
        }
        if (UNREACHABLE.any { text.contains(it) }) {
            reachAt = System.currentTimeMillis()
            reachText = message
            return
        }
        if (HARD_PASSAGE.none { text.contains(it) }) return
        hardAt = System.currentTimeMillis()
        hardText = message
    }

    /** The target could not be pathed to since [since] — retrying the same tile is pointless. */
    fun unreachableSince(since: Long): String? = reachText.takeIf { reachAt >= since && it.isNotEmpty() }

    /** A permanent refusal sent since [since] — the passage is dead and the edge should go down. */
    fun passageRefusedSince(since: Long): String? = hardText.takeIf { hardAt >= since && it.isNotEmpty() }

    /** A "not yet" sent since [since] — the passage is alive and something in the room has to finish first. */
    fun passageWaitingSince(since: Long): String? = waitText.takeIf { waitAt >= since && it.isNotEmpty() }
}
