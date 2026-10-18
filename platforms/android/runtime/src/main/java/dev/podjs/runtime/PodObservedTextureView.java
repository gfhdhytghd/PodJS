package dev.podjs.runtime;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.TextureView;

/** Keeps the player's listener ownership and destruction result unchanged. */
class PodObservedTextureView extends TextureView {
    private SurfaceTextureListener delegate;
    private final java.util.function.Consumer<SurfaceTexture> updated;
    PodObservedTextureView(Context context, java.util.function.Consumer<SurfaceTexture> updated){super(context);this.updated=updated;}
    @Override public SurfaceTextureListener getSurfaceTextureListener(){return delegate;}
    @Override public void setSurfaceTextureListener(SurfaceTextureListener listener){
        delegate=listener;
        if(listener==null){super.setSurfaceTextureListener(null);return;}
        super.setSurfaceTextureListener(new SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height){listener.onSurfaceTextureAvailable(texture,width,height);}
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height){listener.onSurfaceTextureSizeChanged(texture,width,height);}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){return listener.onSurfaceTextureDestroyed(texture);}
            public void onSurfaceTextureUpdated(SurfaceTexture texture){listener.onSurfaceTextureUpdated(texture);try{updated.accept(texture);}catch(RuntimeException ignored){}}
        });
    }
}
