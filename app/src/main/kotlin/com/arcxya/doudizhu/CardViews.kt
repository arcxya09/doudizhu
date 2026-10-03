package com.arcxya.doudizhu

import android.content.Context
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.min

class CardArt(context: Context) {
    val atlas: Bitmap = context.assets.open("cards.webp").use { BitmapFactory.decodeStream(it) }
    fun source(card: Int) = Rect(card%9*160,card/9*240,card%9*160+160,card/9*240+240)
}
class CardFace(context: Context, private val art: CardArt, val card: Int, private val largeIndex: Boolean = false): View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val density = resources.displayMetrics.density
    private fun dp(v: Float)=v*density
    init { contentDescription=if(card==54) "未公开底牌" else Rules.cardName(card);isFocusable=largeIndex }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val border=dp(3f);val lift=if(largeIndex)dp(6f) else 0f
        val h=min(height-border*2-lift,(width-border*2)*240f/160f);val w=h*160/240
        val top=(height-h+lift)/2-if(isSelected)lift else 0f
        val box=RectF((width-w)/2,top,(width+w)/2,top+h)
        paint.color=if(isSelected) Color.rgb(255,209,75) else Color.rgb(249,243,224)
        canvas.drawRoundRect(RectF(box.left-border,box.top-border,box.right+border,box.bottom+border),dp(5f),dp(5f),paint)
        paint.color=Color.WHITE;canvas.drawRoundRect(box,dp(4f),dp(4f),paint)
        canvas.drawBitmap(art.atlas,art.source(card),box,paint)
        if(isSelected) {
            paint.color=Color.rgb(32,112,48);canvas.drawCircle(box.right-dp(8f),box.top+dp(9f),dp(10f),paint)
            paint.color=Color.WHITE;paint.typeface=Typeface.DEFAULT_BOLD;paint.textSize=dp(15f);paint.textAlign=Paint.Align.CENTER
            canvas.drawText("✓",box.right-dp(8f),box.top+dp(14f),paint)
        }
    }
    override fun setSelected(selected: Boolean) { super.setSelected(selected);invalidate() }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.CheckBox";info.isCheckable=largeIndex;info.isChecked=isSelected
    }
}
/** Two independently centered rows. Card images retain aspect ratio; hit boxes never overlap. */
class HandLayout(context: Context): ViewGroup(context) {
    override fun onMeasure(widthSpec: Int,heightSpec: Int) {
        val w=MeasureSpec.getSize(widthSpec);val h=MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(w,h)
        val rows=if(childCount>10)2 else 1
        val rowHeight=(h-paddingTop-paddingBottom)/rows
        val count=min(10,childCount).coerceAtLeast(1)
        val cellWidth=min((w-paddingLeft-paddingRight)/count,(rowHeight*.78f).toInt().coerceAtLeast((48*resources.displayMetrics.density).toInt()))
        for(i in 0 until childCount)getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cellWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(rowHeight,MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        if(childCount==0)return
        val rowCount=if(childCount>10)2 else 1
        val rowHeight=(height-paddingTop-paddingBottom)/rowCount
        for(i in 0 until childCount) {
            val row=i/10;val cols=min(10,childCount-row*10);val child=getChildAt(i);val cw=child.measuredWidth
            val x=(width-cols*cw)/2+(i%10)*cw;val y=paddingTop+row*rowHeight
            child.layout(x,y,x+cw,y+rowHeight)
        }
    }
}
class CardStrip(context: Context,private val art: CardArt): ViewGroup(context) {
    fun show(cards:List<Int>) { removeAllViews();cards.forEach { addView(CardFace(context,art,it)) };requestLayout() }
    override fun onMeasure(ws:Int,hs:Int){val w=MeasureSpec.getSize(ws);val h=MeasureSpec.getSize(hs);setMeasuredDimension(w,h);val cw=min((h*.69f).toInt(),w/childCount.coerceAtLeast(1));for(i in 0 until childCount)getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))}
    override fun onLayout(c:Boolean,l:Int,t:Int,r:Int,b:Int){if(childCount==0)return;val cw=getChildAt(0).measuredWidth;val start=(width-cw*childCount)/2;for(i in 0 until childCount)getChildAt(i).layout(start+i*cw,0,start+(i+1)*cw,height)}
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
