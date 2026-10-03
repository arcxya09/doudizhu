package com.arcxya.doudizhu

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import kotlin.math.min

/** All scene art is packaged locally. Positions are normalized against the supplied landscape reference. */
class TableBackdrop(context:Context):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bitmap=context.assets.open("classic_table.webp").use{BitmapFactory.decodeStream(it)}
    override fun onDraw(canvas:Canvas){canvas.drawBitmap(bitmap,null,RectF(0f,0f,width.toFloat(),height.toFloat()),paint)}
}
class CharacterView(context:Context,private val atlas:Bitmap,private val person:Int):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    var active=false;set(value){field=value;invalidate()}
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(canvas:Canvas){
        val tile=atlas.width/3
        val w=min(width.toFloat(),height*(if(person==2).78f else .58f));val x=(width-w)/2
        if(active){paint.color=0x90fff09c.toInt();canvas.drawOval(RectF(x,height*.85f,x+w,height*.99f),paint)}
        paint.color=Color.WHITE
        canvas.drawBitmap(atlas,Rect(person*tile,0,(person+1)*tile,if(person==2)(atlas.height*.67f).toInt() else atlas.height),RectF(x,0f,x+w,height.toFloat()),paint)
    }
}
/** Native icons above the compact toolbar captions. */
class TableIcon(private val kind:Int,private val pixels:Int):Drawable(){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;strokeWidth=2.5f;style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    override fun getIntrinsicWidth()=pixels
    override fun getIntrinsicHeight()=pixels
    override fun setAlpha(alpha:Int){p.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?){p.colorFilter=filter}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
    override fun draw(c:Canvas){c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/32f,bounds.height()/32f)
        when(kind){
            0->{c.drawRoundRect(RectF(5f,8f,27f,27f),4f,4f,p);c.drawLine(16f,2f,16f,8f,p);c.drawCircle(11f,16f,1.5f,p);c.drawCircle(21f,16f,1.5f,p);c.drawLine(11f,23f,21f,23f,p)}
            1->{c.drawRoundRect(RectF(5f,4f,20f,26f),2f,2f,p);c.drawRoundRect(RectF(12f,8f,27f,30f),2f,2f,p)}
            2->{val q=Path();q.moveTo(5f,12f);q.lineTo(11f,12f);q.lineTo(19f,5f);q.lineTo(19f,27f);q.lineTo(11f,20f);q.lineTo(5f,20f);q.close();c.drawPath(q,p);c.drawArc(RectF(13f,8f,29f,25f),-60f,120f,false,p)}
            4->{p.strokeWidth=4f;c.drawLine(22f,4f,10f,16f,p);c.drawLine(10f,16f,22f,28f,p);p.strokeWidth=2.5f}
            else->{for(y in listOf(7f,16f,25f)){c.drawLine(4f,y,28f,y,p)};c.drawCircle(11f,7f,3f,p);c.drawCircle(23f,16f,3f,p);c.drawCircle(13f,25f,3f,p)}
        };c.restore()
    }
}
class ReferenceTable(context:Context):ViewGroup(context){
    data class Zone(val view:View,val x:Float,val y:Float,val w:Float,val h:Float)
    private val zones=mutableListOf<Zone>()
    fun place(view:View,x:Float,y:Float,w:Float,h:Float){zones.add(Zone(view,x,y,w,h));addView(view)}
    override fun onMeasure(ws:Int,hs:Int){val w=MeasureSpec.getSize(ws);val h=MeasureSpec.getSize(hs);setMeasuredDimension(w,h)
        for(z in zones)z.view.measure(MeasureSpec.makeMeasureSpec((w*z.w).toInt().coerceAtLeast(1),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((h*z.h).toInt().coerceAtLeast(1),MeasureSpec.EXACTLY))}
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){for(z in zones){val x=(width*z.x).toInt();val y=(height*z.y).toInt();z.view.layout(x,y,x+z.view.measuredWidth,y+z.view.measuredHeight)}}
}
