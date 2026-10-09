package com.arcxya.doudizhu

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class SnapshotStorageTest {
    /** Exercise the Activity's real storage methods without opening a window or touching user saves. */
    private fun withStorage(test: (Context, () -> MainActivity) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val token = "snapshot-storage-${System.nanoTime()}"
        val directory = File(base.cacheDir, token).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$token-$name", mode)
        }
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                test(context) {
                    MainActivity().also { activity ->
                        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                            .apply { isAccessible = true }.invoke(activity, context)
                    }
                }
            } catch (error: Throwable) { failure = error }
        }
        directory.deleteRecursively()
        base.deleteSharedPreferences("$token-record")
        failure?.let { throw it }
    }

    private fun call(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)

    private fun setRecord(activity: MainActivity, record: MatchRecord) {
        listOf("games" to record.games, "wins" to record.wins, "score" to record.score).forEach { (name, value) ->
            MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.setInt(activity, value)
        }
    }

    private fun write(file: File, value: Any) = ObjectOutputStream(file.outputStream()).use { it.writeObject(value) }
    private fun game() = Game.create(1, Random(40)).apply { turn = 0; bid(3) }

    @Test fun authoritativeSnapshotRepairsAnOlderRecordMirror() = withStorage { context, create ->
        val expected = MatchRecord(10, 6, 24)
        val file = File(context.filesDir, "native-table-v3")
        write(file, TableSnapshot(1, game(), expected))
        val record = context.getSharedPreferences("record", 0)
        record.edit().putInt("games", 9).putInt("wins", 5).putInt("score", 18).commit()
        val activity = create()
        assertEquals(true, call(activity, "restore"))
        assertEquals(expected.games, record.getInt("games", -1))
        assertEquals(expected.wins, record.getInt("wins", -1))
        assertEquals(expected.score, record.getInt("score", -1))
        assertTrue(usableTable(activity.game))
    }

    @Test fun failedSnapshotWriteCannotPublishANewerScore() = withStorage { context, create ->
        val before = MatchRecord(3, 1, -6)
        val after = MatchRecord(4, 2, 6)
        val file = File(context.filesDir, "native-table-v3")
        write(file, TableSnapshot(1, game(), before))
        val activity = create()
        assertEquals(true, call(activity, "restore"))
        val oldBytes = file.readBytes()
        setRecord(activity, after)
        // AtomicFile cannot create its temporary output when that path is a directory.
        val blockedOutput = File(file.path + ".new").apply { mkdirs() }
        assertTrue(blockedOutput.isDirectory)
        call(activity, "persist")
        assertArrayEquals("a failed write preserves the previous table", oldBytes, file.readBytes())
        val record = context.getSharedPreferences("record", 0)
        assertEquals("the result must not be counted ahead of the table", before.games, record.getInt("games", -1))
        assertEquals(before.wins, record.getInt("wins", -1))
        assertEquals(before.score, record.getInt("score", 0))
        blockedOutput.delete()
        call(activity, "persist")
        val saved = ObjectInputStream(file.inputStream()).use { it.readObject() as TableSnapshot }
        assertEquals("a later successful save commits both together", after, saved.record)
        assertEquals(after.games, record.getInt("games", -1))
    }

    @Test fun missingBaseRecoversBackupButCorruptCurrentNeverReplaysLegacy() = withStorage { context, create ->
        val record = MatchRecord(12, 8, 30)
        val current = File(context.filesDir, "native-table-v3")
        write(File(current.path + ".bak"), TableSnapshot(1, game(), record))
        assertEquals(true, call(create(), "restore"))
        assertTrue("AtomicFile restores the backup when the base is absent", current.isFile)
        write(File(context.filesDir, "native-table-v2"), SavedTable(game(), 1, 1, 6))
        current.writeText("corrupt latest snapshot")
        val activity = create()
        assertEquals("an outdated v2 table must not revive after current corruption", false, call(activity, "restore"))
        assertEquals("bid", activity.game.phase)
        assertEquals("independent record survives current-table corruption", 12,
            context.getSharedPreferences("record", 0).getInt("games", -1))
    }
}
