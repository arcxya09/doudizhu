package com.arcxya.doudizhu

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.min
import kotlin.math.max
import kotlin.math.abs

/** A single decoded atlas is shared by cards, buttons and status labels. */
class GameArtwork private constructor(context: Context) {
    val bitmap: Bitmap = context.assets.open("game_atlas.png").use { BitmapFactory.decodeStream(it) }
    private val data = org.json.JSONObject(context.assets.open("game_atlas.json").bufferedReader().use { it.readText() })
    private val rects = data.keys().asSequence().associateWith { key ->
        val r=data.getJSONArray(key);Rect(r.getInt(0),r.getInt(1),r.getInt(0)+r.getInt(2),r.getInt(1)+r.getInt(3))
    }
    fun rect(name:String):Rect = rects.getValue(name)
    fun draw(canvas:Canvas,name:String,box:RectF,paint:Paint) {canvas.drawBitmap(bitmap,rect(name),box,paint)}
    companion object {
        @Volatile private var instance:GameArtwork?=null
        fun get(context:Context):GameArtwork=instance?:synchronized(this){instance?:GameArtwork(context.applicationContext).also{instance=it}}
    }
}
class CardArt(context: Context) {
    val sprites=GameArtwork.get(context)
    fun rankName(card:Int):String {
        val rank=card/4+3;val number=if(rank==14)1 else if(rank==15)2 else rank
        return "LargeCard_commom_shuzi_${if(card%4==1||card%4==3)"hong" else "hei"}_$number"
    }
}
/** Original sprite indexes stay exposed as cards overlap; selection retains a gold edge. */
class CardFace(context: Context, private val art: CardArt, val card: Int, private val largeIndex: Boolean = false): View(context) {
    companion object { const val WIDTH_HEIGHT_RATIO = .76f }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private fun dp(v:Float)=v*resources.displayMetrics.density
    var indexWidth=0f
    var compactIndex=false
    /** One of the three bottom cards the landlord took. The marker sits in the exposed index strip,
     *  because an overlapped card only shows that strip and a corner ribbon would be covered. */
    var bottomCard=false
    var showBody=true
    init {contentDescription=if(card==54) "未公开底牌" else Rules.cardName(card);isFocusable=largeIndex}
    private fun fitted(canvas:Canvas,name:String,box:RectF) {
        val src=art.sprites.rect(name);val scale=min(box.width()/src.width(),box.height()/src.height())
        val w=src.width()*scale;val h=src.height()*scale
        art.sprites.draw(canvas,name,RectF(box.centerX()-w/2,box.top,box.centerX()+w/2,box.top+h),paint)
    }
    override fun onDraw(canvas:Canvas) {
        val edge=dp(.7f);val lift=if(largeIndex)dp(9f) else 0f
        val h=min(height-edge*2-lift,(width-dp(2f))/WIDTH_HEIGHT_RATIO)
        val w=h*WIDTH_HEIGHT_RATIO;val top=height-dp(1f)-h-if(isSelected)lift else 0f
        val box=RectF((width-w)/2,top,(width+w)/2,top+h)
        paint.style=Paint.Style.FILL;paint.color=0x4021283c
        canvas.drawRoundRect(RectF(box.left-dp(.5f),box.top+dp(1f),box.right+dp(.5f),box.bottom+dp(1.3f)),dp(3f),dp(3f),paint)
        if(isSelected){paint.color=0xffffbd35.toInt();canvas.drawRoundRect(RectF(box.left-edge,box.top-edge,box.right+edge,box.bottom+edge),dp(3f),dp(3f),paint)}
        paint.color=Color.WHITE
        art.sprites.draw(canvas,if(card==54)"large_card_anti" else "large_card_bgx",box,paint)
        if(card==54)return
        val margin=min(if(indexWidth>0)indexWidth-dp(2f) else w*.42f,w*.48f).coerceAtLeast(dp(10f))
        val x=box.left+margin/2+dp(.5f);val available=margin-dp(4f)
        if(card>=52){
            val joker=if(card==52)14 else 15
            fitted(canvas,"LargeCard_king_$joker",RectF(x-available/2,box.top+h*.055f,x+available/2,box.top+h*.895f))
            if(showBody&&!compactIndex)fitted(canvas,"LargeCard_king_huase_$joker",RectF(box.left+margin+dp(3f),box.top+h*.32f,box.right-dp(5f),box.bottom-dp(7f)))
        }else{
            val name=art.rankName(card);val glyph=art.sprites.rect(name)
            val rh=min(h*(if(compactIndex).43f else .27f),available*glyph.height()/glyph.width())
            val rw=rh*glyph.width()/glyph.height();val ry=box.top+h*.045f
            art.sprites.draw(canvas,name,RectF(x-rw/2,ry,x+rw/2,ry+rh),paint)
            val suit="LargeCard_huase_${card%4+1}";val size=min(h*(if(compactIndex).29f else .17f),margin*.70f)
            fitted(canvas,suit,RectF(x-size/2,ry+rh+h*.02f,x+size/2,ry+rh+h*.02f+size))
            if(showBody&&!compactIndex&&h>dp(55f)){
                val pip=min(w*.47f,h*.38f)
                fitted(canvas,suit,RectF(box.right-pip-dp(6f),box.bottom-pip-dp(8f),box.right-dp(6f),box.bottom-dp(8f)))
            }
        }
        if(bottomCard){
            val strip=min(if(indexWidth>0f)indexWidth else w*.42f,w).coerceAtLeast(dp(6f))
            val bar=dp(4f)
            paint.style=Paint.Style.FILL;paint.color=0xffffa92b.toInt()
            canvas.drawRoundRect(RectF(box.left,box.bottom-bar,box.left+strip-dp(2f),box.bottom),bar/2,bar/2,paint)
            if(showBody){
                val sz=w*.46f;val triangle=Path().apply{moveTo(box.right-sz,box.top);lineTo(box.right,box.top);lineTo(box.right,box.top+sz);close()};canvas.drawPath(triangle,paint)
                canvas.save();canvas.rotate(45f,box.right-sz*.32f,box.top+sz*.32f);paint.color=Color.WHITE;paint.typeface=Typeface.DEFAULT_BOLD;paint.textSize=sz*.25f;paint.textAlign=Paint.Align.CENTER;canvas.drawText("地主",box.right-sz*.32f,box.top+sz*.4f,paint);canvas.restore()
            }
        }
    }
    override fun setSelected(selected:Boolean){super.setSelected(selected);invalidate()}
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){
        super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.CheckBox";info.isCheckable=largeIndex;info.isChecked=isSelected
    }
}
/** One overlapping row. Touch selection follows the exposed index strips, in draw order. */
class HandLayout(context:Context):ViewGroup(context) {
    private var stride=0f;private var start=0f
    private var downX=0f;private var downIndex=-1;private var lastIndex=-1;private var dragging=false
    private var before=booleanArrayOf();private var selectRange=true
    private val slop=android.view.ViewConfiguration.get(context).scaledTouchSlop
    override fun onMeasure(ws:Int,hs:Int){
        val w=MeasureSpec.getSize(ws);val h=MeasureSpec.getSize(hs);setMeasuredDimension(w,h)
        val d=resources.displayMetrics.density
        val cw=min(((h-paddingTop-paddingBottom-13*d)*CardFace.WIDTH_HEIGHT_RATIO+2*d).toInt(),(w*.32f).toInt()).coerceAtLeast(1)
        val spread=.955f+.045f*((childCount-17)/3f).coerceIn(0f,1f)
        stride=if(childCount>1)min(cw*.62f,(w-paddingLeft-paddingRight-cw).toFloat()/19f)*spread else 0f
        start=(w-cw-stride*(childCount-1).coerceAtLeast(0))/2f
        for(i in 0 until childCount){
            val v=getChildAt(i) as CardFace;v.showBody=i==childCount-1;v.indexWidth=if(childCount==1)cw*.42f else stride
            v.measure(MeasureSpec.makeMeasureSpec(cw,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h-paddingTop-paddingBottom,MeasureSpec.EXACTLY))
        }
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
        for(i in 0 until childCount){val v=getChildAt(i);val x=(start+i*stride).toInt();v.layout(x,paddingTop,x+v.measuredWidth,paddingTop+v.measuredHeight)}
    }
    internal fun exposedBounds(index:Int):Rect {
        val v=getChildAt(index);return Rect(v.left,v.top,if(index<childCount-1)getChildAt(index+1).left else v.right,v.bottom)
    }
    private fun hit(x:Float,y:Float):Int {
        if(y<paddingTop || y>height-paddingBottom)return -1
        for(i in childCount-1 downTo 0){val v=getChildAt(i);if(x>=v.left&&x<v.right&&v.isEnabled)return i};return -1
    }
    override fun onInterceptTouchEvent(event:MotionEvent)=true
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                downIndex=hit(event.x,event.y);if(downIndex<0)return false
                downX=event.x;lastIndex=downIndex;dragging=false
                before=BooleanArray(childCount){getChildAt(it).isSelected};selectRange=!before[downIndex]
                parent?.requestDisallowInterceptTouchEvent(true);return true
            }
            MotionEvent.ACTION_MOVE->{
                if(downIndex<0)return false
                val index=hit(event.x,event.y)
                if(index>=0&&(dragging||abs(event.x-downX)>slop)){dragging=true;lastIndex=index;applyRange(index)}
                return true
            }
            MotionEvent.ACTION_UP->{
                if(downIndex<0)return false
                if(!dragging){val index=hit(event.x,event.y);if(index==downIndex)getChildAt(index).performClick()} else applyRange(lastIndex)
                downIndex=-1;parent?.requestDisallowInterceptTouchEvent(false);performClick();return true
            }
            MotionEvent.ACTION_CANCEL->{
                if(dragging)for(i in 0 until min(childCount,before.size))if(getChildAt(i).isSelected!=before[i])getChildAt(i).performClick()
                downIndex=-1;parent?.requestDisallowInterceptTouchEvent(false);return true
            }
        };return downIndex>=0
    }
    private fun applyRange(end:Int){
        for(i in 0 until min(childCount,before.size)){
            val desired=if(i in min(downIndex,end)..max(downIndex,end))selectRange else before[i]
            if(getChildAt(i).isSelected!=desired)getChildAt(i).performClick()
        }
    }
    override fun performClick():Boolean{super.performClick();return true}
}
/** Overlap to keep played cards large; very long combinations wrap before indexes become cramped. */
class CardStrip(context:Context,private val art:CardArt,private val maxColumns:Int=Int.MAX_VALUE,private val align:Int=Gravity.CENTER_HORIZONTAL):ViewGroup(context) {
    private var columns=1;private var rowHeight=1;private var cardWidth=1;private var step=0f
    fun show(cards:List<Int>){removeAllViews();cards.forEach{addView(CardFace(context,art,it))};requestLayout()}
    override fun onMeasure(ws:Int,hs:Int){
        val w=MeasureSpec.getSize(ws);val h=MeasureSpec.getSize(hs);setMeasuredDimension(w,h)
        val d=resources.displayMetrics.density
        columns=if(childCount>maxColumns)maxColumns else if(childCount>12 && h>=100*d) (childCount+1)/2 else childCount.coerceAtLeast(1)
        val rows=if(childCount>columns)2 else 1;rowHeight=h/rows
        cardWidth=min((rowHeight*CardFace.WIDTH_HEIGHT_RATIO).toInt(),(w/(1+(columns-1)*.38f)).toInt()).coerceAtLeast(1)
        step=if(columns>1)min(cardWidth*.64f,(w-cardWidth).toFloat()/(columns-1)) else 0f
        for(i in 0 until childCount){val v=getChildAt(i) as CardFace;v.compactIndex=rows>1;v.indexWidth=if(columns==1)cardWidth*.42f else step;v.showBody=i==childCount-1||i%columns==columns-1
            v.measure(MeasureSpec.makeMeasureSpec(cardWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(rowHeight,MeasureSpec.EXACTLY))}
    }
    override fun onLayout(c:Boolean,l:Int,t:Int,r:Int,b:Int){
        for(i in 0 until childCount){val row=i/columns;val col=i%columns;val count=min(columns,childCount-row*columns);val spare=width-cardWidth-step*(count-1)
            val start=when(align and Gravity.HORIZONTAL_GRAVITY_MASK){Gravity.LEFT->0f;Gravity.RIGHT->spare;else->spare/2}
            val x=(start+col*step).toInt();getChildAt(i).layout(x,row*rowHeight,x+cardWidth,(row+1)*rowHeight)}
    }
}

/** Separate measured rows avoid multiline clipping with large system font settings. */
class OpponentPanel(context: Context): android.widget.LinearLayout(context) {
    private val rows=(0..2).map { index -> android.widget.TextView(context).apply {
        tag="opponent-text";gravity=android.view.Gravity.CENTER;includeFontPadding=false;maxLines=1
        setTextColor(if(index==2)Color.rgb(255,221,143) else Color.WHITE)
        setAutoSizeTextTypeUniformWithConfiguration(10,if(index==1)22 else 19,1,android.util.TypedValue.COMPLEX_UNIT_SP)
    } }
    init {orientation=VERTICAL;val pad=(4*resources.displayMetrics.density).toInt();setPadding(pad,pad,pad,pad);rows.forEach{addView(it,LayoutParams(-1,0,1f))}}
    fun bind(name:String,role:String,count:Int,status:String){rows[0].text="$name · $role";rows[1].text="剩 $count 张";rows[2].text=status}
}
