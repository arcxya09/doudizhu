package com.arcxya.doudizhu
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random
class RulesTest {
    private fun cards(vararg ranks:Int):List<Int>{val counts=mutableMapOf<Int,Int>();return ranks.map{r->if(r>=16)r+36 else (r-3)*4+(counts[r]?:0).also{counts[r]=it+1}}}
    @Test fun patterns(){
        val cases=listOf(cards(3) to Kind.SINGLE,cards(4,4) to Kind.PAIR,cards(5,5,5) to Kind.TRIPLE,cards(6,6,6,3) to Kind.TRIPLE_SINGLE,cards(6,6,6,3,3) to Kind.TRIPLE_PAIR,cards(3,4,5,6,7) to Kind.STRAIGHT,cards(3,3,4,4,5,5) to Kind.PAIRS,cards(3,3,3,4,4,4) to Kind.PLANE,cards(3,3,3,4,4,4,7,7) to Kind.PLANE_SINGLE,cards(3,3,3,4,4,4,7,7,8,8) to Kind.PLANE_PAIR,cards(5,5,5,5,7,7) to Kind.FOUR_SINGLE,cards(5,5,5,5,7,7,8,8) to Kind.FOUR_PAIR,cards(7,7,7,7) to Kind.BOMB,cards(16,17) to Kind.ROCKET)
        cases.forEach{(cs,kind)->assertEquals(kind,Rules.classify(cs)?.kind)}
        listOf(cards(11,12,13,14,15),cards(3,3,4,4),cards(3,3,3,4,4,4,16,17),cards(5,5,5,5,7,7,7,7),listOf(0,0),listOf(54)).forEach{assertNull(Rules.classify(it))}
    }
    @Test fun moveGeneratorMatchesExhaustive(){val rng=Random(82);repeat(24){val h=Game.create(1,rng).hands[0].shuffled(rng).take(11);for(target in listOf(null,Rules.classify(cards(5)),Rules.classify(cards(4,4)))){
        val expected=mutableSetOf<List<Int>>();for(mask in 1 until (1 shl h.size)){val cs=h.filterIndexed{i,_->mask and (1 shl i)!=0};if(Rules.beats(Rules.classify(cs),target))expected.add(Rules.sorted(cs).map{Rules.rank(it)})}
        assertEquals(expected,Rules.moves(h,target).map{it.cards.map{c->Rules.rank(c)}}.toSet())
    }}}
    @Test fun biddingAndPasses(){val g=Game.create(1,Random(7));g.turn=0;g.bid(1);g.bid(0);g.bid(0);assertEquals(20,g.hands[0].size);assertThrows(IllegalArgumentException::class.java){g.play(emptyList())};g.play(listOf(g.hands[0][0]));g.play(emptyList());g.play(emptyList());assertEquals(0,g.turn);assertNull(g.last)}
    @Test fun springScoring(){for(win in listOf(true,false)){val g=Game.create(1);g.phase="play";g.landlord=0;g.highBid=2;g.hands=mutableListOf(mutableListOf(0),mutableListOf(4),mutableListOf(8));g.turn=if(win)0 else 1;if(!win){g.played[0]=1;g.played[1]=1};g.play(listOf(if(win)0 else 4));assertEquals("over",g.phase);assertTrue(g.spring);assertEquals(2,g.multiplier);assertEquals(if(win)8 else -8,g.delta)}}
    @Test fun completeGames(){for(level in 0..2)for(seed in 1..60){val rng=Random(seed);var g=Game.create(level,rng);val used=mutableListOf<Int>();var steps=0
        while(g.phase!="over"&&steps++<650){when(g.phase){"redeal"->{g=Game.create(level,rng);used.clear()};"bid"->g.bid(Rules.bid(g.hands[g.turn],g.highBid,level,rng));else->{val m=g.computer(rng);if(m!=null){assertTrue(Rules.beats(m,g.last));used.addAll(m.cards)};g.play(m?.cards?:emptyList());val all=g.hands.flatten()+used;assertEquals(54,all.size);assertEquals(54,all.toSet().size)}}}
        assertEquals("level=$level seed=$seed","over",g.phase)
    }}
    @Test fun cooperation(){assertNull(Rules.ai(cards(4,5),Rules.classify(cards(3)),1,0,2,listOf(3,2,2),2));assertEquals(4,Rules.ai(cards(4),Rules.classify(cards(3)),1,0,2,listOf(3,1,2),2)?.key)}
    @Test fun hardAiVariesAmongEquivalentLeads(){
        val hand=cards(3,5,7,9,11);val rng=Random(2024)
        val picks=(1..240).map{Rules.ai(hand,null,0,0,-1,listOf(5,17,17),2,rng)?.key}.toSet()
        assertTrue("a fixed line would repeat one lead: $picks",picks.size>1)
        assertTrue("only interchangeable cheap leads may be chosen: $picks",picks.all{it!=null&&it<=5})
    }
    @Test fun randomizedPickStaysLegal(){
        val rng=Random(4242);var varied=0
        repeat(300){
            val hand=Game.create(1,rng).hands[0];val legal=Rules.moves(hand,null).toSet()
            val picks=(1..12).map{Rules.ai(hand,null,0,0,-1,listOf(hand.size,17,17),2,rng)!!}
            picks.forEach{assertTrue("every randomized choice must stay legal: $it",it in legal)}
            if(picks.map{it.cards}.toSet().size>1)varied++
        }
        assertTrue("realistic hands should offer interchangeable leads",varied>0)
    }
    @Test fun exhaustiveSampleReachesHighCards(){
        val rng=Random(82);val samples=(1..24).map{Game.create(1,rng).hands[0].shuffled(rng).take(11)}
        assertTrue("a low-card-only sample would skip the 2 and joker paths",samples.any{it.any{Rules.rank(it)==15}})
        assertTrue("a low-card-only sample would skip the joker paths",samples.any{it.any{Rules.rank(it)>=16}})
    }
    @Test fun patternEdgeCases(){
        assertEquals(Kind.FOUR_SINGLE,Rules.classify(cards(5,5,5,5,7,8))?.kind)
        assertEquals(Kind.FOUR_SINGLE,Rules.classify(cards(15,15,15,15,7,8))?.kind)
        assertNull("both jokers may not be the two wings",Rules.classify(cards(5,5,5,5,16,17)))
        assertEquals(Kind.PLANE,Rules.classify(cards(3,3,3,4,4,4,5,5,5))?.kind)
        assertEquals(Kind.PLANE,Rules.classify(cards(3,3,3,4,4,4,5,5,5,6,6,6))?.kind)
        assertEquals(Kind.PLANE_SINGLE,Rules.classify(cards(3,3,3,4,4,4,5,5,5,7,8,9))?.kind)
        assertEquals(Kind.PLANE_PAIR,Rules.classify(cards(3,3,3,4,4,4,5,5,5,7,7,8,8,9,9))?.kind)
        assertNull("a 2 may not close a straight",Rules.classify(cards(11,12,13,14,15)))
        assertNull("a joker may not join a straight",Rules.classify(cards(3,4,5,6,16)))
        assertNull("a 2 may not close a run of pairs",Rules.classify(cards(13,13,14,14,15,15)))
        assertNull("a 2 may not be in a plane body",Rules.classify(cards(14,14,14,15,15,15)))
        assertEquals(Kind.STRAIGHT,Rules.classify(cards(3,4,5,6,7,8))?.kind)
        assertEquals(Kind.STRAIGHT,Rules.classify(cards(3,4,5,6,7,8,9,10,11,12,13,14))?.kind)
        assertEquals(Kind.PAIRS,Rules.classify(cards(3,3,4,4,5,5,6,6))?.kind)
        assertEquals(Kind.PAIRS,Rules.classify(cards(12,12,13,13,14,14))?.kind)
        assertEquals(Kind.TRIPLE_SINGLE,Rules.classify(cards(6,6,6,17))?.kind)
    }
    @Test fun beatsMatrix(){
        fun m(vararg ranks:Int)=Rules.classify(cards(*ranks))
        val single5=m(5);val single6=m(6);val bomb3=m(3,3,3,3);val bomb4=m(4,4,4,4);val rocket=m(16,17)
        val straight6=m(3,4,5,6,7,8)
        assertTrue(Rules.beats(single6,single5));assertFalse(Rules.beats(single5,single6))
        assertFalse("a different pattern never beats",Rules.beats(m(5,5),single5))
        assertFalse("an equal key does not beat",Rules.beats(single5,single5))
        assertTrue(Rules.beats(bomb3,single5));assertTrue(Rules.beats(bomb4,bomb3));assertFalse(Rules.beats(bomb3,bomb4))
        assertTrue(Rules.beats(rocket,bomb4));assertFalse("nothing beats the rocket",Rules.beats(rocket,rocket))
        assertFalse(Rules.beats(bomb4,rocket))
        assertTrue(Rules.beats(single5,null));assertFalse(Rules.beats(null,single5))
        assertFalse("a straight must match length",Rules.beats(straight6,m(4,5,6,7,8)))
        assertTrue(Rules.beats(m(4,5,6,7,8,9),straight6));assertFalse(Rules.beats(straight6,m(4,5,6,7,8,9)))
    }
    @Test fun scoringMatrix(){
        fun settled(landlord:Int,winner:Int,bid:Int):Game{
            val g=Game.create(1);g.phase="play";g.landlord=landlord;g.highBid=bid
            g.hands=mutableListOf(mutableListOf(0),mutableListOf(4),mutableListOf(8))
            // Two plays by every other seat keeps spring and anti-spring out of the way.
            for(p in 0..2)if(p!=winner)g.played[p]=2
            g.played[winner]=2;g.turn=winner
            g.play(listOf(winner*4))
            return g
        }
        // Player 0 is a farmer unless the landlord is 0; farmers settle at base x multiplier.
        assertEquals(-2,settled(landlord=1,winner=1,bid=2).delta)
        assertEquals(2,settled(landlord=1,winner=0,bid=2).delta)
        assertEquals(-2,settled(landlord=2,winner=2,bid=2).delta)
        // The landlord settles at base x multiplier x 2.
        assertEquals(-4,settled(landlord=0,winner=1,bid=2).delta)
        assertEquals(4,settled(landlord=0,winner=0,bid=2).delta)
    }
    @Test fun bombsDoubleTheMultiplier(){
        val bomb=Game.create(1).apply{phase="play";landlord=0;highBid=1;turn=0
            hands=mutableListOf(mutableListOf(0,1,2,3,20),mutableListOf(4),mutableListOf(8))}
        bomb.play(listOf(0,1,2,3));assertEquals(2,bomb.multiplier)
        val rocket=Game.create(1).apply{phase="play";landlord=0;highBid=1;turn=0
            hands=mutableListOf(mutableListOf(52,53,20),mutableListOf(4),mutableListOf(8))}
        rocket.play(listOf(52,53));assertEquals(2,rocket.multiplier)
    }
    @Test fun redealAndRejections(){
        val g=Game.create(1);g.turn=0;g.bid(0);g.bid(0);g.bid(0)
        assertEquals("redeal",g.phase);assertEquals(-1,g.landlord)
        val h=Game.create(1);h.turn=0;h.bid(1)
        assertThrows(IllegalArgumentException::class.java){h.bid(1)}
        assertThrows(IllegalArgumentException::class.java){h.play(listOf(99))}
    }
}
