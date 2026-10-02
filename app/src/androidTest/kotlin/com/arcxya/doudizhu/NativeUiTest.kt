package com.arcxya.doudizhu

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeUiTest {
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    @Test fun nativeLandscapeCardsAndPlay(){
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        var activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        inst.runOnMainSync{activity.testStart()};inst.waitForIdleSync();Thread.sleep(600)
        val decor=activity.window.decorView;assertTrue("Landscape",decor.width>decor.height)
        assertTrue("No browser view",all(decor).none{it.javaClass.name.contains("WebView")})
        assertEquals(20,activity.hand.childCount)
        val hitBoxes=mutableListOf<Rect>()
        inst.runOnMainSync{
            for(i in 0 until activity.hand.childCount){val v=activity.hand.getChildAt(i);val rect=Rect();assertTrue(v.getGlobalVisibleRect(rect));assertEquals(v.width,rect.width());assertEquals(v.height,rect.height());assertTrue(v.width>=48*context.resources.displayMetrics.density-1);assertTrue(v.height>=48*context.resources.displayMetrics.density-1);hitBoxes.forEach{assertFalse("Cards overlap",Rect.intersects(it,rect))};hitBoxes.add(rect)}
            val actionRect=Rect();activity.actions.getGlobalVisibleRect(actionRect);assertTrue("Controls clipped",actionRect.bottom<=decor.height)
            for(v in all(decor).filterIsInstance<TextView>().filter{it.text.contains("剩 ")}) { assertTrue("Opponent status clipped",v.layout.getLineBottom(v.lineCount-1)<=v.height-v.compoundPaddingTop-v.compoundPaddingBottom) }
            val buttons=all(decor).filterIsInstance<Button>();assertFalse(buttons.first{it.text=="出牌"}.isEnabled)
        }
        val dir=File(context.getExternalFilesDir(null),"screenshots");dir.mkdirs()
        inst.uiAutomation.takeScreenshot().also{bitmap->File(dir,"native-table.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        inst.runOnMainSync{all(decor).filterIsInstance<Button>().first{it.text=="提示"}.performClick()};inst.waitForIdleSync()
        assertTrue((0 until activity.hand.childCount).any{activity.hand.getChildAt(it).isSelected})
        inst.runOnMainSync{all(decor).filterIsInstance<Button>().first{it.text=="出牌"}.performClick()};inst.waitForIdleSync()
        val remaining=activity.game.hands[0].size;assertTrue(remaining<20)
        inst.runOnMainSync{activity.finish()};inst.waitForIdleSync()
        activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        assertEquals("Saved hand restored",remaining,activity.game.hands[0].size)
        inst.runOnMainSync{activity.finish()}
    }
}
