package com.arcxya.doudizhu

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class NativeUiTest {
    private fun all(v: View): List<View> = listOf(v) +
        (if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList())

    private fun <T> onMain(inst: Instrumentation, block: () -> T): T {
        val result = AtomicReference<T>()
        inst.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun await(message: String, timeout: Long = 10000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue(message, condition())
    }

    /** Inject a real screen tap, using fresh native bounds rather than a stale accessibility node. */
    private fun tap(inst: Instrumentation, device: UiDevice, activity: MainActivity, label: String) {
        assertTrue("Visible action: $label", device.wait(Until.hasObject(By.text(label).enabled(true)), 5000))
        val bounds = onMain(inst) {
            val button = all(activity.window.decorView).filterIsInstance<Button>()
                .first { it.text.toString() == label && it.isShown && it.isEnabled }
            Rect().also { assertTrue(button.getGlobalVisibleRect(it)) }
        }
        assertEquals("Game still foreground before $label", activity.packageName, device.currentPackageName)
        assertTrue("Touch injected for $label", device.click(bounds.centerX(), bounds.centerY()))
    }

    /** Compare the screenshot with the actual native window, independently of its artwork/palette. */
    private fun captureGame(inst: Instrumentation, device: UiDevice, activity: MainActivity, file: File) {
        inst.waitForIdleSync()
        val frames=CountDownLatch(1)
        inst.runOnMainSync { activity.window.decorView.postOnAnimation { activity.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue("Two complete layout frames before capture",frames.await(5,TimeUnit.SECONDS))
        SystemClock.sleep(250)
        var lastDifference = Float.MAX_VALUE
        repeat(8) {
            device.waitForIdle()
            val expected = onMain(inst) {
                val decor = activity.window.decorView
                val image = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(image))
                val location = IntArray(2)
                decor.getLocationOnScreen(location)
                image to location
            }
            assertTrue("Screen capture", device.takeScreenshot(file))
            val actual = BitmapFactory.decodeFile(file.path)
            val (window, location) = expected
            var difference = 0L
            var samples = 0
            if (actual != null && actual.width > actual.height &&
                location[0] >= 0 && location[1] >= 0 &&
                location[0] + window.width <= actual.width && location[1] + window.height <= actual.height) {
                // Exclude edges where transient system bars can draw over the app.
                for (row in 1..23) for (column in 1..39) {
                    val x = column * window.width / 40
                    val y = row * window.height / 24
                    val a = actual.getPixel(x + location[0], y + location[1])
                    val b = window.getPixel(x, y)
                    difference += abs(Color.red(a) - Color.red(b)) +
                        abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
                    samples += 3
                }
            }
            lastDifference = if (samples > 0) difference.toFloat() / samples else Float.MAX_VALUE
            window.recycle()
            actual?.recycle()
            if (lastDifference < 18f && device.currentPackageName == activity.packageName &&
                onMain(inst) { activity.hasWindowFocus() }) return
            SystemClock.sleep(300)
        }
        fail("Screenshot must match the native game window, difference=$lastDifference")
    }

    @Test fun nativeLandscapeCardsAndPlay() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        device.wakeUp()
        device.setOrientationNatural()
        var activity = inst.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        // Immersive confirmation can steal focus even while the app remains visible.
        device.wait(Until.hasObject(By.pkg(context.packageName)), 10000)
        device.findObject(By.text("GOT IT"))?.click()
        device.findObject(By.text("知道了"))?.click()
        await("Game must be foreground and have window focus") {
            device.currentPackageName == context.packageName && onMain(inst) { activity.hasWindowFocus() }
        }
        inst.runOnMainSync { activity.testStart() }
        inst.waitForIdleSync()
        await("Game layout settled") {
            onMain(inst) { activity.window.decorView.width > activity.window.decorView.height && activity.hand.childCount == 20 }
        }
        val args = InstrumentationRegistry.getArguments()
        args.getString("expectedWidth")?.toInt()?.let { assertEquals("Display width", it, device.displayWidth) }
        args.getString("expectedHeight")?.toInt()?.let { assertEquals("Display height", it, device.displayHeight) }
        args.getString("expectedFontScale")?.toFloat()?.let {
            assertEquals("System font scale", it, context.resources.configuration.fontScale, .01f)
        }
        inst.runOnMainSync {
            val decor = activity.window.decorView
            assertTrue("No browser view", all(decor).none { it.javaClass.name.contains("WebView") })
            var previousTop = -1
            for (i in 0 until activity.hand.childCount) {
                val v = activity.hand.getChildAt(i)
                val rect = Rect()
                assertTrue(v.getGlobalVisibleRect(rect))
                assertEquals("Card width visible", v.width, rect.width())
                assertEquals("Card height visible", v.height, rect.height())
                assertTrue("Card touch width", v.width >= 48 * context.resources.displayMetrics.density - 1)
                assertTrue("Card touch height", v.height >= 48 * context.resources.displayMetrics.density - 1)
                assertTrue("Large hand cards", v.height >= 100 * context.resources.displayMetrics.density)
                if(previousTop>=0)assertEquals("Single row",previousTop,v.top)
                previousTop=v.top
                assertTrue("Readable exposed index",activity.hand.exposedBounds(i).width() >= 24 * context.resources.displayMetrics.density - 1)
                if(i>0)assertTrue("Cards overlap", v.left < activity.hand.getChildAt(i-1).right)
            }
            val actionRect = Rect()
            assertTrue(activity.actions.getGlobalVisibleRect(actionRect))
            assertTrue("Controls clipped", actionRect.bottom <= decor.height)
            for (v in all(decor).filterIsInstance<TextView>().filter { it.tag == "opponent-text" }) {
                assertTrue("Opponent text clipped: ${v.text}", v.layout != null &&
                    v.layout.getLineBottom(v.lineCount - 1) <= v.height - v.compoundPaddingTop - v.compoundPaddingBottom)
            }
            assertFalse(all(decor).filterIsInstance<Button>().first { it.text == "出牌" }.isEnabled)
        }
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        captureGame(inst, device, activity, File(dir, "native-table.png"))
        fun point(index:Int):Pair<Int,Int> = onMain(inst){
            val origin=IntArray(2);activity.hand.getLocationOnScreen(origin)
            val bounds=activity.hand.exposedBounds(index)
            (origin[0]+bounds.centerX()) to (origin[1]+bounds.centerY())
        }
        for(index in listOf(0,19)){
            val p=point(index);assertTrue(device.click(p.first,p.second))
            await("Tap exposed card $index selects it"){onMain(inst){activity.hand.getChildAt(index).isSelected}}
        }
        tap(inst,device,activity,"重选")
        val from=point(0);val to=point(4)
        assertTrue(device.swipe(from.first,from.second,to.first,to.second,24))
        await("Swipe selects exactly five cards"){onMain(inst){(0 until 20).all{activity.hand.getChildAt(it).isSelected == (it<5)}}}
        captureGame(inst,device,activity,File(dir,"native-selected.png"))
        tap(inst,device,activity,"重选")
        tap(inst, device, activity, "提示")
        await("Real Hint tap selects cards and enables Play", 5000) {
            onMain(inst) {
                (0 until activity.hand.childCount).any { activity.hand.getChildAt(it).isSelected } &&
                    all(activity.window.decorView).filterIsInstance<Button>().any { it.text == "出牌" && it.isEnabled }
            }
        }
        tap(inst, device, activity, "出牌")
        await("Real Play tap removes cards and advances turn", 5000) {
            onMain(inst) { activity.game.hands[0].size < 20 && activity.game.turn != 0 }
        }
        captureGame(inst,device,activity,File(dir,"native-played.png"))
        val remaining = onMain(inst) { activity.game.hands[0].size }
        inst.runOnMainSync { activity.finish() }
        inst.waitForIdleSync()
        activity = inst.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        assertEquals("Saved hand restored", remaining, onMain(inst) { activity.game.hands[0].size })
        inst.runOnMainSync {
            activity.testStart()
            activity.testPlayedCards((0..4).flatMap{r->(0..2).map{r*4+it}}+(5..9).map{it*4})
        }
        inst.waitForIdleSync()
        captureGame(inst,device,activity,File(dir,"native-long-play.png"))
        inst.runOnMainSync { activity.finish() }
        device.unfreezeRotation()
    }
}
