package dev.podjs.runtime;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.json.JSONObject;

/** Real Android text fields over PocketJS-drawn editor chrome, owned by the guest lifetime. */
final class PodInlineTextInput {
    interface Events { void send(String event); }
    private final Context context;
    private final Events events;
    private FrameLayout parent;
    private final Map<Integer,EditText> fields=new HashMap<>();
    private final Map<String,DocumentGroup> documents=new HashMap<>();
    private final Map<Integer,String> fieldDocuments=new HashMap<>();
    private final java.util.Set<Integer> updating=new java.util.HashSet<>();

    private static final class DocumentGroup {
        final ScrollView scroll;
        final FrameLayout content;
        final HashSet<Integer> sessions=new HashSet<>();
        final CrownAccelerator crown=new CrownAccelerator();
        final double top;
        final double left,width,overhang;
        final int viewportHeight;
        EditText title,body;
        View unfocusedCaret;
        DocumentGroup(ScrollView scroll,FrameLayout content,double left,double top,double width,double overhang,int viewportHeight){
            this.scroll=scroll;this.content=content;this.left=left;this.top=top;this.width=width;this.overhang=overhang;this.viewportHeight=viewportHeight;
        }
    }
    /** Same detent-to-code-point curve as the native NoteCursorCrownAccelerator. */
    static final class CrownAccelerator {
        private double fractional;
        private long lastUptime=-1;
        private int lastDirection;
        int consume(float delta,long uptime){
            if(delta==0f)return 0;
            int direction=delta>0?1:-1;
            if(direction!=lastDirection){fractional=0;lastUptime=-1;}
            double magnitude=Math.abs((double)delta);
            double interval=lastUptime<0?Double.POSITIVE_INFINITY:Math.max(0L,uptime-lastUptime)/magnitude;
            double progress=Math.max(0,Math.min(1,(250-interval)/200));
            double smooth=progress*progress*(3-2*progress);
            double perDetent=(0.1+0.9*smooth)*0.2;
            fractional+=direction*magnitude*perDetent;
            int whole=(int)Math.floor(Math.abs(fractional)+1e-9);
            int steps=direction*whole;
            fractional-=steps;
            lastUptime=uptime;lastDirection=direction;
            return steps;
        }
    }

    PodInlineTextInput(Context context, Events events) { this.context=context; this.events=events; }
    void attach(ViewGroup group) { parent=group instanceof FrameLayout ? (FrameLayout)group : null; }

    void open(int id,JSONObject args) {
        if(parent==null || !(context instanceof Activity))throw new IllegalStateException("Inline input requires an Activity FrameLayout");
        double x=args.optDouble("x",Double.NaN),y=args.optDouble("y",Double.NaN);
        double width=args.optDouble("width",Double.NaN),height=args.optDouble("height",Double.NaN);
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(width)||!Double.isFinite(height)
                ||x<0||y<0||width<1||height<1||x>4096||y>4096||width>4096||height>4096)
            throw new IllegalArgumentException("Invalid inline input bounds");
        double fontSize=args.optDouble("fontSizeSp",17);
        if(!Double.isFinite(fontSize)||fontSize<8||fontSize>32)throw new IllegalArgumentException("Invalid inline input font size");
        if(!args.optBoolean("keepExisting",false))closeFields();
        boolean multiline=args.optBoolean("multiline",false);
        EditText input=new EditText(context);
        input.setSingleLine(!multiline);
        if(multiline)input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        else if("uri".equals(args.optString("inputType","text")))input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(multiline?EditorInfo.IME_ACTION_NONE:EditorInfo.IME_ACTION_SEARCH);
        input.setTextSize((float)fontSize);
        if(args.has("includeFontPadding"))input.setIncludeFontPadding(args.optBoolean("includeFontPadding",true));
        if(args.has("fontAsset")||args.has("fontWeight")){
            int weight=args.optInt("fontWeight",400);
            if(weight<100||weight>900)throw new IllegalArgumentException("Invalid inline input font weight");
            try{input.setTypeface(PodFontServices.resolve(context,args,weight,false));}
            catch(IOException invalidFont){throw new IllegalArgumentException("Invalid inline input font asset",invalidFont);}
        }else input.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        input.setTextColor(0xfff5f5f5);
        input.setHintTextColor(0xffb6b6b6);
        input.setHint(args.optString("hint",""));
        input.setBackgroundColor(Color.TRANSPARENT);
        String alignment=args.optString("textAlign","start");
        int horizontalGravity="center".equals(alignment)?Gravity.CENTER_HORIZONTAL:"end".equals(alignment)?Gravity.RIGHT:Gravity.LEFT;
        input.setGravity((multiline?Gravity.TOP:Gravity.CENTER_VERTICAL)|horizontalGravity);
        int horizontalPadding=args.has("horizontalPaddingDp")?dp(Math.max(0,Math.min(64,args.optDouble("horizontalPaddingDp",16)))):-1;
        int verticalPadding=args.has("verticalPaddingDp")?dp(Math.max(0,Math.min(64,args.optDouble("verticalPaddingDp",8)))):multiline?dp(8):0;
        input.setPadding(horizontalPadding>=0?horizontalPadding:"center".equals(alignment)?0:dp(16),verticalPadding,horizontalPadding>=0?horizontalPadding:"center".equals(alignment)?0:dp(40),verticalPadding);
        input.setText(args.optString("value",""));
        int selection=args.has("selectionStart")?args.optInt("selectionStart",input.length()):input.length();
        input.setSelection(Math.max(0,Math.min(input.length(),selection)));
        JSONObject document=args.optJSONObject("document");
        if(document==null){
            FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(dp(width),dp(height));
            layout.leftMargin=dp(x);layout.topMargin=dp(y);
            parent.addView(input,layout);
        }else{
            attachDocumentField(id,input,args,document,x,y,width,height);
        }
        fields.put(id,input);
        input.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){}
            public void afterTextChanged(Editable value){
                if(fields.get(id)==input&&!updating.contains(id))emit("input.inline.change",id,value.toString());
                String key=fieldDocuments.get(id);
                if(key!=null){DocumentGroup group=documents.get(key);if(group!=null&&group.body==input)input.post(()->positionUnfocusedCaret(group));}
            }
        });
        input.setOnEditorActionListener((view,action,event)->{
            if(action!=EditorInfo.IME_ACTION_SEARCH)return false;
            if(fields.get(id)==input)emit("input.inline.submit",id,input.getText().toString());
            return true;
        });
        if(args.optBoolean("autoFocus",true)) {
            input.requestFocus();
            input.post(()->{if(fields.get(id)==input){InputMethodManager imm=(InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);if(imm!=null)imm.showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);}});
        } else {
            // Keep the document visible until the user taps a field. A bare EditText can
            // otherwise take focus when it becomes the first focusable child.
            parent.setFocusableInTouchMode(true);
            parent.requestFocus();
        }
    }
    private void attachDocumentField(int id,EditText input,JSONObject args,JSONObject spec,double x,double y,double width,double height){
        String key=spec.optString("id","");
        String role=spec.optString("role","");
        double viewport=spec.optDouble("viewportHeight",Double.NaN);
        if(key.isEmpty()||key.length()>128||!Double.isFinite(viewport)||viewport<56||viewport>4096
                ||!("title".equals(role)||"body".equals(role)))throw new IllegalArgumentException("Invalid inline document");
        DocumentGroup group=documents.get(key);
        if("title".equals(role)){
            if(group!=null)throw new IllegalArgumentException("Inline document already opened");
            double overhang=spec.optDouble("viewportOverhangDp",0);
            if(!Double.isFinite(overhang)||overhang<0||overhang>4||x<overhang)throw new IllegalArgumentException("Invalid inline document overhang");
            ScrollView scroll=new ScrollView(context);
            scroll.setFillViewport(true);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setClipChildren(false);
            scroll.setClipToPadding(false);
            scroll.setBackgroundColor(Color.TRANSPARENT);
            FrameLayout content=new FrameLayout(context);
            content.setClipChildren(false);
            content.setPadding(0,0,0,dp(64));
            scroll.addView(content,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
            FrameLayout.LayoutParams frame=new FrameLayout.LayoutParams(dp(width+2*overhang),dp(viewport));
            frame.leftMargin=dp(x-overhang);frame.topMargin=dp(y);
            parent.addView(scroll,frame);
            group=new DocumentGroup(scroll,content,x,y,width,overhang,dp(viewport));
            documents.put(key,group);
            String metadata=spec.optString("metadata","");
            if(!metadata.isEmpty()){
                TextView label=new TextView(context);
                label.setText(metadata);
                label.setTextSize(12);
                label.setTextColor(0xffb6b6b6);
                label.setIncludeFontPadding(false);
                FrameLayout.LayoutParams metaLayout=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
                metaLayout.leftMargin=dp(overhang);metaLayout.topMargin=dp(40);
                content.addView(label,metaLayout);
            }
        }else if(group==null)throw new IllegalArgumentException("Inline document title missing");
        if(y<group.top||y-group.top>256||x<group.left||x+width>group.left+group.width+1e-6)
            throw new IllegalArgumentException("Inline document field outside viewport");
        FrameLayout.LayoutParams fieldLayout=new FrameLayout.LayoutParams(dp(width),
                "body".equals(role)?ViewGroup.LayoutParams.WRAP_CONTENT:dp(height));
        fieldLayout.leftMargin=dp(x-group.left+group.overhang);
        fieldLayout.topMargin=dp(y-group.top);
        if("body".equals(role)){
            input.setMinHeight(Math.max(dp(height),group.viewportHeight));
            input.setVerticalScrollBarEnabled(false);
            input.setOverScrollMode(View.OVER_SCROLL_NEVER);
        }
        group.content.addView(input,fieldLayout);
        if("title".equals(role))group.title=input;else group.body=input;
        group.sessions.add(id);
        fieldDocuments.put(id,key);
        DocumentGroup activeGroup=group;
        input.setOnFocusChangeListener((view,focused)->positionUnfocusedCaret(activeGroup));
        if("body".equals(role)){
            installDocumentCursorSwipe(group,input);
            String caretColor=spec.optString("unfocusedCaretColor","");
            if(!caretColor.isEmpty()){
                View caret=new View(context);
                caret.setBackgroundColor(Color.parseColor(caretColor));
                caret.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                caret.setClickable(false);
                group.unfocusedCaret=caret;
                contentAddCaret(group,caret);
                input.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->positionUnfocusedCaret(activeGroup));
                input.post(()->positionUnfocusedCaret(activeGroup));
            }
        }
        if("body".equals(role)&&args.has("selectionStart")){
            DocumentGroup targetGroup=group;
            input.post(()->centerDocumentSelection(targetGroup,input));
        }
    }
    private void contentAddCaret(DocumentGroup group,View caret){
        group.content.addView(caret,new FrameLayout.LayoutParams(dp(2),dp(20)));
    }
    private void positionUnfocusedCaret(DocumentGroup group){
        View caret=group.unfocusedCaret;EditText input=group.body;
        if(caret==null||input==null||input.getLayout()==null)return;
        if(input.hasFocus()||(group.title!=null&&group.title.hasFocus())){caret.setVisibility(View.INVISIBLE);return;}
        android.text.Layout layout=input.getLayout();
        int selection=Math.max(0,Math.min(input.length(),input.getSelectionStart()));
        int line=layout.getLineForOffset(selection);
        FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)caret.getLayoutParams();
        params.leftMargin=input.getLeft()+input.getCompoundPaddingLeft()+Math.round(layout.getPrimaryHorizontal(selection))-dp(1);
        params.topMargin=input.getTop()+input.getCompoundPaddingTop()+layout.getLineTop(line);
        caret.setLayoutParams(params);
        caret.setVisibility(View.VISIBLE);
    }
    private void centerDocumentSelection(DocumentGroup group,EditText input){
        if(input.getParent()!=group.content||input.getLayout()==null)return;
        int line=input.getLayout().getLineForOffset(input.getSelectionStart());
        int center=input.getTop()+(input.getLayout().getLineTop(line)+input.getLayout().getLineBottom(line))/2;
        group.scroll.scrollTo(0,Math.max(0,center-group.scroll.getHeight()/2));
    }
    private void installDocumentCursorSwipe(DocumentGroup group,EditText input){
        final int slop=ViewConfiguration.get(context).getScaledTouchSlop();
        final int step=dp(24);
        input.setOnTouchListener(new View.OnTouchListener(){
            float previousY,totalY,accumulated;
            boolean dragging;
            @Override public boolean onTouch(View view,MotionEvent event){
                if(input.hasFocus())return false;
                switch(event.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        previousY=event.getRawY();totalY=0;accumulated=0;dragging=false;
                        input.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float delta=event.getRawY()-previousY;
                        previousY=event.getRawY();totalY+=delta;
                        if(!dragging&&Math.abs(totalY)>slop)dragging=true;
                        if(dragging){
                            accumulated-=delta;
                            int steps=(int)(accumulated/step);
                            if(steps!=0){
                                accumulated-=steps*step;
                                moveDocumentCursor(group,input,steps,step);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if(!dragging){
                            input.requestFocus();
                            InputMethodManager imm=(InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
                            if(imm!=null)imm.showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:return true;
                    default:return true;
                }
            }
        });
    }
    private void moveDocumentCursor(DocumentGroup group,EditText input,int steps,int stepPx){
        android.text.Layout layout=input.getLayout();
        if(layout==null)return;
        int direction=steps>0?1:-1;
        int next=input.getSelectionStart();
        int boundarySteps=0;
        for(int index=0;index<Math.abs(steps);index++){
            int line=layout.getLineForOffset(next);
            int target=line+direction;
            if(target<0){boundarySteps++;continue;}
            if(target>=layout.getLineCount())continue;
            float horizontal=layout.getPrimaryHorizontal(next);
            next=layout.getOffsetForHorizontal(target,horizontal);
        }
        if(next!=input.getSelectionStart()){
            input.setSelection(next);
            ensureDocumentCursorVisible(group,input);
            positionUnfocusedCaret(group);
        }
        if(boundarySteps>0)group.scroll.scrollBy(0,-boundarySteps*stepPx);
    }
    private void ensureDocumentCursorVisible(DocumentGroup group,EditText input){
        android.text.Layout layout=input.getLayout();
        if(layout==null)return;
        int line=layout.getLineForOffset(input.getSelectionStart());
        int top=input.getTop()+layout.getLineTop(line);
        int bottom=input.getTop()+layout.getLineBottom(line);
        int current=group.scroll.getScrollY();
        int visibleTop=current+dp(22),visibleBottom=current+group.scroll.getHeight()-dp(8);
        if(top<visibleTop)group.scroll.scrollTo(0,Math.max(0,top-dp(22)));
        else if(bottom>visibleBottom)group.scroll.scrollTo(0,bottom-group.scroll.getHeight()+dp(8));
    }
    /** Activity-level interception keeps the crown authoritative even when an EditText has focus. */
    boolean handleCrownScroll(MotionEvent event){
        if(documents.isEmpty()||event.getActionMasked()!=MotionEvent.ACTION_SCROLL)return false;
        float delta=event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        if(delta==0f||!Float.isFinite(delta))return false;
        DocumentGroup group=documents.values().iterator().next();
        if(group.body==null)return false;
        if(group.title!=null&&group.title.hasFocus()){
            moveCodePoint(group.title,delta<0?1:-1);
            return true;
        }
        int steps=group.crown.consume(-delta,event.getEventTime());
        if(steps!=0)moveDocumentCodePoints(group,group.body,steps);
        return true;
    }
    private static boolean moveCodePoint(EditText input,int direction){
        int current=direction>0?Math.max(input.getSelectionStart(),input.getSelectionEnd())
            :Math.min(input.getSelectionStart(),input.getSelectionEnd());
        if(direction>0&&current>=input.length()||direction<0&&current<=0)return false;
        int next=Character.offsetByCodePoints(input.getText(),current,direction);
        input.setSelection(next);
        return true;
    }
    private void moveDocumentCodePoints(DocumentGroup group,EditText input,int steps){
        int direction=steps>0?1:-1;
        int boundary=0;
        for(int index=0;index<Math.abs(steps);index++){
            if(!moveCodePoint(input,direction)&&direction<0)boundary++;
        }
        ensureDocumentCursorVisible(group,input);
        positionUnfocusedCaret(group);
        if(boundary>0)group.scroll.scrollBy(0,-boundary*dp(24));
    }
    void setValue(int id,String value){
        EditText input=fields.get(id);
        if(input==null)throw new IllegalArgumentException("Unknown inline input session");
        if(input.getText().toString().equals(value))return;
        updating.add(id);
        try {
            input.setText(value);
            input.setSelection(input.length());
        } finally {updating.remove(id);}
    }
    void close(int id){
        EditText old=fields.remove(id);
        if(old==null)return;
        InputMethodManager imm=(InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if(old.hasFocus()&&imm!=null)imm.hideSoftInputFromWindow(old.getWindowToken(),0);
        if(old.getParent() instanceof ViewGroup)((ViewGroup)old.getParent()).removeView(old);
        String key=fieldDocuments.remove(id);
        if(key!=null){
            DocumentGroup group=documents.get(key);
            if(group!=null){
                if(group.title==old)group.title=null;
                if(group.body==old){
                    group.body=null;
                    if(group.unfocusedCaret!=null){group.content.removeView(group.unfocusedCaret);group.unfocusedCaret=null;}
                }
                group.sessions.remove(id);
                if(group.sessions.isEmpty()){
                    documents.remove(key);
                    if(group.scroll.getParent() instanceof ViewGroup)((ViewGroup)group.scroll.getParent()).removeView(group.scroll);
                }
            }
        }
    }
    private void closeFields(){for(int id:new ArrayList<>(fields.keySet()))close(id);}
    void closeAll(){closeFields();parent=null;}
    private int dp(double value){return Math.round((float)value*context.getResources().getDisplayMetrics().density);}
    private void emit(String type,int id,String value){
        try{events.send(new JSONObject().put("t",type).put("session",id).put("value",value).toString());}
        catch(Exception error){android.util.Log.e("PodJS","Inline input event failed",error);}
    }
}
