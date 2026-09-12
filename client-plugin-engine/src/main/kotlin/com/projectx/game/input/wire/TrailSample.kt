package com.projectx.game.input.wire

/**
 * One cursor position on the server-bound trail.
 *
 * [timestampMs] must come from the same clock the client stamps its own samples with, because the encoder
 * delta-encodes against the previously sent sample and quantises the result — a timestamp from any other
 * source produces deltas that do not match the cadence a real client emits.
 */
data class TrailSample(val x: Int, val y: Int, val timestampMs: Long)
