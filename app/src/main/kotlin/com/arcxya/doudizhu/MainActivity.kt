package com.arcxya.doudizhu

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AtomicFile
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.io.*

private data class SavedTable(val game:Game,val games:Int,val wins:Int,val score:Int):Serializable
class MainActivity: Activity() {
    internal lateinit var game:Game
    internal lateinit var hand:HandLayout
    internal lateinit var actions:LinearLayout
    private lateinit var art:CardArt
    private lateinit var audio:AudioEngine
    private lateinit var table:TableArena
    private lateinit var notice:TextView
    private lateinit var info:TextView
    private lateinit var selection:TextView
    private lateinit var bottom:CardStrip
    private lateinit var last:CardStrip
    private lateinit var lastLabel:TextView
    private lateinit var stakes:TextView
    private val people=mutableListOf<TableSeat>()
    private val selected=linkedSetOf<Int>()
    private val handler=Handler(Looper.getMainLooper())
    private var running=false
    private var modal=false
    private var nextLevel=1
    private var speed=1800L
    private var games=0;private var wins=0;private var score=0
    private var playButton:Button?=null
    private val names=listOf("我","小林","老周")
    private val levels=listOf("简单","普通","困难")
    private val gold=Color.rgb(255,217,126)
    private val settings by lazy { getSharedPreferences("settings",0) }
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun text(value:String,size:Float=18f,color:Int=Color.WHITE)=TextView(this).apply { text=value;textSize=size;setTextColor(color);gravity=Gravity.CENTER;includeFontPadding=false }
    private fun background(color:Int,stroke:Int=Color.TRANSPARENT)=GradientDrawable().apply {setColor(color);cornerRadius=dp(10).toFloat();setStroke(dp(2),stroke)}
    private fun button(label:String,primary:Boolean=false,action:()->Unit)=Button(this).apply {
        text=label;textSize=20f;isAllCaps=false;setTypeface(null,Typeface.BOLD)
        setTextColor(if(primary)Color.rgb(64,44,15) else Color.rgb(255,245,222))
        background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,if(primary)intArrayOf(Color.rgb(255,225,137),Color.rgb(239,178,63)) else intArrayOf(Color.rgb(69,117,83),Color.rgb(39,79,56))).apply {
            cornerRadius=dp(24).toFloat();setStroke(dp(2),if(primary)Color.rgb(255,243,183) else Color.rgb(167,200,153))
        }
        stateListAnimator=null;elevation=dp(3).toFloat()
        if(label=="提示"||label=="不出") {
            val colors=if(label=="提示")intArrayOf(Color.rgb(77,157,151),Color.rgb(30,104,102)) else intArrayOf(Color.rgb(91,113,101),Color.rgb(58,79,68))
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,colors).apply {cornerRadius=dp(24).toFloat();setStroke(dp(2),if(label=="提示")Color.rgb(176,226,206) else Color.rgb(171,187,168))}
        }
        minHeight=dp(48);minimumHeight=dp(48);minWidth=dp(90);setPadding(dp(8),0,dp(8),0);setOnClickListener{action()}
    }
    private fun buttonEnabled(b:Button,value:Boolean){b.isEnabled=value;b.alpha=if(value)1f else .42f}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        nextLevel=settings.getInt("level",1).coerceIn(0,2);speed=settings.getLong("speed",1800L).coerceIn(1000L,2800L)
        restore();art=CardArt(this);audio=AudioEngine(this);buildLayout();render()
    }
    @Suppress("DEPRECATION")
    private fun immersive(){window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION}
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);if(hasFocus)immersive()}
    private fun buildLayout(){
        val canvas=FrameLayout(this)
        canvas.addView(TableBackdrop(this),FrameLayout.LayoutParams(-1,-1))
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(4),dp(8),dp(6))}
        canvas.addView(root,FrameLayout.LayoutParams(-1,-1));setContentView(canvas)
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),0,dp(4),0);background=background(0x9b153b2a.toInt(),0x5dbdc68f)}
        root.addView(header,LinearLayout.LayoutParams(-1,dp(40)))
        val title=text("闲来斗地主",22f,gold).apply{setTypeface(null,Typeface.BOLD);gravity=Gravity.CENTER_VERTICAL;maxLines=1;setAutoSizeTextTypeUniformWithConfiguration(16,22,1,android.util.TypedValue.COMPLEX_UNIT_SP)}
        header.addView(title,LinearLayout.LayoutParams(dp(137),-1))
        stakes=text("",17f,Color.rgb(255,241,197)).apply{maxLines=1;setAutoSizeTextTypeUniformWithConfiguration(12,17,1,android.util.TypedValue.COMPLEX_UNIT_SP)}
        header.addView(stakes,LinearLayout.LayoutParams(0,-1,1f))
        val kitty=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;orientation=LinearLayout.HORIZONTAL}
        kitty.addView(text("底牌",14f,Color.rgb(236,225,186)).apply{maxLines=1;setAutoSizeTextTypeUniformWithConfiguration(11,14,1,android.util.TypedValue.COMPLEX_UNIT_SP)},LinearLayout.LayoutParams(dp(33),-1))
        bottom=CardStrip(this,art).apply{contentDescription="地主底牌"};kitty.addView(bottom,LinearLayout.LayoutParams(dp(72),dp(38)))
        header.addView(kitty,LinearLayout.LayoutParams(dp(105),-1))
        val options=button("设置"){showSettings()}.apply{textSize=17f;contentDescription="声音、难度和出牌速度设置";minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;elevation=0f}
        header.addView(options,LinearLayout.LayoutParams(dp(74),dp(38)).apply{setMargins(dp(8),0,0,0)})
        val left=TableSeat(this,0);val right=TableSeat(this,1);people.add(left);people.add(right)
        notice=text("",21f,gold).apply{setTypeface(null,Typeface.BOLD);maxLines=1;setAutoSizeTextTypeUniformWithConfiguration(15,21,1,android.util.TypedValue.COMPLEX_UNIT_SP)}
        lastLabel=text("",16f,Color.rgb(255,240,206)).apply{maxLines=1;setAutoSizeTextTypeUniformWithConfiguration(11,16,1,android.util.TypedValue.COMPLEX_UNIT_SP)}
        last=CardStrip(this,art).apply{contentDescription="上家打出的牌"}
        table=TableArena(this,left,right,notice,lastLabel,last)
        root.addView(table,LinearLayout.LayoutParams(-1,0,.65f).apply{setMargins(0,dp(2),0,dp(2))})
        actions=LinearLayout(this).apply{gravity=Gravity.CENTER};root.addView(actions,LinearLayout.LayoutParams(-1,dp(52)))
        selection=text("",17f,Color.rgb(255,241,202)).apply{maxLines=1;setAutoSizeTextTypeUniformWithConfiguration(13,17,1,android.util.TypedValue.COMPLEX_UNIT_SP)}
        root.addView(selection,LinearLayout.LayoutParams(-1,dp(22)))
        info=text("",16f,Color.rgb(255,237,178)).apply{setSingleLine();setAutoSizeTextTypeUniformWithConfiguration(12,16,1,android.util.TypedValue.COMPLEX_UNIT_SP);background=background(0x8f143a29.toInt())}
        root.addView(info,LinearLayout.LayoutParams(-1,dp(20)).apply{setMargins(dp(22),dp(2),dp(22),0)})
        hand=HandLayout(this).apply{contentDescription="我的手牌，点击选择，再点出牌";setPadding(0,dp(3),0,0)}
        root.addView(hand,LinearLayout.LayoutParams(-1,0,1.3f))
    }
    private fun addAction(label:String,primary:Boolean=false,enabled:Boolean=true,action:()->Unit):Button {
        val b=button(label,primary,action);buttonEnabled(b,enabled)
        actions.addView(b,LinearLayout.LayoutParams(dp(112),dp(48)).apply{setMargins(dp(6),0,dp(6),0)})
        return b
    }
    private fun render(){
        stakes.text="${levels[game.level]} · ${if(game.highBid>0)game.highBid else "—"} 分 × ${game.multiplier}"
        for(p in 1..2){val v=people[p-1];val role=if(game.landlord<0)"待定" else if(game.landlord==p)"地主" else "农民"
            v.bind(names[p],role,game.hands[p].size,game.status[p],game.turn==p&&game.phase!="over")
        }
        bottom.show(if(game.landlord<0)listOf(54,54,54) else game.bottom)
        notice.text=when(game.phase){"bid"->if(game.turn==0)"轮到你叫地主" else "${names[game.turn]}正在叫分";"redeal"->"无人叫分，重新发牌";"over"->if(game.delta>0)"本局获胜" else "本局结束";else->if(game.turn==0){if(game.last==null)"轮到你自由出牌" else "轮到你出牌"}else "${names[game.turn]}正在出牌"}
        notice.announceForAccessibility(notice.text)
        lastLabel.text=game.last?.let{"${names[game.lastPlayer]} · ${it.kind.title}"}?:if(game.phase=="play")"两家不出后，可自由出牌" else "地主拿底牌，先出牌"
        last.show(game.last?.cards?:emptyList());table.lastPlayer=game.lastPlayer
        lastLabel.visibility=if(game.last==null)View.VISIBLE else View.GONE
        last.contentDescription=game.last?.let { "${names[game.lastPlayer]}打出${it.kind.title}："+it.cards.joinToString("、"){card->Rules.cardName(card)} }?:"尚未出牌"
        info.text="我 · ${if(game.landlord<0)"身份待定" else if(game.landlord==0)"地主" else "农民"} · ${game.hands[0].size} 张     战绩 $wins 胜 / $games 局 · $score 分"
        selected.retainAll(game.hands[0].toSet());hand.removeAllViews()
        game.hands[0].asReversed().forEach { card->
            val face=CardFace(this,art,card,true);face.isSelected=card in selected;face.isEnabled=game.phase=="play"&&game.turn==0
            face.setOnClickListener{audio.cue("select");if(card in selected)selected.remove(card) else selected.add(card);face.isSelected=card in selected;refreshSelection()}
            hand.addView(face)
        }
        actions.removeAllViews();playButton=null
        when {
            game.phase=="over"-> {addAction("再来一局",true){fresh()};addAction("查看结算"){showResult()}}
            game.phase=="bid"&&game.turn==0->{addAction("不叫"){humanBid(0)};for(n in 1..3)addAction("$n 分",n==3,n>game.highBid){humanBid(n)}}
            game.phase=="play"&&game.turn==0->{addAction("不出",enabled=game.last!=null){humanPlay(emptyList())};addAction("提示"){hint()};addAction("重选"){selected.clear();render()};playButton=addAction("出牌",true,false){humanPlay(selected.toList())}}
            else->addAction("请稍候",enabled=false){}
        }
        refreshSelection()
    }
    private fun refreshSelection(){
        val m=Rules.classify(selected.toList());val valid=Rules.beats(m,game.last)
        selection.text=if(selected.isEmpty())"点击手牌选中，再点“出牌” · 不知道出什么，可点“提示”" else "已选 ${selected.size} 张 · ${m?.kind?.title?:"牌型不完整"}${if(m!=null&&!valid)" · 压不过上家" else ""}"
        playButton?.let{buttonEnabled(it,selected.isNotEmpty()&&valid)}
    }
    private fun humanBid(n:Int){if(game.turn!=0||game.phase!="bid")return;game.bid(n);audio.cue("bid");advance()}
    private fun humanPlay(cards:List<Int>){
        if(game.turn!=0||game.phase!="play")return
        if(cards.isEmpty()&&selected.isNotEmpty()){selection.text="已经选牌，请先点“重选”再不出";audio.cue("error");return}
        try{game.play(cards);selected.clear();audio.cue(if(cards.isEmpty())"pass" else if(game.last?.kind in listOf(Kind.BOMB,Kind.ROCKET))"bomb" else "play");advance()}
        catch(e:IllegalArgumentException){selection.text=e.message;audio.cue("error")}
    }
    private fun hint(){val m=Rules.ai(game.hands[0],game.last,0,game.landlord,game.lastPlayer,game.hands.map{it.size},2);selected.clear();m?.cards?.let{selected.addAll(it)};render();if(m==null)selection.text=if(Rules.moves(game.hands[0],game.last).isEmpty())"没有能压过的牌，请点“不出”" else "建议让农民队友继续出牌"}
    private fun advance(){
        if(game.phase=="over"&&!game.settled){games++;if(game.delta>0)wins++;score+=game.delta;game.settled=true;audio.cue(if(game.delta>0)"win" else "lose")}
        else if(game.turn==0)audio.cue("turn")
        persist();render();if(game.phase=="over")showResult() else schedule()
    }
    private fun schedule(){
        handler.removeCallbacksAndMessages(null)
        if(!running||modal||game.phase=="over")return
        if(game.phase=="redeal"){handler.postDelayed({fresh()},speed);return}
        if(game.turn==0)return
        handler.postDelayed({
            if(!running||modal)return@postDelayed
            if(game.phase=="bid"){game.bid(Rules.bid(game.hands[game.turn],game.highBid,game.level));audio.cue("bid")}
            else if(game.phase=="play"){val m=game.computer();game.play(m?.cards?:emptyList());audio.cue(if(m==null)"pass" else if(m.kind in listOf(Kind.BOMB,Kind.ROCKET))"bomb" else "play")}
            advance()
        },speed)
    }
    private fun fresh(){handler.removeCallbacksAndMessages(null);game=Game.create(nextLevel);selected.clear();persist();render();schedule()}
    private fun showResult(){
        if(modal)return;modal=true;handler.removeCallbacksAndMessages(null)
        val message="${if(game.winner==game.landlord)"地主" else "农民"}获胜${if(game.spring)" · 春天 / 反春天" else ""}\n${game.multiplier} 倍 · 本局 ${if(game.delta>0)"+" else ""}${game.delta} 分\n\n"+(1..2).joinToString("\n"){p->"${names[p]}剩余："+game.hands[p].joinToString(" "){Rules.cardName(it)}}
        AlertDialog.Builder(this).setTitle(if(game.delta>0)"好牌，赢了！" else "下局再来").setMessage(message).setPositiveButton("再来一局"){_,_->fresh()}.setNegativeButton("看看牌桌",null).create().apply{setOnDismissListener{modal=false;schedule()};show()}
    }
    private fun showSettings(){
        if(modal)return;modal=true;handler.removeCallbacksAndMessages(null)
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(10),dp(20),dp(10))}
        val music=Switch(this).apply{text="背景音乐";textSize=20f;isChecked=audio.music;minHeight=dp(48)}
        val effects=Switch(this).apply{text="出牌音效";textSize=20f;isChecked=audio.effects;minHeight=dp(48)}
        content.addView(music);content.addView(effects);content.addView(text("声音大小",18f))
        val volume=SeekBar(this).apply{max=100;progress=audio.volume;minimumHeight=dp(48)};content.addView(volume)
        fun applySound(){audio.configure(music.isChecked,effects.isChecked,volume.progress)}
        music.setOnCheckedChangeListener{_,_->applySound()};effects.setOnCheckedChangeListener{_,_->applySound()}
        volume.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){if(user)applySound()};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){audio.cue("play")}})
        content.addView(text("下一局难度",20f,gold));val difficulty=RadioGroup(this).apply{orientation=RadioGroup.HORIZONTAL;gravity=Gravity.CENTER}
        levels.forEachIndexed{i,label->difficulty.addView(RadioButton(this).apply{id=100+i;text=label;textSize=18f;setTextColor(Color.WHITE);minHeight=dp(48);isChecked=nextLevel==i})};difficulty.check(100+nextLevel)
        difficulty.setOnCheckedChangeListener{_,id->nextLevel=id-100;settings.edit().putInt("level",nextLevel).apply()};content.addView(difficulty)
        content.addView(text("电脑出牌速度（玩家不限时）",19f,gold));val speeds=listOf(1000L,1800L,2800L);val speedGroup=RadioGroup(this).apply{orientation=RadioGroup.HORIZONTAL;gravity=Gravity.CENTER}
        listOf("正常","舒缓","更慢").forEachIndexed{i,label->speedGroup.addView(RadioButton(this).apply{id=200+i;text=label;textSize=18f;setTextColor(Color.WHITE);minHeight=dp(48)})};speedGroup.check(200+speeds.indexOf(speed));speedGroup.setOnCheckedChangeListener{_,id->speed=speeds[id-200];settings.edit().putLong("speed",speed).apply()};content.addView(speedGroup)
        content.addView(text("完全离线 · 牌局自动保存\n两家连续不出后，上一家自由出牌。\n顺子、连对、飞机主体不含 2 和王。\n炸弹、王炸、春天均翻倍。",17f).apply{setPadding(0,dp(12),0,dp(12))})
        val scroll=ScrollView(this).apply{addView(content)}
        var restartAfterDismiss=false
        val dialog=AlertDialog.Builder(this).setTitle("声音与牌桌设置").setView(scroll).setPositiveButton("返回牌局",null).setNeutralButton("重新开局"){_,_->restartAfterDismiss=true}.create()
        dialog.setOnDismissListener{if(restartAfterDismiss)confirmRestart() else {modal=false;schedule()}};dialog.show()
    }
    private fun confirmRestart(){modal=true;handler.removeCallbacksAndMessages(null);AlertDialog.Builder(this).setTitle("重新发牌？").setMessage("当前牌局不计入战绩。").setPositiveButton("重新开局"){_,_->fresh()}.setNegativeButton("继续本局",null).create().apply{setOnDismissListener{modal=false;schedule()};show()}}
    private fun restore(){
        try{val saved=ObjectInputStream(AtomicFile(File(filesDir,"native-table-v2")).openRead()).use{it.readObject() as SavedTable};game=saved.game;games=saved.games;wins=saved.wins;score=saved.score}
        catch(_:Exception){game=Game.create(nextLevel)}
    }
    private fun persist(){
        val file=AtomicFile(File(filesDir,"native-table-v2"));var out:FileOutputStream?=null
        try{out=file.startWrite();val stream=ObjectOutputStream(out);stream.writeObject(SavedTable(game,games,wins,score));stream.flush();file.finishWrite(out)}catch(_:IOException){file.failWrite(out)}
    }
    override fun onResume(){super.onResume();running=true;if(::audio.isInitialized)audio.resume();if(::game.isInitialized)schedule()}
    override fun onPause(){running=false;handler.removeCallbacksAndMessages(null);if(::audio.isInitialized)audio.pause();if(::game.isInitialized)persist();super.onPause()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);if(::audio.isInitialized)audio.release();super.onDestroy()}
    @Deprecated("Legacy Android back callback")
    override fun onBackPressed(){if(modal)return;modal=true;handler.removeCallbacksAndMessages(null);AlertDialog.Builder(this).setTitle("暂时离开牌桌？").setMessage("当前牌局已经保存，下次打开继续。").setPositiveButton("离开"){_,_->finish()}.setNegativeButton("继续玩",null).create().apply{setOnDismissListener{modal=false;schedule()};show()}}
    internal fun testStart(level:Int=1){handler.removeCallbacksAndMessages(null);game=Game.create(level,kotlin.random.Random(42));game.turn=0;game.bid(3);selected.clear();persist();render();running=false}
}
