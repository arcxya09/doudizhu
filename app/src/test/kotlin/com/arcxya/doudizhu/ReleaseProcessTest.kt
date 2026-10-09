package com.arcxya.doudizhu
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Invariants between the app and the scripts that gate a release. Renaming the 叫地主 button once
 *  left the upgrade check unable to recognise a restored human turn, so the release job failed with
 *  "No enabled human-turn action appeared" only after the signed APK had already been built. */
class ReleaseProcessTest {
    private fun activitySource()=File("src/main/kotlin/com/arcxya/doudizhu/MainActivity.kt").readText()
    private fun upgradeCheck()=File("../scripts/check-release-upgrade.sh").readText()

    companion object {
        private val verifierClasses:File by lazy {
            val directory=File("build/upgrade-verifier-tests/classes").apply{mkdirs()}
            val javac=File(System.getProperty("java.home"),"bin/javac"+if(System.getProperty("os.name").orEmpty().startsWith("Windows"))".exe" else "")
            val compiler=ProcessBuilder(javac.absolutePath,"--release","17","-d",directory.absolutePath,
                File("../scripts/UpgradeSnapshotCheck.java").absolutePath).redirectErrorStream(true).start()
            if(!compiler.waitFor(30,TimeUnit.SECONDS)){compiler.destroyForcibly();fail("Release verifier compilation timed out")}
            assertEquals(compiler.inputStream.bufferedReader().readText(),0,compiler.exitValue())
            directory
        }
    }

    private fun bytes(value:Any):ByteArray=ByteArrayOutputStream().also { buffer ->
        ObjectOutputStream(buffer).use{it.writeObject(value)}
    }.toByteArray()
    private fun recordXml(record:MatchRecord?)=if(record==null)"<map/>" else
        "<map><int name=\"games\" value=\"${record.games}\"/><int name=\"wins\" value=\"${record.wins}\"/><int name=\"score\" value=\"${record.score}\"/></map>"
    private fun probe(before:ByteArray,after:TableSnapshot,beforeRecord:MatchRecord?,afterRecord:MatchRecord):Pair<Int,String>{
        val classes=verifierClasses
        val directory=Files.createTempDirectory(requireNotNull(classes.parentFile).toPath(),"snapshot-").toFile()
        try{
            File(directory,"before.bin").writeBytes(before)
            File(directory,"after.bin").writeBytes(bytes(after))
            File(directory,"before.xml").writeText(recordXml(beforeRecord))
            File(directory,"after.xml").writeText(recordXml(afterRecord))
            val java=File(System.getProperty("java.home"),"bin/java"+if(System.getProperty("os.name").orEmpty().startsWith("Windows"))".exe" else "")
            val process=ProcessBuilder(java.absolutePath,"-cp",classes.absolutePath,"com.arcxya.doudizhu.UpgradeSnapshotCheck",
                File(directory,"before.bin").absolutePath,File(directory,"before.xml").absolutePath,
                File(directory,"after.bin").absolutePath,File(directory,"after.xml").absolutePath).redirectErrorStream(true).start()
            if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();fail("Semantic upgrade verifier timed out")}
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        }finally{directory.deleteRecursively()}
    }

    @Test fun semanticUpgradeAllowsLegacyAndCurrentSnapshotsWithoutLosingStatistics(){
        val oldV3=javaClass.getResourceAsStream("/native-table-v3-before-record.bin")!!.use{it.readBytes()}
        val previous=ObjectInputStream(ByteArrayInputStream(oldV3)).use{it.readObject() as TableSnapshot}
        val record=MatchRecord(19,8,-24)
        val current=TableSnapshot(1,previous.game,record)
        assertFalse("the new optional record changes the serialization bytes",oldV3.contentEquals(bytes(current)))
        val migration=probe(oldV3,current,record,record)
        assertEquals(migration.second,0,migration.first)
        val oldV2=javaClass.getResourceAsStream("/native-table-v2.bin")!!.use{it.readBytes()}
        val legacy=ObjectInputStream(ByteArrayInputStream(oldV2)).use{it.readObject() as SavedTable}
        val legacyRecord=MatchRecord(legacy.games,legacy.wins,legacy.score)
        val legacyMigration=probe(oldV2,TableSnapshot(1,legacy.game,legacyRecord),null,legacyRecord)
        assertEquals(legacyMigration.second,0,legacyMigration.first)
        val unchanged=probe(bytes(current),current,record,record)
        assertEquals(unchanged.second,0,unchanged.first)
    }

    @Test fun semanticUpgradeRejectsEveryChangedGameField(){
        val game=Game.create(1,Random(31)).apply{turn=0;bid(3);play(listOf(hands[0].first()))}
        val record=MatchRecord(19,8,-24)
        val before=bytes(TableSnapshot(1,game))
        for(field in Game::class.java.declaredFields.filter{!Modifier.isStatic(it.modifiers)}){
            val changed=ObjectInputStream(ByteArrayInputStream(bytes(game))).use{it.readObject() as Game}
            field.isAccessible=true
            when(field.type){
                Int::class.javaPrimitiveType->field.setInt(changed,field.getInt(changed)+1)
                Boolean::class.javaPrimitiveType->field.setBoolean(changed,!field.getBoolean(changed))
                String::class.java->field.set(changed,requireNotNull(field.get(changed)).toString()+" changed")
                IntArray::class.java->field.set(changed,(field.get(changed) as IntArray).copyOf().also{it[0]++})
                Move::class.java->field.set(changed,changed.last!!.copy(key=changed.last!!.key+1))
                else->when(field.name){
                    "hands"->changed.hands[0][0]=(changed.hands[0][0]+1)%54
                    "bottom"->field.set(changed,changed.bottom.toMutableList().also{it[0]=(it[0]+1)%54})
                    "status"->changed.status[0]="changed status"
                    else->error("Add a negative upgrade test for field ${field.name}")
                }
            }
            val rejected=probe(before,TableSnapshot(1,changed,record),record,record)
            assertTrue("${field.name} changes must fail: ${rejected.second}",rejected.first!=0)
            assertTrue("failure must name the changed field: ${rejected.second}",rejected.second.contains("Game field changed during upgrade: ${field.name}:"))
        }
    }

    @Test fun semanticUpgradeRejectsRecordLossAndIncompleteAtomicMigration(){
        val game=Game.create(1,Random(17))
        val record=MatchRecord(19,8,-24)
        val before=bytes(TableSnapshot(1,game,record))
        for(changed in listOf(record.copy(games=20),record.copy(wins=9),record.copy(score=-18))){
            val result=probe(before,TableSnapshot(1,game,changed),record,changed)
            assertTrue(result.second,result.first!=0)
        }
        val missing=probe(before,TableSnapshot(1,game),record,record)
        assertTrue(missing.second,missing.first!=0)
        val staleMirror=probe(before,TableSnapshot(1,game,record),record,record.copy(games=18))
        assertTrue(staleMirror.second,staleMirror.first!=0)
        val unsupported=probe(before,TableSnapshot(2,game,record),record,record)
        assertTrue(unsupported.second,unsupported.first!=0)
    }

    @Test fun releaseProbeKnowsEveryHumanActionCaption(){
        val declared=Regex("HUMAN_ACTIONS = \\{([^}]*)\\}").find(upgradeCheck())?.groupValues?.get(1)
        assertNotNull("the release check declares the captions it reads as a human turn",declared)
        val known=Regex("\"([^\"]+)\"").findAll(declared!!).map{it.groupValues[1]}.toSet()
        assertTrue("the check must cover both the bidding and the playing turn","bid" in declared && "play" in declared)
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
