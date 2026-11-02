package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** One bounded copy of the outgoing decoded surface. No covers or network IO. */
final class PodVideoLastFrame implements AutoCloseable {
    private Bitmap pixels;
    private ImageView view;
    private Object owner;
    private long generation;
    boolean present(){return view!=null;}
    boolean belongsTo(Object candidate,long revision){return owner==candidate&&generation==revision;}
    long bytes(){return pixels==null?0:pixels.getAllocationByteCount();}
    void capture(Object candidate,long revision,TextureView texture,ViewGroup parent) {
        if(belongsTo(candidate,revision)||texture==null||!texture.isAvailable()||texture.getWidth()<1||texture.getHeight()<1)return;
        double scale=Math.min(1,466d/Math.max(texture.getWidth(),texture.getHeight()));
        Bitmap next=texture.getBitmap(Math.max(1,(int)(texture.getWidth()*scale)),Math.max(1,(int)(texture.getHeight()*scale)));
        if(next==null)return;
        close();pixels=next;owner=candidate;generation=revision;
        view=new ImageView(parent.getContext());view.setImageBitmap(next);view.setScaleType(ImageView.ScaleType.FIT_XY);
        view.setClickable(false);view.setFocusable(false);
        if(texture.getClipToOutline()&&texture.getOutlineProvider()!=null){
            android.graphics.Outline frozen=new android.graphics.Outline();texture.getOutlineProvider().getOutline(texture,frozen);
            view.setOutlineProvider(new android.view.ViewOutlineProvider(){@Override public void getOutline(android.view.View v,android.graphics.Outline out){out.set(frozen);}});
            view.setClipToOutline(true);
        }
        parent.addView(view,new FrameLayout.LayoutParams((FrameLayout.LayoutParams)texture.getLayoutParams()));
    }
    void raise(){if(view!=null)view.bringToFront();}
    @Override public void close(){
        if(view!=null){view.setImageDrawable(null);if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);view=null;}
        if(pixels!=null){pixels.recycle();pixels=null;}owner=null;
    }
}
