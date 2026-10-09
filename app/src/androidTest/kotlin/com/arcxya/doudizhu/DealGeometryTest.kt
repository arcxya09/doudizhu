package com.arcxya.doudizhu

import android.animation.ValueAnimator
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real painted-card geometry, sampled at deterministic times instead of emulator frame timings. */
@RunWith(AndroidJUnit4::class)
class DealGeometryTest {
    private val inst:Instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private val device:UiDevice=UiDevice.getInstance(inst)
    private var activity:MainActivity?=null
    private var animationScale:String?=null
    private var updatePreferencePresent=false
    private var updatePreference=true

    @Before fun prepare(){
        val prefs=context.getSharedPreferences("updates",0)
        updatePreferencePresent=prefs.contains("automatic")
        updatePreference=prefs.getBoolean("automatic",true)
        prefs.edit().putBoolean("automatic",false).commit()
        // CI normally disables animations. Preserve that choice, but exercise actual flight frames.
        animationScale=device.executeShellCommand("settings get global animator_duration_scale").trim()
        device.executeShellCommand("settings put global animator_duration_scale 1")
        device.wakeUp();device.setOrientationNatural()
        await("System animation observer enabled the deal timeline"){onMain{ValueAnimator.areAnimatorsEnabled()}}
        activity=inst.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        device.findObject(By.text("GOT IT"))?.click()
        device.findObject(By.text("知道了"))?.click()
        await("Table laid out and foreground"){
            onMain{activity!!.hasWindowFocus()&&activity!!.hand.width>0&&activity!!.hand.childCount>0}
        }
        onMain{activity!!.testDealLayer().performClick();activity!!.testSuspendTurns()}
    }

    @After fun restore(){
        try{
            activity?.let{table->onMain{table.finish()}}
            inst.waitForIdleSync()
        }finally{
            animationScale?.let{value->
                if(value=="null")device.executeShellCommand("settings delete global animator_duration_scale")
                else if(value.matches(Regex("[0-9]+(?:\\.[0-9]+)?")))
                    device.executeShellCommand("settings put global animator_duration_scale $value")
            }
            val edit=context.getSharedPreferences("updates",0).edit()
            if(updatePreferencePresent)edit.putBoolean("automatic",updatePreference) else edit.remove("automatic")
            edit.commit();device.unfreezeRotation()
        }
    }

    private fun <T> onMain(block:()->T):T {
        val result=AtomicReference<T>();inst.runOnMainSync{result.set(block())};return result.get()
    }
    private fun await(message:String,condition:()->Boolean){
        val deadline=SystemClock.uptimeMillis()+10000
        while(SystemClock.uptimeMillis()<deadline){if(condition())return;SystemClock.sleep(30)}
        assertTrue(message,condition())
    }
    private fun frames(){
        val latch=CountDownLatch(1)
        onMain{activity!!.window.decorView.postOnAnimation{
            activity!!.window.decorView.postOnAnimation{latch.countDown()}
        }}
        assertTrue("Layout callbacks completed",latch.await(5,TimeUnit.SECONDS))
        inst.waitForIdleSync()
    }
    private fun startPausedDeal(){
        onMain{activity!!.testDeal()}
        await("Complete flight set prepared before freezing"){
            onMain{
                val table=activity!!
                if(table.testDealing()&&table.testDealAnimating()&&table.testDealCards(0).size==17){
                    table.testDealAt(0f);true
                }else false
            }
        }
    }

    /** Compose the actual Android view matrices up to the window, independent of DealFlight targets. */
    private fun globalPoint(view:View,x:Float,y:Float):PointF {
        val point=floatArrayOf(x,y)
        val root=view.rootView
        var current=view
        while(current!==root){
            current.matrix.mapPoints(point)
            val parent=current.parent as View
            point[0]+=current.left-parent.scrollX;point[1]+=current.top-parent.scrollY
            current=parent
        }
        val origin=IntArray(2);root.getLocationOnScreen(origin)
        return PointF(point[0]+origin[0],point[1]+origin[1])
    }
    private fun faceCenter(card:CardFace):PointF {
        val face=card.faceBounds();return globalPoint(card,face.centerX(),face.centerY())
    }
    private fun assertCenter(message:String,expected:PointF,actual:PointF){
        assertEquals("$message x",expected.x,actual.x,.25f)
        assertEquals("$message y",expected.y,actual.y,.25f)
    }
    /** Deterministic diagnostic images complement NativeUiTest's independently captured screen. */
    private fun captureFrame(name:String,verify:(Bitmap)->Unit={}){
        val decor=activity!!.window.decorView
        val bitmap=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888)
        try{
            decor.draw(Canvas(bitmap))
            val directory=File(context.getExternalFilesDir(null),"screenshots").apply{mkdirs()}
            File(directory,"deal-geometry-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            verify(bitmap)
        }finally{bitmap.recycle()}
    }
    private fun assertAirborneFacePainted(bitmap:Bitmap,card:CardFace){
        val table=activity!!;val face=card.faceBounds()
        val origin=IntArray(2);table.window.decorView.getLocationOnScreen(origin)
        val handTop=globalPoint(table.hand,0f,0f).y
        val backs=(1..2).flatMap{table.testDealCards(it)}.filter{it.alpha>0f}
        var samples=0;var white=0
        // The upper-right body avoids both the left rank and the large suit/Joker below it. Exclude
        // any area actually covered by a later-drawn opponent card rather than assuming no overlap.
        for(x in listOf(.55f,.64f,.73f,.82f))for(y in listOf(.18f,.24f,.30f)){
            val point=globalPoint(card,face.left+face.width()*x,face.top+face.height()*y)
            if(backs.any{back->
                val bounds=back.faceBounds()
                val topLeft=globalPoint(back,bounds.left,bounds.top)
                val bottomRight=globalPoint(back,bounds.right,bounds.bottom)
                point.x>=topLeft.x-2&&point.x<=bottomRight.x+2&&point.y>=topLeft.y-2&&point.y<=bottomRight.y+2
            })continue
            assertTrue("The sampled flying face lies above the hand container",point.y<handTop-1)
            val px=(point.x-origin[0]).toInt();val py=(point.y-origin[1]).toInt()
            assertTrue("Flying-card sample lies inside the window",px in 0 until bitmap.width&&py in 0 until bitmap.height)
            val pixel=bitmap.getPixel(px,py)
            // The paper sprite is shaded off-white (about 218–226 here), not flat RGB 255.
            val channels=listOf(Color.red(pixel),Color.green(pixel),Color.blue(pixel))
            if(channels.min()>=210&&channels.max()-channels.min()<=12)white++
            samples++
        }
        assertTrue("Enough unoccluded card-body samples to detect clipping",samples>=6)
        assertTrue("The real table paints the airborne white card above the padded hand ($white/$samples samples)",white>=samples-1)
    }
    private fun assertResting(table:MainActivity){
        assertFalse("Deal has finished",table.testDealing())
        assertFalse("Old animator has stopped",table.testDealAnimating())
        assertEquals("No lingering opponent cards",0,table.testDealLayer().childCount)
        assertFalse("Overlay no longer intercepts taps",table.testDealLayer().isClickable)
        assertEquals("Full own hand remains",17,table.hand.childCount)
        for(i in 0 until table.hand.childCount){
            val card=table.hand.getChildAt(i)
            assertEquals("Resting card x",0f,card.translationX,0f)
            assertEquals("Resting card y",0f,card.translationY,0f)
            assertEquals("Resting card visible",1f,card.alpha,0f)
            assertEquals("Resting card scale",1f,card.scaleX,0f)
        }
        for(player in 1..2)assertEquals("Resting opponent count","17",table.testDealCounter(player).text.toString())
    }

    @Test fun paintedCardsShareDeckAndLandInTheirActualSlots(){
        startPausedDeal()
        onMain{
            val table=activity!!;val layer=table.testDealLayer()
            val deck=globalPoint(layer,layer.width*.5f,layer.height*.30f)
            for(player in listOf(0,1,2,-1)){
                val cards=table.testDealCards(player)
                assertEquals("All cards present for seat $player",if(player==-1)3 else 17,cards.size)
                for(card in cards)assertCenter("Seat $player starts at the common painted deck",deck,faceCenter(card))
            }
            table.testDealAt(230f)
            assertTrue("Uncovered airborne card includes its large suit",table.testDealCards(0).first().showBody)
            assertTrue("First own card is visible in flight",table.testDealCards(0).first().alpha>0f)
            captureFrame("flying"){assertAirborneFacePainted(it,table.testDealCards(0).first())}
            for((player,time) in listOf(1 to 380f,2 to 400f)){
                table.testDealAt(time)
                val card=table.testDealCards(player).first();val counter=table.testDealCounter(player)
                assertCenter("Opponent $player lands in the actual count badge",
                    globalPoint(counter,counter.width/2f,counter.height/2f),faceCenter(card))
                assertEquals("Arrived opponent card disappears",0f,card.alpha,0f)
                assertEquals("Count increases only after arrival","1",counter.text.toString())
                val face=card.faceBounds()
                assertTrue("Received card fits badge width",face.width()*card.scaleX<=counter.width+.25f)
                assertTrue("Received card fits badge height",face.height()*card.scaleY<=counter.height+.25f)
                if(player==1)assertEquals("Right card has not landed yet","0",table.testDealCounter(2).text.toString())
                captureFrame(if(player==1)"left-arrived" else "right-arrived")
            }
            table.testDealAt(4220f)
            for(player in listOf(0,-1))for(card in table.testDealCards(player)){
                assertEquals("Own/kitty card returns to its laid-out x",0f,card.translationX,.25f)
                assertEquals("Own/kitty card returns to its laid-out y",0f,card.translationY,.25f)
                assertEquals("Own/kitty card is full size",1f,card.scaleX,.001f)
            }
            for(player in 1..2){
                assertEquals("Opponent received all 17 cards","17",table.testDealCounter(player).text.toString())
                assertTrue("No stack of large cards remains on opponent",table.testDealCards(player).all{it.alpha==0f})
            }
            assertEquals("No computer bid during dealing",0,table.game.bidCount)
            table.testDealLayer().performClick();assertResting(table)
        }
    }

    @Test fun airborneHandCardIsVisibleInTheHardwareCompositedScreen(){
        startPausedDeal()
        frames()
        onMain{
            assertTrue("The deal is still paused after layout",activity!!.testDealing())
            activity!!.testDealAt(230f)
        }
        // View properties are not evidence that the parent RenderNode allowed the pixels through.
        // Wait for drawing, then inspect a real screenshot from the display compositor.
        frames()
        val window=onMain{
            val table=activity!!;val decor=table.window.decorView
            assertTrue("Game window focused before the screen capture",table.hasWindowFocus())
            val origin=IntArray(2);decor.getLocationOnScreen(origin)
            intArrayOf(origin[0],origin[1],decor.width,decor.height)
        }
        assertEquals("Game is foreground before the screen capture",context.packageName,device.currentPackageName)
        val directory=File(context.getExternalFilesDir(null),"screenshots").apply{mkdirs()}
        val file=File(directory,"deal-geometry-screen.png")
        assertTrue("Hardware-composited screen capture succeeds",device.takeScreenshot(file))
        val screen=checkNotNull(BitmapFactory.decodeFile(file.path)){"Hardware screenshot can be decoded"}
        try{
            assertTrue("The captured screen contains the whole game window",
                window[0]>=0&&window[1]>=0&&window[2]>0&&window[3]>0&&
                    window[0]+window[2]<=screen.width&&window[1]+window[3]<=screen.height)
            val actualWindow=Bitmap.createBitmap(screen,window[0],window[1],window[2],window[3])
            try{
                onMain{
                    val table=activity!!
                    assertTrue("Game window remains focused after the screen capture",table.hasWindowFocus())
                    assertTrue("Captured flight has not been replaced or ended",table.testDealing())
                    assertAirborneFacePainted(actualWindow,table.testDealCards(0).first())
                    table.testDealLayer().performClick();assertResting(table)
                }
            }finally{if(actualWindow!==screen)actualWindow.recycle()}
        }finally{screen.recycle()}
    }

    @Test fun skippedReplacedResizedAndBackgroundDealsLeaveNoOldFlight(){
        val table=activity!!
        // Skip before the preparation listener has ever run, then give that old frame a chance.
        onMain{table.testDeal();table.testDealLayer().performClick();assertResting(table)}
        frames();onMain{assertResting(table)}

        startPausedDeal()
        val oldCards=onMain{(0..2).flatMap{table.testDealCards(it)}}
        // Replace an active timeline and immediately replace its pending successor as well.
        onMain{table.testDeal();table.testDeal()}
        await("Only the newest deal starts"){
            onMain{if(table.testDealAnimating()&&table.testDealCards(0).size==17){table.testDealAt(0f);true}else false}
        }
        onMain{
            assertEquals("Exactly one opponent flight set",34,table.testDealLayer().childCount)
            assertTrue("All old cards were detached",oldCards.all{it.parent==null})
            assertTrue("Replacement owns new views",(0..2).flatMap{table.testDealCards(it)}.none{it in oldCards})
        }

        val oldPadding=onMain{intArrayOf(table.hand.paddingLeft,table.hand.paddingTop,table.hand.paddingRight,table.hand.paddingBottom)}
        try{
            // Relayout the real card slots, as a resized window or safe-area change would do.
            onMain{table.hand.setPadding(oldPadding[0],oldPadding[1]+2,oldPadding[2],oldPadding[3])}
            await("Layout changes finish the old geometry before drawing"){onMain{!table.testDealing()}}
            frames();onMain{assertResting(table)}
        }finally{onMain{table.hand.setPadding(oldPadding[0],oldPadding[1],oldPadding[2],oldPadding[3])}}

        startPausedDeal()
        assertTrue("Home backgrounds the real activity",device.pressHome())
        await("Backgrounding settles and stops the timeline"){onMain{!table.testDealing()&&!table.hasWindowFocus()}}
        onMain{assertResting(table)}
        context.startActivity(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        await("Same activity returns to foreground"){
            onMain{table.testSuspendTurns();table.hasWindowFocus()}
        }
        frames();onMain{assertResting(table)}
    }
}
