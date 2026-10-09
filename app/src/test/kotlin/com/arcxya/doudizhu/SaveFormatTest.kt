package com.arcxya.doudizhu
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import kotlin.random.Random

/** Guards the stored-table formats. The release upgrade check installs a new build over an old one,
 *  restores the saved turn from the old record and requires that record file to stay unchanged, so a
 *  format change must be additive and must never make an existing file unreadable. */
class SaveFormatTest {
    /** Captured verbatim from a v3.6.0 installation, so this is the real legacy record. */
    private fun legacyBytes():ByteArray=javaClass.getResourceAsStream("/native-table-v2.bin")!!.use{it.readBytes()}

    @Test fun legacyRecordStaysReadableAndReserializesIdentically(){
        val restored=ObjectInputStream(ByteArrayInputStream(legacyBytes())).use{it.readObject()}
        assertTrue("v3.6.0 record must still deserialize",restored is SavedTable)
        val table=restored as SavedTable
        assertEquals(3,table.game.bottom.size)
        assertEquals(54,(table.game.hands.flatten()+table.game.bottom).toSet().size)
        assertTrue(table.game.landlord in 0..2)
        assertTrue(table.game.hands.all{it.isNotEmpty()})
        val rewritten=ByteArrayOutputStream().also{ObjectOutputStream(it).use{out->out.writeObject(table)}}
        assertArrayEquals("v3.6.0 records must re-serialize byte for byte",legacyBytes(),rewritten.toByteArray())
    }

    @Test fun currentSnapshotRoundTrips(){
        val game=Game.create(2,Random(11));game.turn=0;game.bid(3)
        val record=MatchRecord(7,4,18)
        val bytes=ByteArrayOutputStream().also{ObjectOutputStream(it).use{out->out.writeObject(TableSnapshot(1,game,record))}}
        val back=ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use{it.readObject()} as TableSnapshot
        assertEquals(1,back.format)
        assertEquals(game.hands.map{it.toList()},back.game.hands.map{it.toList()})
        assertEquals(game.bottom,back.game.bottom)
        assertEquals(game.landlord,back.game.landlord)
        assertEquals(game.turn,back.game.turn)
        assertEquals(game.phase,back.game.phase)
        assertEquals(game.highBid,back.game.highBid)
        assertEquals(record,back.record)
    }

    @Test fun snapshotWrittenBeforeRecordsWereEmbeddedStillLoads(){
        // Written with the pre-change TableSnapshot class (only format + game, pinned UID 1).
        val back=javaClass.getResourceAsStream("/native-table-v3-before-record.bin")!!.use { input ->
            ObjectInputStream(input).use { it.readObject() as TableSnapshot }
        }
        assertEquals(1,back.format)
        assertNull("a field absent from the old stream must remain optional",back.record)
        assertTrue(usableSnapshot(back,1))
    }

    @Test fun realLegacyTablePassesValidationAndBadOnesDoNot(){
        val table=ObjectInputStream(ByteArrayInputStream(legacyBytes())).use{it.readObject() as SavedTable}
        assertTrue("the real v3.6.0 record must stay usable",usableTable(table.game))
        assertFalse(usableTable(null))
        assertFalse("a missing seat must be rejected",usableTable(table.game.copy(hands=mutableListOf(mutableListOf(0)))))
        assertFalse("an out-of-range difficulty must be rejected",usableTable(table.game.copy(level=7)))
        assertFalse("play without a landlord must be rejected",usableTable(table.game.copy(phase="play",landlord=-1)))
        assertFalse("an unknown phase must be rejected",usableTable(table.game.copy(phase="paused")))
        assertFalse("a duplicated card must be rejected",usableTable(table.game.copy(hands=mutableListOf(mutableListOf(0,0),mutableListOf(4),mutableListOf(8)))))
    }

    private fun started():Game=Game.create(1,Random(23)).apply{turn=0;bid(3)}
    private fun afterLead():Game=started().apply{play(listOf(hands[turn].first()))}

    @Test fun corruptTurnTargetsAndCrossSeatCardsCannotReachRendering(){
        val lead=afterLead()
        assertTrue(usableTable(lead))
        assertFalse("lastPlayer=-1 previously crashed seatMoves during launch",usableTable(lead.copy(lastPlayer=-1)))
        assertFalse("a player cannot answer their own live target",usableTable(lead.copy(turn=lead.lastPlayer)))
        assertFalse("last target key must match its actual cards",usableTable(lead.copy(last=lead.last!!.copy(key=17))))
        assertFalse("last target kind must match its actual cards",usableTable(lead.copy(last=lead.last!!.copy(kind=Kind.ROCKET))))
        assertFalse("last target span must match its actual cards",usableTable(lead.copy(last=lead.last!!.copy(span=8))))
        assertFalse("target cards cannot still be in a hand",usableTable(lead.copy(last=Rules.classify(listOf(lead.hands[1].first())))))
        assertFalse("only zero or one unanswered pass can be stored",usableTable(lead.copy(passes=2)))
        assertFalse("a stored target cannot disappear before a round resets",usableTable(lead.copy(last=null)))
        assertFalse("invalid score factors must not enter settlement",usableTable(lead.copy(multiplier=3)))
        assertFalse("a live table cannot already have a winner",usableTable(lead.copy(winner=0)))
        assertFalse("a live table cannot already be settled",usableTable(lead.copy(settled=true)))
        assertFalse("landlord must be the winning bidder",usableTable(lead.copy(bidder=2)))
        val duplicated=started()
        duplicated.hands[1][0]=duplicated.hands[2][0]
        assertFalse("cards must be unique across all three seats",usableTable(duplicated))
        val wrongKitty=started()
        val kitty=wrongKitty.bottom.first()
        val swap=wrongKitty.hands[1][0]
        wrongKitty.hands[1][0]=kitty
        wrongKitty.hands[0][wrongKitty.hands[0].indexOf(kitty)]=swap
        assertFalse("farmers cannot own a landlord's bottom card",usableTable(wrongKitty))
    }

    @Test fun unsupportedFormatsAndDeserializedNullFieldsAreRejected(){
        assertTrue(usableSnapshot(TableSnapshot(1,started()),1))
        assertFalse(usableSnapshot(TableSnapshot(0,started()),1))
        assertFalse(usableSnapshot(TableSnapshot(2,started()),1))
        assertFalse(usableSnapshot(null,1))
        assertFalse(usableSnapshot(TableSnapshot(1,started(),MatchRecord(2,3,0)),1))
        assertFalse(usableSnapshot(TableSnapshot(1,started(),MatchRecord(-1,0,0)),1))
        val game=started()
        // ObjectInputStream does not invoke the Kotlin constructor's non-null checks.
        Game::class.java.getDeclaredField("hands").apply{isAccessible=true}.set(game,null)
        val bytes=ByteArrayOutputStream().also{ObjectOutputStream(it).use{out->out.writeObject(game)}}
        val restored=ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use{it.readObject()} as Game
        assertFalse("corrupt null fields are rejected instead of throwing",usableTable(restored))
    }

    @Test fun realStateMachineSnapshotsRemainValidAtEveryStage(){
        val redeal=Game.create(1,Random(1))
        repeat(3){assertTrue(usableTable(redeal));redeal.bid(0)}
        assertEquals("redeal",redeal.phase)
        assertTrue(usableTable(redeal))
        val lowBid=Game.create(1,Random(2))
        listOf(1,2,0).forEach{assertTrue(usableTable(lowBid));lowBid.bid(it)}
        assertTrue(usableTable(lowBid))
        for(seed in 0..23){
            val rng=Random(seed)
            val game=Game.create(seed%3,rng)
            assertTrue(usableTable(game));game.bid(3)
            var steps=0
            while(game.phase=="play"&&steps++<500){
                assertTrue("seed $seed before turn $steps",usableTable(game))
                val moves=Rules.moves(game.hands[game.turn],game.last)
                val move=if(game.last!=null&&(moves.isEmpty()||rng.nextInt(4)==0))null else moves.random(rng)
                game.play(move?.cards?:emptyList())
            }
            assertEquals("seed $seed finishes","over",game.phase)
            assertTrue("seed $seed final settlement",usableTable(game))
            game.settled=true
            assertTrue("persisted counted games remain valid",usableTable(game))
            assertFalse("the winner must match the empty hand",usableTable(game.copy(winner=(game.winner+1)%3)))
            assertFalse("settlement cannot claim an incorrect score",usableTable(game.copy(delta=game.delta+1)))
            assertFalse("spring must match public move counts",usableTable(game.copy(spring=!game.spring)))
        }
    }

    /** Pinned to the values Java derived before they were declared. Changing one would make every
     *  stored game unreadable, so the constants are asserted rather than recomputed. */
    @Test fun serializationIdsStayPinned(){
        assertEquals(-2247460378412267329L,ObjectStreamClass.lookup(Game::class.java).serialVersionUID)
        assertEquals(1167266074364606188L,ObjectStreamClass.lookup(Move::class.java).serialVersionUID)
        assertEquals(6861108738685167990L,ObjectStreamClass.lookup(SavedTable::class.java).serialVersionUID)
        assertEquals(1L,ObjectStreamClass.lookup(TableSnapshot::class.java).serialVersionUID)
        assertEquals(1L,ObjectStreamClass.lookup(MatchRecord::class.java).serialVersionUID)
    }
}
