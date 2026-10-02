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
    @Test fun moveGeneratorMatchesExhaustive(){val rng=Random(82);repeat(24){val h=Game.create(1,rng).hands[0].take(11);for(target in listOf(null,Rules.classify(cards(5)),Rules.classify(cards(4,4)))){
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
}
