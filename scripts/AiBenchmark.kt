package com.arcxya.doudizhu

import kotlin.random.Random

/** Standalone harness compiled by benchmark-ai.py; not shipped with the Android app. */
fun main(args: Array<String>) {
    val first = args[0].toInt()
    val games = args[1].toInt()
    val samples = args[2].toInt()
    fun match(newLandlord: Boolean, newFarmers: Boolean, landlordLevel: Int = 2, farmerLevel: Int = 2): Int {
        var wins = 0
        for (seed in first until first + games) {
            val rng = Random(seed)
            val game = Game.create(1, rng).apply { turn = 0; bid(3) }
            var steps = 0
            while (game.phase != "over" && steps++ < 650) {
                val current = if (game.turn == 0) newLandlord else newFarmers
                val level = if (game.turn == 0) landlordLevel else farmerLevel
                val move = if (current) Rules.ai(game.hands[game.turn], game.last, game.turn, game.landlord,
                    game.lastPlayer, game.hands.map { it.size }, level, rng)
                else BaselineRules.ai(game.hands[game.turn], game.last, game.turn, game.landlord,
                    game.lastPlayer, game.hands.map { it.size }, level, rng)
                check(move == null || Rules.beats(move, game.last))
                game.play(move?.cards ?: emptyList())
            }
            check(game.phase == "over") { "Unfinished game at seed $seed" }
            if (game.winner == 0) wins++
        }
        return wins
    }
    println("Seeds=$first..${first + games - 1}; seat 0 fixed as landlord; numbers are landlord wins")
    println("current-hard / current-easy = ${match(true,true,2,0)}/$games")
    println("current-easy / current-hard = ${match(true,true,0,2)}/$games")
    println("current-hard / baseline-hard = ${match(true,false)}/$games")
    println("baseline-hard / current-hard = ${match(false,true)}/$games")
    println("baseline-hard / baseline-hard = ${match(false,false)}/$games")

    fun cards(vararg ranks: Int): List<Int> {
        val used = mutableMapOf<Int,Int>()
        return ranks.map { r -> if (r >= 16) r+36 else (r-3)*4+(used[r] ?: 0).also { used[r]=it+1 } }
    }
    val dense = listOf(
        cards(3,3,3,4,4,4,5,5,5,6,6,6,7,7,8,8,9,9,10,10),
        cards(3,3,3,3,5,5,5,5,7,7,7,7,9,9,9,9,11,11,11,11),
        cards(3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12)
    )
    val hands = dense + (first until first + samples).map { seed ->
        val game = Game.create(2, Random(seed)); game.hands[0] + game.bottom
    }
    repeat(3) { Rules.ai(dense[0],null,0,0,-1,listOf(20,17,17),2,Random(1)) }
    val times = hands.map { hand ->
        val start = System.nanoTime()
        val move = Rules.ai(hand,null,0,0,-1,listOf(20,17,17),2,Random(1))
        val elapsed = (System.nanoTime()-start)/1_000_000.0
        check(move != null && move.cards.all { it in hand } && Rules.classify(move.cards) == move)
        elapsed
    }.sorted()
    println("Desktop JVM timing, ${hands.size} twenty-card hands: mean=${times.average()}ms, " +
        "p95=${times[((times.size-1)*.95).toInt()]}ms, max=${times.last()}ms")
    println("These fixed-role samples do not estimate general human-play win rates or phone latency.")
}
