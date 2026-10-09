package com.arcxya.doudizhu

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ActivityNotFoundException
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
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.*
import java.io.*

private const val SNAPSHOT_FORMAT=1
// deal.wav carries one slap per round: 17 onsets for 51 cards, three cards a round. Launching each
// card on its own ran the whole deal about 40% faster than the cue, so the animation now rides the
// measured beats instead. Indices are milliseconds from the start of the clip.
internal val DEAL_BEATS=floatArrayOf(100f,310f,530f,770f,1000f,1230f,1460f,1680f,1920f,2160f,2410f,2670f,2920f,3160f,3400f,3620f,3850f)
/** The three cards of one round leave a few milliseconds apart, the way a dealer flicks them out. */
private const val DEAL_ROUND_STAGGER=20f
/** Shorter than a beat, so every card is down before the next slap lands. */
private const val DEAL_FLIGHT=260f
/** The kitty follows the last round; the cue has no beat for it. */
private const val DEAL_KITTY_LEAD=70f
/** Endpoints are painted-face centers in the card's parent, never view top-left coordinates. */
private class DealFlight(val launch:Float,val duration:Float,val fromX:Float,val fromY:Float,val targetX:Float,val targetY:Float,val view:CardFace,val player:Int,val targetScale:Float=1f)
// Frozen legacy record written by builds up to v3.6.0. Its field list and serialVersionUID must not
// change: the release upgrade check reads it and requires the file to stay byte-identical.
internal data class SavedTable(val game:Game,val games:Int,val wins:Int,val score:Int):Serializable {
    companion object{private const val serialVersionUID=6861108738685167990L}
}
// Current snapshot. The format tag lets a later build migrate a stored table instead of dropping it.
internal data class TableSnapshot(val format:Int,val game:Game,val record:MatchRecord?=null):Serializable {
    companion object{private const val serialVersionUID=1L}
}
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
    private val people=mutableListOf<SeatAvatar>()
    private lateinit var effectBanner:TextView
    private val difficultyLabels=mutableListOf<TextView>()
    private val badges=mutableListOf<TextView>()
    private val turnClocks=mutableListOf<TurnClock>()
    private lateinit var counter:RankCounter
    private val selected=linkedSetOf<Int>()
    private val handler=Handler(Looper.getMainLooper())
    private var running=false
    private var modal=false
    private var corrupt=false
    private var unreadable=false
    private var dealing=false
    /** A table that still owes its deal; on the first launch it starts once audio focus is granted. */
    private var dealPending=false
    private lateinit var dealLayer:DealLayer
    private var dealAnimator:android.animation.ValueAnimator?=null
    private var dealPreDraw:ViewTreeObserver.OnPreDrawListener?=null
    private var dealGeometry=emptyList<Int>()
    private val dealFlights=mutableListOf<DealFlight>()
    private val handFlights=mutableListOf<DealFlight>()
    private val bottomFlights=mutableListOf<DealFlight>()
    private var nextLevel=1
    private var speed=1800L
    private var games=0;private var wins=0;private var score=0
    private var playButton:Button?=null
    private val names=listOf("我","小林","老周")
    private val levels=listOf("简单","普通","困难")
    private val gold=Color.rgb(255,217,126)
    private val settings by lazy { getSharedPreferences("settings",0) }
    private lateinit var updates:UpdateController
    private var updateBadge:TextView?=null
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private fun text(value:String,size:Float=18f,color:Int=Color.WHITE)=TextView(this).apply { text=value;textSize=size;setTextColor(color);gravity=Gravity.CENTER;includeFontPadding=false }
    private fun background(color:Int,stroke:Int=Color.TRANSPARENT)=GradientDrawable().apply {setColor(color);cornerRadius=dp(10).toFloat();setStroke(dp(2),stroke)}
    private fun button(label:String,primary:Boolean=false,action:()->Unit)=ClassicActionButton(this,primary).apply {
        text=label;textSize=15f;isAllCaps=false;setTypeface(null,Typeface.BOLD);maxLines=1;gravity=Gravity.CENTER;includeFontPadding=false
        setAutoSizeTextTypeUniformWithConfiguration(12,15,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        setTextColor(Color.WHITE);background=null;stateListAnimator=null;elevation=0f
        minHeight=dp(48);minimumHeight=dp(48);minWidth=0;minimumWidth=0
        setPadding(dp(10),dp(8),dp(10),dp(8));setOnClickListener{action()}
    }
    private fun buttonEnabled(b:Button,value:Boolean){b.isEnabled=value;b.alpha=1f}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        nextLevel=settings.getInt("level",1).coerceIn(0,2);speed=settings.getLong("speed",1800L).coerceIn(1000L,2800L)
        val restored=restore();game.last?.let{seatMoves[game.lastPlayer]=it.cards};art=CardArt(this);audio=AudioEngine(this);buildLayout()
        updates=UpdateController(this,changed={updateBadge?.visibility=if(updates.hasUpdate)View.VISIBLE else View.GONE})
        // A table with no save is dealt too, but audio focus only arrives in onResume, so defer the
        // timeline to that moment and let the animation share the cue's origin.
        dealing=!restored;dealPending=dealing
        render()
        registerBackCallback()
        if(corrupt)Toast.makeText(this,"存档无法读取，本局已重新开始；战绩与设置已保留",Toast.LENGTH_LONG).show()
        if(!restored)audio.requestDeal()
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
            window.decorView.windowInsetsController?.apply{
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
        fun label(value:String,size:Float=17f,color:Int=Color.WHITE)=text(value,size,color).apply{
            tag="table-label";minimumHeight=dp(19)
            maxLines=1;setTypeface(null,Typeface.BOLD);setAutoSizeTextTypeUniformWithConfiguration(minOf(10,size.toInt()-1),size.toInt(),1,android.util.TypedValue.COMPLEX_UNIT_SP)
            setShadowLayer(dp(1).toFloat(),0f,dp(1).toFloat(),0xff274979.toInt())
        }
        fun tool(label:String,icon:Int,action:()->Unit)=Button(this).apply{
            text=label;textSize=10f;isAllCaps=false;gravity=Gravity.CENTER;includeFontPadding=false;setTextColor(Color.WHITE);setTypeface(null,Typeface.NORMAL)
            // The top drawable uses 16 dp of this compact button's height. Fit the
            // remaining single line as well as its width when system text is larger.
            maxLines=1;setAutoSizeTextTypeUniformWithConfiguration(8,10,1,android.util.TypedValue.COMPLEX_UNIT_SP)
            setShadowLayer(dp(1).toFloat(),0f,dp(1).toFloat(),0xff26467b.toInt());background=null
            minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setPadding(0,0,0,0)
            setCompoundDrawablesWithIntrinsicBounds(null,TableIcon(icon,dp(16)),null,null)
            setOnClickListener{action()}
        }
        val back=tool("",4){handleBack()};back.contentDescription="返回，保存牌局"
        table.place(back,.036f,.008f,.058f,.10f)
        counter=RankCounter(this);table.place(counter,.145f,.01f,.31f,.084f)
        bottom=CardStrip(this,art).apply{contentDescription="地主底牌"};table.place(bottom,.46f,.009f,.09f,.088f)
        autoButton=tool("托管",0){autoPlay=!autoPlay;selected.clear();render();schedule()}
        val clear=tool("重选",1){selected.clear();render()}
        val options=tool("设置",3){showSettings()}
        listOf(clear,autoButton,options).forEachIndexed{i,v->table.place(v,.709f+i*.052f,.003f,.047f,.11f)}
        updateBadge=text("新",9f,gold).apply{maxLines=1;setAutoSizeTextTypeUniformWithConfiguration(7,9,1,android.util.TypedValue.COMPLEX_UNIT_SP);visibility=View.GONE;contentDescription="设置中有新版本";importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        table.place(updateBadge!!,.848f,.005f,.02f,.055f)
        table.place(TableWordmark(this),.425f,.198f,.15f,.18f)
        stakes=label("",12f,0xffeef4ff.toInt()).apply{background=background(0x60304368)};table.place(stakes,.32f,.38f,.36f,.04f)
        val self=SeatAvatar(this,"seat_self.webp");val left=SeatAvatar(this,"seat_left.webp");val right=SeatAvatar(this,"seat_right.webp")
        people.addAll(listOf(self,left,right))
        table.place(left,.047f,.255f,.066f,.143f);table.place(right,.89f,.255f,.066f,.143f)
        for(p in 1..2){
            val x=if(p==1).045f else .885f
            val badge=label("",9f).apply{minimumHeight=dp(16);background=RoleBadge()};badges.add(badge)
            table.place(badge,x+.005f,.405f,.067f,.042f)
            // The short landscape bands can be smaller than the minimum autosize
            // Chinese fallback-font line at 1.3x text size.
            // Keep enough height for those lines and separate the name and difficulty.
            val name=label(names[p],12f).apply{tag="opponent-text";minimumHeight=dp(20)}
            seatNames.add(name);table.place(name,x,.463f,.08f,.06f)
            val local=label("",10f,0xffffe77b.toInt()).apply{minimumHeight=dp(18)}
            difficultyLabels.add(local);table.place(local,x,.539f,.08f,.05f)
            val count=label("",17f).apply{tag="opponent-text";background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xff70a7d3.toInt(),0xff467db2.toInt())).apply{cornerRadius=dp(2).toFloat();setStroke(dp(1),0xffc6eaff.toInt())}}
            counts.add(count);table.place(count,if(p==1).122f else .856f,.41f,.025f,.071f)
            val cue=GameCueLabel(this).apply{textSize=25f;gravity=Gravity.CENTER;setTextColor(0xffc2f1ff.toInt());tag="opponent-text";setTypeface(null,Typeface.BOLD_ITALIC)};cues.add(cue)
            table.place(cue,if(p==1).166f else .694f,.277f,.14f,.075f)
            val clock=TurnClock(this,"…");turnClocks.add(clock);table.place(clock,if(p==1).16f else .78f,.265f,.065f,.135f)
        }
        for(p in 0..2){val cards=CardStrip(this,art,if(p==0)12 else 10,if(p==1)Gravity.START else if(p==2)Gravity.END else Gravity.CENTER_HORIZONTAL).apply{tag="seat-play-$p"};seatCards.add(cards)
            if(p==0)table.place(cards,.365f,.435f,.27f,.16f)
            else table.place(cards,if(p==1).174f else .550f,.25f,.28f,.164f)
        }
        notice=label("",14f,0xffffe6a3.toInt()).apply{
            background=background(0xb0223554.toInt())
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        };table.place(notice,.29f,.126f,.42f,.062f)
        actions=ActionRow(this);table.place(actions,.19f,.465f,.62f,.132f)
        hand=HandLayout(this).apply{contentDescription="我的手牌，点击或横滑选择，再点出牌";setPadding(0,dp(2),0,0)};table.place(hand,.048f,.585f,.904f,.332f)
        table.place(View(this).apply{setBackgroundColor(0x38303c69)},0f,.934f,1f,.066f)
        table.place(self,.047f,.846f,.069f,.137f)
        selfName=label("",13f);table.place(selfName,.13f,.938f,.15f,.052f)
        info=label("",15f,0xffffe875.toInt()).apply{gravity=Gravity.CENTER_VERTICAL;setCompoundDrawablesWithIntrinsicBounds(CoinIcon(dp(15)),null,null,null);compoundDrawablePadding=dp(4)};table.place(info,.29f,.938f,.16f,.052f)
        selection=label("",12f).apply{accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};table.place(selection,.445f,.938f,.29f,.052f)
        record=label("",11f,0xffdbebff.toInt()).apply{visibility=View.GONE};table.place(record,.585f,.015f,.18f,.052f)
        multiple=label("",17f,0xffffe591.toInt()).apply{background=background(0x55402f55)};table.place(multiple,.75f,.939f,.12f,.05f)
        val help=Button(this).apply{text="帮助";textSize=13f;isAllCaps=false;includeFontPadding=false;maxLines=1;setAutoSizeTextTypeUniformWithConfiguration(8,13,1,android.util.TypedValue.COMPLEX_UNIT_SP);setTextColor(Color.WHITE);background=background(0xff53c99c.toInt(),0xffa4edce.toInt());minHeight=0;minimumHeight=0;minWidth=0;minimumWidth=0;setPadding(0,0,0,0);setOnClickListener{showHelp()}}
        table.place(help,.893f,.938f,.077f,.055f)
        effectBanner=label("",28f,0xffffd35b.toInt()).apply{alpha=0f;setTypeface(null,Typeface.BOLD_ITALIC);setShadowLayer(dp(2).toFloat(),0f,dp(2).toFloat(),0xff564222.toInt())}
        table.place(effectBanner,.32f,.345f,.36f,.09f)
        // Added last so cards dealt to the seats draw above the table. It only takes touches while a
        // deal is running, where a tap skips the rest of it.
        dealLayer=DealLayer(this).apply{contentDescription="正在发牌，点击跳过";setOnClickListener{endDeal()};isClickable=false}
        table.place(dealLayer,0f,0f,1f,1f)
    }
    private fun addAction(label:String,primary:Boolean=false,enabled:Boolean=true,action:()->Unit):Button {
        val b=button(label,primary,action);buttonEnabled(b,enabled)
        val width=(resources.configuration.screenWidthDp*.105f).toInt().coerceIn(if(label.length>3)94 else 70,104)
        actions.addView(b,LinearLayout.LayoutParams(dp(width),dp(48)).apply{setMargins(dp(4),0,dp(4),0)})
        return b
    }
    private fun addClock(){
        val clock=TurnClock(this,"∞").apply{contentDescription="玩家不限时"}
        actions.addView(clock,LinearLayout.LayoutParams(dp(43),dp(48)).apply{setMargins(dp(2),0,dp(2),0)})
    }
    private fun displayCards(cards:List<Int>)=cards.sortedWith(compareByDescending<Int>{Rules.rank(it)}.thenBy{it%4})
    private fun render(){
        stakes.text="单机${levels[game.level]}场  底分：${if(game.highBid>0)game.highBid else "—"}"
        stakes.visibility=View.VISIBLE
        notice.visibility=View.VISIBLE
        autoButton.text=if(autoPlay)"手动" else "托管"
        autoButton.contentDescription=if(autoPlay)"取消托管，自己出牌" else "开启托管，由电脑代打"
        autoButton.isSelected=autoPlay
        fun role(p:Int)=if(game.landlord<0)"" else if(game.landlord==p)"地主" else "农民"
        for(p in 1..2){
            seatNames[p-1].text=names[p]
            badges[p-1].text=role(p).ifEmpty{"电脑"}
            difficultyLabels[p-1].text=levels[game.level]
            turnClocks[p-1].visibility=if(!dealing && game.turn==p && game.phase in listOf("bid","play"))View.VISIBLE else View.INVISIBLE
            val shown=if(dealing)0 else game.hands[p].size
            counts[p-1].text=shown.toString();counts[p-1].contentDescription="${names[p]}剩余${shown}张牌"
            counts[p-1].setTextColor(if(!dealing&&shown<=2)0xffffdd7b.toInt() else Color.WHITE)
            cues[p-1].visibility=if(game.turn==p && game.phase!="over")View.INVISIBLE else View.VISIBLE
            cues[p-1].text=if(game.status[p] in listOf("不出","不叫")||game.phase=="bid")game.status[p].replace("等待叫分","") else ""
        }
        counter.show(game)
        counter.visibility=if(game.landlord<0)View.INVISIBLE else View.VISIBLE
        // The three bottom cards are dealt face down and stay visible for the deal, then follow the
        // existing rule of staying hidden until the landlord is known.
        bottom.visibility=if(dealing)View.VISIBLE else counter.visibility
        for(p in 0..2){seatCards[p].visibility=if(game.turn==p && game.phase!="over")View.INVISIBLE else View.VISIBLE;people[p].active=game.turn==p&&game.phase!="over";seatCards[p].show(displayCards(seatMoves[p]));seatCards[p].contentDescription="${names[p]}出牌："+seatMoves[p].joinToString("、"){Rules.cardName(it)}}
        bottom.show(if(game.landlord<0)listOf(54,54,54) else game.bottom.asReversed())
        if(dealing)for(i in 0 until bottom.childCount)bottom.getChildAt(i).alpha=0f
        dealLayer.isClickable=dealing
        dealLayer.importantForAccessibility=if(dealing)View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        notice.text=if(dealing)"正在发牌 · 点击桌面跳过" else when(game.phase){
            "bid"->if(game.turn==0)if(autoPlay)"托管中 · 正在叫分" else "轮到你叫分 · 最高 ${game.highBid} 分" else "${names[game.turn]}正在叫分"
            "redeal"->"无人叫分，重新发牌"
            "over"->if(game.delta>0)"本局获胜 · +${game.delta} 分" else "本局结束 · ${game.delta} 分"
            else->if(game.turn==0)if(autoPlay)"托管中 · 正在出牌" else if(game.last==null)"轮到你 · 自由出牌" else "轮到你 · 接${names[game.lastPlayer]}的${game.last!!.kind.title}" else "${names[game.turn]}正在出牌"
        }
        selfName.text=if(dealing)"我 · 0张" else "${role(0).ifEmpty{"我"}} · ${game.hands[0].size}张"
        info.text=score.toString();record.text="$wins 胜 / $games 局";multiple.text="×${game.multiplier} 倍"
        selected.retainAll(game.hands[0].toSet());hand.removeAllViews()
        displayCards(game.hands[0]).forEach { card->
            val face=CardFace(this,art,card,true);face.bottomCard=game.landlord==0 && card in game.bottom;face.isSelected=card in selected;face.isEnabled=game.phase=="play"&&game.turn==0&&!autoPlay
            if(dealing)face.alpha=0f
            face.setOnClickListener{audio.cue("select");if(card in selected)selected.remove(card) else selected.add(card);face.isSelected=card in selected;refreshSelection()}
            hand.addView(face)
        }
        actions.removeAllViews();playButton=null
        when {
            dealing->{}
            autoPlay&&game.phase!="over"->{addAction("取消托管",true){autoPlay=false;render();schedule()}}
            game.phase=="over"-> {addAction("再来一局",true){fresh()};addAction("查看结算"){showResult()}}
            // Score bidding: a call must beat the current highest, so lower scores grey out.
            game.phase=="bid"&&game.turn==0->{
                addAction("不叫",false){humanBid(0)}
                addClock()
                for(n in 1..3)addAction("${n}分",primary=n==3,enabled=n>game.highBid){humanBid(n)}
            }
            game.phase=="play"&&game.turn==0->{
                addAction("不出",enabled=game.last!=null){humanPlay(emptyList())}
                // Leading freely always has a move, so the generator only runs when there is something
                // to beat. With nothing playable the hint offers no line, so it greys out and says so.
                val canPlay=game.last==null||Rules.moves(game.hands[0],game.last).isNotEmpty()
                addAction(if(canPlay)"提示" else "无可出",enabled=canPlay){hint()}
                addClock();playButton=addAction("出牌",true,false){humanPlay(selected.toList())}
            }
            else->{}
        }
        refreshSelection()
    }
    private fun refreshSelection(){
        val m=Rules.classify(selected.toList());val valid=Rules.beats(m,game.last)
        selection.text=if(selected.isEmpty())when{
            dealing->"发牌中，点击桌面跳过"
            game.phase=="over"->"本局 ${if(game.delta>0)"+" else ""}${game.delta} 分"
            autoPlay->"电脑代打 · 点手动可接管"
            game.phase=="bid"->"叫分越高，输赢底分越高"
            game.turn!=0->"等待对手出牌"
            game.last==null->"点选或滑选 · 自由出牌"
            else->"点选手牌，或点提示"
        } else "${selected.size} 张 · ${m?.kind?.title?:"牌型不完整"}${if(m!=null&&!valid)" · 压不过" else ""}"
        selection.setTextColor(if(selected.isNotEmpty()&&!valid)0xffffd594.toInt() else Color.WHITE)
        playButton?.let{buttonEnabled(it,selected.isNotEmpty()&&valid)}
    }
    private fun humanBid(n:Int){if(game.turn!=0||game.phase!="bid")return;game.bid(n);audio.cue(AudioCues.forBid(n));advance()}
    private fun humanPlay(cards:List<Int>){
        if(game.turn!=0||game.phase!="play")return
        try{val cue=AudioCues.forMove(Rules.classify(cards),game.hands[game.turn],game.last);playCards(cards);selected.clear();audio.cue(cue);advance()}
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
        if(!running||modal||dealing||game.phase=="over")return
        if(game.phase=="redeal"){handler.postDelayed({fresh()},speed);return}
        if(game.turn==0&&!autoPlay)return
        handler.postDelayed({
            if(!running||modal||dealing)return@postDelayed
            if(game.phase=="bid"){val bid=Rules.bid(game.hands[game.turn],game.highBid,game.level);game.bid(bid);audio.cue(AudioCues.forBid(bid))}
            else if(game.phase=="play"){val m=game.computer();val cue=AudioCues.forMove(m,game.hands[game.turn],game.last);playCards(m?.cards?:emptyList());audio.cue(cue)}
            advance()
        },speed)
    }
    private fun playCards(cards:List<Int>){
        val actor=game.turn;val newTrick=game.last==null
        game.play(cards)
        if(newTrick)for(p in 0..2)if(p!=actor)game.status[p]="等待出牌"
        if(cards.isNotEmpty() && game.last?.kind in listOf(Kind.BOMB,Kind.ROCKET)){
            effectBanner.animate().cancel();effectBanner.alpha=1f
            effectBanner.text=if(game.last?.kind==Kind.ROCKET)"王炸 ×2" else "炸弹 ×2"
            effectBanner.animate().alpha(0f).setStartDelay(800L).setDuration(250L).start()
        }
        if(newTrick||game.last==null)seatMoves=Array(3){emptyList()}
        seatMoves[actor]=cards.toList()
    }
    private fun fresh(){
        handler.removeCallbacksAndMessages(null);seatMoves=Array(3){emptyList()}
        // Cancelling invokes animation-end listeners: detach them before replacing the game.
        cancelDealTimeline()
        dealFlights.clear();handFlights.clear();bottomFlights.clear();dealLayer.removeAllViews()
        effectBanner.animate().cancel();effectBanner.alpha=0f
        game=Game.create(nextLevel);selected.clear();persist()
        dealing=true;dealPending=true;render();audio.requestDeal();startDeal()
    }
    /** Builds the timeline from the laid-out views, so every card lands exactly where it comes to rest. */
    private fun startDeal(){
        if(!dealing)return
        dealPending=false
        cancelDealTimeline()
        // A posted runnable can run before the new hand has been laid out, or outlive a skipped
        // deal. Pre-draw gives every anchor its final safe-area coordinates and is cancellable.
        val listener=ViewTreeObserver.OnPreDrawListener {
            if(!dealing||isFinishing||isDestroyed){cancelDealTimeline();true}
            else if(dealAnimator==null){prepareDeal();true}
            else if(dealLayoutGeometry()!=dealGeometry){endDeal();false}
            else true
        }
        dealPreDraw=listener;table.viewTreeObserver.addOnPreDrawListener(listener);table.invalidate()
    }
    private fun cancelDealTimeline(){
        dealPreDraw?.let{if(table.viewTreeObserver.isAlive)table.viewTreeObserver.removeOnPreDrawListener(it)};dealPreDraw=null
        dealAnimator?.let{it.removeAllListeners();it.removeAllUpdateListeners();it.cancel()};dealAnimator=null
    }
    /** Include nested card slots: a resized window or safe inset settles the deal before it draws
     *  with old geometry. Text updates and unrelated toolbar relayouts keep the timeline running. */
    private fun dealLayoutGeometry():List<Int>{
        val anchors=listOf<View>(table,dealLayer,hand,bottom)+counts+
            (0 until hand.childCount).map{hand.getChildAt(it)}+(0 until bottom.childCount).map{bottom.getChildAt(it)}
        return anchors.flatMap{listOf(System.identityHashCode(it),it.left,it.top,it.width,it.height)}
    }
    private fun prepareDeal(){
            dealLayer.removeAllViews();dealFlights.clear();handFlights.clear();bottomFlights.clear()
            val first=hand.getChildAt(0) as? CardFace
            if(dealLayer.width<=0||dealLayer.height<=0||first==null||first.height<=0){endDeal();return}
            // The overlay occupies ReferenceTable's safe rectangle; its origin is not always (0,0).
            val deckX=dealLayer.left+dealLayer.width*.5f;val deckY=dealLayer.top+dealLayer.height*.30f
            dealLayer.cardWidth=first.width;dealLayer.cardHeight=first.height
            fun flight(launch:Float,view:CardFace,parent:View,targetX:Float,targetY:Float,player:Int,targetScale:Float=1f):DealFlight{
                val face=view.faceBounds();view.pivotX=face.centerX();view.pivotY=face.centerY()
                return DealFlight(launch,DEAL_FLIGHT,deckX-parent.left,deckY-parent.top,targetX,targetY,view,player,targetScale)
            }
            for(round in DEAL_BEATS.indices)for(p in 0..2){
                val launch=DEAL_BEATS[round]+p*DEAL_ROUND_STAGGER
                if(p==0){
                    val v=hand.getChildAt(round) as? CardFace?:continue;val face=v.faceBounds()
                    flight(launch,v,hand,v.left+face.centerX(),v.top+face.centerY(),0).also{dealFlights.add(it);handFlights.add(it)}
                }else{
                    val back=CardFace(this,art,54);dealLayer.addCard(back)
                    val badge=counts[p-1];val face=back.faceBounds()
                    val targetX=badge.left+badge.width/2f-dealLayer.left;val targetY=badge.top+badge.height/2f-dealLayer.top
                    val scale=minOf(badge.width/face.width(),badge.height/face.height(),1f)
                    dealFlights.add(flight(launch,back,dealLayer,targetX,targetY,p,scale))
                }
            }
            val kittyLead=DEAL_BEATS.last()+DEAL_KITTY_LEAD
            for(i in 0 until Math.min(3,bottom.childCount)){
                val v=bottom.getChildAt(i) as CardFace;val face=v.faceBounds()
                flight(kittyLead+i*DEAL_ROUND_STAGGER,v,bottom,v.left+face.centerX(),v.top+face.centerY(),-1).also{dealFlights.add(it);bottomFlights.add(it)}
            }
            dealLayer.isClickable=true
            dealGeometry=dealLayoutGeometry()
            applyDeal(0f)
            val total=kittyLead+2*DEAL_ROUND_STAGGER+DEAL_FLIGHT
            dealAnimator=android.animation.ValueAnimator.ofFloat(0f,total).apply{
                duration=total.toLong();interpolator=android.view.animation.LinearInterpolator()
                addUpdateListener{applyDeal(it.animatedValue as Float)}
                addListener(object:android.animation.AnimatorListenerAdapter(){
                    override fun onAnimationEnd(animation:android.animation.Animator){endDeal()}
                })
            }
            // Assign before start: disabled system animations may invoke onAnimationEnd immediately.
            dealAnimator?.start()
    }
    /** One timeline drives every card, so skipping lands them all in their final positions at once. */
    private fun applyDeal(t:Float){
        val arc=dealLayer.height*.06f
        for(f in dealFlights){
            val moving=t>=f.launch
            val p=if(moving)((t-f.launch)/f.duration).coerceIn(0f,1f) else 0f
            val eased=p*p*(3-2*p);val bow=if(p==0f||p==1f)0f else (-arc*Math.sin(Math.PI*p)).toFloat()
            val scale=.82f+(f.targetScale-.82f)*eased
            val fade=if(f.player>0)((1f-p)/.22f).coerceIn(0f,1f) else 1f
            f.view.alpha=if(moving)(p/.4f).coerceAtMost(1f)*fade else 0f
            f.view.scaleX=scale;f.view.scaleY=scale
            // Scaling is about the actual face center, so different card sizes share the same deck
            // and an opponent's back shrinks into the badge without drifting right and down.
            f.view.x=f.fromX+(f.targetX-f.fromX)*eased-f.view.pivotX
            f.view.y=f.fromY+(f.targetY-f.fromY)*eased+bow-f.view.pivotY
        }
        applyStrip(handFlights,t,hand.stride)
        applyStrip(bottomFlights,t,bottom.cardStride())
        for(p in 1..2){
            val count=dealFlights.count{f->f.player==p&&t>=f.launch+f.duration}
            counts[p-1].text=count.toString();counts[p-1].contentDescription="${names[p]}剩余${count}张牌"
        }
        selfName.text="我 · ${dealFlights.count{f->f.player==0&&t>=f.launch+f.duration}}张"
    }
    /** A row card normally draws its rank inside the strip its right neighbour leaves free, which
     *  reads as a blank white body once the card is airborne. While a card is uncovered it draws its
     *  whole face instead, and returns to the strip the moment the next card covers it. */
    private fun applyStrip(flights:List<DealFlight>,t:Float,stride:Float){
        for(i in flights.indices){
            val face=flights[i].view
            val settled=i==flights.size-1||t>=flights[i+1].launch+flights[i+1].duration
            val want=if(settled)stride else 0f
            val body=!settled||i==flights.size-1
            if(face.indexWidth!=want||face.showBody!=body){face.indexWidth=want;face.showBody=body;face.invalidate()}
        }
    }
    private fun endDeal(){
        if(!dealing)return
        dealing=false;dealPending=false
        cancelDealTimeline()
        for(f in dealFlights){f.view.alpha=1f;f.view.scaleX=1f;f.view.scaleY=1f;f.view.translationX=0f;f.view.translationY=0f}
        dealFlights.clear();handFlights.clear();bottomFlights.clear();dealLayer.removeAllViews();dealLayer.isClickable=false
        render();schedule()
    }
    private fun showResult(){
        if(modal)return;modal=true;handler.removeCallbacksAndMessages(null)
        val message="${if(game.winner==game.landlord)"地主" else "农民"}获胜${if(game.spring)" · 春天 / 反春天" else ""}\n${game.multiplier} 倍 · 本局 ${if(game.delta>0)"+" else ""}${game.delta} 分\n\n"+(1..2).joinToString("\n"){p->"${names[p]}剩余："+game.hands[p].joinToString(" "){Rules.cardName(it)}}
        val dialog=android.app.Dialog(this)
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(22),dp(16),dp(22),dp(16));background=background(0xee294781.toInt(),0xff85b1eb.toInt())}
        panel.addView(text(if(game.delta>0)"胜利！" else "下局再来",30f,0xffffd65e.toInt()).apply{setTypeface(null,Typeface.BOLD_ITALIC)})
        panel.addView(text(message,17f).apply{setPadding(0,dp(10),0,dp(10))})
        val row=LinearLayout(this).apply{gravity=Gravity.CENTER}
        row.addView(button("看看牌桌"){dialog.dismiss()},LinearLayout.LayoutParams(dp(120),dp(54)))
        row.addView(button("再来一局",true){dialog.dismiss();fresh()},LinearLayout.LayoutParams(dp(120),dp(54)))
        panel.addView(row);dialog.setContentView(ScrollView(this).apply{addView(panel)})
        dialog.setOnDismissListener{modal=false;schedule()};dialog.show()
        dialog.window?.apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));setLayout((resources.displayMetrics.widthPixels*.70f).toInt(),WindowManager.LayoutParams.WRAP_CONTENT)}

    }
    private fun showHelp(){
        if(modal)return;modal=true;handler.removeCallbacksAndMessages(null)
        val rules="点一下选牌，再点一下取消；横滑可连续选牌。点击“提示”选择建议组合，再点“出牌”确认。\n\n"+
            "叫分：不叫 / 1 / 2 / 3 分，必须高于当前最高分；最高分者当地主，拿三张底牌并先出。\n\n"+
            "接牌：同牌型、同张数比较大小；炸弹可压普通牌，王炸最大。两家连续不出后，上一位出牌者自由出牌。\n\n"+
            "顺子至少 5 张、连对至少 3 对、飞机至少两组三张，主体不含 2 和王。飞机单翅可带对子，不能带双王或主体同点数牌；对翅须为不同点数对子。四带二可带一对，但不能带双王。\n\n"+
            "地主一方与两名农民分别组队，任一农民出完即农民获胜。炸弹、王炸、春天或反春天翻倍。积分仅作本地战绩。\n\n"+
            "玩家不限时；托管可随时取消。牌局自动保存，下次打开继续。"
        val content=text(rules,17f).apply{gravity=Gravity.START;setPadding(dp(22),dp(12),dp(22),dp(12))}
        AlertDialog.Builder(this).setTitle("玩法与操作").setView(ScrollView(this).apply{addView(content)})
            .setPositiveButton("返回牌局",null).create().apply{
                setOnDismissListener{modal=false;schedule()};show()
                window?.setBackgroundDrawable(background(0xff283e78.toInt(),0xff769cda.toInt()))
            }
    }
    private fun showSettings(){
        if(modal)return;modal=true;handler.removeCallbacksAndMessages(null)
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(10),dp(20),dp(10))}
        val music=Switch(this).apply{text="背景音乐";textSize=20f;isChecked=audio.music;minHeight=dp(48)}
        val effects=Switch(this).apply{text="出牌音效";textSize=20f;isChecked=audio.effects;minHeight=dp(48)}
        content.addView(music);content.addView(effects)
        // Music and effects are separate channels: the deal cue is 3.9 s of continuous sound and can
        // need a very different level from the short spoken cues. Label and slider share one row to
        // keep the dialog short enough that the music buttons stay on screen.
        fun levelLabel()=text("",18f).apply{
            gravity=Gravity.START;maxLines=1;isSingleLine=true
            setAutoSizeTextTypeUniformWithConfiguration(10,18,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        fun levelRow(label:TextView,bar:SeekBar)=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            addView(label,LinearLayout.LayoutParams(dp(168),LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(bar,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        }
        val musicLabel=levelLabel();val effectLabel=levelLabel()
        val musicBar=SeekBar(this).apply{max=100;progress=audio.musicVolume;minimumHeight=dp(48)}
        val effectBar=SeekBar(this).apply{max=100;progress=audio.effectVolume;minimumHeight=dp(48)}
        content.addView(levelRow(musicLabel,musicBar));content.addView(levelRow(effectLabel,effectBar))
        fun applySound(){
            audio.configure(music.isChecked,effects.isChecked,musicBar.progress,effectBar.progress)
            musicLabel.text="音乐音量 ${musicBar.progress}%"
            effectLabel.text="音效音量 ${effectBar.progress}%"
            musicBar.contentDescription=musicLabel.text;effectBar.contentDescription=effectLabel.text
        }
        applySound()
        music.setOnCheckedChangeListener{_,_->applySound()};effects.setOnCheckedChangeListener{_,_->applySound()}
        musicBar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){if(user)applySound()};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}})
        effectBar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){if(user)applySound()};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){audio.cue("play")}})
        val musicName=text("",17f).apply{gravity=Gravity.START;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,dp(8),0,dp(4))}
        content.addView(musicName)
        val musicButtons=LinearLayout(this).apply{gravity=Gravity.CENTER}
        val chooseMusic=button("选择本地音乐",true){chooseLocalMusic()}
        val defaultMusic=button("恢复默认"){audio.restoreDefaultMusic{result->if(!isFinishing&&!isDestroyed)Toast.makeText(this,result.message,Toast.LENGTH_LONG).show()}}
        musicButtons.addView(chooseMusic,LinearLayout.LayoutParams(0,dp(54),1f))
        musicButtons.addView(defaultMusic,LinearLayout.LayoutParams(0,dp(54),1f))
        content.addView(musicButtons)
        content.addView(text("选择手机里的音频，导入后可离线播放（最大 32 MB）。",15f).apply{gravity=Gravity.START;setPadding(0,0,0,dp(8))})
        fun refreshMusic(){
            musicName.text=if(audio.isMusicBusy)"正在处理音乐…" else "当前音乐：${audio.currentMusicName}"
            buttonEnabled(chooseMusic,!audio.isMusicBusy)
            buttonEnabled(defaultMusic,!audio.isMusicBusy&&audio.hasCustomMusic)
        }
        audio.onMusicChanged={if(!isFinishing&&!isDestroyed)refreshMusic()};refreshMusic()
        content.addView(text("本地战绩：$wins 胜 / $games 局 · $score 分",16f))
        content.addView(text("下一局难度",20f,gold));val difficulty=RadioGroup(this).apply{orientation=RadioGroup.HORIZONTAL;gravity=Gravity.CENTER}
        levels.forEachIndexed{i,label->difficulty.addView(RadioButton(this).apply{id=100+i;text=label;textSize=18f;setTextColor(Color.WHITE);minHeight=dp(48);isChecked=nextLevel==i})};difficulty.check(100+nextLevel)
        difficulty.setOnCheckedChangeListener{_,id->nextLevel=id-100;settings.edit().putInt("level",nextLevel).apply()};content.addView(difficulty)
        content.addView(text("电脑出牌速度（玩家不限时）",19f,gold));val speeds=listOf(1000L,1800L,2800L);val speedGroup=RadioGroup(this).apply{orientation=RadioGroup.HORIZONTAL;gravity=Gravity.CENTER}
        listOf("正常","舒缓","更慢").forEachIndexed{i,label->speedGroup.addView(RadioButton(this).apply{id=200+i;text=label;textSize=18f;setTextColor(Color.WHITE);minHeight=dp(48)})};speedGroup.check(200+speeds.indexOf(speed));speedGroup.setOnCheckedChangeListener{_,id->speed=speeds[id-200];settings.edit().putLong("speed",speed).apply()};content.addView(speedGroup)
        content.addView(updates.settingsView())
        content.addView(text("离线对局 · 牌局自动保存\n两家连续不出后，上一家自由出牌。\n顺子、连对、飞机主体不含 2 和王。\n炸弹、王炸、春天均翻倍。",17f).apply{setPadding(0,dp(12),0,dp(12))})
        val scroll=ScrollView(this).apply{addView(content)}
        var restartAfterDismiss=false
        val dialog=AlertDialog.Builder(this).setTitle("声音与牌桌设置").setView(scroll).setPositiveButton("返回牌局",null).setNeutralButton("重新开局"){_,_->restartAfterDismiss=true}.create()
        dialog.setOnDismissListener{audio.onMusicChanged=null;updates.unbindSettings();if(restartAfterDismiss)confirmRestart() else {modal=false;schedule()}};dialog.show();dialog.window?.setBackgroundDrawable(background(0xff283e78.toInt(),0xff769cda.toInt()))
    }
    private fun confirmRestart(){modal=true;handler.removeCallbacksAndMessages(null);AlertDialog.Builder(this).setTitle("重新发牌？").setMessage("当前牌局不计入战绩。").setPositiveButton("重新开局"){_,_->fresh()}.setNegativeButton("继续本局",null).create().apply{setOnDismissListener{modal=false;schedule()};show()}}
    private fun chooseLocalMusic(){
        if(audio.isMusicBusy)return
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="audio/*";putExtra(Intent.EXTRA_LOCAL_ONLY,true);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        try{startActivityForResult(intent,401)}
        catch(_:ActivityNotFoundException){Toast.makeText(this,"手机上没有可用的文件选择器",Toast.LENGTH_LONG).show()}
    }
    @Deprecated("Android platform result callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==401&&resultCode==RESULT_OK){
            data?.data?.let{uri->audio.importMusic(uri){result->if(!isFinishing&&!isDestroyed)Toast.makeText(this,result.message,Toast.LENGTH_LONG).show()}}
        }
    }
    /** An independent record mirror survives an unreadable table. Valid snapshots remain authoritative. */
    private fun recordStore()=getSharedPreferences("record",0)
    private fun saveRecord(){recordStore().edit().putInt("format",SNAPSHOT_FORMAT).putInt("games",games).putInt("wins",wins).putInt("score",score).apply()}
    private fun readStored(file:File):Any?{
        // AtomicFile may have only its backup after an interrupted replacement.
        if(!file.exists()&&!File(file.path+".bak").exists())return null
        return try{ObjectInputStream(AtomicFile(file).openRead()).use{it.readObject()}}catch(_:Exception){unreadable=true;null}
    }
    private fun restore():Boolean{
        // v3 is the current format; v2 is only ever read, never rewritten, so older builds keep it.
        val currentFile=File(filesDir,"native-table-v3")
        val hasCurrent=currentFile.exists()||File(currentFile.path+".bak").exists()
        val current=readStored(currentFile) as? TableSnapshot
        val currentUsable=usableSnapshot(current,SNAPSHOT_FORMAT)
        val stored=recordStore()
        val legacyFile=File(filesDir,"native-table-v2")
        // Old statistics may still need migrating, but an old table must not replace a newer broken
        // one: that could replay an already counted result and silently roll the game back.
        val legacy=if(!hasCurrent||(!stored.contains("games")&&(!currentUsable||current?.record==null)))readStored(legacyFile) as? SavedTable else null
        when {
            currentUsable&&current?.record!=null->{games=current.record.games;wins=current.record.wins;score=current.record.score;saveRecord()}
            stored.contains("games")->{games=stored.getInt("games",0);wins=stored.getInt("wins",0);score=stored.getInt("score",0)}
            legacy!=null->{games=legacy.games;wins=legacy.wins;score=legacy.score;saveRecord()}
        }
        val candidate=if(hasCurrent)current?.game else legacy?.game
        if(if(hasCurrent)currentUsable else usableTable(candidate)){game=candidate!!;return true}
        corrupt=unreadable||hasCurrent||candidate!=null||legacyFile.exists()||File(legacyFile.path+".bak").exists()
        game=Game.create(nextLevel)
        return false
    }
    private fun persist(){
        val file=AtomicFile(File(filesDir,"native-table-v3"));var out:FileOutputStream?=null
        // The table's settled flag and its resulting record must commit together. Preferences are
        // only a recovery mirror; publishing them first could count a result twice after a failed save.
        try{out=file.startWrite();val stream=ObjectOutputStream(out);stream.writeObject(TableSnapshot(SNAPSHOT_FORMAT,game,MatchRecord(games,wins,score)));stream.flush();file.finishWrite(out)}catch(_:IOException){file.failWrite(out);return}
        saveRecord()
    }
    override fun onResume(){super.onResume();running=true;if(::audio.isInitialized)audio.resume();if(::game.isInitialized){if(dealPending)startDeal();schedule()};if(::updates.isInitialized)updates.onResume()}
    override fun onPause(){running=false;if(::updates.isInitialized)updates.onPause();handler.removeCallbacksAndMessages(null);if(dealing)endDeal();if(::audio.isInitialized)audio.pause();if(::game.isInitialized)persist();super.onPause()}
    override fun onDestroy(){if(::updates.isInitialized)updates.close();handler.removeCallbacksAndMessages(null);if(::table.isInitialized)cancelDealTimeline();if(::audio.isInitialized)audio.release();super.onDestroy()}
    private fun handleBack(){
        if(modal)return;modal=true;handler.removeCallbacksAndMessages(null)
        AlertDialog.Builder(this).setTitle("暂时离开牌桌？").setMessage("当前牌局已经保存，下次打开继续。").setPositiveButton("离开"){_,_->finish()}.setNegativeButton("继续玩",null).create().apply{setOnDismissListener{modal=false;schedule()};show()}
    }
    /** Android 13 and later deliver back through the invoked-callback dispatcher, not onBackPressed. */
    private fun registerBackCallback(){
        if(Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){handleBack()}
    }
    @Deprecated("Legacy Android back callback for API 32 and below")
    override fun onBackPressed(){handleBack()}
    internal fun testAudio():AudioEngine=audio
    internal fun testBidding(){
        handler.removeCallbacksAndMessages(null);seatMoves=Array(3){emptyList()}
        val own=listOf(53,52,48,51,40,43,36,37,38,39,28,29,31,24,16,19,6)
        val kitty=listOf(25,26,7);val rest=(0..53).filter{it !in own && it !in kitty}
        game=Game.create(1,kotlin.random.Random(42)).copy(hands=mutableListOf(Rules.sorted(own).toMutableList(),Rules.sorted(rest.take(17)).toMutableList(),Rules.sorted(rest.drop(17)).toMutableList()),bottom=kitty)
        game.turn=0;running=false;selected.clear();render()
    }
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
    /** Starts a real deal so a device test can observe both the running and the finished state. */
    internal fun testDeal(runComputer:Boolean=false):Boolean{
        handler.removeCallbacksAndMessages(null);autoPlay=false;fresh();running=runComputer
        if(runComputer){game.turn=1;speed=1000L;schedule()}
        return dealing
    }
    internal fun testSuspendTurns(){running=false;handler.removeCallbacksAndMessages(null)}
    internal fun testDealing():Boolean=dealing
    internal fun testDealAnimating():Boolean=dealAnimator?.isRunning==true
    /** A frozen frame makes geometry assertions independent of emulator timing and animation scale. */
    internal fun testDealAt(time:Float){dealAnimator?.pause();applyDeal(time)}
    internal fun testDealCards(player:Int):List<CardFace> = dealFlights.filter{it.player==player}.map{it.view}
    internal fun testDealCounter(player:Int):TextView=counts[player-1]
    /** Redraws after a test has rewritten the table state directly. */
    internal fun testRender(){render()}
    internal fun testDealLayer():DealLayer=dealLayer
    internal fun testBottomStrip():CardStrip=bottom
}
