package com.arcxya.doudizhu

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.min
import kotlin.math.max
import kotlin.math.abs

class CardArt(context: Context) {
    val atlas: Bitmap = context.assets.open("cards.webp").use { BitmapFactory.decodeStream(it) }
    fun source(card: Int) = Rect(card%9*160,card/9*240,card%9*160+160,card/9*240+240)
}
/** Jumbo corner indexes occupy a clean margin; original deck artwork remains on the face. */
class CardFace(context: Context, private val art: CardArt, val card: Int, private val largeIndex: Boolean = false): View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private fun dp(v:Float)=v*resources.displayMetrics.density
    var indexWidth=0f
    var compactIndex=false
    init {contentDescription=if(card==54) "未公开底牌" else Rules.cardName(card);isFocusable=largeIndex}
    override fun onDraw(canvas:Canvas) {
        val edge=dp(2f);val lift=if(largeIndex)dp(12f) else 0f
        val h=min(height-edge*2-lift,(width-edge*2)*1.5f)
        val w=h/1.5f;val top=height-edge-h-if(isSelected)lift else 0f
        val box=RectF((width-w)/2,top,(width+w)/2,top+h)
        paint.style=Paint.Style.FILL;paint.color=if(isSelected)Color.rgb(255,199,56) else Color.rgb(191,181,155)
        canvas.drawRoundRect(RectF(box.left-edge,box.top-edge,box.right+edge,box.bottom+edge),dp(5f),dp(5f),paint)
        paint.color=Color.rgb(255,253,242);canvas.drawRoundRect(box,dp(4f),dp(4f),paint)
        if(card==54) {canvas.drawBitmap(art.atlas,art.source(card),box,paint);return}
        val margin=min(if(indexWidth>0)indexWidth-dp(3f) else w*.42f,w*.48f).coerceAtLeast(dp(10f))
        // Crop only the central illustration, so the original small corner indexes are not duplicated.
        val src=art.source(card);src.inset(30,42)
        val artBox=RectF(box.left+margin+dp(3f),box.top+h*.30f,box.right-dp(4f),box.bottom-dp(5f))
        if(!compactIndex&&artBox.width()>0)canvas.drawBitmap(art.atlas,src,artBox,paint)
        val red=card==53 || (card<52 && card%4 in listOf(1,3))
        paint.color=if(red)Color.rgb(187,30,24) else Color.rgb(21,25,23)
        paint.typeface=Typeface.create("serif",Typeface.BOLD);paint.textAlign=Paint.Align.CENTER
        val x=box.left+margin/2+dp(1f)
        val rank=if(card>=52)if(card==53)"大" else "小" else when(val r=card/4+3){11->"J";12->"Q";13->"K";14->"A";15->"2";else->r.toString()}
        paint.textSize=min(h*(if(compactIndex).43f else .25f),dp(30f))
        val available=margin-dp(2f)
        paint.textScaleX=min(1f,available/paint.measureText(rank))
        val baseline=box.top+dp(3f)-paint.fontMetrics.ascent
        canvas.drawText(rank,x,baseline,paint);paint.textScaleX=1f
        val size=min(h*(if(compactIndex).31f else .24f),margin*.88f)
        if(card>=52){paint.textSize=size;canvas.drawText("王",x,baseline+size*1.05f,paint)}
        else drawSuit(canvas,card%4,x-size/2,baseline+dp(3f),size)
        if(isSelected){paint.color=Color.rgb(220,151,18);canvas.drawRect(box.left,box.bottom-dp(5f),box.right,box.bottom,paint)}
    }
    private fun drawSuit(canvas:Canvas,suit:Int,x:Float,y:Float,size:Float){
        canvas.save();canvas.translate(x,y);canvas.scale(size,size)
        val shape=Path()
        when(suit){
            1->{shape.moveTo(.5f,.95f);shape.cubicTo(-.32f,.37f,.08f,-.22f,.5f,.19f);shape.cubicTo(.92f,-.22f,1.32f,.37f,.5f,.95f);shape.close();canvas.drawPath(shape,paint)}
            3->{shape.moveTo(.5f,0f);shape.lineTo(.94f,.5f);shape.lineTo(.5f,1f);shape.lineTo(.06f,.5f);shape.close();canvas.drawPath(shape,paint)}
            else->{
                if(suit==0){shape.moveTo(.5f,0f);shape.cubicTo(.36f,.24f,-.03f,.39f,.05f,.66f);shape.cubicTo(.11f,.88f,.4f,.86f,.5f,.65f);shape.cubicTo(.6f,.86f,.89f,.88f,.95f,.66f);shape.cubicTo(1.03f,.39f,.64f,.24f,.5f,0f);shape.close();canvas.drawPath(shape,paint)}
                else{canvas.drawCircle(.5f,.26f,.25f,paint);canvas.drawCircle(.25f,.59f,.25f,paint);canvas.drawCircle(.75f,.59f,.25f,paint)}
                shape.reset();shape.moveTo(.5f,.45f);shape.lineTo(.31f,1f);shape.lineTo(.69f,1f);shape.close();canvas.drawPath(shape,paint)
            }
        };canvas.restore()
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
        val cw=min(((h-paddingTop-paddingBottom-16*resources.displayMetrics.density)/1.5f).toInt()+4,(w*.32f).toInt()).coerceAtLeast(1)
        stride=if(childCount>1)min(cw*.70f,(w-paddingLeft-paddingRight-cw).toFloat()/(childCount-1)) else 0f
        start=(w-cw-stride*(childCount-1).coerceAtLeast(0))/2f
        for(i in 0 until childCount){
            val v=getChildAt(i) as CardFace;v.indexWidth=if(childCount==1)cw*.42f else stride
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
class CardStrip(context:Context,private val art:CardArt,private val maxColumns:Int=Int.MAX_VALUE):ViewGroup(context) {
    private var columns=1;private var rowHeight=1;private var cardWidth=1;private var step=0f
    fun show(cards:List<Int>){removeAllViews();cards.forEach{addView(CardFace(context,art,it))};requestLayout()}
    override fun onMeasure(ws:Int,hs:Int){
        val w=MeasureSpec.getSize(ws);val h=MeasureSpec.getSize(hs);setMeasuredDimension(w,h)
        val d=resources.displayMetrics.density
        columns=if(childCount>maxColumns)maxColumns else if(childCount>12 && h>=100*d) (childCount+1)/2 else childCount.coerceAtLeast(1)
        val rows=if(childCount>columns)2 else 1;rowHeight=h/rows
        cardWidth=min((rowHeight/1.5f).toInt(),(w/(1+(columns-1)*.38f)).toInt()).coerceAtLeast(1)
        step=if(columns>1)min(cardWidth*.64f,(w-cardWidth).toFloat()/(columns-1)) else 0f
        for(i in 0 until childCount){val v=getChildAt(i) as CardFace;v.compactIndex=rows>1;v.indexWidth=if(columns==1)cardWidth*.42f else step
            v.measure(MeasureSpec.makeMeasureSpec(cardWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(rowHeight,MeasureSpec.EXACTLY))}
    }
    override fun onLayout(c:Boolean,l:Int,t:Int,r:Int,b:Int){
        for(i in 0 until childCount){val row=i/columns;val col=i%columns;val count=min(columns,childCount-row*columns);val start=(width-cardWidth-step*(count-1))/2
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
