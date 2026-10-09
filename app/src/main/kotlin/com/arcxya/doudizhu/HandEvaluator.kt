package com.arcxya.doudizhu

/** Minimum number of legal plays needed to empty our own hand, ignoring opponents' replies.
 *
 * A decision shares this cache across all candidate moves. Rank counts occupy four bits each;
 * the spare high bit lets us test containment without allocating new card lists in the search.
 * Every partition must include a play containing its lowest remaining rank, so only those plays
 * need to be explored. This avoids exploring every ordering of the same hand decomposition.
 */
internal class HandEvaluator(hand: List<Int>, legal: List<Move> = Rules.moves(hand)) {
    private data class Pattern(val code: Long, val size: Int)
    private val patterns = legal.map { Pattern(code(it.cards), it.cards.size) }
        .distinctBy { it.code }.sortedByDescending { it.size }
    private val byRank = Array(15) { r -> patterns.filter { (it.code ushr (r * 4)) and 15L != 0L } }
    private val onePlay = patterns.mapTo(hashSetOf()) { it.code }
    private val cache = hashMapOf(0L to 0)

    fun plays(cards: List<Int>): Int = search(code(cards), cards.size)

    private fun search(state: Long, size: Int): Int {
        cache[state]?.let { return it }
        if (state in onePlay) return 1
        val rank = java.lang.Long.numberOfTrailingZeros(state) / 4
        var best = size
        for (move in byRank[rank]) {
            if (((state or GUARDS) - move.code) and GUARDS != GUARDS) continue
            best = minOf(best, 1 + search(state - move.code, size - move.size))
            // We already ruled out a one-play finish, so two is the exact lower bound.
            if (best == 2) break
        }
        cache[state] = best
        return best
    }

    private companion object {
        const val GUARDS = 0x888888888888888L
        fun code(cards: List<Int>): Long {
            var state = 0L
            for (card in cards) state += 1L shl ((Rules.rank(card) - 3) * 4)
            return state
        }
    }
}
