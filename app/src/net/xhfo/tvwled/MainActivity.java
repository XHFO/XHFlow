package net.xhfo.tvwled;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.TextView;
import io.nayuki.qrcodegen.QrCode;
import java.util.ArrayList;
import org.json.JSONObject;

/** Fixed TV dashboard. The screen edge remains clear for actual display-color sampling. */
public final class MainActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final int BRAND_START=0xff7a84ff,BRAND_END=0xff9a7ce8,BRAND_ROSE=0xffd280c8;
    private static final float FOCUS_SCALE_X=1.008f,FOCUS_SCALE_Y=1.015f;
    private static final int FOCUS_ELEVATION_DP=2,FOCUS_SHADOW_RESERVE_DP=8;
    private TextView address,status,keepAlive,permission,outputStatus,displayStatus,suspendedStatus;
    private Button syncButton;
    private ImageView qrImage;
    private RainbowView rainbowView;
    private Bitmap qrBitmap;
    private String lastUrl;
    private boolean visible,dark,fitPosted,building;
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
        textColor=dark?0xfff4f5fb:0xff1d1d2b;secondary=dark?0xffb6b8c9:0xff606173;muted=dark?0xff9294a8:0xff78798c;accent=dark?0xffbbb2ff:0xff5848b5;warningColor=dark?0xffe8c385:0xff7b591b;
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
        float density=getResources().getDisplayMetrics().density;
        layoutScale=Math.min(1f,Math.min((panelHeight/density-24f)/430f,(panelWidth/density-24f)/744f));
        layoutScale=Math.max(0.45f,layoutScale);
        int padding=u(12);contentWidth=panelWidth-2*padding;contentHeight=panelHeight-2*padding;
        centerPanel=new FrameLayout(this);centerPanel.setTag("center_panel");allowChildEffects(centerPanel);
        GradientDrawable panel=new GradientDrawable(GradientDrawable.Orientation.TL_BR,dark?new int[]{0xff111524,0xff18182a}:new int[]{0xfff7f8fd,0xffefeff8});panel.setCornerRadius(RainbowView.centerRadius(this,width,height));centerPanel.setBackground(panel);centerPanel.setClipToOutline(true);
        FrameLayout.LayoutParams panelParams=new FrameLayout.LayoutParams(panelWidth,panelHeight);panelParams.leftMargin=Math.round(panelBounds.left);panelParams.topMargin=Math.round(panelBounds.top);scene.addView(centerPanel,panelParams);
        columns=new LinearLayout(this);columns.setTag("columns");columns.setOrientation(LinearLayout.HORIZONTAL);allowChildEffects(columns);
        FrameLayout.LayoutParams safeBounds=new FrameLayout.LayoutParams(-1,-1);safeBounds.setMargins(padding,padding,padding,padding);centerPanel.addView(columns,safeBounds);
        left=new LinearLayout(this);left.setTag("left_column");left.setOrientation(LinearLayout.VERTICAL);allowChildEffects(left);columns.addView(left,new LinearLayout.LayoutParams(0,-1,0.41f));
        columns.addView(new View(this),new LinearLayout.LayoutParams(u(16),1));
        right=card(14);right.setTag("right_card");allowChildEffects(right);right.setClipToOutline(false);right.setGravity(Gravity.CENTER);columns.addView(right,new LinearLayout.LayoutParams(0,-1,0.59f));

        LinearLayout brand=new LinearLayout(this);brand.setOrientation(LinearLayout.HORIZONTAL);brand.setGravity(Gravity.CENTER_VERTICAL);
        int mark=getResources().getIdentifier("app_icon","drawable",getPackageName());
        if(mark!=0){ImageView icon=new ImageView(this);icon.setImageResource(mark);icon.setContentDescription(null);LinearLayout.LayoutParams iconParams=new LinearLayout.LayoutParams(u(40),u(40));iconParams.setMargins(0,0,u(10),0);brand.addView(icon,iconParams);}
        LinearLayout headings=new LinearLayout(this);headings.setOrientation(LinearLayout.VERTICAL);
        TextView title=text("brand_title","XH光流",22,textColor,Gravity.START,1);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);addText(headings,title);
        TextView subtitle=text("brand_subtitle","让屏幕的色彩，延伸到灯光",11,secondary,Gravity.START,1);subtitle.setPadding(0,u(3),0,0);addText(headings,subtitle);brand.addView(headings,new LinearLayout.LayoutParams(0,-2,1));left.addView(brand,new LinearLayout.LayoutParams(-1,-2));
        gap(left,8);
        LinearLayout explanation=card(10);explanation.setGravity(Gravity.CENTER_VERTICAL);left.addView(explanation,new LinearLayout.LayoutParams(-1,-2));
        TextView guideTitle=text("guide_title","从扫码配置开始",12,textColor,Gravity.START,1);guideTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);addText(explanation,guideTitle);
        TextView guide=text("guide","连接 WLED 并保存灯带排布，再开启画面同步。边缘彩虹可用于核对灯光跟随效果。",11,secondary,Gravity.START,2);fitText(guide,9,11);addText(explanation,guide);
        gap(left,8);
        runtime=card(11);runtime.setGravity(Gravity.TOP);left.addView(runtime,new LinearLayout.LayoutParams(-1,-2));
        addText(runtime,text("runtime_title","当前运行",11,muted,Gravity.START,1));gap(runtime,3);
        status=text("runtime_state","正在检查运行状态…",13,textColor,Gravity.START,2);status.setTypeface(Typeface.DEFAULT,Typeface.BOLD);fitText(status,10,13);addText(runtime,status);
        outputStatus=text("output_status","同步请求：关闭 · 实际输出：已停止",10,secondary,Gravity.START,1);fitText(outputStatus,9,10);addText(runtime,outputStatus);
        displayStatus=text("display_status","显示：等待检查 · 输入：等待识别",10,secondary,Gravity.START,1);fitText(displayStatus,9,10);addText(runtime,displayStatus);
        suspendedStatus=text("suspension_reason","",11,warningColor,Gravity.START,2);fitText(suspendedStatus,9,11);addText(runtime,suspendedStatus);suspendedStatus.setVisibility(View.GONE);
        gap(runtime,4);keepAlive=text("guard_warning","正在检查后台保护状态…",10,muted,Gravity.START,3);fitText(keepAlive,9,10);addText(runtime,keepAlive);
        gap(left,8);
        LinearLayout backgroundControls=new LinearLayout(this);backgroundControls.setTag("background_controls");backgroundControls.setOrientation(LinearLayout.VERTICAL);allowChildEffects(backgroundControls);left.addView(backgroundControls,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout controlRow=new LinearLayout(this);controlRow.setTag("control_row");controlRow.setOrientation(LinearLayout.HORIZONTAL);allowChildEffects(controlRow);controlRow.setPadding(u(3),u(3),u(3),u(3));backgroundControls.addView(controlRow,new LinearLayout.LayoutParams(-1,-2));
        Button start=button("start_backend","开启配置后台",false);start.setOnClickListener(v->ensureService());LinearLayout.LayoutParams startParams=new LinearLayout.LayoutParams(0,-2,1);startParams.rightMargin=u(4);controlRow.addView(start,startParams);
        Button guard=button("guard_settings","后台保活设置",false);guard.setOnClickListener(v->showGuardSettings());LinearLayout.LayoutParams guardParams=new LinearLayout.LayoutParams(0,-2,1);guardParams.leftMargin=u(4);controlRow.addView(guard,guardParams);
        gap(backgroundControls,8);Button stop=button("stop_backend","关闭整个后台",true);stop.setOnClickListener(v->shutdown());backgroundControls.addView(stop,new LinearLayout.LayoutParams(-1,-2));
        TextView note=text("recovery_note","开机恢复可在网页设置",10,muted,Gravity.START,1);note.setPadding(0,u(3),0,0);addText(left,note);

        TextView scan=text("scan_title","扫码配置灯带",17,textColor,Gravity.CENTER,1);scan.setTypeface(Typeface.DEFAULT,Typeface.BOLD);addText(right,scan);
        addText(right,text("scan_intro","手机扫码，或打开下方局域网地址",11,secondary,Gravity.CENTER,1));gap(right,10);
        qrFrame=new FrameLayout(this);qrFrame.setTag("qr_frame");qrFrame.setPadding(u(3),u(3),u(3),u(3));qrFrame.setBackground(rounded(new int[]{Color.WHITE,0xfffdfcff},14,0xffe8e5f4,1));qrFrame.setClipToOutline(true);
        qrImage=new ImageView(this);qrImage.setContentDescription("扫码打开XH光流配置后台，二维码包含本机访问凭据");qrImage.setScaleType(ImageView.ScaleType.FIT_CENTER);if(qrBitmap!=null)qrImage.setImageBitmap(qrBitmap);qrFrame.addView(qrImage,new FrameLayout.LayoutParams(-1,-1));right.addView(qrFrame,new LinearLayout.LayoutParams(0,0));
        gap(right,10);address=text("server_address","正在获取局域网地址…",11,accent,Gravity.CENTER,3);address.setTypeface(Typeface.MONOSPACE);fitText(address,9,11);addText(right,address);gap(right,14);
        syncButton=button("toggle_sync","开启画面同步",false);syncButton.setMinimumHeight(u(40));syncButton.setOnClickListener(v->toggleSync());right.addView(syncButton,new LinearLayout.LayoutParams(-1,-2));gap(right,8);
        Button back=button("return_picture","返回画面",false);back.setOnClickListener(v->moveTaskToBack(true));right.addView(back,new LinearLayout.LayoutParams(-1,-2));
        permission=text("action_feedback","配置完成后，打开画面同步即可。",11,secondary,Gravity.CENTER,2);fitText(permission,9,11);permission.setPadding(0,u(8),0,0);addText(right,permission);
        permission.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){}public void afterTextChanged(Editable value){scheduleFit();}});

        start.setNextFocusRightId(guard.getId());start.setNextFocusDownId(stop.getId());guard.setNextFocusLeftId(start.getId());guard.setNextFocusRightId(syncButton.getId());guard.setNextFocusDownId(stop.getId());stop.setNextFocusUpId(start.getId());stop.setNextFocusRightId(back.getId());syncButton.setNextFocusLeftId(guard.getId());syncButton.setNextFocusDownId(back.getId());back.setNextFocusLeftId(stop.getId());back.setNextFocusUpId(syncButton.getId());
        View nextFocus=focusedRole==null?syncButton:columns.findViewWithTag(focusedRole);if(nextFocus!=null)nextFocus.requestFocus();else syncButton.requestFocus();
        if(first){if(!getSharedPreferences("tvwled",MODE_PRIVATE).contains("serverEnabled"))ensureService();else if(visible)maybeRestoreServer();}
        renderStatus();building=false;lastFitSignature=Long.MIN_VALUE;scheduleFit();
    }
    private void addOrb(FrameLayout parent,int gravity,int size,int horizontalMargin,int top,int color){
        View orb=new View(this);GradientDrawable glow=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{color,color&0x00ffffff});glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);glow.setGradientRadius(size/2f);glow.setShape(GradientDrawable.OVAL);orb.setBackground(glow);FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(size,size,gravity);params.topMargin=top;if((gravity&Gravity.HORIZONTAL_GRAVITY_MASK)==Gravity.RIGHT)params.rightMargin=horizontalMargin;else params.leftMargin=horizontalMargin;parent.addView(orb,params);
    }
    private LinearLayout card(int padding){LinearLayout result=new LinearLayout(this);result.setOrientation(LinearLayout.VERTICAL);result.setPadding(u(padding),u(padding),u(padding),u(padding));result.setBackground(rounded(dark?new int[]{0x24ffffff,0x0cffffff}:new int[]{0xe6ffffff,0x80ffffff},22,dark?0x29ffffff:0xdbffffff,1));result.setClipToOutline(true);result.setElevation(u(dark?2:3));return result;}
    private GradientDrawable rounded(int[] colors,int radius,int border,int borderDp){GradientDrawable drawable=new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors);drawable.setCornerRadius(u(radius));if(borderDp>0)drawable.setStroke(Math.max(1,u(borderDp)),border);return drawable;}
    private TextView text(String role,String value,float size,int color,int gravity,int lines){TextView result=new TextView(this);result.setTag(role);result.setText(value);result.setTextSize(TypedValue.COMPLEX_UNIT_SP,size*layoutScale);result.setTextColor(color);result.setGravity(gravity);result.setIncludeFontPadding(true);result.setLineSpacing(0,1f);textSpecs.add(new TextSpec(result,size,lines));return result;}
    private void fitText(TextView view,int min,int max){for(TextSpec spec:textSpecs)if(spec.view==view){spec.minSp=min;break;}}
    private void addText(LinearLayout parent,TextView view){parent.addView(view,new LinearLayout.LayoutParams(-1,-2));}
    private void gap(LinearLayout column,int height){column.addView(new View(this),new LinearLayout.LayoutParams(1,u(height)));}
    private Button button(String role,String title,boolean quiet){
        Button result=new Button(this);result.setTag(role);result.setId(View.generateViewId());result.setText(title);result.setTextSize(TypedValue.COMPLEX_UNIT_SP,12*layoutScale);textSpecs.add(new TextSpec(result,12,1));result.setTypeface(Typeface.DEFAULT,quiet?Typeface.NORMAL:Typeface.BOLD);result.setAllCaps(false);result.setSingleLine(true);result.setIncludeFontPadding(true);result.setMinHeight(0);result.setMinimumHeight(u(36));result.setMinWidth(0);result.setMinimumWidth(0);result.setPadding(u(10),u(7),u(10),u(7));result.setBackgroundTintList(null);result.setStateListAnimator(null);
        StateListDrawable backgrounds=new StateListDrawable();backgrounds.addState(new int[]{android.R.attr.state_pressed},rounded(new int[]{BRAND_END,BRAND_ROSE},999,dark?0xffe3dcff:0xff5848b5,2));backgrounds.addState(new int[]{android.R.attr.state_focused},rounded(new int[]{BRAND_START,BRAND_END,BRAND_ROSE},999,dark?0xfff4edff:0xff5848b5,2));backgrounds.addState(new int[]{android.R.attr.state_selected},rounded(dark?new int[]{0xff363052,0xff292541}:new int[]{0xffe9e7fb,0xfff0e8f7},999,BRAND_END,1));backgrounds.addState(new int[]{},rounded(dark?new int[]{0x1affffff,0x0fffffff}:new int[]{0xcfffffff,0x8fffffff},999,dark?0x2dffffff:0x201d1d2b,1));result.setBackground(backgrounds);
        result.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_focused},new int[]{android.R.attr.state_pressed},new int[]{android.R.attr.state_selected},new int[]{}},new int[]{0xff161425,0xff161425,accent,quiet?muted:textColor}));result.setOnFocusChangeListener((view,focused)->{view.setScaleX(focused?FOCUS_SCALE_X:1f);view.setScaleY(focused?FOCUS_SCALE_Y:1f);view.setElevation(u(focused?FOCUS_ELEVATION_DP:0));main.post(()->logFocusDiagnostics((Button)view));});return result;
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
            if(group.getClipToOutline()&&group==centerPanel&&!insideRoundedPanel(envelope))clipRisk=true;
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
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences(MODE_PRIVATE).getBoolean("notificationAsked",false)){getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked",true).commit();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},21);permission.setText("允许通知后，可从系统通知随时停止同步。");return;}
        try{AmbientService.dispatch(this,AmbientService.ACTION_CONFIG);permission.setText(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED?"已请求开启配置后台；通知未授权，可从网页或此处停止。":"已请求开启配置后台，扫码即可配置灯带。");main.postDelayed(this::renderStatus,400);}
        catch(Exception failure){permission.setText("配置后台启动失败："+failure.getMessage());}
    }
    private void toggleSync(){
        try{
            boolean enabled=new JSONObject(AmbientService.status()).optBoolean("enabled",false);
            if(!enabled){try{new LayoutConfig(getSharedPreferences("tvwled",MODE_PRIVATE).getString("layout",""));}catch(Exception invalid){permission.setText("请先扫码，在网页中保存灯带布局，再开启画面同步。");return;}}
            AmbientService.dispatch(this,enabled?AmbientService.ACTION_STOP:AmbientService.ACTION_START);permission.setText(enabled?"已请求关闭画面同步，配置后台继续运行。":"已请求开启画面同步，实际输出状态见左侧。");main.postDelayed(this::renderStatus,400);
        }catch(Exception failure){permission.setText("同步操作失败："+failure.getMessage());}
    }
    private void shutdown(){try{AmbientService.dispatch(this,AmbientService.ACTION_SHUTDOWN);permission.setText("已请求关闭配置后台与同步；手动开启前不会自动恢复。");main.postDelayed(this::renderStatus,400);}catch(Exception failure){permission.setText("关闭后台失败："+failure.getMessage());}}
    private void showGuardSettings(){
        new AlertDialog.Builder(this).setTitle("可选的后台保活设置").setMessage("部分电视固件会停止普通后台服务。你可以在系统设置中手动启用本应用的后台守护，让 Android 绑定它以托管配置网页和已开启的灯带同步。\n\n这个守护不接收界面事件、不读取窗口内容、不监听按键，不执行手势或系统全局操作。画面取色仍由原厂接口独立完成。\n\n这是可选授权，程序不会代你开启。下一页请手动选择本应用；后台关闭与自动恢复开关仍然有效。实际保活效果以这台电视的测试为准。").setNegativeButton("取消",null).setPositiveButton("前往系统设置",(dialog,which)->{try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}catch(Exception failure){permission.setText("此固件未提供通用辅助功能设置入口，请在系统设置中手动查找。");}}).show();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){super.onRequestPermissionsResult(code,permissions,grants);if(code==21)ensureService();}
    private void renderStatus(){
        if(status==null)return;
        String url=ConfigServer.pairingUrl(this);
        if(url==null){address.setText("尚未连接局域网，请连接 Wi-Fi 或有线网络。");qrImage.setImageDrawable(null);lastUrl=null;}
        else{address.setText(url);if(!url.equals(lastUrl)){QrCode code=QrCode.encodeText(url,QrCode.Ecc.MEDIUM);int scale=6,border=4,width=(code.size+border*2)*scale;int[] pixels=new int[width*width];for(int y=0;y<width;y++)for(int x=0;x<width;x++)pixels[y*width+x]=code.getModule(x/scale-border,y/scale-border)?Color.BLACK:Color.WHITE;Bitmap next=Bitmap.createBitmap(pixels,width,width,Bitmap.Config.ARGB_8888);qrImage.setImageBitmap(next);if(qrBitmap!=null)qrBitmap.recycle();qrBitmap=next;lastUrl=url;}}
        try{
            JSONObject detail=new JSONObject(AmbientService.status());boolean enabled=detail.optBoolean("enabled",false),running=detail.optBoolean("running",false);syncButton.setText(enabled?"关闭画面同步":"开启画面同步");syncButton.setSelected(enabled);
            status.setText(detail.optString("state",ConfigServer.isLive()?"配置后台已开启":"配置后台已关闭"));
            outputStatus.setText("同步请求："+(enabled?"开启":"关闭")+" · 实际输出："+(running?"运行中":enabled?"已暂停":"已停止"));
            boolean interactive=detail.optBoolean("displayInteractive",true);String screen=displayLabel(detail.optString("displayState",interactive?"on":"off"));JSONObject source=detail.optJSONObject("inputSource");String input=source!=null&&source.optBoolean("known",false)?source.optString("label","未识别"):"未识别";displayStatus.setText("显示："+screen+" · 输入："+input);
            String suspended=detail.optString("suspendedReason","");boolean paused=enabled&&!suspended.isEmpty();suspendedStatus.setVisibility(paused?View.VISIBLE:View.GONE);suspendedStatus.setText(paused?"暂停原因："+suspensionLabel(suspended):"");
            boolean guarded=detail.optBoolean("guardConnected",false),registered=detail.optBoolean("foregroundRegistered",false);String mode=detail.optString("keepAliveMode","limited");String protection=!ConfigServer.isLive()?"后台保护：未运行":guarded&&"guard".equals(mode)?"后台保护：系统守护已连接":registered?"后台保护：前台服务已登记":"后台保护：后台运行受限";String warning=detail.optString("keepAliveWarning","");keepAlive.setText(protection+(warning.isEmpty()?"":"\n"+warning));keepAlive.setTextColor(guarded||registered?accent:warningColor);
        }catch(Exception ignored){status.setText(ConfigServer.isLive()?"配置后台已开启":"配置后台尚未开启");keepAlive.setText("后台保护状态暂不可用");}
        scheduleFit();
    }
    private static String displayLabel(String state){switch(state){case "on":case "vr":case "on_suspend":return "亮屏";case "off":return "休眠";case "doze":case "doze_suspend":return "待机";default:return "待确认";}}
    private static String suspensionLabel(String reason){switch(reason){case "display_sleep":return "显示设备休眠";case "input_unknown":return "当前接口未识别";case "input_not_allowed":return "当前接口未开启同步";case "wled_power_pending":return "正在确认灯带电源";case "wled_power_error":return "灯带电源尚未确认";case "config_invalid":return "请检查已应用的配置";case "capture_error":return "暂时无法读取画面";default:return "正在准备同步";}}
    private void maybeRestoreServer(){if(getSharedPreferences("tvwled",MODE_PRIVATE).getBoolean("serverEnabled",false)&&!ConfigServer.isLive())ensureService();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(permission!=null)maybeRestoreServer();}
    @Override protected void onResume(){super.onResume();visible=true;if(rainbowView!=null)rainbowView.start();if(permission!=null)maybeRestoreServer();refresh.run();scheduleFit();}
    @Override protected void onPause(){visible=false;if(rainbowView!=null)rainbowView.stop();main.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){if(rainbowView!=null)rainbowView.stop();main.removeCallbacksAndMessages(null);if(qrBitmap!=null)qrBitmap.recycle();super.onDestroy();}
}
