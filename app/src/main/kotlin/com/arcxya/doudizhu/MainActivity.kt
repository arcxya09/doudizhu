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
    private fun button(label:String,primary:Boolean=false,action:()->Unit)=ClassicActionButton(this,primary).apply {
        text=label;textSize=15f;isAllCaps=false;setTypeface(null,Typeface.BOLD);maxLines=1
        setAutoSizeTextTypeUniformWithConfiguration(12,15,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        setTextColor(Color.WHITE);background=null;stateListAnimator=null;elevation=0f
        minHeight=dp(48);minimumHeight=dp(48);minWidth=0;minimumWidth=0
        setPadding(dp(10),dp(13),dp(10),dp(13));setOnClickListener{action()}
    }
    private fun buttonEnabled(b:Button,value:Boolean){b.isEnabled=value;b.alpha=if(value)1f else .54f}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        nextLevel=settings.getInt("level",1).coerceIn(0,2);speed=settings.getLong("speed",1800L).coerceIn(1000L,2800L)
        val restored=restore();game.last?.let{seatMoves[game.lastPlayer]=it.cards};art=CardArt(this);audio=AudioEngine(this);buildLayout();render()
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
            maxLines=1;setTypeface(null,Typeface.BOLD);setAutoSizeTextTypeUniformWithConfiguration(minOf(10,size.toInt()-1),size.toInt(),1,android.util.TypedValue.COMPLEX_UNIT_SP)
            setShadowLayer(dp(1).toFloat(),0f,dp(1).toFloat(),0xff274979.toInt())
        }
        fun tool(label:String,icon:Int,action:()->Unit)=Button(this).apply{
            text=label;textSize=10f;isAllCaps=false;setTextColor(Color.WHITE);setTypeface(null,Typeface.NORMAL)
            setShadowLayer(dp(1).toFloat(),0f,dp(1).toFloat(),0xff26467b.toInt());background=null
            minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setPadding(0,0,0,0)
            setCompoundDrawablesWithIntrinsicBounds(null,TableIcon(icon,dp(16)),null,null)
            setOnClickListener{action()}
        }
        val back=tool("",4){onBackPressed()};back.contentDescription="返回，保存牌局"
        table.place(back,.036f,.008f,.058f,.10f)
        counter=RankCounter(this);table.place(counter,.145f,.01f,.31f,.084f)
        bottom=CardStrip(this,art).apply{contentDescription="地主底牌"};table.place(bottom,.46f,.009f,.09f,.088f)
        autoButton=tool("托管",0){autoPlay=!autoPlay;render();schedule()}
        val clear=tool("重选",1){selected.clear();render()}
        val options=tool("设置",3){showSettings()}
        listOf(clear,autoButton,options).forEachIndexed{i,v->table.place(v,.709f+i*.052f,.003f,.047f,.11f)}
        table.place(TableWordmark(this),.425f,.198f,.15f,.18f)
        stakes=label("",12f,0xffeef4ff.toInt()).apply{background=background(0x60304368)};table.place(stakes,.32f,.38f,.36f,.04f)
        val self=SeatAvatar(this,"seat_self.webp");val left=SeatAvatar(this,"seat_left.webp");val right=SeatAvatar(this,"seat_right.webp")
        people.addAll(listOf(self,left,right))
        table.place(left,.047f,.255f,.066f,.143f);table.place(right,.89f,.255f,.066f,.143f)
        for(p in 1..2){
            val x=if(p==1).045f else .885f
            val badge=label("",9f).apply{background=RoleBadge()};badges.add(badge)
            table.place(badge,x+.005f,.405f,.067f,.042f)
            val name=label(names[p],12f).apply{tag="opponent-text"};seatNames.add(name);table.place(name,x,.448f,.08f,.06f)
            val local=label("",10f,0xffffe77b.toInt());difficultyLabels.add(local);table.place(local,x,.51f,.08f,.05f)
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
        notice=label("",13f,0xffdaedff.toInt());table.place(notice,.29f,.50f,.42f,.078f)
        actions=LinearLayout(this).apply{gravity=Gravity.CENTER;clipChildren=false};table.place(actions,.25f,.465f,.5f,.132f)
        hand=HandLayout(this).apply{contentDescription="我的手牌，点击或横滑选择，再点出牌";setPadding(0,dp(2),0,0)};table.place(hand,.048f,.585f,.904f,.332f)
        table.place(View(this).apply{setBackgroundColor(0x38303c69)},0f,.934f,1f,.066f)
        table.place(self,.047f,.846f,.069f,.137f)
        selfName=label("",13f);table.place(selfName,.13f,.938f,.15f,.052f)
        info=label("",15f,0xffffe875.toInt()).apply{gravity=Gravity.CENTER_VERTICAL;setCompoundDrawablesWithIntrinsicBounds(CoinIcon(dp(15)),null,null,null);compoundDrawablePadding=dp(4)};table.place(info,.29f,.938f,.16f,.052f)
        selection=label("",12f);table.place(selection,.45f,.938f,.26f,.052f)
        record=label("",11f,0xffdbebff.toInt()).apply{visibility=View.GONE};table.place(record,.585f,.015f,.18f,.052f)
        multiple=label("",17f,0xffffe591.toInt()).apply{background=background(0x55402f55)};table.place(multiple,.75f,.939f,.12f,.05f)
        val help=Button(this).apply{text="帮助";textSize=13f;isAllCaps=false;includeFontPadding=false;maxLines=1;setAutoSizeTextTypeUniformWithConfiguration(10,13,1,android.util.TypedValue.COMPLEX_UNIT_SP);setTextColor(Color.WHITE);background=background(0xff53c99c.toInt(),0xffa4edce.toInt());minHeight=0;minimumHeight=0;minWidth=0;minimumWidth=0;setPadding(0,0,0,0);setOnClickListener{showSettings()}}
        table.place(help,.893f,.938f,.077f,.055f)
        effectBanner=label("",28f,0xffffd35b.toInt()).apply{alpha=0f;setTypeface(null,Typeface.BOLD_ITALIC);setShadowLayer(dp(2).toFloat(),0f,dp(2).toFloat(),0xff564222.toInt())}
        table.place(effectBanner,.32f,.345f,.36f,.09f)
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
        notice.visibility=if(game.phase=="redeal")View.VISIBLE else View.INVISIBLE
        autoButton.text=if(autoPlay)"手动" else "托管"
        fun role(p:Int)=if(game.landlord<0)"" else if(game.landlord==p)"地主" else "农民"
        for(p in 1..2){
            seatNames[p-1].text=names[p]
            badges[p-1].text=role(p).ifEmpty{"电脑"}
            difficultyLabels[p-1].text=levels[game.level]
            turnClocks[p-1].visibility=if(game.turn==p && game.phase in listOf("bid","play"))View.VISIBLE else View.INVISIBLE
            counts[p-1].text=game.hands[p].size.toString();counts[p-1].contentDescription="${names[p]}剩余${game.hands[p].size}张牌"
            cues[p-1].visibility=if(game.turn==p && game.phase!="over")View.INVISIBLE else View.VISIBLE
            cues[p-1].text=if(game.status[p] in listOf("不出","不叫")||game.phase=="bid")game.status[p].replace("等待叫分","") else ""
        }
        counter.show(game)
        counter.visibility=if(game.landlord<0)View.INVISIBLE else View.VISIBLE
        bottom.visibility=counter.visibility
        for(p in 0..2){seatCards[p].visibility=if(game.turn==p && game.phase!="over")View.INVISIBLE else View.VISIBLE;people[p].active=game.turn==p&&game.phase!="over";seatCards[p].show(displayCards(seatMoves[p]));seatCards[p].contentDescription="${names[p]}出牌："+seatMoves[p].joinToString("、"){Rules.cardName(it)}}
        bottom.show(if(game.landlord<0)listOf(54,54,54) else game.bottom.asReversed())
        notice.text=when(game.phase){"bid"->if(game.turn==0)"轮到你叫地主" else "${names[game.turn]}正在叫分";"redeal"->"无人叫分，重新发牌";"over"->if(game.delta>0)"本局获胜" else "本局结束";else->if(game.turn==0)"轮到你出牌" else "${names[game.turn]}正在出牌"}
        notice.announceForAccessibility(notice.text)
        selfName.text="${role(0).ifEmpty{"我"}} · ${game.hands[0].size}张"
        info.text=score.toString();record.text="$wins 胜 / $games 局";multiple.text="×${game.multiplier} 倍"
        selected.retainAll(game.hands[0].toSet());hand.removeAllViews()
        displayCards(game.hands[0]).forEachIndexed { index,card->
            val face=CardFace(this,art,card,true);face.landlordRibbon=game.landlord==0 && index==game.hands[0].lastIndex;face.isSelected=card in selected;face.isEnabled=game.phase=="play"&&game.turn==0&&!autoPlay
            face.setOnClickListener{audio.cue("select");if(card in selected)selected.remove(card) else selected.add(card);face.isSelected=card in selected;refreshSelection()}
            hand.addView(face)
        }
        actions.removeAllViews();playButton=null
        when {
            autoPlay&&game.phase!="over"->{addAction("取消托管",true){autoPlay=false;render();schedule()}}
            game.phase=="over"-> {addAction("再来一局",true){fresh()};addAction("查看结算"){showResult()}}
            game.phase=="bid"&&game.turn==0->{addAction("叫地主",true){humanBid(3)};addClock();addAction("不叫",false){humanBid(0)}}
            game.phase=="play"&&game.turn==0->{addAction("不出",enabled=game.last!=null){humanPlay(emptyList())};addAction("提示"){hint()};addClock();playButton=addAction("出牌",true,false){humanPlay(selected.toList())}}
            else->{}
        }
        refreshSelection()
    }
    private fun refreshSelection(){
        val m=Rules.classify(selected.toList());val valid=Rules.beats(m,game.last)
        selection.text=if(selected.isEmpty())if(game.phase=="over")"本局 ${game.delta}" else "" else "已选 ${selected.size} 张 · ${m?.kind?.title?:"牌型不完整"}${if(m!=null&&!valid)" · 压不过上家" else ""}"
        playButton?.let{buttonEnabled(it,selected.isNotEmpty()&&valid)}
    }
    private fun humanBid(n:Int){if(game.turn!=0||game.phase!="bid")return;game.bid(n);audio.cue(AudioCues.forBid(n));advance()}
    private fun humanPlay(cards:List<Int>){
        if(game.turn!=0||game.phase!="play")return
        if(cards.isEmpty()&&selected.isNotEmpty()){selection.text="已经选牌，请先点“重选”再不出";audio.cue("error");return}
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
        if(!running||modal||game.phase=="over")return
        if(game.phase=="redeal"){handler.postDelayed({fresh()},speed);return}
        if(game.turn==0&&!autoPlay)return
        handler.postDelayed({
            if(!running||modal)return@postDelayed
            if(game.phase=="bid"){val bid=Rules.bid(game.hands[game.turn],game.highBid,game.level);game.bid(bid);audio.cue(AudioCues.forBid(bid))}
            else if(game.phase=="play"){val m=game.computer();val cue=AudioCues.forMove(m,game.hands[game.turn],game.last);playCards(m?.cards?:emptyList());audio.cue(cue)}
            advance()
        },speed)
    }
    private fun playCards(cards:List<Int>){
        val actor=game.turn;val newTrick=game.last==null
        game.play(cards)
        if(cards.isNotEmpty() && game.last?.kind in listOf(Kind.BOMB,Kind.ROCKET)){
            effectBanner.animate().cancel();effectBanner.alpha=1f
            effectBanner.text=if(game.last?.kind==Kind.ROCKET)"王炸 ×2" else "炸弹 ×2"
            effectBanner.animate().alpha(0f).setStartDelay(800L).setDuration(250L).start()
        }
        if(newTrick||game.last==null)seatMoves=Array(3){emptyList()}
        seatMoves[actor]=cards.toList()
    }
    private fun fresh(){handler.removeCallbacksAndMessages(null);seatMoves=Array(3){emptyList()};game=Game.create(nextLevel);selected.clear();persist();render();audio.requestDeal();schedule()}
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
        content.addView(text("完全离线 · 牌局自动保存\n两家连续不出后，上一家自由出牌。\n顺子、连对、飞机主体不含 2 和王。\n炸弹、王炸、春天均翻倍。",17f).apply{setPadding(0,dp(12),0,dp(12))})
        val scroll=ScrollView(this).apply{addView(content)}
        var restartAfterDismiss=false
        val dialog=AlertDialog.Builder(this).setTitle("声音与牌桌设置").setView(scroll).setPositiveButton("返回牌局",null).setNeutralButton("重新开局"){_,_->restartAfterDismiss=true}.create()
        dialog.setOnDismissListener{audio.onMusicChanged=null;if(restartAfterDismiss)confirmRestart() else {modal=false;schedule()}};dialog.show();dialog.window?.setBackgroundDrawable(background(0xff283e78.toInt(),0xff769cda.toInt()))
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
    private fun restore():Boolean{
        return try{val saved=ObjectInputStream(AtomicFile(File(filesDir,"native-table-v2")).openRead()).use{it.readObject() as SavedTable};game=saved.game;games=saved.games;wins=saved.wins;score=saved.score;true}
        catch(_:Exception){game=Game.create(nextLevel);false}
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
}
