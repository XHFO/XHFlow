package net.xhfo.tvwled;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/** Foreground-only pixels on the actual display; capture and WLED output remain independent. */
final class RainbowView extends View {
    static final float EDGE_FRACTION=0.10f;
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
    static void centerBounds(RectF result,int width,int height){result.set(Math.round(width*EDGE_FRACTION),Math.round(height*EDGE_FRACTION),Math.round(width*(1-EDGE_FRACTION)),Math.round(height*(1-EDGE_FRACTION)));}
    static float centerRadius(Context context,int width,int height){return Math.min(28f*context.getResources().getDisplayMetrics().density,Math.min(width,height)*0.06f);}
    @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){
        super.onSizeChanged(width,height,oldWidth,oldHeight);
        if(width>0&&height>0){
            centerBounds(centerRect,width,height);float radius=centerRadius(getContext(),width,height);centerHole.rewind();centerHole.addRoundRect(centerRect,radius,radius,Path.Direction.CW);
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
