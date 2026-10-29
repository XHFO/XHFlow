package net.xhfo.tvwled;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/** Foreground-only pixels on the actual display; capture and WLED output remain independent. */
final class RainbowView extends View {
    private static final long REVOLUTION_MS=16000;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix rotation=new Matrix();
    private final Path centerHole=new Path();
    private final RectF centerRect=new RectF();
    private SweepGradient rainbow;
    private boolean running,framePosted;
    private long startedAt;
    private float phase;
    private final Runnable frame=new Runnable(){
        @Override public void run(){
            framePosted=false;
            if(!running||!isAttachedToWindow()||getWindowVisibility()!=VISIBLE)return;
            invalidate();
            scheduleFrame();
        }
    };

    RainbowView(Context context){
        super(context);
        setFocusable(false);setFocusableInTouchMode(false);setClickable(false);setLongClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_NONE);setContentDescription(null);
        setSaveEnabled(false);setWillNotDraw(false);
    }
    void start(){
        if(running)return;
        startedAt=SystemClock.uptimeMillis()-Math.round(phase*REVOLUTION_MS/360f);
        running=true;
        invalidate();
        scheduleFrame();
    }
    void stop(){
        if(running)phase=currentPhase();
        running=false;
        removeCallbacks(frame);framePosted=false;
    }
    private float currentPhase(){
        return (SystemClock.uptimeMillis()-startedAt)%REVOLUTION_MS*360f/REVOLUTION_MS;
    }
    private void scheduleFrame(){
        if(running&&!framePosted&&isAttachedToWindow()&&getWindowVisibility()==VISIBLE){framePosted=true;postOnAnimation(frame);}
    }
    static void centerBounds(RectF result,int width,int height){int inset=ContinuousCorners.panelInsetPx(width,height);result.set(inset,inset,width-inset,height-inset);}
    static float designScale(Context context,int width,int height){
        float density=context.getResources().getDisplayMetrics().density;
        int inset=ContinuousCorners.panelInsetPx(width,height);
        return Math.max(.45f,Math.min(1f,Math.min(((height-2*inset)/density-24f)/430f,((width-2*inset)/density-24f)/744f)));
    }
    static float centerRadius(Context context,int width,int height){return ContinuousCorners.PANEL_RADIUS_DP*context.getResources().getDisplayMetrics().density*designScale(context,width,height);}
    static void continuousRect(Path path,RectF bounds,float radius){
        path.rewind();ContinuousCorners.rect(new ContinuousCorners.Sink(){
            @Override public void move(double x,double y){path.moveTo((float)x,(float)y);}
            @Override public void line(double x,double y){path.lineTo((float)x,(float)y);}
            @Override public void cubic(double x1,double y1,double x2,double y2,double x3,double y3){path.cubicTo((float)x1,(float)y1,(float)x2,(float)y2,(float)x3,(float)y3);}
            @Override public void close(){path.close();}
        },bounds.left,bounds.top,bounds.right,bounds.bottom,radius);
    }
    /** The panel background, border, shadow outline and rainbow hole use the same corner path. */
    static final class ContinuousDrawable extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path fillPath=new Path(),strokePath=new Path();
        private final RectF rect=new RectF();
        private final int[] colors;
        private final int border;
        private final float radius,stroke;
        private Shader gradient;
        private int alpha=255;
        ContinuousDrawable(int[] colors,float radius,int border,float stroke){this.colors=colors.clone();this.radius=radius;this.border=border;this.stroke=stroke;}
        @Override protected void onBoundsChange(android.graphics.Rect bounds){
            if(bounds.isEmpty()){fillPath.rewind();strokePath.rewind();gradient=null;return;}
            rect.set(bounds);continuousRect(fillPath,rect,radius);
            gradient=new LinearGradient(rect.left,rect.top,Math.max(rect.left+1,rect.right),Math.max(rect.top+1,rect.bottom),colors,null,Shader.TileMode.CLAMP);
            rect.inset(stroke/2,stroke/2);continuousRect(strokePath,rect,Math.max(0,radius-stroke/2));
        }
        @Override public void draw(Canvas canvas){
            if(gradient==null)return;
            paint.setAlpha(alpha);paint.setStyle(Paint.Style.FILL);paint.setShader(gradient);canvas.drawPath(fillPath,paint);
            if(stroke>0){paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setColor(border);paint.setAlpha(Math.round(android.graphics.Color.alpha(border)*alpha/255f));canvas.drawPath(strokePath,paint);}
        }
        @Override public void getOutline(Outline outline){if(!fillPath.isEmpty()){outline.setConvexPath(fillPath);outline.setAlpha(1f);}}
        @Override public void setAlpha(int value){alpha=value;invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){
        super.onSizeChanged(width,height,oldWidth,oldHeight);
        if(width>0&&height>0){
            centerBounds(centerRect,width,height);continuousRect(centerHole,centerRect,centerRadius(getContext(),width,height));
            rainbow=new SweepGradient(width/2f,height/2f,new int[]{0xffff315c,0xffffa32e,0xffffe94e,0xff37ed80,0xff2edbff,0xff6278ff,0xffc45bff,0xffff315c},null);
            paint.setShader(rainbow);
        }
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(rainbow==null)return;
        if(running)phase=currentPhase();
        rotation.setRotate(phase,getWidth()/2f,getHeight()/2f);
        rainbow.setLocalMatrix(rotation);
        int saved=canvas.save();
        canvas.clipOutPath(centerHole);
        canvas.drawRect(0,0,getWidth(),getHeight(),paint);
        canvas.restoreToCount(saved);
    }
    @Override public boolean onTouchEvent(MotionEvent event){return false;}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();scheduleFrame();}
    @Override protected void onWindowVisibilityChanged(int visibility){
        super.onWindowVisibilityChanged(visibility);
        if(visibility==VISIBLE)scheduleFrame();
        else{removeCallbacks(frame);framePosted=false;}
    }
    @Override protected void onDetachedFromWindow(){stop();super.onDetachedFromWindow();}
}
