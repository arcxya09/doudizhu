package com.arcxya.doudizhu

import org.junit.Assert.*
import org.junit.Test

class AudioCuesTest {
    private fun cards(vararg ranks: Int): List<Int> {
        val counts = mutableMapOf<Int, Int>()
        return ranks.map { rank ->
            if (rank >= 16) rank + 36
            else (rank-3)*4 + counts.getOrDefault(rank, 0).also { counts[rank] = it+1 }
        }
    }

    @Test fun passingToPartnerDoesNotClaimThereIsNoLegalResponse() {
        val hand = cards(4, 5)
        val target = Rules.classify(cards(3))!!
        // Advanced AI can voluntarily pass so its farmer partner keeps control.
        val chosen = Rules.ai(hand, target, 1, 0, 2, listOf(3, 2, 2), 2)
        assertNull(chosen)
        assertTrue(Rules.moves(hand, target).isNotEmpty())
        assertEquals("pass", AudioCues.forMove(chosen, hand, target))

        val unbeatable = Rules.classify(cards(16, 17))!!
        assertEquals("cannot_beat", AudioCues.forMove(null, cards(14, 14, 14, 14), unbeatable))
        assertEquals("cannot_beat", AudioCues.forMove(null, cards(13), Rules.classify(cards(14))))
        assertEquals("pass", AudioCues.forMove(null, cards(15), Rules.classify(cards(14))))
    }

    @Test fun decliningTheScoreBidDoesNotAnnounceAnUnimplementedRobbingPhase() {
        // The game's zero-point action is “不叫”; the available “不抢” clip must
        // never be assigned to it. Use the neutral selection sound instead.
        assertEquals("select", AudioCues.forBid(0))
        for (points in 1..3) assertEquals("bid", AudioCues.forBid(points))
    }

    @Test fun allLegalAirplaneVariantsUseTheAirplaneAnnouncement() {
        val body = cards(3, 3, 3, 4, 4, 4)
        val fixtures = listOf(body, body+cards(7, 8), body+cards(7, 7, 8, 8))
        val expectedKinds = listOf(Kind.PLANE, Kind.PLANE_SINGLE, Kind.PLANE_PAIR)
        for ((index, played) in fixtures.withIndex()) {
            val move = Rules.classify(played)!!
            assertEquals(expectedKinds[index], move.kind)
            assertEquals("airplane", AudioCues.forMove(move, played, null))
        }
    }
}
