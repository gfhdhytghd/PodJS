package dev.podjs.runtime;

import android.view.MotionEvent;

/** Preserve a large terminal displacement when Android supplied no usable MOVE.
 * The existing native snapshot queue delivers the endpoint before the UP edge. */
final class PodTouchReleasePolicy {
    private int pointerId=-1;
    private float startX,startY;
    private boolean single,crossed;

    void reset(){pointerId=-1;single=false;crossed=false;}

    boolean observe(int action,int count,int id,float x,float y,float slop){
        boolean valid=count==1&&id>=0&&Float.isFinite(x)&&Float.isFinite(y)&&Float.isFinite(slop)&&slop>0;
        if(action==MotionEvent.ACTION_DOWN){reset();if(valid){single=true;pointerId=id;startX=x;startY=y;}return false;}
        if(!single||!valid||id!=pointerId){reset();return false;}
        boolean travelled=Math.abs(x-startX)>slop||Math.abs(y-startY)>slop;
        if(action==MotionEvent.ACTION_MOVE){crossed|=travelled;return false;}
        if(action==MotionEvent.ACTION_UP){boolean endpoint=!crossed&&travelled;reset();return endpoint;}
        reset();return false;
    }
}
