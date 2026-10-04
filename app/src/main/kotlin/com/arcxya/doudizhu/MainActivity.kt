package com.arcxya.doudizhu

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
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
    private lateinit var table:ReferenceTable
    private lateinit var notice:TextView
    private lateinit var info:TextView
    private lateinit var selection:TextView
    private lateinit var bottom:CardStrip
    private val seatCards=mutableListOf<CardStrip>()
    private val seatNames=mutableListOf<TextView>()
    private val counts=mutableListOf<TextView>()
    private val cues=mutableListOf<TextView>()
    private var seatMoves=Array<List<Int>>(3){emptyList()}
    private lateinit var selfName:TextView
    private lateinit var multiple:TextView
    private lateinit var record:TextView
    private lateinit var autoButton:Button
    private var autoPlay=false
    private lateinit var stakes:TextView
    private val people=mutableListOf<CharacterView>()
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
        text=label;textSize=20f;isAllCaps=false;setTypeface(null,Typeface.BOLD);maxLines=1;setAutoSizeTextTypeUniformWithConfiguration(14,20,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        setTextColor(Color.WHITE);setShadowLayer(dp(2).toFloat(),0f,dp(1).toFloat(),Color.rgb(112,58,15))
        val face=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,if(primary)intArrayOf(0xffffe75b.toInt(),0xfff5a412.toInt(),0xffd97108.toInt()) else intArrayOf(0xff76d0f1.toInt(),0xff228ac2.toInt(),0xff176693.toInt())).apply {
            cornerRadius=dp(18).toFloat();setStroke(dp(2),if(primary)0xfffff0a2.toInt() else 0xffc4f1ff.toInt())
        }
        background=android.graphics.drawable.InsetDrawable(face,0,dp(5),0,dp(5));stateListAnimator=null;elevation=dp(2).toFloat()
        minHeight=dp(48);minimumHeight=dp(48);minWidth=dp(90);setPadding(dp(8),0,dp(8),0);setOnClickListener{action()}
    }
    private fun buttonEnabled(b:Button,value:Boolean){b.isEnabled=value;b.alpha=if(value)1f else .42f}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        nextLevel=settings.getInt("level",1).coerceIn(0,2);speed=settings.getLong("speed",1800L).coerceIn(1000L,2800L)
        restore();game.last?.let{seatMoves[game.lastPlayer]=it.cards};art=CardArt(this);audio=AudioEngine(this);buildLayout();render()
    }
    @Suppress("DEPRECATION")
    private fun immersive(){
        window.statusBarColor=Color.TRANSPARENT
        window.navigationBarColor=Color.TRANSPARENT
        if(Build.VERSION.SDK_INT>=28){
            window.attributes=window.attributes.apply{
                layoutInDisplayCutoutMode=if(Build.VERSION.SDK_INT>=30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if(Build.VERSION.SDK_INT>=29){window.isStatusBarContrastEnforced=false;window.isNavigationBarContrastEnforced=false}
        if(Build.VERSION.SDK_INT>=30){
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply{
                systemBarsBehavior=android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(android.view.WindowInsets.Type.systemBars())
            }
        }else{
            window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);if(hasFocus)immersive()}
    private fun buildLayout(){
        table=ReferenceTable(this);setContentView(table)
        table.place(TableBackdrop(this),0f,0f,1f,1f)
        val portraits=assets.open("characters.webp").use{android.graphics.BitmapFactory.decodeStream(it)}
        fun label(value:String,size:Float=17f,color:Int=Color.WHITE)=text(value,size,color).apply{
            maxLines=1;setTypeface(null,Typeface.BOLD);setAutoSizeTextTypeUniformWithConfiguration(10,size.toInt(),1,android.util.TypedValue.COMPLEX_UNIT_SP)
            setShadowLayer(dp(1).toFloat(),0f,dp(1).toFloat(),0xff633815.toInt())
        }
        fun tool(label:String,icon:Int,action:()->Unit)=Button(this).apply{
            text=label;textSize=12f;isAllCaps=false;setTextColor(Color.WHITE);setTypeface(null,Typeface.BOLD)
            setShadowLayer(dp(2).toFloat(),0f,dp(1).toFloat(),0xff423e37.toInt());background=null
            minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setPadding(0,dp(2),0,0)
            setCompoundDrawablesWithIntrinsicBounds(null,TableIcon(icon,dp(22)),null,null)
            setOnClickListener{action()}
        }
        val back=tool("返回",3){onBackPressed()};back.setCompoundDrawablesWithIntrinsicBounds(null,TableIcon(4,dp(28)),null,null);back.text="";back.textSize=1f;back.contentDescription="返回，保存牌局"
        table.place(back,.012f,.006f,.065f,.12f)
        table.place(label("单机斗地主",14f),.085f,.018f,.18f,.055f)
        autoButton=tool("托管",0){autoPlay=!autoPlay;render();schedule()}
        val clear=tool("重选",1){selected.clear();render()}
        val sound=tool("声音",2){audio.configure(!(audio.music||audio.effects),!(audio.music||audio.effects),audio.volume)}
        val options=tool("设置",3){showSettings()}
        listOf(autoButton,clear,sound,options).forEachIndexed{i,v->table.place(v,.684f+i*.077f,.006f,.076f,.125f)}
        bottom=CardStrip(this,art).apply{contentDescription="地主底牌"};table.place(bottom,.443f,.013f,.114f,.082f)
        val left=CharacterView(this,portraits,0);val right=CharacterView(this,portraits,1);val self=CharacterView(this,portraits,2)
        people.addAll(listOf(self,left,right))
        table.place(left,.008f,.12f,.175f,.405f);table.place(right,.82f,.12f,.175f,.405f)
        table.place(self,.001f,.57f,.165f,.36f)
        for(p in 1..2){
            val name=label("",16f).apply{tag="opponent-text";setBackgroundColor(0x80504939.toInt())};seatNames.add(name)
            table.place(name,if(p==1)0f else .818f,.413f,.18f,.06f)
            val count=label("",19f).apply{tag="opponent-text";background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xff58bcea.toInt(),0xff17649b.toInt())).apply{cornerRadius=dp(3).toFloat();setStroke(dp(2),0xffdaefff.toInt())}}
            counts.add(count);table.place(count,if(p==1).23f else .745f,.455f,.032f,.078f)
            val cue=label("",24f,0xffffdb56.toInt()).apply{tag="opponent-text"};cues.add(cue)
            table.place(cue,if(p==1).225f else .535f,.326f,.245f,.068f)
        }
        selfName=label("",14f).apply{setBackgroundColor(0x99504831.toInt())};table.place(selfName,0f,.879f,.168f,.047f)
        for(p in 0..2){val cards=CardStrip(this,art,if(p==0)12 else 10);seatCards.add(cards)
            if(p==0)table.place(cards,.315f,.242f,.37f,.157f)
            else table.place(cards,if(p==1).225f else .535f,.134f,.25f,.193f)
        }
        stakes=label("",14f,0xff825d30.toInt()).apply{setShadowLayer(0f,0f,0f,0)};table.place(stakes,.32f,.405f,.36f,.045f)
        notice=label("",18f,0xff7c451d.toInt()).apply{setShadowLayer(0f,0f,0f,0)};table.place(notice,.30f,.41f,.4f,.049f)
        actions=LinearLayout(this).apply{gravity=Gravity.CENTER;clipChildren=false};table.place(actions,.25f,.464f,.5f,.14f)
        hand=HandLayout(this).apply{contentDescription="我的手牌，点击或横滑选择，再点出牌";setPadding(0,dp(2),0,0)};table.place(hand,.17f,.60f,.80f,.308f)
        table.place(View(this).apply{setBackgroundColor(0xb05a371e.toInt())},0f,.937f,1f,.063f)
        info=label("",19f,0xffffdf7d.toInt()).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,0,0)};table.place(info,0f,.94f,.33f,.055f)
        selection=label("",15f);table.place(selection,.34f,.94f,.30f,.055f)
        record=label("",14f,0xffffdfa2.toInt());table.place(record,.65f,.94f,.16f,.055f)
        multiple=label("",19f,0xffffe591.toInt());table.place(multiple,.81f,.94f,.11f,.055f)
        val help=Button(this).apply{text="帮助";textSize=13f;isAllCaps=false;setTextColor(Color.WHITE);background=background(0xff389ca9.toInt(),0xffb7f5e9.toInt());minHeight=0;minimumHeight=0;minWidth=0;minimumWidth=0;setPadding(0,0,0,0);setOnClickListener{showSettings()}}
        table.place(help,.925f,.94f,.068f,.054f)
    }
    private fun addAction(label:String,primary:Boolean=false,enabled:Boolean=true,action:()->Unit):Button {
        val b=button(label,primary,action);buttonEnabled(b,enabled)
        val width=(resources.configuration.screenWidthDp*.125f).toInt().coerceIn(76,110)
        actions.addView(b,LinearLayout.LayoutParams(dp(width),dp(48)).apply{setMargins(dp(4),0,dp(4),0)})
        return b
    }
    private fun addClock(){
        val clock=text("∞",26f).apply{contentDescription="玩家不限时";setTypeface(null,Typeface.BOLD);background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xff7ce9ff.toInt(),0xff0875b5.toInt())).apply{shape=GradientDrawable.OVAL;setStroke(dp(2),0xffffe9a0.toInt())}}
        actions.addView(clock,LinearLayout.LayoutParams(dp(38),dp(38)).apply{setMargins(dp(4),0,dp(4),0)})
    }
    private fun render(){
        stakes.text="单机${levels[game.level]}场  底分：${if(game.highBid>0)game.highBid else "—"}"
        stakes.visibility=if(game.phase=="bid")View.VISIBLE else View.INVISIBLE
        notice.visibility=if(game.phase=="bid")View.INVISIBLE else View.VISIBLE
        autoButton.text=if(autoPlay)"手动" else "托管"
        fun role(p:Int)=if(game.landlord<0)"" else if(game.landlord==p)"地主" else "农民"
        for(p in 1..2){
            seatNames[p-1].text="${names[p]} ${role(p)}"
            counts[p-1].text=game.hands[p].size.toString();counts[p-1].contentDescription="${names[p]}剩余${game.hands[p].size}张牌"
            cues[p-1].text=if(game.status[p] in listOf("不出","不叫")||game.phase=="bid")game.status[p].replace("等待叫分","") else ""
        }
        for(p in 0..2){people[p].active=game.turn==p&&game.phase!="over";seatCards[p].show(seatMoves[p].asReversed());seatCards[p].contentDescription="${names[p]}出牌："+seatMoves[p].joinToString("、"){Rules.cardName(it)}}
        bottom.show(if(game.landlord<0)listOf(54,54,54) else game.bottom)
        notice.text=when(game.phase){"bid"->if(game.turn==0)"轮到你叫地主" else "${names[game.turn]}正在叫分";"redeal"->"无人叫分，重新发牌";"over"->if(game.delta>0)"本局获胜" else "本局结束";else->if(game.turn==0)"轮到你出牌" else "${names[game.turn]}正在出牌"}
        notice.announceForAccessibility(notice.text)
        selfName.text="我 ${role(0)} · ${game.hands[0].size}张"
        info.text="●  $score  总计";record.text="$wins 胜 / $games 局";multiple.text="×${game.multiplier} 倍"
        selected.retainAll(game.hands[0].toSet());hand.removeAllViews()
        game.hands[0].asReversed().forEach { card->
            val face=CardFace(this,art,card,true);face.isSelected=card in selected;face.isEnabled=game.phase=="play"&&game.turn==0&&!autoPlay
            face.setOnClickListener{audio.cue("select");if(card in selected)selected.remove(card) else selected.add(card);face.isSelected=card in selected;refreshSelection()}
            hand.addView(face)
        }
        actions.removeAllViews();playButton=null
        when {
            autoPlay&&game.phase!="over"->{addAction("取消托管",true){autoPlay=false;render();schedule()}}
            game.phase=="over"-> {addAction("再来一局",true){fresh()};addAction("查看结算"){showResult()}}
            game.phase=="bid"&&game.turn==0->{addAction("叫地主",true){humanBid(3)};addClock();addAction("不叫",true){humanBid(0)}}
            game.phase=="play"&&game.turn==0->{addAction("不出",enabled=game.last!=null){humanPlay(emptyList())};addAction("提示"){hint()};playButton=addAction("出牌",true,false){humanPlay(selected.toList())}}
            else->addAction("请稍候",enabled=false){}
        }
        refreshSelection()
    }
    private fun refreshSelection(){
        val m=Rules.classify(selected.toList());val valid=Rules.beats(m,game.last)
        selection.text=if(selected.isEmpty())"本局  ${if(game.phase=="over")game.delta else 0}" else "已选 ${selected.size} 张 · ${m?.kind?.title?:"牌型不完整"}${if(m!=null&&!valid)" · 压不过上家" else ""}"
        playButton?.let{buttonEnabled(it,selected.isNotEmpty()&&valid)}
    }
    private fun humanBid(n:Int){if(game.turn!=0||game.phase!="bid")return;game.bid(n);audio.cue("bid");advance()}
    private fun humanPlay(cards:List<Int>){
        if(game.turn!=0||game.phase!="play")return
        if(cards.isEmpty()&&selected.isNotEmpty()){selection.text="已经选牌，请先点“重选”再不出";audio.cue("error");return}
        try{playCards(cards);selected.clear();audio.cue(if(cards.isEmpty())"pass" else if(game.last?.kind in listOf(Kind.BOMB,Kind.ROCKET))"bomb" else "play");advance()}
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
        if(game.turn==0&&!autoPlay)return
        handler.postDelayed({
            if(!running||modal)return@postDelayed
            if(game.phase=="bid"){game.bid(Rules.bid(game.hands[game.turn],game.highBid,game.level));audio.cue("bid")}
            else if(game.phase=="play"){val m=game.computer();playCards(m?.cards?:emptyList());audio.cue(if(m==null)"pass" else if(m.kind in listOf(Kind.BOMB,Kind.ROCKET))"bomb" else "play")}
            advance()
        },speed)
    }
    private fun playCards(cards:List<Int>){
        val actor=game.turn;val newTrick=game.last==null
        game.play(cards)
        if(newTrick||game.last==null)seatMoves=Array(3){emptyList()}
        seatMoves[actor]=cards.toList()
    }
    private fun fresh(){handler.removeCallbacksAndMessages(null);seatMoves=Array(3){emptyList()};game=Game.create(nextLevel);selected.clear();persist();render();schedule()}
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
    internal fun testBidding(){handler.removeCallbacksAndMessages(null);seatMoves=Array(3){emptyList()};game=Game.create(1,kotlin.random.Random(42));game.turn=0;running=false;selected.clear();render()}
    internal fun testSeatPlays(){
        testStart()
        for(p in 0..2){val card=p*4;val owner=game.hands.indexOfFirst{card in it};if(owner!=p){val swap=game.hands[p].first();game.hands[p].remove(swap);game.hands[owner].remove(card);game.hands[p].add(card);game.hands[owner].add(swap)};playCards(listOf(card))}
        for(p in 0..2)game.hands[p]=Rules.sorted(game.hands[p]).toMutableList()
        render()
    }
    internal fun testPlayedCards(cards:List<Int>){
        val rest=(0..53).filter{it !in cards}
        game.hands[0].clear();game.hands[0].addAll(rest.take(17))
        game.hands[1].clear();game.hands[1].addAll(cards)
        game.hands[2].clear();game.hands[2].addAll(rest.drop(17))
        game.landlord=1;game.turn=1;game.phase="play";game.last=null
        playCards(cards);render()
    }
    internal fun testStart(level:Int=1){handler.removeCallbacksAndMessages(null);seatMoves=Array(3){emptyList()};autoPlay=false;game=Game.create(level,kotlin.random.Random(42));game.turn=0;game.bid(3);selected.clear();persist();render();running=false}
}
