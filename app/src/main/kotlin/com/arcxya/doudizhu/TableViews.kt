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
    private val bitmap=context.assets.open("blue_table.webp").use{BitmapFactory.decodeStream(it)}
    init{
        val color=ColorMatrix().apply{setSaturation(.74f)}
        color.postConcat(ColorMatrix(floatArrayOf(1.08f,0f,0f,0f,0f, 0f,.98f,0f,0f,0f, 0f,0f,.94f,0f,0f, 0f,0f,0f,1f,0f)))
        paint.colorFilter=ColorMatrixColorFilter(color)
    }
    override fun onDraw(canvas:Canvas){canvas.drawBitmap(bitmap,null,RectF(0f,0f,width.toFloat(),height.toFloat()),paint)}
}
/** Compact, low-contrast table mark; drawn independently of accessibility font scale. */
class TableWordmark(context:Context):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=Typeface.create("sans-serif",Typeface.BOLD_ITALIC);textAlign=Paint.Align.CENTER}
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(c:Canvas){
        c.save();c.scale(width/192f,height/106f)
        fun line(text:String,size:Float,baseline:Float){
            p.textSize=size;p.style=Paint.Style.STROKE;p.strokeWidth=2f;p.color=0x24333d72;c.drawText(text,96f,baseline,p)
            p.style=Paint.Style.FILL;p.color=0x403b548b;c.drawText(text,96f,baseline,p)
        }
        line("单机",37f,42f);line("斗地主",46f,87f)
        p.color=0x283b548b;p.strokeWidth=1.5f;c.drawLine(32f,94f,163f,94f,p);c.restore()
    }
}
/** Native pill skin with a 48 dp touch target and the smaller reference-sized face. */
class ClassicActionButton(context:Context,private val primary:Boolean):android.widget.Button(context){
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas){
        val d=resources.displayMetrics.density
        val face=RectF(6*d,height/2f-14*d,width-6*d,height/2f+14*d)
        fill.shader=null;fill.color=if(primary)0x80592f08.toInt() else 0x80393081.toInt()
        c.drawRoundRect(RectF(face.left,face.top+2*d,face.right,face.bottom+2*d),15*d,15*d,fill)
        fill.shader=LinearGradient(0f,face.top,0f,face.bottom,
            if(primary)intArrayOf(0xffffe78c.toInt(),0xffffbc3e.toInt(),0xfff0991e.toInt()) else intArrayOf(0xffb7e7ff.toInt(),0xff98acff.toInt(),0xff8072e9.toInt()),floatArrayOf(0f,.46f,1f),Shader.TileMode.CLAMP)
        fill.alpha=255;c.drawRoundRect(face,15*d,15*d,fill);fill.shader=null
        fill.style=Paint.Style.STROKE;fill.strokeWidth=d;fill.color=if(primary)0xffffd66d.toInt() else 0xffb5c8ff.toInt();c.drawRoundRect(face,15*d,15*d,fill);fill.style=Paint.Style.FILL
        val y=height/2f-(paint.fontMetrics.ascent+paint.fontMetrics.descent)/2
        paint.textAlign=Paint.Align.CENTER;paint.style=Paint.Style.STROKE;paint.strokeWidth=1.4f*d;paint.color=if(primary)0xffa26426.toInt() else 0xff5268ad.toInt()
        c.drawText(text.toString(),width/2f,y,paint);paint.style=Paint.Style.FILL;paint.color=Color.WHITE
        c.drawText(text.toString(),width/2f,y,paint)
    }
}
class RoleBadge:Drawable(){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c:Canvas){
        val b=RectF(bounds);val path=Path().apply{moveTo(b.left+b.height()*.25f,b.top);lineTo(b.right-b.height()*.25f,b.top);lineTo(b.right,b.centerY());lineTo(b.right-b.height()*.25f,b.bottom);lineTo(b.left+b.height()*.25f,b.bottom);lineTo(b.left,b.centerY());close()}
        p.shader=LinearGradient(0f,b.top,0f,b.bottom,0xffe4b459.toInt(),0xffb5672b.toInt(),Shader.TileMode.CLAMP);p.style=Paint.Style.FILL;c.drawPath(path,p);p.shader=null
        p.style=Paint.Style.STROKE;p.strokeWidth=1.5f;p.color=0xffffd984.toInt();c.drawPath(path,p);p.style=Paint.Style.FILL
    }
    override fun setAlpha(a:Int){p.alpha=a};override fun setColorFilter(f:ColorFilter?){p.colorFilter=f}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
class CoinIcon(private val size:Int):Drawable(){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun getIntrinsicWidth()=size;override fun getIntrinsicHeight()=size
    override fun draw(c:Canvas){
        val b=RectF(bounds);b.inset(size*.15f,size*.05f);c.save();c.rotate(-25f,b.centerX(),b.centerY())
        p.shader=LinearGradient(b.left,b.top,b.right,b.bottom,0xfffff298.toInt(),0xffefaa28.toInt(),Shader.TileMode.CLAMP);p.style=Paint.Style.FILL;c.drawOval(b,p);p.shader=null
        p.color=0xffffe67c.toInt();p.strokeWidth=size*.08f;p.style=Paint.Style.STROKE;c.drawOval(b,p);p.style=Paint.Style.FILL;c.restore()
    }
    override fun setAlpha(a:Int){p.alpha=a};override fun setColorFilter(f:ColorFilter?){p.colorFilter=f}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
/** Small circular portraits replace the full-body courtyard characters. */
class SeatAvatar(context:Context,asset:String):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bitmap=context.assets.open(asset).use{BitmapFactory.decodeStream(it)}
    var active=false;set(value){field=value;invalidate()}
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(c:Canvas){
        val d=resources.displayMetrics.density;val radius=min(width,height)/2f-3*d
        val x=width/2f;val y=height/2f
        paint.style=Paint.Style.FILL;paint.color=0x60304a78;c.drawCircle(x,y,radius+3*d,paint)
        c.save();c.clipPath(Path().apply{addCircle(x,y,radius,Path.Direction.CW)})
        paint.color=Color.WHITE;c.drawBitmap(bitmap,null,RectF(x-radius,y-radius,x+radius,y+radius),paint);c.restore()
        paint.style=Paint.Style.STROKE;paint.strokeWidth=2*d;paint.color=if(active)0xffffd878.toInt() else Color.WHITE
        c.drawCircle(x,y,radius,paint);paint.style=Paint.Style.FILL
    }
}
/** Gold alarm-clock frame from the reference, without a time limit on the player. */
class TurnClock(context:Context,private val value:String):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas){
        val size=min(width.toFloat(),height*.84f);val x=width/2f;val y=height/2f+size*.025f;val r=size*.38f
        p.color=0xffd9e6eb.toInt();c.drawOval(RectF(x-r*1.05f,y-r*1.22f,x-r*.35f,y-r*.63f),p);c.drawOval(RectF(x+r*.35f,y-r*1.22f,x+r*1.05f,y-r*.63f),p)
        p.color=0xff977537.toInt();c.drawCircle(x,y+size*.03f,r*1.13f,p)
        p.shader=LinearGradient(x,y-r,x,y+r,intArrayOf(0xffffe87c.toInt(),0xffffbd16.toInt(),0xffe9900d.toInt()),null,Shader.TileMode.CLAMP)
        c.drawCircle(x,y,r,p);p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=size*.032f;p.color=0xfffff3bf.toInt();c.drawCircle(x,y,r*.89f,p);p.style=Paint.Style.FILL
        p.typeface=Typeface.create("sans-serif",Typeface.BOLD);p.textSize=r*1.27f;p.textAlign=Paint.Align.CENTER;p.color=Color.WHITE
        c.drawText(value,x,y-(p.fontMetrics.ascent+p.fontMetrics.descent)/2,p)
    }
}
/** Aggregate unknown ranks only: equivalent to the deck minus own hand and public plays. */
class RankCounter(context:Context):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    internal var counts=IntArray(15);private var ready=false
    private val ranks=listOf("大王","小王","2","A","K","Q","J","10","9","8","7","6","5","4","3")
    fun show(game:Game){
        ready=game.landlord>=0;counts=IntArray(15)
        if(ready)for(card in game.hands.drop(1).flatten()){val index=if(card==53)0 else if(card==52)1 else 17-(card/4+3);counts[index]++}
        contentDescription=if(ready)"记牌器，其他两家未出牌合计："+ranks.indices.joinToString("，"){"${ranks[it]} ${counts[it]}张"} else "记牌器，叫地主后显示"
        invalidate()
    }
    override fun onDraw(c:Canvas){
        val cell=width/15f
        p.color=0xfff2f0e7.toInt();c.drawRoundRect(RectF(0f,0f,width.toFloat(),height.toFloat()),4f,4f,p)
        p.typeface=Typeface.create("sans-serif",Typeface.NORMAL);p.textAlign=Paint.Align.CENTER
        for(i in ranks.indices){
            p.color=0xffd2d1ca.toInt();p.strokeWidth=1f;c.drawLine(i*cell,0f,i*cell,height.toFloat(),p)
            p.textSize=min(height*.32f,cell*(if(i<2).43f else .77f));p.color=0xff555963.toInt();c.drawText(ranks[i],(i+.5f)*cell,height*.4f,p)
            p.textSize=min(height*.34f,cell*.76f);p.color=if(ready&&counts[i]>0)0xffbe803c.toInt() else 0xffc9c4bb.toInt();c.drawText(if(ready)counts[i].toString() else "–",(i+.5f)*cell,height*.88f,p)
        }
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
            4->{c.drawRect(RectF(7f,3f,26f,29f),p);c.drawLine(17f,16f,31f,16f,p);c.drawLine(17f,16f,23f,10f,p);c.drawLine(17f,16f,23f,22f,p);p.style=Paint.Style.FILL;c.drawPath(Path().apply{moveTo(3f,2f);lineTo(13f,5f);lineTo(13f,27f);lineTo(3f,30f);close()},p);p.style=Paint.Style.STROKE}
            else->{p.style=Paint.Style.FILL;for(x in listOf(4f,18f))for(y in listOf(3f,17f))c.drawRoundRect(RectF(x,y,x+10f,y+10f),2f,2f,p);p.style=Paint.Style.STROKE}
        };c.restore()
    }
}
class ReferenceTable(context:Context):ViewGroup(context){
    data class Zone(val view:View,val x:Float,val y:Float,val w:Float,val h:Float)
    private val zones=mutableListOf<Zone>()
    private var safe=Rect()
    override fun onApplyWindowInsets(insets:android.view.WindowInsets):android.view.WindowInsets{
        val next=if(android.os.Build.VERSION.SDK_INT>=28)insets.displayCutout?.let{Rect(it.safeInsetLeft,it.safeInsetTop,it.safeInsetRight,it.safeInsetBottom)}?:Rect() else Rect()
        if(next!=safe){safe=next;requestLayout()}
        return insets
    }
    private fun area(view:View):Rect=if(view is TableBackdrop)Rect(0,0,measuredWidth,measuredHeight)
        else Rect(safe.left,safe.top,measuredWidth-safe.right,measuredHeight-safe.bottom)

    fun place(view:View,x:Float,y:Float,w:Float,h:Float){zones.add(Zone(view,x,y,w,h));addView(view)}
    override fun onMeasure(ws:Int,hs:Int){
        setMeasuredDimension(MeasureSpec.getSize(ws),MeasureSpec.getSize(hs))
        for(z in zones){val a=area(z.view);z.view.measure(MeasureSpec.makeMeasureSpec((a.width()*z.w).toInt().coerceAtLeast(1),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((a.height()*z.h).toInt().coerceAtLeast(1),MeasureSpec.EXACTLY))}
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
        for(z in zones){val a=area(z.view);val x=a.left+(a.width()*z.x).toInt();val y=a.top+(a.height()*z.y).toInt();z.view.layout(x,y,x+z.view.measuredWidth,y+z.view.measuredHeight)}
    }
}
