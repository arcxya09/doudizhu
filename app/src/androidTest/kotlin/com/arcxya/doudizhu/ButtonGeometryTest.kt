package com.arcxya.doudizhu

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pixel regressions: equal View rectangles alone did not catch the lower gold face. */
@RunWith(AndroidJUnit4::class)
class ButtonGeometryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun awaitRow(activity: MainActivity, children: Int) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain { activity.hasWindowFocus() && activity.actions.width > 0 &&
                    activity.actions.childCount == children && (0 until children).all { activity.actions.getChildAt(it).width > 0 } }) return
            SystemClock.sleep(40)
        }
        fail("Action row did not complete layout")
    }

    private fun pixelBounds(bitmap: Bitmap, accepts: (Int) -> Boolean): Rect {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        pixels.forEachIndexed { index, color ->
            if (accepts(color)) {
                val x = index % bitmap.width
                val y = index / bitmap.width
                left = minOf(left, x); top = minOf(top, y)
                right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        assertTrue("Expected visible pixels", right >= left && bottom >= top)
        return Rect(left, top, right + 1, bottom + 1)
    }

    @Test fun atlasBodiesAndOuterShadowsMatchTheMeasuredBounds() {
        val artwork = GameArtwork.get(context)
        val expected = listOf(
            Triple("btn_jdz", Rect(16, 14, 195, 88), Rect(1, 7, 211, 98)),
            Triple("xiaolu_button", Rect(5, 0, 165, 72), Rect(0, 0, 170, 94)),
            Triple("xiaohui_button", Rect(5, 1, 165, 72), Rect(0, 0, 170, 90))
        )
        for ((name, body, ink) in expected) {
            val source = artwork.rect(name)
            val bitmap = Bitmap.createBitmap(artwork.bitmap, source.left, source.top, source.width(), source.height())
            try {
                assertEquals("$name opaque face", body, pixelBounds(bitmap) { Color.alpha(it) >= 248 })
                assertEquals("$name complete glow/shadow", ink, pixelBounds(bitmap) { Color.alpha(it) > 0 })
            } finally { bitmap.recycle() }
        }
    }

    private data class Screen(val name: String, val widthDp: Int, val heightDp: Int)
    private data class State(val name: String, val enabled: Boolean, val pressed: Boolean)

    private fun button(context: Context, primary: Boolean) = ClassicActionButton(context, primary).apply {
        textSize = 15f; isAllCaps = false; setTypeface(null, Typeface.BOLD)
        maxLines = 1; gravity = Gravity.CENTER; includeFontPadding = false
        setAutoSizeTextTypeUniformWithConfiguration(12, 15, 1, TypedValue.COMPLEX_UNIT_SP)
        background = null; stateListAnimator = null; elevation = 0f
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        val d = resources.displayMetrics.density
        setPadding((10 * d).roundToInt(), (8 * d).roundToInt(), (10 * d).roundToInt(), (8 * d).roundToInt())
        isFocusable = false
    }

    private fun render(button: ClassicActionButton, text: String, width: Int, height: Int): Bitmap {
        button.layoutParams = android.view.ViewGroup.LayoutParams(width, height)
        button.text = text
        button.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        button.layout(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { button.draw(Canvas(it)) }
    }

    private fun assertAligned(message: String, expected: Rect, actual: Rect, tolerance: Float) {
        assertEquals("$message top", expected.top.toFloat(), actual.top.toFloat(), tolerance)
        assertEquals("$message bottom", expected.bottom.toFloat(), actual.bottom.toFloat(), tolerance)
        assertEquals("$message left", expected.left.toFloat(), actual.left.toFloat(), tolerance)
        assertEquals("$message right", expected.right.toFloat(), actual.right.toFloat(), tolerance)
    }

    @Test fun actualFacesAndCaptionBaselinesAlignInStandardAndWideLayouts() {
        instrumentation.runOnMainSync {
            for (screen in listOf(Screen("standard", 640, 360), Screen("wide", 960, 432))) {
                val configuration = Configuration(context.resources.configuration).apply {
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                    screenWidthDp = screen.widthDp; screenHeightDp = screen.heightDp
                    densityDpi = 320; fontScale = 1f
                }
                val configured = ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.AppTheme)
                val d = configured.resources.displayMetrics.density
                val widthDp = (screen.widthDp * .105).toInt().coerceIn(70, 104)
                val width = (widthDp * d).roundToInt()
                val height = (48 * d).roundToInt()
                val states = listOf(State("Enabled", true, false), State("Pressed", true, true),
                    State("Disabled", false, false), State("Disabled + pressed", false, true))
                val buttons = listOf(button(configured, false), button(configured, true))
                val sheet = Bitmap.createBitmap(((widthDp * 2 + 160) * d).roundToInt(), (320 * d).roundToInt(), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(sheet)
                canvas.drawColor(0xff566a91.toInt())
                val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13 * d }
                canvas.drawText("${screen.name}: normal / primary", 12 * d, 22 * d, label)
                var referenceBody: Rect? = null
                var referenceCaption: Rect? = null
                try {
                    for ((row, state) in states.withIndex()) {
                        canvas.drawText(state.name, 10 * d, (63 + row * 72) * d, label)
                        for ((column, button) in buttons.withIndex()) {
                            button.isEnabled = state.enabled; button.isPressed = state.pressed
                            val plain = render(button, "", width, height)
                            val captioned = render(button, "出牌", width, height)
                            try {
                                val alpha = if (state.enabled && state.pressed) 210 else 255
                                val body = pixelBounds(plain) { Color.alpha(it) >= 248 * alpha / 255 }
                                val ink = pixelBounds(plain) { Color.alpha(it) > 0 }
                                val caption = pixelBounds(captioned) {
                                    Color.alpha(it) >= 248 && Color.red(it) >= 245 && Color.green(it) >= 245 && Color.blue(it) >= 245
                                }
                                val name = "${screen.name} ${state.name} primary=${column == 1}"
                                assertEquals("$name pressed feedback alpha", alpha,
                                    Color.alpha(plain.getPixel(body.centerX(), body.centerY())))
                                // Compare pixels from different sprites, including a state change on
                                // the same View. The previous gold/green offset was about 6 dp.
                                referenceBody?.let { assertAligned("$name body", it, body, 2 * d) }
                                    ?: run { referenceBody = Rect(body) }
                                referenceCaption?.let { assertAligned("$name caption/baseline", it, caption, 1f) }
                                    ?: run { referenceCaption = Rect(caption) }
                                assertTrue("$name label vertically centered in opaque face", abs(caption.exactCenterY() - body.exactCenterY()) <= 2 * d)
                                assertTrue("$name label horizontally centered in opaque face", abs(caption.exactCenterX() - body.exactCenterX()) <= 2 * d)
                                assertTrue("$name complete shadow/glow is inside touch bounds",
                                    ink.left > 0 && ink.top > 0 && ink.right < width && ink.bottom < height)
                                assertTrue("$name label stays inside face", body.contains(caption))
                                assertEquals("Touch target remains 48 dp", 48 * d, button.height.toFloat(), 1f)
                                canvas.drawBitmap(captioned, (140 * d + column * (width + 8 * d)), (38 + row * 72) * d, null)
                            } finally { plain.recycle(); captioned.recycle() }
                        }
                    }
                    val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                    File(directory, "button-geometry-${screen.name}.png").outputStream().use {
                        assertTrue("Button geometry screenshot saved", sheet.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { sheet.recycle() }
            }
        }
    }

    /** Also runs on the small 480x270 dp and wide font-scale 1.3 CI devices. */
    @Test fun realBiddingAndPlayActionsStayInsideTheRowWithoutCoveringOpponentCounts() {
        val preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val hadAutomatic = preferences.contains("automatic")
        val automatic = preferences.getBoolean("automatic", true)
        preferences.edit().putBoolean("automatic", false).commit()
        var activity: MainActivity? = null
        try {
            val device = UiDevice.getInstance(instrumentation)
            device.wakeUp()
            val active = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = active
            onMain { active.testDealLayer().performClick(); active.testStart(); active.testBidding() }
            awaitRow(active, 5)
            checkActionRow(active)
            onMain { active.game.highBid = 2; active.testRender() }
            awaitRow(active, 5)
            checkActionRow(active)
            instrumentation.waitForIdleSync()
            val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            assertTrue("Actual action row screenshot saved", device.takeScreenshot(File(directory, "button-geometry-actions.png")))
            onMain { active.testStart() }
            awaitRow(active, 4)
            checkActionRow(active)
        } finally {
            activity?.let { onMain { it.finish() }; instrumentation.waitForIdleSync() }
            val edit = preferences.edit()
            if (hadAutomatic) edit.putBoolean("automatic", automatic) else edit.remove("automatic")
            edit.commit()
        }
    }

    private fun checkActionRow(activity: MainActivity) = onMain {
        val row = activity.actions
        assertTrue("Real activity uses the adaptive row", row is ActionRow)
        val bounds = Rect()
        assertTrue("Action row is visible", row.getGlobalVisibleRect(bounds))
        val d = row.resources.displayMetrics.density
        val occupied = mutableListOf<Rect>()
        for (index in 0 until row.childCount) {
            val child = row.getChildAt(index)
            assertTrue("Child $index stays in the actual parent width", child.left >= 0 && child.right <= row.width)
            assertTrue("Child $index stays in the actual parent height", child.top >= 0 && child.bottom <= row.height)
            val visible = Rect()
            assertTrue("Child $index is visible", child.getGlobalVisibleRect(visible))
            assertEquals("Child $index not clipped horizontally", child.width, visible.width())
            assertEquals("Child $index not clipped vertically", child.height, visible.height())
            assertTrue("Child $index stays inside row screen bounds", bounds.contains(visible))
            assertTrue("Actions and clock do not overlap", occupied.none { Rect.intersects(it, visible) })
            occupied.add(Rect(visible))
            for (player in 1..2) {
                val count = Rect()
                assertTrue(activity.testDealCounter(player).getGlobalVisibleRect(count))
                assertFalse("Child $index must not cover opponent $player count", Rect.intersects(count, visible))
            }
            if (child is ClassicActionButton) {
                assertTrue("48 dp high action touch target", child.height >= 48 * d - 1)
                assertTrue("48 dp wide action touch target", child.width >= 48 * d - 1)
                assertTrue("Full label fits after auto sizing: ${child.text}",
                    child.paint.measureText(child.text.toString()) <= child.width - child.paddingLeft - child.paddingRight + 1)
                assertTrue("Label stays at least 12 sp", child.textSize >=
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, child.resources.displayMetrics) - 1)
            }
        }
        // Measuring the same children at a wide size must undo any earlier compression.
        // Restore the real bounds before returning so screenshot/touch checks use the device layout.
        val original = Rect(row.left, row.top, row.right, row.bottom)
        val originalWidths = (0 until row.childCount).map { row.getChildAt(it).width }
        row.measure(View.MeasureSpec.makeMeasureSpec((700 * d).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(row.height, View.MeasureSpec.EXACTLY))
        row.layout(original.left, original.top, original.left + row.measuredWidth, original.bottom)
        for (index in 0 until row.childCount) assertTrue("Wider row restores preferred sizes", row.getChildAt(index).width >= originalWidths[index])
        row.measure(View.MeasureSpec.makeMeasureSpec(original.width(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(original.height(), View.MeasureSpec.EXACTLY))
        row.layout(original.left, original.top, original.right, original.bottom)
        for (index in 0 until row.childCount) assertEquals("Repeated layout does not keep shrinking", originalWidths[index], row.getChildAt(index).width)
    }
}
