package com.arcxya.doudizhu

/** Decide from the position before a move, while the actor's hand and target still exist. */
internal object AudioCues {
    fun forBid(points: Int) = if (points > 0) "bid" else "select"

    fun forMove(move: Move?, hand: List<Int>, target: Move?): String = when (move?.kind) {
        null -> if (target != null && Rules.moves(hand, target).isEmpty()) "cannot_beat" else "pass"
        Kind.BOMB -> "bomb"
        Kind.ROCKET -> "rocket"
        Kind.PLANE, Kind.PLANE_SINGLE, Kind.PLANE_PAIR -> "airplane"
        Kind.PAIR -> when (move.key) { 13 -> "pair_k"; 14 -> "pair_a"; 15 -> "pair_2"; else -> "play" }
        else -> "play"
    }
}
