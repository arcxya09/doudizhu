package com.arcxya.doudizhu

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

@RunWith(AndroidJUnit4::class)
class AudioStorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Isolates the tests from the player's saved soundtrack and sound preferences. */
    private fun withStorage(test: (Context, LocalMusicStore) -> Unit) {
        val base = instrumentation.targetContext
        val token = "audio-storage-${System.nanoTime()}"
        val directory = File(base.cacheDir, token).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$token-$name", mode)
        }
        try { test(context, LocalMusicStore(context)) }
        finally {
            directory.deleteRecursively()
            base.deleteSharedPreferences("$token-settings")
        }
    }

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return result!!.getOrThrow()
    }

    private fun await(message: String, timeoutMs: Long = 25000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        assertTrue(message, condition())
    }

    @Test fun brokenSavedAudioFallsBackOnceAndKeepsVolumesWithinRange() = withStorage { context, store ->
        val broken = store.stage(ByteArrayInputStream("not decodable audio".toByteArray()), "失效音乐.wav")
        store.commit(broken)
        context.getSharedPreferences("settings", 0).edit()
            .putInt("musicVolume", 140).putInt("effectVolume", -20).commit()
        val engine = onMain { AudioEngine(context) }
        try {
            await("invalid saved media must recover to the bundled track") {
                onMain { !engine.isMusicBusy && !engine.hasCustomMusic && engine.testState().durationMs > 0 }
            }
            await("the failed file must not be decoded again on the next launch") { !broken.file.exists() }
            assertNull(store.read())
            onMain {
                assertEquals(100, engine.musicVolume)
                assertEquals(0, engine.effectVolume)
                assertFalse("preparing audio alone must not start background playback", engine.testState().playing)
            }
        } finally { onMain { engine.release() } }
    }

    @Test fun failedCopiesCloseTheSourceAndPreserveTheSelectedTrack() = withStorage { context, store ->
        val selected = store.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "已选音乐")
        store.commit(selected)
        var closed = false
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("interrupted provider")
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) { store.stage(failing, "失败替换") }
        assertTrue("provider streams must close even on read failure", closed)
        assertThrows(IOException::class.java) { store.stage(ByteArrayInputStream(byteArrayOf()), "空文件") }
        assertEquals(selected, store.read())
        assertArrayEquals(byteArrayOf(1, 2, 3), selected.file.readBytes())
        assertEquals("failed imports must not leak temporary media", 1,
            File(context.filesDir, "local-music").listFiles()!!.count { it.extension == "audio" })
    }

    @Test fun atomicBackupAndLateCleanupKeepTheNewestCommittedSelection() = withStorage { context, store ->
        val previous = store.stage(ByteArrayInputStream(byteArrayOf(1)), "旧音乐")
        store.commit(previous)
        val metadata = File(context.filesDir, "local-music/selection.json")
        assertTrue(metadata.renameTo(File(metadata.path + ".bak")))
        assertEquals("an interrupted write can restore the last atomic backup", previous, store.read())
        val replacement = store.stage(ByteArrayInputStream(byteArrayOf(2)), "新音乐")
        store.commit(replacement)
        store.forgetIfSelected(previous)
        assertEquals("a delayed failure from the old player cannot erase the new selection", replacement, store.read())
        assertTrue(replacement.file.isFile)
        assertFalse(previous.file.exists())
        store.discardIfUnselected(replacement)
        assertTrue("release cleanup must preserve committed audio", replacement.file.isFile)
    }
}
