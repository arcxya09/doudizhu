package com.arcxya.doudizhu

import java.io.Serializable
import kotlin.random.Random

enum class Kind(val title: String) { SINGLE("单张"), PAIR("对子"), TRIPLE("三张"), TRIPLE_SINGLE("三带一"), TRIPLE_PAIR("三带二"), STRAIGHT("顺子"), PAIRS("连对"), PLANE("飞机"), PLANE_SINGLE("飞机带单"), PLANE_PAIR("飞机带对"), FOUR_SINGLE("四带二"), FOUR_PAIR("四带两对"), BOMB("炸弹"), ROCKET("王炸") }
data class Move(val cards: List<Int>, val kind: Kind, val key: Int, val span: Int = 1): Serializable {
    // Pinned to the value Java derived before this was declared. Without it, any later field
    // change would alter the computed UID and make stored games unreadable.
    companion object { private const val serialVersionUID = 1167266074364606188L }
}
object Rules {
    fun rank(c: Int) = if (c < 52) 3 + c / 4 else c - 36
    fun sorted(cards: List<Int>) = cards.sortedWith(compareBy({rank(it)}, {it}))
    private fun groups(cards: List<Int>) = sorted(cards).groupBy { rank(it) }
    private fun consecutive(rs: List<Int>) = rs.last() < 15 && rs.zipWithNext().all { (a,b) -> b == a + 1 }
    fun classify(cards: List<Int>): Move? {
        if (cards.isEmpty() || cards.toSet().size != cards.size || cards.any { it !in 0..53 }) return null
        val cs = sorted(cards); val g = groups(cs); val rs = g.keys.toList(); val counts = g.values.map { it.size }; val n = cs.size
        fun m(k: Kind, key: Int, span: Int = 1) = Move(cs,k,key,span)
        if (cs == listOf(52,53)) return m(Kind.ROCKET,17)
        if (rs.size == 1) return m(listOf(Kind.SINGLE,Kind.PAIR,Kind.TRIPLE,Kind.BOMB)[n-1],rs[0])
        if (n == 4 && 3 in counts) return m(Kind.TRIPLE_SINGLE,rs[counts.indexOf(3)])
        if (n == 5 && 3 in counts && 2 in counts) return m(Kind.TRIPLE_PAIR,rs[counts.indexOf(3)])
        if (n >= 5 && counts.all { it == 1 } && consecutive(rs)) return m(Kind.STRAIGHT,rs.last(),rs.size)
        if (n >= 6 && counts.all { it == 2 } && consecutive(rs)) return m(Kind.PAIRS,rs.last(),rs.size)
        if (n >= 6 && counts.all { it == 3 } && consecutive(rs)) return m(Kind.PLANE,rs.last(),rs.size)
        for (wing in 1..2) {
            if (n % (3+wing) != 0) continue
            val k = n / (3+wing); if (k < 2) continue
            for (start in 3..(15-k)) {
                val body = start until start+k
                if (!body.all { g[it]?.size == 3 }) continue
                val rest = rs.filter { it !in body }
                if (wing == 1 && !(16 in rs && 17 in rs) && rest.sumOf { g.getValue(it).size } == k) return m(Kind.PLANE_SINGLE,start+k-1,k)
                if (wing == 2 && rest.size == k && rest.all { g[it]?.size == 2 }) return m(Kind.PLANE_PAIR,start+k-1,k)
            }
        }
        if (n == 6 && 4 in counts && !(16 in rs && 17 in rs)) return m(Kind.FOUR_SINGLE,rs[counts.indexOf(4)])
        if (n == 8 && counts.count { it == 4 } == 1 && counts.count { it == 2 } == 2) return m(Kind.FOUR_PAIR,rs[counts.indexOf(4)])
        return null
    }
    fun beats(a: Move?, b: Move?): Boolean {
        if (a == null) return false
        if (b == null) return true
        if (b.kind == Kind.ROCKET) return false
        if (a.kind == Kind.ROCKET || a.kind == Kind.BOMB && b.kind != Kind.BOMB) return true
        return a.kind == b.kind && a.cards.size == b.cards.size && a.span == b.span && a.key > b.key
    }
    private fun choose(xs: List<Int>, k: Int, use: (List<Int>) -> Unit, start: Int = 0, acc: List<Int> = emptyList()) {
        if (k == 0) { use(acc); return }
        if (k < 0 || xs.size-start < k) return
        for (i in start..xs.size-k) choose(xs,k-1,use,i+1,acc+xs[i])
    }
    fun moves(hand: List<Int>, target: Move? = null): List<Move> {
        val g = groups(hand); val rs = g.keys.toList(); val out = linkedMapOf<List<Int>,Move>()
        fun add(cs: List<Int>) { val m = classify(cs); if (beats(m,target)) out[m!!.cards.map { rank(it) }] = m }
        for (r in rs) {
            for (n in 1..g.getValue(r).size) add(g.getValue(r).take(n))
            if (g.getValue(r).size >= 3) for (s in rs.filter { it != r }) {
                add(g.getValue(r).take(3)+g.getValue(s).take(1))
                if (g.getValue(s).size >= 2) add(g.getValue(r).take(3)+g.getValue(s).take(2))
            }
        }
        if (16 in rs && 17 in rs) add(listOf(52,53))
        for (mult in 1..3) for (start in 3..14) {
            var body = emptyList<Int>()
            for (end in start..14) {
                if ((g[end]?.size ?: 0) < mult) break
                body = body + g.getValue(end).take(mult)
                val k = end-start+1
                if (k < when(mult) { 1 -> 5; 2 -> 3; else -> 2 }) continue
                add(body)
                if (mult != 3) continue
                val others = rs.filter { it !in start..end }
                if (4*k <= hand.size) choose(others.flatMap { g.getValue(it) },k,{ add(body+it) })
                if (5*k <= hand.size) choose(others.filter { g.getValue(it).size >= 2 },k,{ w -> add(body+w.flatMap { g.getValue(it).take(2) }) })
            }
        }
        for (r in rs.filter { g.getValue(it).size == 4 }) {
            val others = rs.filter { it != r }
            choose(others.flatMap { g.getValue(it) },2,{ add(g.getValue(r)+it) })
            choose(others.filter { g.getValue(it).size >= 2 },2,{ w -> add(g.getValue(r)+w.flatMap { g.getValue(it).take(2) }) })
        }
        return out.values.toList()
    }
    /** Longest run of consecutive ranks that each hold at least `need` cards, if one is playable. */
    private fun longestRun(g: Map<Int,Int>, need: Int): List<Int>? {
        val least = when (need) { 1 -> 5; 2 -> 3; else -> 2 }
        var best: List<Int>? = null
        var start = 3
        while (start <= 14) {
            if ((g[start] ?: 0) < need) { start++; continue }
            var end = start
            while (end < 14 && (g[end + 1] ?: 0) >= need) end++
            val run = (start..end).toList()
            if (run.size >= least && run.size > (best?.size ?: 0)) best = run
            start = end + 1
        }
        return best
    }
    /** Empties a hand by repeatedly taking the longest run of each shape, then counting what is left. */
    private fun decompose(counts: Map<Int,Int>, order: IntArray): Int {
        val left = counts.toMutableMap(); var plays = 0
        if (16 in left && 17 in left) { plays++; left.remove(16); left.remove(17) }
        for (r in left.keys.toList()) if (left[r] == 4) { plays++; left.remove(r) }
        for (need in order) while (true) {
            val run = longestRun(left, need) ?: break
            for (r in run) { val rest = left.getValue(r) - need; if (rest <= 0) left.remove(r) else left[r] = rest }
            plays++
        }
        val triples = left.values.count { it == 3 }
        val singles = left.values.count { it == 1 }
        plays += triples + left.values.count { it == 2 } + singles
        // A triple can carry one single, so that attachment is not a play of its own.
        return plays - minOf(triples, singles)
    }
    /** How many plays the hand still needs. Taking the runs in a different order changes the count, so
     *  a few sensible orders are tried and the cheapest kept. This replaces a per-rank weighting that
     *  could not tell a five-card straight (one play) from five loose singles (five plays). */
    fun handsLeft(hand: List<Int>): Int {
        val counts = groups(hand).mapValues { it.value.size }
        return DECOMPOSE_ORDERS.minOf { decompose(counts, it) }
    }
    private val DECOMPOSE_ORDERS = listOf(intArrayOf(3,1,2), intArrayOf(1,3,2), intArrayOf(2,1,3), intArrayOf(1,2,3))
    // AI receives only its own cards and public information; never opponents' cards.
    fun ai(hand: List<Int>, target: Move?, player: Int, landlord: Int, lastPlayer: Int, counts: List<Int>, level: Int, rng: Random = Random.Default): Move? {
        val ms = moves(hand,target); if (ms.isEmpty()) return null
        ms.firstOrNull { it.cards.size == hand.size }?.let { return it }
        if (target != null && player != landlord && lastPlayer != landlord && (level > 0 || rng.nextDouble() < .8)) return null
        if (level == 0) {
            if (target != null && rng.nextDouble() < .16) return null
            val ordinary = ms.filter { it.kind != Kind.BOMB && it.kind != Kind.ROCKET }
            return (ordinary.ifEmpty { ms }).random(rng)
        }
        val danger = counts.indices.any { it != player && (player == landlord || it == landlord) && counts[it] <= 2 }
        val next = (player+1)%3
        // The teammate plays right after me and is nearly out: a small single lets them finish.
        val feeding = target == null && player != landlord && next != landlord && counts[next] <= 2
        fun score(m: Move): Double {
            val left = hand - m.cards.toSet(); var s = handsLeft(left)*3.0 + m.key*.08
            // Twos and jokers hold the lead, so spending one has to buy something.
            s -= left.count { rank(it) >= 15 } * .9
            if (m.kind == Kind.BOMB || m.kind == Kind.ROCKET) s += if(target == null) 11 else 7
            if (feeding) { if (m.kind == Kind.SINGLE) s += m.key*.9 else s += 6 }
            if (level == 2) {
                val before = groups(hand); val after = groups(left)
                for (r in before.keys) if (before[r]?.size == 4 && after[r] != null && after[r]!!.size < 4) s += 4
                if (danger && target != null) s -= m.key*.45
                if (danger && target == null) {
                    // Someone is about to win: a wide play they cannot answer keeps the lead, and of
                    // the singles the highest is the hardest to beat. The two rank different kinds, so
                    // only one applies per move.
                    if (m.kind == Kind.SINGLE) s -= m.key*.6 else s -= m.cards.size*1.2
                }
            }
            return s
        }
        val ranked = ms.map { it to score(it) }
        val best = ranked.minOf { it.second }
        // Moves inside this margin are interchangeable, so picking among them removes the one fixed
        // line a deterministic choice would repeat for the same hand without weakening the play.
        val pool = ranked.filter { it.second <= best + .25 }.map { it.first }
        return pool.random(rng)
    }
    fun bid(hand: List<Int>, current: Int, level: Int, rng: Random = Random.Default): Int {
        val g = groups(hand)
        val strength = (if (53 in hand) 2.5 else 0.0) + (if (52 in hand) 1.6 else 0.0) + (g[15]?.size ?: 0)*.8 + g.values.count { it.size == 4 }*2 + (g[14]?.size ?: 0)*.25 + if(level == 0) rng.nextDouble()*3-1.5 else 0.0
        val value = when { strength >= 6 -> 3; strength >= 4 -> 2; strength >= 2.3 -> 1; else -> 0 }
        return if (value > current) value else 0
    }
    fun cardName(c: Int): String {
        val r=rank(c); if(r==16)return "小王";if(r==17)return "大王"
        return listOf("黑桃","红桃","梅花","方块")[c%4] + when(r){11->"J";12->"Q";13->"K";14->"A";15->"2";else->r.toString()}
    }
}

data class Game(
    var hands: MutableList<MutableList<Int>>, val bottom: List<Int>, val level: Int,
    var turn: Int, var phase: String = "bid", var bidCount: Int = 0, var highBid: Int = 0,
    var bidder: Int = -1, var landlord: Int = -1, var last: Move? = null, var lastPlayer: Int = -1,
    var passes: Int = 0, var multiplier: Int = 1, val played: IntArray = intArrayOf(0,0,0),
    val status: MutableList<String> = mutableListOf("等待叫分","等待叫分","等待叫分"),
    var winner: Int = -1, var spring: Boolean = false, var settled: Boolean = false, var delta: Int = 0
): Serializable {
    companion object {
        // Pinned to the value Java derived before this was declared, so saved tables written by
        // earlier builds stay readable and future fields default instead of invalidating the class.
        private const val serialVersionUID = -2247460378412267329L
        fun create(level: Int, rng: Random = Random.Default): Game {
            val deck = (0..53).shuffled(rng)
            return Game((0..2).map { Rules.sorted(deck.subList(it*17,it*17+17)).toMutableList() }.toMutableList(),deck.takeLast(3),level,rng.nextInt(3))
        }
    }
    fun bid(value: Int) {
        require(phase == "bid" && value in 0..3 && (value == 0 || value > highBid)) { "叫分无效" }
        status[turn] = if(value == 0) "不叫" else "叫 $value 分"
        if(value > 0) { highBid=value; bidder=turn }; bidCount++
        if(value == 3 || bidCount == 3) {
            if(bidder < 0) { phase="redeal";return }
            landlord=bidder; hands[landlord] = Rules.sorted(hands[landlord]+bottom).toMutableList(); turn=landlord; phase="play"
            for(i in 0..2) status[i]="等待出牌"
        } else turn=(turn+1)%3
    }
    fun play(cards: List<Int>) {
        require(phase == "play") { "尚未开始出牌" }; val p=turn
        if(cards.isEmpty()) {
            require(last != null) { "自由出牌时不能不出" }; status[p]="不出";passes++
            if(passes == 2) { last=null;passes=0 };turn=(p+1)%3;return
        }
        require(cards.all { it in hands[p] }) { "手牌不存在" }
        val m = Rules.classify(cards);require(Rules.beats(m,last)) { "牌型不合法或压不过上家" }
        hands[p].removeAll(cards.toSet());last=m;lastPlayer=p;passes=0;played[p]++;status[p]="${m!!.kind.title} · ${cards.size}张"
        if(m.kind == Kind.BOMB || m.kind == Kind.ROCKET) multiplier*=2
        if(hands[p].isEmpty()) {
            phase="over";winner=p
            spring = if(p == landlord) (0..2).all { it == landlord || played[it] == 0 } else played[landlord] == 1
            if(spring) multiplier*=2
            val won=(winner == landlord) == (landlord == 0)
            delta=(if(won)1 else -1)*highBid*multiplier*(if(landlord==0)2 else 1)
        } else turn=(p+1)%3
    }
    fun computer(rng: Random = Random.Default): Move? = Rules.ai(hands[turn],last,turn,landlord,lastPlayer,hands.map { it.size },level,rng)
}
