package com.arcxya.doudizhu

import java.io.Serializable

/** Stored alongside the settled flag; older snapshots deserialize the new optional field as null. */
internal data class MatchRecord(val games: Int, val wins: Int, val score: Int): Serializable {
    companion object { private const val serialVersionUID = 1L }
}

internal fun usableRecord(record: MatchRecord): Boolean = record.games >= 0 && record.wins in 0..record.games &&
    kotlin.math.abs(record.score.toLong()) <= record.games.toLong() * 196608

/** Java deserialization can bypass Kotlin's null checks, so corrupt fields are rejected here too. */
internal fun usableTable(game: Game?): Boolean = runCatching { validTable(game) }.getOrDefault(false)

internal fun usableSnapshot(snapshot: TableSnapshot?, expectedFormat: Int): Boolean =
    snapshot != null && snapshot.format == expectedFormat && usableTable(snapshot.game) &&
        (snapshot.record == null || usableRecord(snapshot.record))

private fun validTable(g: Game?): Boolean {
    if (g == null || g.level !in 0..2 || g.turn !in 0..2) return false
    if (g.lastPlayer !in -1..2 || g.landlord !in -1..2 || g.bidder !in -1..2 || g.winner !in -1..2) return false
    if (g.phase !in setOf("bid", "redeal", "play", "over")) return false
    if (g.hands.size != 3 || g.bottom.size != 3 || g.status.size != 3 || g.played.size != 3) return false
    if (g.status.any { it.length > 256 }) return false
    if (g.bottom.toSet().size != 3 || g.bottom.any { it !in 0..53 }) return false
    val held = g.hands.flatten()
    if (held.any { it !in 0..53 } || held.toSet().size != held.size) return false
    if (g.bidCount !in 0..3 || g.highBid !in 0..3 || g.passes !in 0..1) return false
    // At most thirteen four-of-a-kind bombs, one rocket and one spring exist in one deck.
    if (g.multiplier !in 1..32768 || (g.multiplier and (g.multiplier - 1)) != 0) return false

    if (g.phase == "bid" || g.phase == "redeal") {
        if (g.landlord != -1 || g.winner != -1 || g.last != null || g.lastPlayer != -1) return false
        if (g.hands.any { it.size != 17 } || g.bottom.any { it in held }) return false
        if (g.played.any { it != 0 } || g.passes != 0 || g.multiplier != 1 || g.spring || g.settled || g.delta != 0) return false
        if (g.phase == "redeal") return g.bidCount == 3 && g.highBid == 0 && g.bidder == -1
        if (g.bidCount !in 0..2 || g.highBid !in 0..2) return false
        return if (g.highBid == 0) g.bidder == -1 else g.bidCount > 0 && g.bidder in 0..2 && g.bidder != g.turn
    }

    if (g.landlord !in 0..2 || g.bidder != g.landlord || g.highBid !in 1..3 || g.bidCount !in 1..3) return false
    if (g.highBid < 3 && g.bidCount != 3) return false
    for (seat in 0..2) {
        val initialSize = if (seat == g.landlord) 20 else 17
        val removed = initialSize - g.hands[seat].size
        if (removed < 0 || g.played[seat] !in 0..removed) return false
        if ((g.played[seat] == 0) != (removed == 0)) return false
        if (seat != g.landlord && g.hands[seat].any { it in g.bottom }) return false
    }
    val last = g.last
    if (last == null) {
        if (g.passes != 0) return false
        if (g.played.all { it == 0 }) {
            if (g.lastPlayer != -1 || g.turn != g.landlord) return false
        } else if (g.lastPlayer !in 0..2 || g.played[g.lastPlayer] == 0 || g.turn != g.lastPlayer) return false
    } else {
        if (g.lastPlayer !in 0..2 || g.played[g.lastPlayer] == 0 || last.cards.any { it in held }) return false
        val classified = Rules.classify(last.cards) ?: return false
        if (last.kind != classified.kind || last.key != classified.key || last.span != classified.span) return false
        if (g.phase == "play" && g.turn != (g.lastPlayer + g.passes + 1) % 3) return false
    }
    if (g.phase == "play") return g.hands.all { it.isNotEmpty() } && g.winner == -1 && !g.spring && !g.settled && g.delta == 0

    if (g.winner !in 0..2 || g.turn != g.winner || g.lastPlayer != g.winner || last == null || g.passes != 0) return false
    if (g.hands.indices.any { g.hands[it].isEmpty() != (it == g.winner) }) return false
    val spring = if (g.winner == g.landlord) (0..2).all { it == g.landlord || g.played[it] == 0 } else g.played[g.landlord] == 1
    if (g.spring != spring || (g.spring && g.multiplier < 2)) return false
    val won = (g.winner == g.landlord) == (g.landlord == 0)
    val delta = (if (won) 1 else -1) * g.highBid * g.multiplier * (if (g.landlord == 0) 2 else 1)
    return g.delta == delta
}
