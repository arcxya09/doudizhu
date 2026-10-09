package com.arcxya.doudizhu

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Exercises the real controller and native dialogs using only local, explicitly controlled fakes. */
@RunWith(AndroidJUnit4::class)
class UpdateControllerTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val device=UiDevice.getInstance(inst)

    private fun <T> onMain(block:()->T):T {
        var result:Result<T>?=null
        inst.runOnMainSync{result=runCatching(block)}
        return result!!.getOrThrow()
    }
    private fun await(message:String,condition:()->Boolean){
        val deadline=SystemClock.uptimeMillis()+10000
        while(SystemClock.uptimeMillis()<deadline){if(condition())return;SystemClock.sleep(30)}
        assertTrue(message,condition())
    }
    private fun all(view:View):List<View> = listOf(view)+
        if(view is ViewGroup)(0 until view.childCount).flatMap{all(view.getChildAt(it))} else emptyList()
    private fun screenshot(name:String){
        inst.waitForIdleSync();device.waitForIdle()
        val directory=File(inst.targetContext.getExternalFilesDir(null),"screenshots").apply{mkdirs()}
        assertTrue("Update screenshot is saved: $name",device.takeScreenshot(File(directory,name)))
    }

    /** Intentionally ignores interruption to model an already completing socket/provider callback. */
    private class Gate {
        val entered=CountDownLatch(1)
        private val released=CountDownLatch(1)
        fun waitForRelease(){
            entered.countDown()
            val end=SystemClock.uptimeMillis()+10000
            while(SystemClock.uptimeMillis()<end){
                try{if(released.await(100,TimeUnit.MILLISECONDS))return}catch(_:InterruptedException){}
            }
            throw IOException("Test response was not released")
        }
        fun open(){released.countDown()}
    }
    private class Source:UpdateSource {
        val checks=AtomicInteger()
        val downloads=AtomicInteger()
        var fetch:(String)->UpdateRelease?={null}
        var transfer:(UpdateRelease,File,(Int)->Unit)->File={_,_,_->throw IOException("No download configured")}
        override fun latest(currentVersion:String):UpdateRelease?{checks.incrementAndGet();return fetch(currentVersion)}
        override fun download(release:UpdateRelease,directory:File,onProgress:(Int)->Unit):File{
            downloads.incrementAndGet();return transfer(release,directory,onProgress)
        }
    }
    private inner class Fixture(val activity:MainActivity,val prefs:SharedPreferences,val token:String){
        val source=Source()
        val clock=AtomicLong(1_800_000_000_000L)
        val callbacks=AtomicInteger()
        val verifications=AtomicInteger()
        val gates=mutableListOf<Gate>()
        val completedStatuses=mutableListOf<String>()
        var host:AlertDialog?=null
        lateinit var controller:UpdateController
        val fakeApk=File(activity.cacheDir,"$token.apk")
        fun gate()=Gate().also{gates.add(it)}
        fun create(){controller=onMain{UpdateController(activity,{
            callbacks.incrementAndGet();completedStatuses.add(controller.status)
        },source,prefs,{clock.get()},{file,_->
            assertTrue("Only the fake local APK reaches the verifier",file==fakeApk)
            verifications.incrementAndGet()
        })}}
        fun release()=UpdateRelease("99.0.0","测试更新说明：改善牌桌操作。",
            UpdatePolicy.pageUrl("99.0.0"),UpdatePolicy.downloadUrl("99.0.0"),"a".repeat(64),4)
    }
    private fun withController(test:(Fixture)->Unit){
        val context=inst.targetContext
        val realPrefs=context.getSharedPreferences("updates",0)
        val hadAutomatic=realPrefs.contains("automatic")
        val wasAutomatic=realPrefs.getBoolean("automatic",true)
        realPrefs.edit().putBoolean("automatic",false).commit()
        val token="update-controller-test-${System.nanoTime()}"
        val prefs=context.getSharedPreferences(token,0)
        prefs.edit().putBoolean("automatic",false).commit()
        var activity:MainActivity?=null
        var fixture:Fixture?=null
        try{
            device.wakeUp();device.setOrientationNatural()
            activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            device.findObject(By.text("GOT IT"))?.click();device.findObject(By.text("知道了"))?.click()
            val current=activity
            await("Update test has a foreground game window"){onMain{current.hasWindowFocus()&&!current.testDealing()}}
            onMain{current.testStart()}
            fixture=Fixture(current,prefs,token).also{it.create()}
            test(fixture)
        }finally{
            fixture?.let{f->
                f.gates.forEach{it.open()}
                onMain{f.controller.close();f.host?.dismiss()}
                f.fakeApk.delete()
            }
            activity?.let{current->onMain{if(!current.isFinishing)current.finish()}}
            inst.waitForIdleSync()
            context.deleteSharedPreferences(token)
            val edit=realPrefs.edit()
            if(hadAutomatic)edit.putBoolean("automatic",wasAutomatic) else edit.remove("automatic")
            edit.commit();device.unfreezeRotation()
        }
    }

    @Test fun automaticChecksAreDailyAndPausedRepliesDoNotOpenDialogs()=withController{f->
        val gate=f.gate()
        f.source.fetch={gate.waitForRelease();null}
        onMain{f.controller.setAutomatic(true);f.controller.onResume()}
        assertTrue("Automatic query begins",gate.entered.await(5,TimeUnit.SECONDS))
        onMain{f.controller.onPause()}
        val callbacks=onMain{f.callbacks.get()}
        gate.open()
        await("Paused query finishes without becoming stuck busy"){onMain{!f.controller.busy}}
        assertEquals("A late background reply does not refresh foreground UI",callbacks,onMain{f.callbacks.get()})
        assertFalse("Automatic checking never opens update details",device.hasObject(By.text("发现新版本 v99.0.0")))
        onMain{f.controller.onResume();f.controller.onResume()}
        assertEquals("Resuming does not repeat a query inside one day",1,f.source.checks.get())
        val nextDay=f.gate()
        f.source.fetch={nextDay.waitForRelease();f.release()}
        f.clock.addAndGet(24*60*60*1000L)
        onMain{f.controller.onResume()}
        assertTrue("The next day permits another automatic query",nextDay.entered.await(5,TimeUnit.SECONDS))
        onMain{f.controller.onPause()}
        val nextCallbacks=onMain{f.callbacks.get()}
        nextDay.open()
        await("An available update is retained while backgrounded"){onMain{!f.controller.busy&&f.controller.hasUpdate}}
        assertEquals("A background new-version response stays quiet",nextCallbacks,onMain{f.callbacks.get()})
        assertFalse("A background new-version response cannot open a modal",device.hasObject(By.text("发现新版本 v99.0.0")))
    }

    @Test fun manualUpdateShowsNotesAndCancelledDownloadCannotBecomeReady()=withController{f->
        val downloaded=f.gate()
        f.source.fetch={f.release()}
        f.source.transfer={_,_,progress->
            progress(42);downloaded.waitForRelease()
            f.fakeApk.writeBytes(byteArrayOf(1,2,3,4));f.fakeApk
        }
        onMain{
            f.controller.onResume()
            val section=f.controller.settingsView()
            val nodes=all(section)
            assertTrue("Installed version is displayed",nodes.filterIsInstance<TextView>().any{it.text.contains(f.controller.currentVersion)})
            assertFalse("The automatic switch reflects the isolated preference",(nodes.single{it.tag=="update-auto"} as Switch).isChecked)
            val check=nodes.single{it.tag=="update-check"} as Button
            assertTrue("Manual update has a usable touch height",check.minimumHeight>=48*f.activity.resources.displayMetrics.density-1)
            f.host=AlertDialog.Builder(f.activity).setTitle("更新测试设置").setView(section).setPositiveButton("关闭",null).create().apply{show()}
            check.performClick()
        }
        assertTrue("Manual check presents the available update",device.wait(Until.hasObject(By.text("发现新版本 v99.0.0")),5000))
        if(!device.hasObject(By.text(f.release().notes))){
            val scroll=UiScrollable(UiSelector().scrollable(true))
            if(scroll.exists())scroll.scrollTextIntoView(f.release().notes)
        }
        assertTrue("Release notes are visible",device.hasObject(By.text(f.release().notes)))
        assertTrue("The controller exposes the discovered release",onMain{f.controller.hasUpdate})
        screenshot("update-details.png")
        val before=onMain{f.activity.game.hands.map{it.toList()}}
        device.findObject(By.text("下载更新")).click()
        assertTrue("The fake download starts",downloaded.entered.await(5,TimeUnit.SECONDS))
        await("Download progress reaches the controller"){onMain{f.controller.status.contains("42%")}}
        // Android exposes no scrollable node when the entire dialog already fits its viewport.
        if(!device.hasObject(By.text("正在下载更新：42%"))){
            val scroll=UiScrollable(UiSelector().scrollable(true))
            if(scroll.exists())scroll.scrollTextIntoView("正在下载更新：42%")
        }
        assertTrue("Progress is rendered",device.wait(Until.hasObject(By.text("正在下载更新：42%")),5000))
        device.findObject(By.text("取消下载")).click()
        assertFalse("Cancellation leaves the controller idle",onMain{f.controller.busy})
        assertTrue("Cancellation remains explicit",onMain{f.controller.status.contains("取消")})
        downloaded.open()
        // An already returning provider may still reach verification, but must never publish Ready.
        SystemClock.sleep(350);inst.waitForIdleSync()
        assertFalse("Late download callbacks must not expose install readiness",onMain{f.completedStatuses.any{it.contains("安装包已校验")}})
        assertTrue("Late completion cannot replace the cancelled status",onMain{f.controller.status.contains("取消")})
        assertEquals("The updater never changes the current hand",before,onMain{f.activity.game.hands.map{it.toList()}})
        assertEquals("No external installer took focus",f.activity.packageName,device.currentPackageName)
    }

    @Test fun manualOfflineAndNoNewVersionRemainRetryable()=withController{f->
        f.source.fetch={throw IOException("测试网络不可用")}
        onMain{f.controller.onResume();f.controller.check(false)}
        await("Offline check completes"){onMain{!f.controller.busy}}
        assertFalse(onMain{f.controller.hasUpdate})
        assertTrue(onMain{f.controller.status.contains("测试网络不可用")})
        f.source.fetch={null}
        onMain{f.controller.check(false)}
        await("Manual retry bypasses the automatic daily throttle"){f.source.checks.get()==2&&onMain{!f.controller.busy}}
        assertTrue("A valid empty result says the installed version is current",onMain{f.controller.status.contains("最新版本")})
        assertFalse(device.hasObject(By.text("发现新版本 v99.0.0")))
        assertEquals("The game stays foreground",f.activity.packageName,device.currentPackageName)
    }

    @Test fun disablingAutomaticAndClosingInvalidateLateQueries()=withController{f->
        val first=f.gate();val second=f.gate()
        f.source.fetch={if(f.source.checks.get()==1)first.waitForRelease() else second.waitForRelease();f.release()}
        onMain{f.controller.setAutomatic(true);f.controller.onResume()}
        assertTrue(first.entered.await(5,TimeUnit.SECONDS))
        onMain{f.controller.setAutomatic(false)}
        first.open()
        onMain{f.controller.check(false)}
        assertTrue("The next request runs after the cancelled provider returns",second.entered.await(5,TimeUnit.SECONDS))
        inst.waitForIdleSync()
        assertFalse("Disabled automatic query cannot publish its late release",onMain{f.controller.hasUpdate})
        val before=onMain{f.controller.close();f.callbacks.get()}
        second.open()
        SystemClock.sleep(250);inst.waitForIdleSync()
        assertEquals("Closing prevents all late UI callbacks",before,onMain{f.callbacks.get()})
        assertFalse("Closing cannot leave a late update dialog",device.hasObject(By.text("发现新版本 v99.0.0")))
    }

    @Test fun aCachedReleaseRemainsAvailableAfterControllerRecreation()=withController{f->
        f.source.fetch={f.release()}
        // Checking before foregrounding stores the response without presenting a modal dialog.
        onMain{f.controller.check(false)}
        await("The first response is cached"){onMain{!f.controller.busy&&f.controller.hasUpdate}}
        val attempted=f.prefs.getLong("last_attempt",0)
        assertEquals(f.clock.get(),attempted)
        onMain{f.controller.close()}
        f.create()
        assertEquals("Reopening retains the already discovered version",f.release(),onMain{f.controller.release})
        onMain{f.controller.setAutomatic(true);f.controller.onResume();f.controller.onResume()}
        assertTrue("The update remains actionable",onMain{f.controller.hasUpdate})
        assertEquals("Reopening does not bypass the daily query limit",1,f.source.checks.get())
        assertEquals("Reading the cache does not rewrite the last check time",attempted,f.prefs.getLong("last_attempt",0))
        assertFalse("A cached automatic notification never forces an update dialog",device.hasObject(By.text("发现新版本 v99.0.0")))
    }

    @Test fun realSettingsExposeUpdateControlsWithoutChangingTheGame()=withController{f->
        val before=onMain{f.activity.game.hands.map{it.toList()}}
        val version=onMain{f.controller.currentVersion}
        val livePreferences=inst.targetContext.getSharedPreferences("updates",0)
        val lastAttempt=livePreferences.getLong("last_attempt",0)
        assertTrue("The real settings entry is visible",device.wait(Until.hasObject(By.text("设置")),5000))
        device.findObject(By.text("设置")).click()
        assertTrue("The real game settings open",device.wait(Until.hasObject(By.text("声音与牌桌设置")),5000))
        try{
            val scroll=UiScrollable(UiSelector().scrollable(true))
            val versionLabel="应用更新 · v$version"
            assertTrue("The installed version is reachable in real settings",scroll.scrollTextIntoView(versionLabel))
            assertTrue(device.hasObject(By.text(versionLabel)))
            assertTrue("Automatic updates are reachable",scroll.scrollTextIntoView("自动检查更新"))
            assertFalse("Live networking stays disabled during device tests",device.findObject(By.text("自动检查更新")).isChecked)
            assertTrue("The manual update action is reachable",scroll.scrollTextIntoView("检查更新"))
            assertTrue("Manual checking is available even when automatic checks are off",device.findObject(By.text("检查更新")).isEnabled)
            screenshot("update-settings.png")
            // Inspect only: neither the network action nor the automatic switch is activated.
        }finally{
            device.findObject(By.text("返回牌局"))?.click()
        }
        await("Returning from update settings restores the table"){onMain{f.activity.hasWindowFocus()}}
        assertEquals("Opening update settings keeps the complete current deal",before,onMain{f.activity.game.hands.map{it.toList()}})
        assertEquals("Inspecting settings does not initiate a live update check",lastAttempt,livePreferences.getLong("last_attempt",0))
        assertEquals("The fake controller was not used for real settings",0,f.source.checks.get())
    }
}
