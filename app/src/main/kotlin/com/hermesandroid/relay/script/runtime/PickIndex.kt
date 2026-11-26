package com.hermesandroid.relay.script.runtime

/** Missing/empty groups invalidate a card; equal totals select the first card. */
internal fun pickIndex(
    readings: List<List<Int?>>,
    mode: String,
    fallbackIndex: Int,
    cardCount: Int,
): Int {
    require(cardCount > 0) { "invalid_script: pick requires at least one card" }
    require(mode == "min" || mode == "max") { "invalid_script: pick mode must be min or max" }
    var selected = fallbackIndex.coerceIn(0, cardCount - 1)
    var best: Long? = null
    repeat(cardCount) { index ->
        val groups = readings.getOrNull(index) ?: return@repeat
        if (groups.isEmpty() || groups.any { it == null }) return@repeat
        val total = groups.sumOf { it!!.toLong() }
        val previous = best
        if (previous == null || (mode == "min" && total < previous) || (mode == "max" && total > previous)) {
            selected = index
            best = total
        }
    }
    return selected
}
