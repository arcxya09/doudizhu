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
        val bytes=ByteArrayOutputStream().also{ObjectOutputStream(it).use{out->out.writeObject(TableSnapshot(1,game))}}
        val back=ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use{it.readObject()} as TableSnapshot
        assertEquals(1,back.format)
        assertEquals(game.hands.map{it.toList()},back.game.hands.map{it.toList()})
        assertEquals(game.bottom,back.game.bottom)
        assertEquals(game.landlord,back.game.landlord)
        assertEquals(game.turn,back.game.turn)
        assertEquals(game.phase,back.game.phase)
        assertEquals(game.highBid,back.game.highBid)
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

    /** Pinned to the values Java derived before they were declared. Changing one would make every
     *  stored game unreadable, so the constants are asserted rather than recomputed. */
    @Test fun serializationIdsStayPinned(){
        assertEquals(-2247460378412267329L,ObjectStreamClass.lookup(Game::class.java).serialVersionUID)
        assertEquals(1167266074364606188L,ObjectStreamClass.lookup(Move::class.java).serialVersionUID)
        assertEquals(6861108738685167990L,ObjectStreamClass.lookup(SavedTable::class.java).serialVersionUID)
        assertEquals(1L,ObjectStreamClass.lookup(TableSnapshot::class.java).serialVersionUID)
    }
}
