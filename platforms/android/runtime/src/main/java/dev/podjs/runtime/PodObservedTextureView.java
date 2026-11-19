package dev.podjs.runtime;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.view.TextureView;

/** Keeps the player's listener ownership and destruction result unchanged. */
class PodObservedTextureView extends TextureView {
    private SurfaceTextureListener delegate;
    private SurfaceTexture surface;
    private long outputRevision, queuedRevision=-1, queuedGeneration;
    private final java.util.function.BiConsumer<PodObservedTextureView,SurfaceTexture> updated;
    PodObservedTextureView(Context context, java.util.function.BiConsumer<PodObservedTextureView,SurfaceTexture> updated){super(context);this.updated=updated;}
    long outputRevision(){return outputRevision;}
    /** Media3 may replace the BufferQueue or its listener while retaining the view. */
    void outputChanged(){}
    private void replaceOutput(SurfaceTexture next){surface=next;outputRevision++;outputChanged();}
    /** Traversal cannot change layers. Several updates from the same output can share its pending work. */
    void afterUpdate(Handler main,long generation,Runnable work){
        final long revision=outputRevision;
        if(queuedRevision==revision&&queuedGeneration==generation)return;
        queuedRevision=revision;queuedGeneration=generation;
        main.post(()->{
            if(queuedRevision!=revision||queuedGeneration!=generation)return;
            queuedRevision=-1;
            if(outputRevision==revision)work.run();
        });
    }
    @Override public SurfaceTextureListener getSurfaceTextureListener(){return delegate;}
    @Override public void setSurfaceTextureListener(SurfaceTextureListener listener){
        if(delegate!=listener)replaceOutput(listener==null?null:getSurfaceTexture());
        delegate=listener;
        if(listener==null){super.setSurfaceTextureListener(null);return;}
        super.setSurfaceTextureListener(new SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height){if(delegate==listener&&surface!=texture)replaceOutput(texture);listener.onSurfaceTextureAvailable(texture,width,height);}
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height){listener.onSurfaceTextureSizeChanged(texture,width,height);}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){if(delegate==listener&&surface==texture)replaceOutput(null);return listener.onSurfaceTextureDestroyed(texture);}
            public void onSurfaceTextureUpdated(SurfaceTexture texture){
                listener.onSurfaceTextureUpdated(texture);
                if(delegate!=listener)return;
                if(surface!=texture){
                    // TextureView.setSurfaceTexture can install a buffer without an available callback.
                    if(texture==null||getSurfaceTexture()!=texture)return;
                    replaceOutput(texture);
                }
                try{updated.accept(PodObservedTextureView.this,texture);}catch(RuntimeException ignored){}
            }
        });
    }
}
