package com.arcxya.doudizhu

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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
        val device=UiDevice.getInstance(inst)
        device.setOrientationNatural();device.waitForIdle()
        var activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        inst.runOnMainSync{activity.testStart()};inst.waitForIdleSync()
        device.wakeUp()
        assertTrue("App must be foreground",device.wait(Until.hasObject(By.pkg(context.packageName)),10000))
        device.findObject(By.text("GOT IT"))?.click()
        device.waitForIdle();Thread.sleep(1500)
        device.findObject(By.text("GOT IT"))?.click()
        device.waitForIdle()
        assertTrue("Game window must have focus",activity.hasWindowFocus())
        val decor=activity.window.decorView;assertTrue("Landscape",decor.width>decor.height)
        assertTrue("No browser view",all(decor).none{it.javaClass.name.contains("WebView")})
        assertEquals(20,activity.hand.childCount)
        val hitBoxes=mutableListOf<Rect>()
        inst.runOnMainSync{
            for(i in 0 until activity.hand.childCount){val v=activity.hand.getChildAt(i);val rect=Rect();assertTrue(v.getGlobalVisibleRect(rect));assertEquals(v.width,rect.width());assertEquals(v.height,rect.height());assertTrue(v.width>=48*context.resources.displayMetrics.density-1);assertTrue(v.height>=48*context.resources.displayMetrics.density-1);hitBoxes.forEach{assertFalse("Cards overlap",Rect.intersects(it,rect))};hitBoxes.add(rect)}
            val actionRect=Rect();activity.actions.getGlobalVisibleRect(actionRect);assertTrue("Controls clipped",actionRect.bottom<=decor.height)
            for(v in all(decor).filterIsInstance<TextView>().filter{it.tag=="opponent-text"}) { assertTrue("Opponent text clipped: ${v.text}, height=${v.height}, line=${v.layout.getLineBottom(v.lineCount-1)}, size=${v.textSize}",v.layout.getLineBottom(v.lineCount-1)<=v.height-v.compoundPaddingTop-v.compoundPaddingBottom) }
            val buttons=all(decor).filterIsInstance<Button>();assertFalse(buttons.first{it.text=="出牌"}.isEnabled)
        }
        val dir=File(context.getExternalFilesDir(null),"screenshots");dir.mkdirs()
        assertTrue(device.takeScreenshot(File(dir,"native-table.png")))
        android.graphics.BitmapFactory.decodeFile(File(dir,"native-table.png").path).also{assertTrue("Screenshot orientation",it.width>it.height);it.recycle()}
        device.findObject(By.text("提示")).click();device.waitForIdle()
        assertTrue((0 until activity.hand.childCount).any{activity.hand.getChildAt(it).isSelected})
        device.findObject(By.text("出牌")).click();device.waitForIdle()

        val remaining=activity.game.hands[0].size;assertTrue(remaining<20)
        inst.runOnMainSync{activity.finish()};inst.waitForIdleSync()
        activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        assertEquals("Saved hand restored",remaining,activity.game.hands[0].size)
        inst.runOnMainSync{activity.finish()}
    }
}
