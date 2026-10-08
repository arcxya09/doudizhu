package com.arcxya.doudizhu
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Invariants between the app and the scripts that gate a release. Renaming the 叫地主 button once
 *  left the upgrade check unable to recognise a restored human turn, so the release job failed with
 *  "No enabled human-turn action appeared" only after the signed APK had already been built. */
class ReleaseProcessTest {
    private fun activitySource()=File("src/main/kotlin/com/arcxya/doudizhu/MainActivity.kt").readText()
    private fun upgradeCheck()=File("../scripts/check-release-upgrade.sh").readText()

    @Test fun releaseProbeKnowsEveryHumanActionCaption(){
        val declared=Regex("HUMAN_ACTIONS = \\(([^)]*)\\)").find(upgradeCheck())?.groupValues?.get(1)
        assertNotNull("the release check declares the captions it reads as a human turn",declared)
        val known=declared!!.split(",").map{it.trim().trim('"')}.filter{it.isNotEmpty()}.toSet()
        // The action row only: the other buttons on the table are outside this slice.
        val block=activitySource().substringAfter("actions.removeAllViews()").substringBefore("refreshSelection()")
        // Buttons that cannot mark a human turn: the ones offered whatever the position is, plus the
        // two that are disabled exactly when the check runs (nothing selected, nothing playable).
        val notIndicators=setOf("取消托管","再来一局","查看结算","出牌","无可出")
        val captions=Regex("\"([^\"]+)\"").findAll(block).map{it.groupValues[1]}
            .filter{it.any{ch->ch.code in 0x4E00..0x9FFF} && it.length<=4}
            .toSet()-notIndicators
        assertTrue("the action row should still offer captions to check",captions.isNotEmpty())
        for(caption in captions)assertTrue("the release check must recognise \"$caption\"",caption in known)
        // The score buttons come from a loop, so they are named rather than parsed.
        for(n in 1..3)assertTrue("the release check must recognise \"${n}分\"","${n}分" in known)
    }
}
