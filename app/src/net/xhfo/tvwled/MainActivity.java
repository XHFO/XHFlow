package net.xhfo.tvwled;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import io.nayuki.qrcodegen.QrCode;
import java.util.ArrayList;
import org.json.JSONObject;

/** Fixed TV dashboard. The screen edge remains clear for actual display-color sampling. */
public final class MainActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final int BRAND_START=0xff7a84ff,BRAND_END=0xff9a7ce8,BRAND_ROSE=0xffd280c8;
    private static final float FOCUS_SCALE_X=1f,FOCUS_SCALE_Y=1f;
    private static final int FOCUS_ELEVATION_DP=0,FOCUS_SHADOW_RESERVE_DP=0;
    private TextView address,status,keepAlive,permission,outputStatus,displayStatus,suspendedStatus;
    private Switch masterSwitch;
    private ImageView qrImage;
    private RainbowView rainbowView;
    private Bitmap qrBitmap;
    private String lastUrl;
    private boolean visible,dark,fitPosted,building,renderingSwitch,controlPending,backendStartPending;
    private boolean requestedMaster;
    private long controlDeadline;
    private float layoutScale=1f;
    private int textColor,secondary,muted,accent,warningColor,windowWidth,windowHeight;
    private FrameLayout scene,centerPanel,qrFrame;
    private LinearLayout columns,left,right,runtime;
    private int contentWidth,contentHeight;
    private long lastFitSignature=Long.MIN_VALUE;
    private final ArrayList<TextSpec> textSpecs=new ArrayList<>();
    private final Runnable refresh=new Runnable(){@Override public void run(){if(!visible)return;if(status!=null)renderStatus();main.postDelayed(this,1500);}};
    private final Runnable fitContent=new Runnable(){@Override public void run(){fitPosted=false;if(columns!=null&&visible)fitDashboard();}};
    private static final class TextSpec {
        final TextView view;final float maxSp;final int lines;float minSp;
        TextSpec(TextView view,float maxSp,int lines){this.view=view;this.maxSp=maxSp;this.minSp=maxSp;this.lines=lines;}
    }

    @Override public void onCreate(Bundle saved){
        dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        textColor=dark?0xfff4f5fb:0xff1d1d2b;secondary=dark?0xffb6b8c9:0xff606173;muted=dark?0xff9294a8:0xff686a7b;accent=dark?0xffbbb2ff:0xff5848b5;warningColor=dark?0xffe8c385:0xff7b591b;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        scene=new FrameLayout(this);scene.setTag("scene");allowChildEffects(scene);scene.setBackgroundColor(dark?0xff0b0e1b:0xfff2f4fa);
        addOrb(scene,Gravity.TOP|Gravity.START,dp(440),-dp(140),-dp(200),dark?0x247a84ff:0x387a84ff);
        addOrb(scene,Gravity.TOP|Gravity.END,dp(420),-dp(150),-dp(80),dark?0x24d280c8:0x35d280c8);
        rainbowView=new RainbowView(this);scene.addView(rainbowView,new FrameLayout.LayoutParams(-1,-1));
        scene.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->{
            int width=r-l,height=b-t;
            if(width>0&&height>0&&(width!=windowWidth||height!=windowHeight)){
                windowWidth=width;windowHeight=height;buildDashboard(width,height);
            }
        });
        setContentView(scene);
    }
    /** Use the measured fullscreen window, including any space the system reserves. */
    private void buildDashboard(int width,int height){
        building=true;
        String focusedRole=null;View focused=getCurrentFocus();if(focused!=null&&focused.getTag() instanceof String)focusedRole=(String)focused.getTag();
        boolean first=centerPanel==null;
        if(centerPanel!=null)scene.removeView(centerPanel);
        textSpecs.clear();
        RectF panelBounds=new RectF();RainbowView.centerBounds(panelBounds,width,height);
        int panelWidth=Math.round(panelBounds.width()),panelHeight=Math.round(panelBounds.height());
        layoutScale=RainbowView.designScale(this,width,height);
        int padding=u(ContinuousCorners.PANEL_INSET_DP);contentWidth=panelWidth-2*padding;contentHeight=panelHeight-2*padding;
        centerPanel=new ContinuousFrame(RainbowView.centerRadius(this,width,height));centerPanel.setTag("center_panel");allowChildEffects(centerPanel);
        centerPanel.setBackground(new RainbowView.ContinuousDrawable(dark?new int[]{0xff111524,0xff18182a}:new int[]{0xfff7f8fd,0xffefeff8},RainbowView.centerRadius(this,width,height),0,0));
        // Only the whole dashboard floats. Its background supplies the shared convex
        // continuous path, so the external shadow follows the rounded panel precisely.
        centerPanel.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);centerPanel.setStateListAnimator(null);centerPanel.setTranslationZ(0);centerPanel.setElevation(u(12));
        if(Build.VERSION.SDK_INT>=28){centerPanel.setOutlineAmbientShadowColor(dark?0x60000000:0x30212a50);centerPanel.setOutlineSpotShadowColor(dark?0x80000000:0x40212a50);}
        FrameLayout.LayoutParams panelParams=new FrameLayout.LayoutParams(panelWidth,panelHeight);panelParams.leftMargin=Math.round(panelBounds.left);panelParams.topMargin=Math.round(panelBounds.top);scene.addView(centerPanel,panelParams);
        columns=new LinearLayout(this);columns.setTag("columns");columns.setOrientation(LinearLayout.HORIZONTAL);allowChildEffects(columns);
        FrameLayout.LayoutParams safeBounds=new FrameLayout.LayoutParams(-1,-1);safeBounds.setMargins(padding,padding,padding,padding);centerPanel.addView(columns,safeBounds);
        left=new LinearLayout(this);left.setTag("left_column");left.setOrientation(LinearLayout.VERTICAL);allowChildEffects(left);columns.addView(left,new LinearLayout.LayoutParams(0,-1,.41f));
        columns.addView(new View(this),new LinearLayout.LayoutParams(u(16),1));
        right=card();right.setTag("right_card");allowChildEffects(right);right.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);columns.addView(right,new LinearLayout.LayoutParams(0,-1,.59f));

        LinearLayout brand=new LinearLayout(this);brand.setOrientation(LinearLayout.HORIZONTAL);brand.setGravity(Gravity.CENTER_VERTICAL);brand.setPadding(u(12),u(12),u(12),0);
        int mark=getResources().getIdentifier("app_icon","drawable",getPackageName());
        if(mark!=0){ImageView icon=new ImageView(this);icon.setImageResource(mark);icon.setContentDescription(null);LinearLayout.LayoutParams iconParams=new LinearLayout.LayoutParams(u(44),u(44));iconParams.setMargins(0,0,u(12),0);brand.addView(icon,iconParams);}
        LinearLayout headings=new LinearLayout(this);headings.setOrientation(LinearLayout.VERTICAL);
        TextView title=text("brand_title","XH光流",20,textColor,Gravity.START,1);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);addText(headings,title);
        TextView subtitle=text("brand_subtitle","让屏幕的色彩，延伸到灯光",11,secondary,Gravity.START,1);subtitle.setPadding(0,u(4),0,0);addText(headings,subtitle);brand.addView(headings,new LinearLayout.LayoutParams(0,-2,1));left.addView(brand,new LinearLayout.LayoutParams(-1,-2));
        gap(left,12);
        runtime=card();runtime.setGravity(Gravity.TOP);left.addView(runtime,new LinearLayout.LayoutParams(-1,-2));
        addText(runtime,text("runtime_title","运行概况",12,muted,Gravity.START,1));gap(runtime,4);
        status=text("runtime_state","正在检查运行状态…",16,textColor,Gravity.START,2);status.setTypeface(Typeface.DEFAULT,Typeface.BOLD);fitText(status,12,16);addText(runtime,status);gap(runtime,8);
        outputStatus=text("output_status","总开关：关闭 · 灯光输出：已停止",11,secondary,Gravity.START,1);fitText(outputStatus,10,11);addText(runtime,outputStatus);
        displayStatus=text("display_status","显示：等待检查 · 输入：等待识别",11,secondary,Gravity.START,2);fitText(displayStatus,10,11);addText(runtime,displayStatus);
        suspendedStatus=text("suspension_reason","",11,warningColor,Gravity.START,2);fitText(suspendedStatus,10,11);addText(runtime,suspendedStatus);suspendedStatus.setVisibility(View.GONE);
        gap(left,12);
        LinearLayout protection=card();protection.setTag("background_help");allowChildEffects(protection);left.addView(protection,new LinearLayout.LayoutParams(-1,-2));
        addText(protection,text("background_title","后台运行",12,muted,Gravity.START,1));gap(protection,4);
        keepAlive=text("guard_warning","正在检查后台保护状态…",10,secondary,Gravity.START,3);fitText(keepAlive,9,10);addText(protection,keepAlive);gap(protection,8);
        Button guard=button("guard_settings","管理后台守护",false);guard.setOnClickListener(v->showGuardSettings());protection.addView(guard,new LinearLayout.LayoutParams(-1,u(44)));
        TextView note=text("recovery_note","开机恢复与输入源条件在网页中设置",11,muted,Gravity.START|Gravity.CENTER_VERTICAL,2);fitText(note,10,11);note.setPadding(0,u(8),0,0);protection.addView(note,new LinearLayout.LayoutParams(-1,u(44)));

        TextView scan=text("scan_title","用手机配置灯光",20,textColor,Gravity.CENTER,1);scan.setTypeface(Typeface.DEFAULT,Typeface.BOLD);addText(right,scan);
        TextView scanIntro=text("scan_intro","连接 WLED，编辑排布与画面效果",11,secondary,Gravity.CENTER,1);scanIntro.setPadding(0,u(4),0,0);addText(right,scanIntro);gap(right,8);
        right.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
        qrFrame=new ContinuousFrame(u(ContinuousCorners.INSET_RADIUS_DP));qrFrame.setTag("qr_frame");qrFrame.setPadding(u(4),u(4),u(4),u(4));qrFrame.setBackground(rounded(new int[]{Color.WHITE,0xfffdfcff},ContinuousCorners.INSET_RADIUS_DP,0xffe8e5f4,1));
        qrImage=new ImageView(this);qrImage.setContentDescription("扫码打开XH光流网页配置，二维码包含本机访问凭据");qrImage.setScaleType(ImageView.ScaleType.FIT_CENTER);if(qrBitmap!=null)qrImage.setImageBitmap(qrBitmap);qrFrame.addView(qrImage,new FrameLayout.LayoutParams(-1,-1));right.addView(qrFrame,new LinearLayout.LayoutParams(0,0));
        gap(right,8);address=text("server_address","正在准备网页配置…",11,accent,Gravity.CENTER,3);address.setTypeface(Typeface.MONOSPACE);fitText(address,9,11);addText(right,address);gap(right,12);
        right.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
        LinearLayout actionRow=new LinearLayout(this);actionRow.setOrientation(LinearLayout.HORIZONTAL);actionRow.setTag("bottom_actions");allowChildEffects(actionRow);right.addView(actionRow,new LinearLayout.LayoutParams(-1,u(44)));
        masterSwitch=masterControl();actionRow.addView(masterSwitch,new LinearLayout.LayoutParams(0,-1,2));actionRow.addView(new View(this),new LinearLayout.LayoutParams(u(8),1));
        Button back=button("return_picture","返回画面",false);back.setOnClickListener(v->moveTaskToBack(true));actionRow.addView(back,new LinearLayout.LayoutParams(0,-1,1));
        permission=text("action_feedback","网页配置始终可用；总开关控制灯光同步。",11,secondary,Gravity.CENTER,2);fitText(permission,10,11);permission.setPadding(0,u(8),0,0);right.addView(permission,new LinearLayout.LayoutParams(-1,u(44)));
        permission.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){}public void afterTextChanged(Editable value){scheduleFit();}});

        guard.setNextFocusRightId(masterSwitch.getId());masterSwitch.setNextFocusLeftId(guard.getId());masterSwitch.setNextFocusRightId(back.getId());back.setNextFocusLeftId(masterSwitch.getId());
        View nextFocus=focusedRole==null?masterSwitch:columns.findViewWithTag(focusedRole);if(nextFocus!=null)nextFocus.requestFocus();else masterSwitch.requestFocus();
        // Opening the app always makes its configuration page available. ACTION_CONFIG preserves
        // the saved master-switch intent; it never implicitly enables an unconfigured light strip.
        if(first||visible)maybeRestoreServer();
        renderStatus();building=false;lastFitSignature=Long.MIN_VALUE;scheduleFit();
    }
    private final class ContinuousFrame extends FrameLayout {
        private final Path clipPath=new Path();private final RectF clipBounds=new RectF();private final float radius;
        ContinuousFrame(float radius){super(MainActivity.this);this.radius=radius;setClipToOutline(false);}
        @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){super.onSizeChanged(width,height,oldWidth,oldHeight);clipBounds.set(0,0,width,height);RainbowView.continuousRect(clipPath,clipBounds,radius);}
        @Override protected void dispatchDraw(Canvas canvas){int saved=canvas.save();canvas.clipPath(clipPath);super.dispatchDraw(canvas);canvas.restoreToCount(saved);}
    }
    private void addOrb(FrameLayout parent,int gravity,int size,int horizontalMargin,int top,int color){
        View orb=new View(this);GradientDrawable glow=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{color,color&0x00ffffff});glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);glow.setGradientRadius(size/2f);glow.setShape(GradientDrawable.OVAL);orb.setBackground(glow);FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(size,size,gravity);params.topMargin=top;if((gravity&Gravity.HORIZONTAL_GRAVITY_MASK)==Gravity.RIGHT)params.rightMargin=horizontalMargin;else params.leftMargin=horizontalMargin;parent.addView(orb,params);
    }
    private LinearLayout card(){LinearLayout result=new LinearLayout(this);result.setOrientation(LinearLayout.VERTICAL);int padding=u(ContinuousCorners.CARD_PADDING_DP);result.setPadding(padding,padding,padding,padding);result.setBackground(rounded(dark?new int[]{0x1fffffff,0x0cffffff}:new int[]{0xf2ffffff,0xb3ffffff},ContinuousCorners.CARD_RADIUS_DP,dark?0x29ffffff:0xdbffffff,1));allowChildEffects(result);result.setClipToOutline(false);result.setStateListAnimator(null);result.setTranslationZ(0);result.setElevation(0);return result;}
    private Drawable rounded(int[] colors,int radius,int border,int borderDp){
        if(radius>=999){GradientDrawable pill=new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors);pill.setCornerRadius(u(radius));if(borderDp>0)pill.setStroke(Math.max(1,u(borderDp)),border);return pill;}
        return new RainbowView.ContinuousDrawable(colors,u(radius),border,borderDp>0?Math.max(1,u(borderDp)):0);
    }
    private TextView text(String role,String value,float size,int color,int gravity,int lines){TextView result=new TextView(this);result.setTag(role);result.setText(value);result.setTextSize(TypedValue.COMPLEX_UNIT_SP,size*layoutScale);result.setTextColor(color);result.setGravity(gravity);result.setIncludeFontPadding(true);result.setLineSpacing(0,1f);textSpecs.add(new TextSpec(result,size,lines));return result;}
    private void fitText(TextView view,int min,int max){for(TextSpec spec:textSpecs)if(spec.view==view){spec.minSp=min;break;}}
    private void addText(LinearLayout parent,TextView view){parent.addView(view,new LinearLayout.LayoutParams(-1,-2));}
    private void gap(LinearLayout column,int height){column.addView(new View(this),new LinearLayout.LayoutParams(1,u(height)));}
    private Button button(String role,String title,boolean quiet){
        Button result=new Button(this);result.setTag(role);result.setId(View.generateViewId());result.setText(title);result.setTextSize(TypedValue.COMPLEX_UNIT_SP,12*layoutScale);textSpecs.add(new TextSpec(result,12,1));result.setTypeface(Typeface.DEFAULT,quiet?Typeface.NORMAL:Typeface.BOLD);result.setAllCaps(false);result.setSingleLine(true);result.setIncludeFontPadding(true);result.setMinHeight(0);result.setMinimumHeight(u(36));result.setMinWidth(0);result.setMinimumWidth(0);result.setPadding(u(12),u(8),u(12),u(8));result.setBackgroundTintList(null);result.setStateListAnimator(null);
        decorateControl(result,ContinuousCorners.INSET_RADIUS_DP,quiet);return result;
    }
    private Switch masterControl(){
        Switch result=new Switch(this);result.setTag("toggle_master");result.setId(View.generateViewId());result.setText("XH光流");result.setShowText(false);result.setSwitchMinWidth(u(52));result.setSwitchPadding(u(12));result.setGravity(Gravity.CENTER_VERTICAL);result.setTextSize(TypedValue.COMPLEX_UNIT_SP,14*layoutScale);result.setTypeface(Typeface.DEFAULT,Typeface.BOLD);result.setSingleLine(true);result.setMinHeight(0);result.setMinimumHeight(u(44));result.setPadding(u(12),u(4),u(12),u(4));result.setBackgroundTintList(null);result.setStateListAnimator(null);textSpecs.add(new TextSpec(result,14,1));
        // SharedPreferences is the only owner of sync intent. View hierarchy state must
        // never replay a stale checked value through CompoundButton's change listener.
        result.setSaveEnabled(false);result.setSaveFromParentEnabled(false);
        result.setTrackTintList(null);result.setThumbTintList(null);result.setSplitTrack(false);SwitchGlyph track=new SwitchGlyph(false,u(52),u(32)),thumb=new SwitchGlyph(true,u(26),u(32));thumb.track=track;result.setTrackDrawable(track);result.setThumbDrawable(thumb);decorateControl(result,ContinuousCorners.INSET_RADIUS_DP,false);
        result.setOnCheckedChangeListener((control,checked)->{if(!renderingSwitch)requestMaster(checked);});return result;
    }
    /** Native Switch owns touch, keyboard, accessibility and thumb animation; only its pixels change. */
    private final class SwitchGlyph extends Drawable {
        private final boolean thumb;private final int width,height;private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private int alpha=255;private SwitchGlyph track;
        SwitchGlyph(boolean thumb,int width,int height){this.thumb=thumb;this.width=width;this.height=height;}
        @Override public void draw(Canvas canvas){
            Rect bounds=getBounds();float cx=bounds.exactCenterX(),cy=bounds.exactCenterY();paint.setAlpha(alpha);
            if(thumb){if(track!=null&&!track.getBounds().isEmpty()){Rect rails=track.getBounds();cx=Math.max(rails.left+rails.height()/2f,Math.min(cx,rails.right-rails.height()/2f));}paint.setColor(0xfffbfaff);paint.setAlpha(alpha);canvas.drawCircle(cx,cy,Math.min(bounds.width(),bounds.height())/2f,paint);}
            else{boolean checked=false;for(int state:getState())if(state==android.R.attr.state_checked){checked=true;break;}paint.setColor(checked?(dark?0xffb0a0ec:0xff7462b8):(dark?0xff646879:0xffb5b5bf));paint.setAlpha(alpha);canvas.drawRoundRect(new RectF(bounds),bounds.height()/2f,bounds.height()/2f,paint);}
        }
        @Override public int getIntrinsicWidth(){return width;}
        @Override public int getIntrinsicHeight(){return height;}
        @Override public boolean isStateful(){return !thumb;}
        @Override protected boolean onStateChange(int[] state){invalidateSelf();return true;}
        @Override public void setAlpha(int value){alpha=value;invalidateSelf();}
        @Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    private void decorateControl(Button result,int radius,boolean quiet){
        result.setStateListAnimator(null);result.setTranslationZ(0);result.setElevation(0);
        int[] fill=dark?new int[]{0x1affffff,0x0fffffff}:new int[]{0xcfffffff,0x8fffffff};
        StateListDrawable backgrounds=new StateListDrawable();backgrounds.addState(new int[]{android.R.attr.state_pressed},rounded(fill,radius,accent,2));backgrounds.addState(new int[]{android.R.attr.state_focused},rounded(fill,radius,accent,2));backgrounds.addState(new int[]{},rounded(fill,radius,dark?0x2dffffff:0x201d1d2b,1));result.setBackground(backgrounds);
        result.setTextColor(quiet?muted:textColor);result.setOnFocusChangeListener((view,focused)->{view.setScaleX(focused?FOCUS_SCALE_X:1f);view.setScaleY(focused?FOCUS_SCALE_Y:1f);view.setElevation(u(focused?FOCUS_ELEVATION_DP:0));main.post(()->logFocusDiagnostics((Button)view));});
    }
    private void allowChildEffects(ViewGroup group){group.setClipChildren(false);group.setClipToPadding(false);}
    private void scheduleFit(){if(!building&&!fitPosted&&columns!=null){fitPosted=true;main.post(fitContent);}}
    /** Text owns its natural height. Only the QR and the runtime card consume remaining space. */
    private void fitDashboard(){
        long signature=contentWidth*31L+contentHeight;
        for(TextSpec spec:textSpecs)signature=signature*31+spec.view.getText().hashCode()+spec.view.getVisibility();
        if(signature==lastFitSignature)return;lastFitSignature=signature;
        LinearLayout.LayoutParams runtimeParams=(LinearLayout.LayoutParams)runtime.getLayoutParams();runtimeParams.height=-2;runtimeParams.weight=0;runtime.setLayoutParams(runtimeParams);
        LinearLayout.LayoutParams qrParams=(LinearLayout.LayoutParams)qrFrame.getLayoutParams();qrParams.width=0;qrParams.height=0;qrFrame.setLayoutParams(qrParams);
        float reduction=1f;
        int leftMinimum=0,rightFixed=0;
        for(int attempt=0;attempt<14;attempt++){
            for(TextSpec spec:textSpecs)spec.view.setTextSize(TypedValue.COMPLEX_UNIT_SP,spec.maxSp*layoutScale*reduction);
            measureNaturalColumns();
            for(TextSpec spec:textSpecs)fitNaturalText(spec,reduction);
            measureNaturalColumns();leftMinimum=left.getMeasuredHeight();rightFixed=right.getMeasuredHeight();
            if(leftMinimum<=contentHeight&&rightFixed+u(96)<=contentHeight)break;
            reduction*=0.94f;
        }
        int qrPixels=Math.max(1,Math.min(u(128),Math.min(right.getMeasuredWidth()-right.getPaddingLeft()-right.getPaddingRight(),contentHeight-rightFixed)));
        // Equal weighted spacers place the QR and address together in the visual
        // space between the fixed heading and footer, without moving either baseline.
        qrParams.width=qrPixels;qrParams.height=qrPixels;qrFrame.setLayoutParams(qrParams);
        runtimeParams.height=0;runtimeParams.weight=1;runtime.setLayoutParams(runtimeParams);
        final int minLeft=leftMinimum,fixedRight=rightFixed;final float textReduction=reduction;final LinearLayout fittedColumns=columns;
        columns.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){@Override public void onGlobalLayout(){fittedColumns.getViewTreeObserver().removeOnGlobalLayoutListener(this);if(columns==fittedColumns)logLayoutDiagnostics(minLeft,fixedRight,qrPixels,textReduction);}});
        columns.requestLayout();
    }
    private void measureNaturalColumns(){
        int natural=View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED);columns.measure(View.MeasureSpec.makeMeasureSpec(contentWidth,View.MeasureSpec.EXACTLY),natural);
        left.measure(View.MeasureSpec.makeMeasureSpec(left.getMeasuredWidth(),View.MeasureSpec.EXACTLY),natural);right.measure(View.MeasureSpec.makeMeasureSpec(right.getMeasuredWidth(),View.MeasureSpec.EXACTLY),natural);
    }
    private void fitNaturalText(TextSpec spec,float reduction){
        TextView view=spec.view;int width=view.getMeasuredWidth()-view.getCompoundPaddingLeft()-view.getCompoundPaddingRight();if(width<1||view.getVisibility()!=View.VISIBLE)return;
        TextPaint paint=new TextPaint(view.getPaint());float max=spec.maxSp*layoutScale*reduction,min=spec.minSp*layoutScale*reduction;
        float chosen=max;
        for(float candidate=max;candidate>=min-0.01f;candidate-=0.5f){
            chosen=Math.max(min,candidate);paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,chosen,getResources().getDisplayMetrics()));
            CharSequence value=view.getText();StaticLayout layout=StaticLayout.Builder.obtain(value,0,value.length(),paint,width).setIncludePad(false).setBreakStrategy(view.getBreakStrategy()).setHyphenationFrequency(view.getHyphenationFrequency()).build();
            if(layout.getLineCount()<=spec.lines)break;
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP,chosen);
    }
    /** Geometry only: never print text, the pairing URL, or QR contents. */
    private void logLayoutDiagnostics(int minLeft,int fixedRight,int qrPixels,float reduction){
        if(!visible||columns==null||!columns.isLaidOut())return;
        Log.i("XHFlowLayout","window="+windowWidth+"x"+windowHeight+" content="+contentWidth+"x"+contentHeight+" density="+getResources().getDisplayMetrics().density+" fontScale="+getResources().getConfiguration().fontScale+" scale="+layoutScale+" textReduction="+reduction+" leftMin="+minLeft+" rightFixed="+fixedRight+" qr="+qrPixels);
        for(TextSpec spec:textSpecs){
            TextView view=spec.view;if(view.getVisibility()!=View.VISIBLE)continue;Layout layout=view.getLayout();if(layout==null)continue;
            int innerHeight=view.getHeight()-view.getCompoundPaddingTop()-view.getCompoundPaddingBottom(),innerWidth=view.getWidth()-view.getCompoundPaddingLeft()-view.getCompoundPaddingRight();int lines=layout.getLineCount();int covered=lines==0?0:layout.getLineEnd(lines-1);float maxWidth=0;for(int i=0;i<lines;i++)maxWidth=Math.max(maxWidth,layout.getLineMax(i));
            boolean clipped=layout.getHeight()>innerHeight+1||maxWidth>innerWidth+1||covered<view.getText().length();
            Rect rect=layoutBoundsIn(view,columns);boolean outside=rect.left<0||rect.top<0||rect.right>contentWidth||rect.bottom>contentHeight;
            if(view.getParent() instanceof ViewGroup){ViewGroup parent=(ViewGroup)view.getParent();outside|=view.getTop()<parent.getPaddingTop()||view.getBottom()>parent.getHeight()-parent.getPaddingBottom();}
            Log.i("XHFlowLayout","role="+view.getTag()+" bounds="+rect.toShortString()+" inner="+innerWidth+"x"+innerHeight+" textPx="+view.getTextSize()+" lines="+lines+" chars="+view.getText().length()+" covered="+covered+" clipped="+clipped+" outside="+outside);
            if(view instanceof Button)logFocusDiagnostics((Button)view);
        }
    }
    /** Outer layout box: subtract ancestor scrolling, never TextView's own text scroll. */
    private Rect layoutBoundsIn(View view,View ancestor){
        int x=0,y=0;View current=view;
        while(current!=ancestor){
            x+=current.getLeft();y+=current.getTop();
            if(!(current.getParent() instanceof View))break;
            View parent=(View)current.getParent();x-=parent.getScrollX();y-=parent.getScrollY();current=parent;
        }
        return new Rect(x,y,x+view.getWidth(),y+view.getHeight());
    }
    /** Focus transforms are separate from layout boxes. Shadow bounds are a conservative reserve. */
    private void logFocusDiagnostics(Button button){
        if(!visible||scene==null||!button.isAttachedToWindow()||!button.isLaidOut()||button.getWidth()==0)return;
        Rect outer=layoutBoundsIn(button,scene);RectF visual=new RectF(0,0,button.getWidth(),button.getHeight());button.getMatrix().mapRect(visual);visual.offset(outer.left,outer.top);
        float reserve=button.getElevation()+button.getTranslationZ()>0?u(FOCUS_SHADOW_RESERVE_DP):0;
        RectF envelope=new RectF(visual);envelope.inset(-reserve,-reserve);
        boolean clipRisk=false;StringBuilder ancestors=new StringBuilder();
        for(android.view.ViewParent next=button.getParent();next instanceof ViewGroup;next=next.getParent()){
            ViewGroup group=(ViewGroup)next;Rect bounds=layoutBoundsIn(group,scene);RectF clip=new RectF(bounds);
            if(group.getClipToPadding()){clip.left+=group.getPaddingLeft();clip.top+=group.getPaddingTop();clip.right-=group.getPaddingRight();clip.bottom-=group.getPaddingBottom();}
            if((group.getClipChildren()||group.getClipToPadding())&&!clip.contains(envelope))clipRisk=true;
            if(group==centerPanel&&!insideRoundedPanel(envelope))clipRisk=true;
            if(ancestors.length()>0)ancestors.append(';');ancestors.append(group.getTag()==null?group.getClass().getSimpleName():group.getTag()).append(':').append(group.getClipChildren()?'1':'0').append(group.getClipToPadding()?'1':'0').append(group.getClipToOutline()?'1':'0');
            if(group==scene)break;
        }
        Log.i("XHFlowLayout","focusRole="+button.getTag()+" focused="+button.hasFocus()+" visual="+visual.toShortString()+" shadowReservePx="+reserve+" panelReserveInside="+insideRoundedPanel(envelope)+" ancestorClipRisk="+clipRisk+" clips="+ancestors);
    }
    private boolean insideRoundedPanel(RectF rect){
        if(centerPanel==null)return false;
        Rect panel=layoutBoundsIn(centerPanel,scene);if(rect.left<panel.left||rect.top<panel.top||rect.right>panel.right||rect.bottom>panel.bottom)return false;
        float radius=RainbowView.centerRadius(this,windowWidth,windowHeight);
        return insideRoundedCorner(rect.left,rect.top,panel,radius)&&insideRoundedCorner(rect.right,rect.top,panel,radius)&&insideRoundedCorner(rect.left,rect.bottom,panel,radius)&&insideRoundedCorner(rect.right,rect.bottom,panel,radius);
    }
    private boolean insideRoundedCorner(float x,float y,Rect panel,float radius){
        float cx=Math.max(panel.left+radius,Math.min(x,panel.right-radius)),cy=Math.max(panel.top+radius,Math.min(y,panel.bottom-radius));float dx=x-cx,dy=y-cy;return dx*dx+dy*dy<=radius*radius+1f;
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private int u(float value){return Math.round(value*getResources().getDisplayMetrics().density*layoutScale);}
    private void ensureService(){
        if(backendStartPending)return;
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences(MODE_PRIVATE).getBoolean("notificationAsked",false)){getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked",true).commit();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},21);permission.setText("允许通知后，可从系统通知随时停止同步。");return;}
        try{backendStartPending=true;AmbientService.dispatch(this,AmbientService.ACTION_CONFIG);permission.setText(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED?"网页配置已请求启动；通知未授权，仍可使用总开关控制灯光。":"网页配置已准备好，扫码即可调整灯光。");main.postDelayed(()->{backendStartPending=false;renderStatus();},400);}
        catch(Exception failure){backendStartPending=false;permission.setText("网页配置启动失败："+failure.getMessage());}
    }
    private void renderMaster(boolean enabled){
        if(masterSwitch==null)return;
        renderingSwitch=true;
        try{masterSwitch.setChecked(enabled);masterSwitch.setAlpha(controlPending?.65f:1f);if(Build.VERSION.SDK_INT>=30)masterSwitch.setStateDescription(controlPending?"正在确认总开关状态":enabled?"已开启":"已关闭");}
        finally{renderingSwitch=false;}
    }
    private void requestMaster(boolean requested){
        boolean enabled=getSharedPreferences("tvwled",MODE_PRIVATE).getBoolean("enabled",false);renderMaster(enabled);
        if(controlPending||requested==enabled)return;
        try{
            if(requested){try{new LayoutConfig(getSharedPreferences("tvwled",MODE_PRIVATE).getString("layout",""));}catch(Exception invalid){permission.setText("先扫码连接 WLED、完成灯带排布，再开启 XH光流。");return;}}
            requestedMaster=requested;controlPending=true;controlDeadline=SystemClock.uptimeMillis()+5000;renderMaster(enabled);
            AmbientService.dispatch(this,requested?AmbientService.ACTION_START:AmbientService.ACTION_PAUSE);permission.setText(requested?"正在开启 XH光流，实际输出见左侧。":"正在关闭 XH光流，网页配置仍可使用。");main.postDelayed(this::renderStatus,400);
        }catch(Exception failure){controlPending=false;renderMaster(enabled);permission.setText("总开关操作失败："+failure.getMessage());}
    }
    private void showGuardSettings(){
        new AlertDialog.Builder(this).setTitle("后台运行保护").setMessage("推荐开启“系统恢复保护”：当电视清理进程后，由 Android 重新绑定本应用，恢复网页后台和上次已开启的灯光同步。\n\n系统将这个授权称为“通知使用权”。本应用仅使用服务绑定，不读取、保存或操作通知，已禁用全部通知类别。若电视没有这个入口，可通过 ADB 授权。原有的辅助功能守护仍可作为补充。\n\n授权可随时在系统设置撤销，程序不会自行重新授权。关闭总开关后，恢复进程也不会重新开启灯光；开机恢复在网页中设置。恢复速度和可用性取决于电视固件。").setNegativeButton("返回",null).setNeutralButton("辅助功能守护",(dialog,which)->openProtectionSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)).setPositiveButton("系统恢复保护",(dialog,which)->openProtectionSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)).show();
    }
    private void openProtectionSettings(String action){
        try{startActivity(new Intent(action));}
        catch(Exception failure){permission.setText("此固件未提供该系统设置入口，可通过 ADB 配置系统恢复保护。");}
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){super.onRequestPermissionsResult(code,permissions,grants);if(code==21)ensureService();}
    private void renderStatus(){
        if(status==null)return;
        String url=ConfigServer.pairingUrl(this);
        if(url==null){address.setText("尚未连接局域网，请连接 Wi-Fi 或有线网络。");qrImage.setImageDrawable(null);lastUrl=null;}
        else{address.setText(url);if(!url.equals(lastUrl)){QrCode code=QrCode.encodeText(url,QrCode.Ecc.MEDIUM);int scale=6,border=4,width=(code.size+border*2)*scale;int[] pixels=new int[width*width];for(int y=0;y<width;y++)for(int x=0;x<width;x++)pixels[y*width+x]=code.getModule(x/scale-border,y/scale-border)?Color.BLACK:Color.WHITE;Bitmap next=Bitmap.createBitmap(pixels,width,width,Bitmap.Config.ARGB_8888);qrImage.setImageBitmap(next);if(qrBitmap!=null)qrBitmap.recycle();qrBitmap=next;lastUrl=url;}}
        try{
            JSONObject detail=new JSONObject(AmbientService.status());boolean enabled=getSharedPreferences("tvwled",MODE_PRIVATE).getBoolean("enabled",false),running=detail.optBoolean("running",false);
            if(controlPending&&(enabled==requestedMaster||SystemClock.uptimeMillis()>=controlDeadline)){controlPending=false;permission.setText(enabled==requestedMaster?(enabled?"XH光流已开启；网页修改会自动保存。":"XH光流已关闭，网页配置仍可使用。") :"总开关状态尚未确认，请再试一次。");}
            renderMaster(enabled);
            status.setText(detail.optString("state",ConfigServer.isLive()?"配置后台已开启":"配置后台已关闭"));
            outputStatus.setText("总开关："+(enabled?"开启":"关闭")+" · 灯光输出："+(running?"运行中":enabled?"已暂停":"已停止"));
            boolean interactive=detail.optBoolean("displayInteractive",true);String screen=displayLabel(detail.optString("displayState",interactive?"on":"off"));JSONObject source=detail.optJSONObject("inputSource");String input=source!=null&&source.optBoolean("known",false)?source.optString("label","未识别"):"未识别";displayStatus.setText("显示："+screen+" · 输入："+input);
            String suspended=detail.optString("suspendedReason","");boolean paused=enabled&&!suspended.isEmpty();suspendedStatus.setVisibility(paused?View.VISIBLE:View.GONE);suspendedStatus.setText(paused?"暂停原因："+suspensionLabel(suspended):"");
            boolean revival=detail.optBoolean("revivalConnected",false),revivalAuthorized=detail.optBoolean("revivalAuthorized",false),guarded=detail.optBoolean("guardConnected",false),authorized=detail.optBoolean("guardAuthorized",false),registered=detail.optBoolean("foregroundRegistered",false);
            String protection=revival?"系统恢复保护已连接":revivalAuthorized?"系统恢复保护已授权，尚未连接":guarded?"辅助功能守护已连接":authorized?"辅助功能守护已授权，尚未连接":registered?"系统前台服务已登记":"后台保护尚未授权";
            String warning=revival?(detail.optBoolean("autoStart",true)?"系统重绑后恢复上次状态；总开关关闭时不恢复灯光。":"自动恢复已关闭，仅保护本次手动运行。"):revivalAuthorized?"等待系统绑定，可在管理后台守护中检查授权。":guarded||registered?"建议再启用系统恢复保护，应对整个进程被清理。":"进入管理后台守护，启用系统恢复保护。";
            keepAlive.setText(protection+"\n"+warning);keepAlive.setTextColor(revival||guarded||registered?secondary:warningColor);
        }catch(Exception ignored){status.setText(ConfigServer.isLive()?"配置后台已开启":"配置后台尚未开启");keepAlive.setText("后台保护状态暂不可用");}
        scheduleFit();
    }
    private static String displayLabel(String state){switch(state){case "on":case "vr":case "on_suspend":return "亮屏";case "off":return "休眠";case "doze":case "doze_suspend":return "待机";default:return "待确认";}}
    private static String suspensionLabel(String reason){switch(reason){case "display_sleep":return "显示设备休眠";case "input_unknown":return "当前接口未识别";case "input_not_allowed":return "当前接口未开启同步";case "wled_power_pending":return "正在确认灯带电源";case "wled_power_error":return "灯带电源尚未确认";case "config_invalid":return "请检查已应用的配置";case "capture_error":return "暂时无法读取画面";case "initialization_retry":return "连接暂不可用，正在自动重连";default:return "正在准备同步";}}
    private void maybeRestoreServer(){if(!ConfigServer.isLive())ensureService();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(permission!=null)maybeRestoreServer();}
    @Override protected void onResume(){super.onResume();visible=true;if(rainbowView!=null)rainbowView.start();if(permission!=null)maybeRestoreServer();refresh.run();scheduleFit();}
    @Override protected void onPause(){visible=false;if(rainbowView!=null)rainbowView.stop();main.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){if(rainbowView!=null)rainbowView.stop();main.removeCallbacksAndMessages(null);if(qrBitmap!=null)qrBitmap.recycle();super.onDestroy();}
}
