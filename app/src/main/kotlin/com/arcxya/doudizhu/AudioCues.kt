package com.arcxya.doudizhu

/** Decide from the position before a move, while the actor's hand and target still exist. */
internal object AudioCues {
    /** The human picks a score too, so both sides of the table announce their own points. */
    fun forBid(points: Int) = if (points in 1..3) "bid_$points" else "bid_pass"

    fun forMove(move: Move?, hand: List<Int>, target: Move?): String = when (move?.kind) {
        null -> if (target != null && Rules.moves(hand, target).isEmpty()) "cannot_beat" else "pass"
        Kind.BOMB -> "bomb"
        Kind.ROCKET -> "rocket"
        Kind.PLANE, Kind.PLANE_SINGLE, Kind.PLANE_PAIR -> "airplane"
        Kind.SINGLE -> "single_${move.key}"
        Kind.TRIPLE -> "triple_${move.key}"
        Kind.STRAIGHT -> "straight"
        Kind.PAIRS -> "pairs"
        Kind.TRIPLE_SINGLE -> "triple_single"
        Kind.TRIPLE_PAIR -> "triple_pair"
        Kind.FOUR_SINGLE -> "four_single"
        Kind.FOUR_PAIR -> "four_pair"
        Kind.PAIR -> when (move.key) { 13 -> "pair_k"; 14 -> "pair_a"; 15 -> "pair_2"; else -> "pair_${move.key}" }
        else -> "play"
    }
}
