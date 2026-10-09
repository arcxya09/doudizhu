package com.arcxya.doudizhu

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaPlayer
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class NativeUiTest {
    private var automaticUpdateWasPresent=false
    private var originalAutomaticUpdate=true

    @Before fun disableLiveUpdateChecks(){
        val prefs=InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("updates",0)
        automaticUpdateWasPresent=prefs.contains("automatic")
        originalAutomaticUpdate=prefs.getBoolean("automatic",true)
        prefs.edit().putBoolean("automatic",false).commit()
    }

    @After fun restoreAutomaticUpdatePreference(){
        val edit=InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("updates",0).edit()
        if(automaticUpdateWasPresent)edit.putBoolean("automatic",originalAutomaticUpdate) else edit.remove("automatic")
        edit.commit()
    }

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
                for(button in all(decor).filterIsInstance<Button>().filter{it.isShown && it.text.isNotEmpty()}){
                    assertTrue("Full button caption visible: ${button.text}",button.paint.measureText(button.text.toString())<=button.width-button.compoundPaddingLeft-button.compoundPaddingRight+1)
                    val caption = button.layout
                    assertNotNull("Button caption laid out: ${button.text}", caption)
                    assertTrue("Full button caption height visible: ${button.text}",
                        caption.getLineBottom(caption.lineCount - 1) <= button.height-button.compoundPaddingTop-button.compoundPaddingBottom+1)
                    if(button is ClassicActionButton){
                        val bounds=Rect()
                        assertTrue("Action visible: ${button.text}",button.getGlobalVisibleRect(bounds))
                        assertEquals("Entire action height visible: ${button.text}",button.height,bounds.height())
                        assertEquals("Entire action width visible: ${button.text}",button.width,bounds.width())
                        checkButtonArtwork(button)
                    }
                }
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

    /** Inspect rendered pixels: active faces must be colored and letters centered on the face. */
    private fun checkButtonArtwork(button: ClassicActionButton) {
        val bitmap=Bitmap.createBitmap(button.width,button.height,Bitmap.Config.ARGB_8888)
        button.draw(Canvas(bitmap))
        var faceTop=bitmap.height;var faceBottom=-1;var textTop=bitmap.height;var textBottom=-1
        for(y in 0 until bitmap.height)for(x in 0 until bitmap.width){
            val pixel=bitmap.getPixel(x,y)
            if(Color.alpha(pixel)<192)continue
            val r=Color.red(pixel);val g=Color.green(pixel);val b=Color.blue(pixel)
            if((g>=150&&g>r*1.25&&g>b*1.2)||(r>=200&&g>=120&&b<=170&&r>g*1.04)){
                faceTop=minOf(faceTop,y);faceBottom=maxOf(faceBottom,y)
            }
            if(r>=248&&g>=248&&b>=248){textTop=minOf(textTop,y);textBottom=maxOf(textBottom,y)}
        }
        bitmap.recycle()
        if(button.isEnabled){
            assertTrue("Enabled action has a colored face: ${button.text}",faceBottom>=faceTop)
            assertTrue("Visible white caption: ${button.text}",textBottom>=textTop)
            assertTrue("Caption centered on button face: ${button.text}",
                abs((textTop+textBottom-faceTop-faceBottom)/2f)<=3*button.resources.displayMetrics.density)
        }else assertEquals("Disabled action has a gray face: ${button.text}",-1,faceBottom)
    }

    /** Validate drawn seat placement, not just the size of the empty play containers. */
    private fun checkSeatPlayAlignment(inst: Instrumentation, activity: MainActivity) = onMain(inst) {
        val strips = all(activity.window.decorView).filterIsInstance<CardStrip>()
        val left = strips.single { it.tag == "seat-play-1" }
        val right = strips.single { it.tag == "seat-play-2" }
        val self = strips.single { it.tag == "seat-play-0" }
        for (strip in listOf(left, right, self)) {
            assertEquals("One card retained for ${strip.contentDescription}", 1, strip.childCount)
            val card = strip.getChildAt(0)
            // The previous self play is intentionally hidden when it becomes our turn again.
            if (strip === self && !strip.isShown) continue
            val bounds = Rect()
            assertTrue("Played card shown: ${strip.contentDescription}", card.getGlobalVisibleRect(bounds))
            assertEquals("Played card full width: ${strip.contentDescription}", card.width, bounds.width())
            assertEquals("Played card full height: ${strip.contentDescription}", card.height, bounds.height())
        }
        assertTrue("Left play stays beside the left avatar", left.getChildAt(0).left <= 1)
        assertTrue("Right play stays beside the right avatar", abs(right.getChildAt(0).right-right.width) <= 1)
        val ownCard = self.getChildAt(0)
        assertTrue("Own play remains centered", abs(ownCard.left+ownCard.right-self.width) <= 2)
        val screenWidth = activity.window.decorView.width
        val leftBounds = Rect().also { left.getChildAt(0).getGlobalVisibleRect(it) }
        val rightBounds = Rect().also { right.getChildAt(0).getGlobalVisibleRect(it) }
        assertTrue("Left single card is in left seat band", leftBounds.centerX() < screenWidth*.28f)
        assertTrue("Right single card is in right seat band", rightBounds.centerX() > screenWidth*.72f)
    }

    /** Exercise the overlap geometry and interrupted native gestures without relying on swipe timing. */
    private fun checkHandGestures(inst: Instrumentation, activity: MainActivity) = onMain(inst) {
        val hand=activity.hand
        val first=hand.getChildAt(0) as CardFace
        val next=hand.getChildAt(1) as CardFace
        fun selected()=(0 until hand.childCount).filter{hand.getChildAt(it).isSelected}
        fun point(index:Int)=hand.exposedBounds(index).let{it.centerX().toFloat() to it.centerY().toFloat()}
        var downTime=SystemClock.uptimeMillis()
        var time=downTime
        fun send(action:Int,x:Float,y:Float){
            if(action==MotionEvent.ACTION_DOWN){downTime=SystemClock.uptimeMillis();time=downTime}
            val event=MotionEvent.obtain(downTime,time,action,x,y,0)
            try{hand.dispatchTouchEvent(event)}finally{event.recycle()}
            time+=16
        }
        assertTrue("Gesture fixture starts clear",selected().isEmpty())
        first.performClick()
        val raised=first.faceBounds();val resting=next.faceBounds()
        val x=(next.left+resting.left+first.left+raised.right)/2
        val y=(first.top+raised.top+next.top+resting.top)/2
        assertTrue("Raised strip overlaps the next view horizontally",x>=next.left&&x<next.right)
        assertTrue("Tap is above the next card's visible face",y<next.top+resting.top)
        send(MotionEvent.ACTION_DOWN,x,y);send(MotionEvent.ACTION_UP,x,y)
        assertTrue("Raised face tap clears that card without selecting its neighbour",selected().isEmpty())

        val from=point(0);val to=point(4)
        hand.getChildAt(8).performClick()
        send(MotionEvent.ACTION_DOWN,from.first,from.second)
        send(MotionEvent.ACTION_MOVE,to.first,to.second)
        assertEquals("Drag retains previous selections",listOf(0,1,2,3,4,8),selected())
        send(MotionEvent.ACTION_CANCEL,to.first,to.second)
        assertEquals("System cancellation restores the selection before the drag",listOf(8),selected())
        hand.getChildAt(8).performClick()

        send(MotionEvent.ACTION_DOWN,from.first,from.second)
        send(MotionEvent.ACTION_MOVE,to.first,to.second)
        assertEquals("Multi-touch fixture has a live drag",listOf(0,1,2,3,4),selected())
        val second=point(9)
        val properties=Array(2){i->MotionEvent.PointerProperties().apply{id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
        val coordinates=arrayOf(to,second).map{p->MotionEvent.PointerCoords().apply{this.x=p.first;this.y=p.second;pressure=1f;size=1f}}.toTypedArray()
        val extraFinger=MotionEvent.obtain(downTime,time,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,properties,coordinates,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
        try{hand.dispatchTouchEvent(extraFinger)}finally{extraFinger.recycle()}
        assertTrue("A second finger cancels the drag and restores the original selection",selected().isEmpty())
        // Even when the first finger keeps moving, an interrupted gesture cannot begin selecting again.
        send(MotionEvent.ACTION_MOVE,second.first,second.second)
        send(MotionEvent.ACTION_UP,second.first,second.second)
        assertTrue("Cancelled multi-touch stays cancelled until release",selected().isEmpty())

        val handNode=first.createAccessibilityNodeInfo()
        assertEquals("Hand card identifies itself as a checkbox","android.widget.CheckBox",handNode.className.toString())
        assertTrue("Hand card exposes selection state",handNode.isCheckable)
        val bottom=activity.testBottomStrip().getChildAt(0).createAccessibilityNodeInfo()
        assertEquals("Public cards are read-only images","android.widget.ImageView",bottom.className.toString())
        assertFalse("Public cards do not offer a checkbox",bottom.isCheckable)
        val marked=(0 until hand.childCount).map{hand.getChildAt(it) as CardFace}.filter{it.bottomCard}
        assertEquals("The landlord has three marked bottom cards",3,marked.size)
        assertTrue("Bottom-card identity is announced",marked.all{it.contentDescription.toString().contains("地主底牌")})
    }

    @Test fun bundledAudioDecodesAndFollowsActivityLifecycle() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        val prefs = context.getSharedPreferences("settings", 0)
        val originalMusic = prefs.getBoolean("music", true)
        val originalEffects = prefs.getBoolean("effects", true)
        val originalVolume = prefs.getInt("volume", 45)
        device.wakeUp()
        device.setOrientationNatural()
        var activity = inst.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val report = StringBuilder("Device audio checks; subjective timbre and fidelity require listening.\n")
        try {
            device.wait(Until.hasObject(By.pkg(context.packageName)), 10000)
            device.findObject(By.text("GOT IT"))?.click()
            device.findObject(By.text("知道了"))?.click()
            await("Audio test has foreground window") {
                device.currentPackageName == context.packageName && onMain(inst) { activity.hasWindowFocus() }
            }
            var engine = onMain(inst) { activity.testStart(); activity.testAudio() }
            fun importTrack(bytes: ByteArray, name: String): Boolean {
                val completed = CountDownLatch(1)
                val success = AtomicBoolean(false)
                val callbackOnMain = AtomicBoolean(false)
                onMain(inst) {
                    engine.importMusic(bytes.inputStream(), name) { result ->
                        success.set(result.success)
                        callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                        completed.countDown()
                    }
                }
                assertTrue("Local music import completes: $name", completed.await(25, TimeUnit.SECONDS))
                assertTrue("Music result reaches the UI thread: $name", callbackOnMain.get())
                assertFalse("Import ends its busy state: $name", onMain(inst) { engine.isMusicBusy })
                return success.get()
            }
            fun restoreDefaultMusic() {
                val completed = CountDownLatch(1)
                val success = AtomicBoolean(false)
                onMain(inst) {
                    engine.restoreDefaultMusic { result ->
                        success.set(result.success)
                        completed.countDown()
                    }
                }
                assertTrue("Default music restoration completes", completed.await(25, TimeUnit.SECONDS))
                assertTrue("Default music can be restored", success.get())
                assertFalse("Restoration ends its busy state", onMain(inst) { engine.isMusicBusy })
            }
            await("Initial music source is prepared") { onMain(inst) {
                !engine.isMusicBusy && engine.testState().durationMs > 0
            } }
            // Keep each display configuration independent if a previous test imported a track.
            if (onMain(inst) { engine.hasCustomMusic }) restoreDefaultMusic()
            val decodedDurations = mutableMapOf<String, Int>()
            val files = context.assets.list("audio")!!.toSet()
            for (name in AudioEngine.CUE_DURATIONS.keys + "table_loop") {
                val assetFile=if(name=="table_loop")"table_loop.ogg" else "$name.wav"
                assertTrue("Bundled audio exists: $name", assetFile in files)
                val decoder = MediaPlayer()
                try {
                    context.assets.openFd("audio/$assetFile").use {
                        decoder.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                    }
                    decoder.prepare()
                    decodedDurations[name] = decoder.duration
                    val duration = AudioEngine.CUE_DURATIONS[name]
                    if (duration != null) assertTrue("Bundled WAV decodes to expected duration: $name", abs(decoder.duration-duration) <= 100)
                    else assertTrue("BGM decodes to a usable loop", decoder.duration >= 3000)
                    report.append("$assetFile: decoded ${decoder.duration} ms\n")
                } finally { decoder.release() }
            }
            onMain(inst) { engine.configure(true, true, 60, 55) }
            await("All recorded cues loaded and BGM playing") { onMain(inst) {
                val state = engine.testState()
                state.playing && state.looping && state.loadedCues == AudioEngine.CUE_DURATIONS.keys
            } }
            val duration = onMain(inst) { engine.testState().durationMs }
            onMain(inst) { engine.testSeekMusic(duration-1200) }
            await("BGM seek reaches the end of the track", 3000) {
                onMain(inst) { engine.testState().positionMs >= duration-1500 }
            }
            var previousPosition = onMain(inst) { engine.testState().positionMs }
            await("BGM passes its end and loops during playback", 4500) {
                val state = onMain(inst) { engine.testState() }
                val wrapped = state.playing && state.positionMs+300 < previousPosition
                previousPosition = state.positionMs
                wrapped
            }
            report.append("Background music: actual loop boundary observed\n")

            // Import real encoded data through the same production entry point used by SAF.
            // A short deal recording is intentionally distinct from the bundled BGM, so a
            // source label change cannot hide a MediaPlayer still playing the old track.
            val importedBytes = context.assets.open("audio/deal.wav").use { it.readBytes() }
            val importedDuration = decodedDurations.getValue("deal")
            assertTrue("Imported fixture differs from bundled BGM", abs(importedDuration-duration) > 200)
            assertTrue("Valid local audio accepted", importTrack(importedBytes, "本地测试音乐.wav"))
            await("The active player adopts and plays the imported audio") { onMain(inst) {
                val state = engine.testState()
                engine.hasCustomMusic && state.musicSource == "local" &&
                    state.musicName == "本地测试音乐.wav" && state.playing && state.looping &&
                    abs(state.durationMs-importedDuration) <= 100
            } }
            val importedPosition = onMain(inst) { engine.testState().positionMs }
            await("Imported music playback advances", 3000) {
                onMain(inst) { engine.testState().positionMs != importedPosition }
            }
            assertFalse("Corrupt audio is rejected", importTrack("not a supported audio file".toByteArray(), "损坏的音乐.wav"))
            onMain(inst) {
                val state = engine.testState()
                assertTrue("Failed replacement preserves current imported source", engine.hasCustomMusic)
                assertEquals("Failed replacement preserves source identity", "local", state.musicSource)
                assertEquals("Failed replacement preserves selection name", "本地测试音乐.wav", state.musicName)
                assertTrue("Failed replacement leaves old audio playable", state.playing)
                assertTrue("Failed replacement leaves old decoded track", abs(state.durationMs-importedDuration) <= 100)
                engine.configure(false, true, 60, 55)
            }
            assertTrue("Import remains usable with music switched off", importTrack(importedBytes, "静音导入音乐.wav"))
            onMain(inst) {
                assertFalse("Import does not change the music switch", engine.music)
                assertFalse("Import does not start disabled music", engine.testState().playing)
                assertEquals("Muted import still adopts new source", "静音导入音乐.wav", engine.testState().musicName)
            }
            // Reproduce the async-preparation boundary before the candidate is committed.
            val store=LocalMusicStore(context)
            val committedFile=store.read()!!.file
            val abandoned=store.stage(importedBytes.inputStream(),"取消中的音乐.wav")
            onMain(inst){
                AudioEngine::class.java.getDeclaredField("pendingSelection").apply{isAccessible=true}.set(engine,abandoned)
                AudioEngine::class.java.getDeclaredField("pendingPlayer").apply{isAccessible=true}.set(engine,MediaPlayer())
            }
            val replacedEngine = engine
            onMain(inst) { activity.finish() }
            await("Old engine releases before restart") { onMain(inst) { replacedEngine.testState().released } }
            await("Destroying during preparation removes the uncommitted copied music"){!abandoned.file.exists()}
            assertTrue("Cancelled import preserves the committed local music",committedFile.isFile)
            activity = inst.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            engine = onMain(inst) { activity.testStart(); activity.testAudio() }
            await("Restart restores the copied local audio and disabled music switch") { onMain(inst) {
                val state = engine.testState()
                activity.hasWindowFocus() && !engine.isMusicBusy && engine.hasCustomMusic &&
                    state.musicSource == "local" && state.musicName == "静音导入音乐.wav" &&
                    abs(state.durationMs-importedDuration) <= 100 && !engine.music && !state.playing
            } }
            onMain(inst) { engine.configure(true, true, 60, 55) }
            await("Persisted local audio plays after enabling music") { onMain(inst) { engine.testState().playing } }
            onMain(inst){
                val playing=AudioEngine::class.java.getDeclaredField("player").apply{isAccessible=true}.get(engine) as MediaPlayer
                playing.reset()
                engine.resume()
            }
            await("An invalid live player recovers to default music without crashing"){onMain(inst){
                !engine.isMusicBusy&&!engine.hasCustomMusic&&engine.testState().playing
            }}
            assertTrue("A new import works after playback error recovery",importTrack(importedBytes,"恢复后音乐.wav"))
            restoreDefaultMusic()
            await("Restore default replaces the actual active player") { onMain(inst) {
                val state = engine.testState()
                !engine.hasCustomMusic && state.musicSource == "bundled" && state.playing &&
                    state.loadedCues == AudioEngine.CUE_DURATIONS.keys &&
                    abs(state.durationMs-decodedDurations.getValue("table_loop")) <= 100
            } }
            report.append("Local music: decoded replacement, live playback, corrupt-file rollback, cancelled-preparation cleanup, restart persistence, playback error recovery and default restoration: passed\n")

            val expandedPoint=onMain(inst){
                val button=all(activity.window.decorView).filterIsInstance<Button>().single{it.text=="设置"}
                val d=context.resources.displayMetrics.density
                val margin=(48*d-button.height)/2
                if(margin<2) null else {
                    val rect=Rect().also{button.getGlobalVisibleRect(it)}
                    rect.centerX() to rect.bottom+maxOf(1,minOf((4*d).toInt(),(margin/2).toInt()))
                }
            }
            if(expandedPoint==null)tap(inst,device,activity,"设置")
            else assertTrue("Small settings button responds outside its original bounds",device.click(expandedPoint.first,expandedPoint.second))
            assertTrue("Sound settings dialog opens", device.wait(Until.hasObject(By.text("声音与牌桌设置")), 5000))
            val settingsScroll = UiScrollable(UiSelector().scrollable(true))
            assertTrue("Local music action can be reached by scrolling", settingsScroll.scrollIntoView(UiSelector().text("选择本地音乐")))
            device.waitForIdle()
            assertEquals("Settings belongs to the game", context.packageName, device.currentPackageName)
            assertTrue("Settings title remains visible", device.hasObject(By.text("声音与牌桌设置")))
            val chooseMusic = device.findObject(By.text("选择本地音乐").enabled(true))
            assertNotNull("Local music action is enabled and visible", chooseMusic)
            assertFalse("Local music action has visible bounds", chooseMusic!!.visibleBounds.isEmpty)
            val settingsScreenshot = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                .resolve("native-settings.png")
            // AlertDialog owns a separate window, so compare neither it nor its dimming
            // with the Activity decor used by the game-table capture helper.
            assertTrue("Settings dialog screenshot saved", device.takeScreenshot(settingsScreenshot))
            chooseMusic.click()
            val documentPackages = setOf("com.google.android.documentsui", "com.android.documentsui")
            await("Choose local music opens the Android document picker") {
                device.currentPackageName in documentPackages
            }
            assertTrue("Cancel the document picker", device.pressBack())
            await("Cancelling the picker returns to sound settings") {
                device.currentPackageName == context.packageName && device.hasObject(By.text("声音与牌桌设置"))
            }
            val returnToTable = device.findObject(By.text("返回牌局"))
            assertNotNull("Settings retains its return action after cancellation", returnToTable)
            returnToTable!!.click()
            await("Return action closes settings and restores the game window") {
                !device.hasObject(By.text("声音与牌桌设置")) && onMain(inst) { activity.hasWindowFocus() }
            }
            report.append("Settings: local music action reachable, native document picker opened, cancellation and return to game: passed\n")
            onMain(inst) {
                engine.cue("rocket")
                assertTrue("Recorded event starts", engine.testState().activeStreams > 0)
                engine.cue("single_6")
                assertEquals("Short spoken rank replaces the previous foreground voice", 1, engine.testState().activeStreams)
                assertEquals("Short rank is the new foreground cue", "single_6", engine.testState().lastCue)
                engine.configure(true, false, 60, 55)
                assertEquals("Disabling effects stops current cues", 0, engine.testState().activeStreams)
                assertTrue("Music keeps playing when only effects are disabled", engine.testState().playing)
                engine.configure(false, true, 60, 55)
                assertFalse("Disabling music pauses BGM", engine.testState().playing)
                engine.cue("pass")
                assertTrue("Effects remain usable without music", engine.testState().activeStreams > 0)
                engine.configure(false, false, 60, 55)
                assertFalse("Both sound switches silence BGM", engine.testState().playing)
                assertEquals("Both sound switches silence effects", 0, engine.testState().activeStreams)
                engine.configure(true, true, 0, 0)
                assertFalse("Zero volume pauses BGM", engine.testState().playing)
                assertFalse("Zero volume releases audio focus", engine.testState().focused)
                // The two channels are independent: silencing one must leave the other working.
                engine.configure(true, true, 0, 55)
                assertFalse("Zero music volume pauses BGM", engine.testState().playing)
                engine.cue("pass")
                assertTrue("Effects still play with music volume at zero", engine.testState().activeStreams > 0)
                engine.configure(true, true, 60, 0)
                assertTrue("Music still plays with effect volume at zero", engine.testState().playing)
                assertEquals("Zero effect volume stops cues", 0, engine.testState().activeStreams)
                assertEquals("Music level is stored on its own channel", 60, engine.musicVolume)
                assertEquals("Effect level is stored on its own channel", 0, engine.effectVolume)
                engine.configure(true, true, 60, 55)
                engine.cue("rocket")
            }
            assertTrue("Move app to background", device.pressHome())
            await("Background Activity pauses all audio") { onMain(inst) {
                val state = engine.testState()
                !state.active && !state.playing && state.activeStreams == 0
            } }
            val pausedPosition = onMain(inst) { engine.testState().positionMs }
            SystemClock.sleep(200)
            assertEquals("Paused BGM position is stable", pausedPosition, onMain(inst) { engine.testState().positionMs })
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            await("Foreground Activity resumes BGM") { onMain(inst) {
                activity.hasWindowFocus() && engine.testState().active && engine.testState().playing
            } }
            onMain(inst) { activity.finish() }
            await("Destroyed Activity releases audio") { onMain(inst) { engine.testState().released } }
            report.append("Sound switches, cue cancellation, background pause, foreground resume and release: passed\n")
            File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                .resolve("native-audio.txt").writeText(report.toString())
        } finally {
            onMain(inst) { if (!activity.isFinishing) activity.finish() }
            prefs.edit().putBoolean("music", originalMusic).putBoolean("effects", originalEffects)
                .putInt("volume", originalVolume).commit()
            device.unfreezeRotation()
        }
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
            assertEquals("Window spans full physical display width",device.displayWidth,decor.width)
            assertEquals("Window spans full physical display height",device.displayHeight,decor.height)
            if(args.getString("expectedCutout")=="true"){
                val cutout=decor.rootWindowInsets.displayCutout
                assertNotNull("Emulated cutout exists",cutout)
                val table=all(decor).filterIsInstance<ReferenceTable>().single()
                val bg=all(table).filterIsInstance<TableBackdrop>().single()
                assertEquals("Background reaches cutout edge",0,bg.left)
                assertEquals("Background covers display",device.displayWidth,bg.width)
                for(v in all(table).filterIsInstance<Button>()){
                    val xy=IntArray(2);v.getLocationOnScreen(xy)
                    assertTrue("Button avoids left cutout",xy[0]>=cutout!!.safeInsetLeft)
                    assertTrue("Button avoids right cutout",xy[0]+v.width<=decor.width-cutout.safeInsetRight)
                }
            }
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
                assertTrue("Large hand cards", v.height >= (if(device.displayHeight<640)90 else 100) * context.resources.displayMetrics.density)
                if(previousTop>=0)assertEquals("Single row",previousTop,v.top)
                previousTop=v.top
                assertTrue("Readable exposed index",activity.hand.exposedBounds(i).width() >= 22 * context.resources.displayMetrics.density - 1)
                if(i>0)assertTrue("Cards overlap", v.left < activity.hand.getChildAt(i-1).right)
            }
            assertTrue("Hand spans reference table",activity.hand.width >= decor.width*.80f)
            assertTrue("Hand follows lower reference band",activity.hand.top >= decor.height*.57f && activity.hand.bottom <= decor.height*.93f)
            assertTrue("Actions in central table",activity.actions.top >= decor.height*.43f && activity.actions.bottom <= decor.height*.63f)
            val actionRect = Rect()
            assertTrue(activity.actions.getGlobalVisibleRect(actionRect))
            assertTrue("Controls clipped", actionRect.bottom <= decor.height)
            for (v in all(decor).filterIsInstance<TextView>().filter { it.tag == "opponent-text" }) {
                assertTrue("Opponent text clipped: ${v.text}", v.layout != null &&
                    v.layout.getLineBottom(v.lineCount - 1) <= v.height - v.compoundPaddingTop - v.compoundPaddingBottom)
            }
            assertEquals("Three round portraits",3,all(decor).filterIsInstance<SeatAvatar>().size)
            assertEquals("Counter shows only aggregate opponent ranks",activity.game.hands[1].size+activity.game.hands[2].size,all(decor).filterIsInstance<RankCounter>().single().counts.sum())
            val labels=all(decor).filterIsInstance<TextView>().map{it.text.toString()}
            assertTrue("No online modules",labels.none{it in listOf("商城","聊天","回归礼遇","任务","排行榜","充值")})
            assertFalse(all(decor).filterIsInstance<Button>().first { it.text == "出牌" }.isEnabled)
        }
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        captureGame(inst, device, activity, File(dir, "native-table.png"))
        assertTrue("The current turn is visible",device.hasObject(By.text("轮到你 · 自由出牌")))
        tap(inst,device,activity,"帮助")
        assertTrue("Help opens the game rules",device.wait(Until.hasObject(By.text("玩法与操作")),5000))
        device.findObject(By.text("返回牌局"))!!.click()
        await("Help returns to the same table"){onMain(inst){activity.hasWindowFocus()&&activity.hand.childCount==20}}
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
        await("Reselection clears cards and completes layout"){onMain(inst){activity.hand.childCount==20 && (0 until 20).all{!activity.hand.getChildAt(it).isSelected && activity.hand.getChildAt(it).width>0}}}
        checkHandGestures(inst,activity)
        val from=point(0);val to=point(4)
        assertTrue(device.swipe(from.first,from.second,to.first,to.second,24))
        await("Swipe selects exactly five cards"){onMain(inst){(0 until 20).all{activity.hand.getChildAt(it).isSelected == (it<5)}}}
        captureGame(inst,device,activity,File(dir,"native-selected.png"))
        tap(inst,device,activity,"重选")
        await("Reselection clears cards and completes layout"){onMain(inst){activity.hand.childCount==20 && (0 until 20).all{!activity.hand.getChildAt(it).isSelected && activity.hand.getChildAt(it).width>0}}}
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
        inst.runOnMainSync {activity.testSeatPlays()}
        captureGame(inst,device,activity,File(dir,"native-three-seats.png"))
        checkSeatPlayAlignment(inst, activity)
        val pass=onMain(inst){all(activity.window.decorView).filterIsInstance<Button>().single{it.text=="不出"}}
        assertTrue("Pass enabled while responding to another seat",onMain(inst){pass.isEnabled})
        val beforePass=onMain(inst){activity.game.hands[0].toList()}
        val passCard=point(0);assertTrue(device.click(passCard.first,passCard.second))
        await("A selected card does not block passing"){onMain(inst){activity.hand.getChildAt(0).isSelected}}
        captureGame(inst,device,activity,File(dir,"native-pass.png"))
        tap(inst,device,activity,"不出")
        await("Pass advances turn without playing selected cards"){onMain(inst){activity.game.turn==1&&activity.game.hands[0]==beforePass}}
        onMain(inst){
            activity.game.status[0]="不出";activity.game.status[2]="不出"
            activity.testPlayedCards(listOf(4))
            assertEquals("A new lead clears the previous player pass","等待出牌",activity.game.status[0])
            assertEquals("A new lead clears the previous opponent pass","等待出牌",activity.game.status[2])
        }
        // A rocket cannot be beaten by anything, so the hint has no line to offer.
        onMain(inst){
            activity.testStart()
            activity.game.last=Rules.classify(listOf(52,53))
            activity.game.lastPlayer=1
            activity.testRender()
        }
        val noHint=onMain(inst){all(activity.window.decorView).filterIsInstance<Button>().single{it.text=="无可出"}}
        assertFalse("Hint greys out when nothing can beat the table",onMain(inst){noHint.isEnabled})
        onMain(inst){
            assertEquals("Table is still in play when the shot is taken","play",activity.game.phase)
            assertEquals("Hint label holds for the shot","无可出",noHint.text.toString())
            assertFalse("Hint stays grey for the shot",noHint.isEnabled)
        }
        captureGame(inst,device,activity,File(dir,"native-no-hint.png"))
        onMain(inst){activity.game.last=null;activity.testRender()}
        val hint=onMain(inst){all(activity.window.decorView).filterIsInstance<Button>().single{it.text=="提示"}}
        assertTrue("Hint returns while leading freely",onMain(inst){hint.isEnabled})
        inst.runOnMainSync {activity.testBidding()}
        captureGame(inst,device,activity,File(dir,"native-bidding.png"))
        assertEquals("Reference bidding fixture has seventeen cards", 17, onMain(inst) { activity.hand.childCount })
        onMain(inst){activity.testAudio().configure(true,true,60,55)}
        await("Score bid voices ready"){onMain(inst){"bid_3" in activity.testAudio().testState().loadedCues}}
        // Before anyone calls, all three scores are on offer.
        onMain(inst){
            val scores=all(activity.window.decorView).filterIsInstance<Button>().filter{it.text.toString().endsWith("分")}
            assertEquals("Every score is offered before any call",3,scores.size)
            assertTrue("Every score is enabled before any call",scores.all{it.isEnabled})
        }
        // A call must beat the highest so far, so an equal or lower score greys out.
        onMain(inst){
            activity.game.highBid=2;activity.testRender()
            assertFalse("A score at the current call greys out",all(activity.window.decorView).filterIsInstance<Button>().single{it.text=="2分"}.isEnabled)
            assertFalse("A score below the current call greys out",all(activity.window.decorView).filterIsInstance<Button>().single{it.text=="1分"}.isEnabled)
            assertTrue("A higher score stays available",all(activity.window.decorView).filterIsInstance<Button>().single{it.text=="3分"}.isEnabled)
            activity.game.highBid=0;activity.testRender()
        }
        tap(inst,device,activity,"3分")
        await("Call three points from reference-layout button"){onMain(inst){activity.game.landlord==0 && activity.game.hands[0].size==20}}
        assertEquals("A three-point call announces three points","bid_3",onMain(inst){activity.testAudio().testState().lastCue})
        assertEquals("The call is recorded as the base score",3,onMain(inst){activity.game.highBid})
        inst.runOnMainSync {activity.finish()};inst.waitForIdleSync()
        activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        await("Restored table ready for autoplay"){onMain(inst){activity.hasWindowFocus() && activity.hand.width>0}}
        tap(inst,device,activity,"托管")
        await("Autoplay completes a legal player move",8000){onMain(inst){activity.game.hands[0].size<20}}
        tap(inst,device,activity,"手动")
        inst.runOnMainSync { activity.finish() }
        device.unfreezeRotation()
    }

    /** A real deal must move cards in, then leave a complete resting hand and a usable action row. */
    @Test fun nativeDealAnimationCompletes() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        device.wakeUp()
        device.setOrientationNatural()
        val activity = inst.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        device.wait(Until.hasObject(By.pkg(context.packageName)), 10000)
        device.findObject(By.text("GOT IT"))?.click()
        device.findObject(By.text("知道了"))?.click()
        await("Game must be foreground and have window focus") {
            device.currentPackageName == context.packageName && onMain(inst) { activity.hasWindowFocus() }
        }
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        onMain(inst) { activity.testDeal(runComputer=true) }
        inst.waitForIdleSync()
        // With animations disabled the timeline completes at once, so only the resting state is
        // asserted there; a real animation is additionally checked while it is still running.
        if(onMain(inst){android.animation.ValueAnimator.areAnimatorsEnabled()}){
            await("Deal timeline starts or finishes"){
                onMain(inst){!activity.testDealing()||activity.testDealAnimating()}
            }
        }
        // Observe the flag, animator and all moving-state assertions in one UI-thread transaction.
        // A disabled or just-completed animation cannot end between the guard and the assertions.
        val observedAnimation=onMain(inst){
            if(!android.animation.ValueAnimator.areAnimatorsEnabled()||!activity.testDealing()||!activity.testDealAnimating())false
            else{
                val strips=listOf(activity.hand,activity.testBottomStrip())
                val offset=strips.maxOf{strip->(0 until strip.childCount).maxOfOrNull{i->
                    val card=strip.getChildAt(i);abs(card.translationX)+abs(card.translationY)
                }?:0f}
                // The last kitty card is still in flight after the last own-hand card has landed.
                assertTrue("An active deal has cards away from their resting slots",offset>0f)
                assertEquals("No actions while dealing",0,activity.actions.childCount)
                assertEquals("Bottom cards are dealt face down",3,activity.testBottomStrip().childCount)
                assertEquals("Bottom cards show while dealing",View.VISIBLE,activity.testBottomStrip().visibility)
                true
            }
        }
        if(observedAnimation){
            // Let the cards get airborne before capturing, so the shot shows the flight itself.
            SystemClock.sleep(1200)
            onMain(inst){
                if(activity.testDealing()){
                    assertEquals("Computer cannot bid before the deal is complete",0,activity.game.bidCount)
                    assertEquals("Dealing must not create turn actions",0,activity.actions.childCount)
                    assertEquals("Computer must not take the kitty during the deal",17,activity.game.hands[0].size)
                }
            }
            device.takeScreenshot(File(dir, "native-dealing.png"))
        }
        onMain(inst){activity.testSuspendTurns()}
        await("Deal finishes and settles the whole hand", 15000) {
            onMain(inst) { !activity.testDealing() && activity.hand.childCount == 17 }
        }
        // The row only appears when the deal leaves the player to act; otherwise a seat bids first.
        assertEquals("Action row matches whose turn it is",
            onMain(inst) { activity.game.turn == 0 },
            onMain(inst) { activity.actions.childCount } > 0)
        assertTrue("Every dealt card rests in its own slot", onMain(inst) {
            (0 until activity.hand.childCount).all {
                val card = activity.hand.getChildAt(it)
                card.translationX == 0f && card.translationY == 0f && card.alpha == 1f && card.scaleX == 1f
            }
        })
        assertTrue("Transient deal cards are removed", onMain(inst) { activity.testDealLayer().childCount } == 0)
        assertEquals("Bottom cards hide again for bidding", View.INVISIBLE, onMain(inst) { activity.testBottomStrip().visibility })
        inst.runOnMainSync { activity.finish() }
        device.unfreezeRotation()
    }
}
