package com.arcxya.doudizhu

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** Original local artwork is decorative only; all controls remain native accessible views. */
class TableBackdrop(context:Context):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val image:Bitmap?=runCatching { context.assets.open("table_background.webp").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    private val destination=RectF()
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(17,62,44))
        image?.let { bitmap ->
            val scale=max(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
            val w=bitmap.width*scale;val h=bitmap.height*scale
            destination.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
            canvas.drawBitmap(bitmap,null,destination,paint)
        }
        // A quiet dark edge keeps labels and card indexes clear over the scenic artwork.
        paint.shader=LinearGradient(0f,0f,0f,height.toFloat(),intArrayOf(0x55163324,0x08112619,0x80133325.toInt()),floatArrayOf(0f,.48f,1f),Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint);paint.shader=null
    }
}

class PortraitView(context:Context,private val person:Int):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val image:Bitmap?=runCatching { context.assets.open("avatars.webp").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    private val path=Path()
    var active=false;set(value){field=value;invalidate()}
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas:Canvas) {
        val d=resources.displayMetrics.density;val radius=min(width,height)/2f-4*d;val x=width/2f;val y=height/2f
        paint.color=Color.rgb(8,37,29);canvas.drawCircle(x,y+2*d,radius+3*d,paint)
        paint.color=if(active)Color.rgb(255,220,124) else Color.rgb(221,198,137)
        canvas.drawCircle(x,y,radius+3*d,paint)
        val box=RectF(x-radius,y-radius,x+radius,y+radius)
        canvas.save();path.reset();path.addCircle(x,y,radius,Path.Direction.CW);canvas.clipPath(path)
        if(image!=null) {
            val tileWidth=image.width/2;val source=Rect(person*tileWidth,0,(person+1)*tileWidth,image.height)
            canvas.drawBitmap(image,source,box,paint)
        } else {
            paint.color=if(person==0)Color.rgb(147,96,49) else Color.rgb(74,120,117);canvas.drawRect(box,paint)
            paint.color=Color.rgb(255,243,204);paint.textSize=radius;paint.typeface=Typeface.DEFAULT_BOLD;paint.textAlign=Paint.Align.CENTER
            canvas.drawText(if(person==0)"林" else "周",x,y+radius*.34f,paint)
        }
        canvas.restore()
    }
}

/** Avatar and three separately measured text rows stay legible with a larger system font. */
class TableSeat(context:Context,person:Int):LinearLayout(context) {
    private val density=resources.displayMetrics.density
    private fun dp(value:Int)=(value*density+.5f).toInt()
    private val portrait=PortraitView(context,person)
    private val details=OpponentPanel(context)
    init {
        orientation=HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(3),dp(2),dp(3),dp(2))
        addView(portrait,LayoutParams(dp(36),dp(48)))
        addView(details,LayoutParams(0,LayoutParams.MATCH_PARENT,1f))
    }
    fun bind(name:String,role:String,count:Int,status:String,active:Boolean) {
        details.bind(name,role,count,status);portrait.active=active
        background=GradientDrawable().apply {
            setColor(if(active)0xbc284f34.toInt() else 0x98203b2c.toInt());cornerRadius=dp(16).toFloat()
            setStroke(dp(if(active)2 else 1),if(active)Color.rgb(255,216,123) else 0x709bb68d)
        }
    }
}

/** Explicit table zones: seats at the sides, turn message above the current played cards. */
class TableArena(context:Context,private val left:TableSeat,private val right:TableSeat,
    private val turn:TextView,private val playedLabel:TextView,private val played:CardStrip):ViewGroup(context) {
    private val density=resources.displayMetrics.density
    private fun dp(value:Int)=(value*density+.5f).toInt()
    var lastPlayer=-1;set(value){field=value;requestLayout()}
    init { addView(left);addView(right);addView(turn);addView(playedLabel);addView(played) }
    override fun onMeasure(widthSpec:Int,heightSpec:Int) {
        val w=MeasureSpec.getSize(widthSpec);val h=MeasureSpec.getSize(heightSpec);setMeasuredDimension(w,h)
        val seatWidth=min(dp(if(w/density>760)166 else 124),w/3)
        val seatHeight=min(h,dp(94))
        val centerWidth=(w-seatWidth*2-dp(8)).coerceAtLeast(dp(150))
        fun measure(view:View,width:Int,height:Int)=view.measure(MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height.coerceAtLeast(1),MeasureSpec.EXACTLY))
        measure(left,seatWidth,seatHeight);measure(right,seatWidth,seatHeight)
        measure(turn,centerWidth,dp(24));measure(playedLabel,centerWidth,dp(18))
        measure(played,(centerWidth-dp(24)).coerceAtLeast(dp(126)),(h-dp(24)).coerceAtLeast(dp(24)))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val sideY=(height-left.measuredHeight)/2
        left.layout(0,sideY,left.measuredWidth,sideY+left.measuredHeight)
        right.layout(width-right.measuredWidth,sideY,width,sideY+right.measuredHeight)
        val x=(width-turn.measuredWidth)/2
        turn.layout(x,0,x+turn.measuredWidth,turn.measuredHeight)
        playedLabel.layout(x,dp(24),x+playedLabel.measuredWidth,dp(42))
        // Move the played cards slightly toward their owner, within the clear center zone.
        val playedX=x+when(lastPlayer){1->0;2->dp(24);else->dp(12)}
        played.layout(playedX,dp(24),playedX+played.measuredWidth,dp(24)+played.measuredHeight)
    }
}
