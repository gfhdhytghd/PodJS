package dev.podjs.runtime;

import android.graphics.Matrix;

/** Translation of a pager page does not change its content geometry. Retain the
 * matrix so a paused TextureView does not rebuild its display list each frame. */
final class PodVideoTransformCache {
    private float w,h,vw,vh,rotation,scaleRotation,panX,panY,factor;
    private String mode;
    private Matrix matrix;
    Matrix get(float w,float h,float vw,float vh,float rotation,float scaleRotation,String mode,float panX,float panY) {
        boolean scaleChanged=matrix==null||this.w!=w||this.h!=h||this.vw!=vw||this.vh!=vh
                ||this.scaleRotation!=scaleRotation||!mode.equals(this.mode);
        // Compute destination scale once. Animated angle and pan do not affect it.
        if(scaleChanged)factor=PodVideoServices.videoScale(w,h,vw,vh,scaleRotation,mode);
        if(scaleChanged||this.rotation!=rotation||this.panX!=panX||this.panY!=panY) {
            this.w=w;this.h=h;this.vw=vw;this.vh=vh;this.rotation=rotation;this.scaleRotation=scaleRotation;
            this.mode=mode;this.panX=panX;this.panY=panY;
            matrix=PodVideoServices.videoTransformWithScale(w,h,vw,vh,rotation,factor,panX,panY);
        }
        return matrix;
    }
}
