package dev.podjs.runtime;

/** Native equivalent of watch-ui safeTopBlock/rectInsideCircle, in viewport pixels. */
final class PodRecoveryRoundLayout {
    static final class Rect {
        final int x,y,width,height;
        Rect(int x,int y,int width,int height){this.x=x;this.y=y;this.width=width;this.height=height;}
    }
    interface Measure {int height(int width);}
    static boolean rectInsideCircle(Rect rect,int width,int height,double margin) {
        double cx=width/2d,cy=height/2d,radius=Math.min(width,height)/2d-margin;
        for(int x:new int[]{rect.x,rect.x+rect.width})for(int y:new int[]{rect.y,rect.y+rect.height})
            if(Math.hypot(x-cx,y-cy)>radius+1e-6)return false;
        return true;
    }
    static Rect safeTopBlock(int width,int height,Measure measure,int minTop,int maxWidth,int bottom,int minWidth) {
        return safeTopBlock(width,height,measure,minTop,maxWidth,bottom,minWidth,2);
    }
    static Rect safeTopBlock(int width,int height,Measure measure,int minTop,int maxWidth,int bottom,int minWidth,int margin) {
        double cy=height/2d,inner=Math.min(width,height)/2d-margin;
        if(inner<=0)return null;
        Rect best=null;
        for(int y=(int)Math.ceil(Math.max(minTop,cy-inner));y<Math.min(bottom,cy+inner);y++) {
            if(best!=null&&y>=best.y+best.height)break;
            int w=Math.min(maxWidth,chord(inner,cy-y));
            while(w>=Math.max(1,minWidth)){
                if((w&1)!=(width&1))w--;
                if(w<Math.max(1,minWidth))break;
                Rect rect=new Rect((width-w)/2,y,w,measure.height(w));
                if(y+rect.height>bottom)break;
                if(rectInsideCircle(rect,width,height,margin)){
                    if(best==null||y+rect.height<best.y+best.height)best=rect;
                    break;
                }
                int narrowed=chord(inner,y+rect.height-cy);
                if(narrowed>=w)break;
                w=narrowed;
            }
        }
        return best;
    }
    private static int chord(double radius,double distance) {
        return (int)Math.floor(2*Math.sqrt(Math.max(0,radius*radius-distance*distance)));
    }
    /** The safe vertical span for a fixed horizontal interval. */
    static Rect verticalSpan(int width,int height,int left,int right,double margin) {
        double radius=Math.min(width,height)/2d-margin;
        double edge=Math.max(Math.abs(left-width/2d),Math.abs(right-width/2d));
        if(radius<=edge)return new Rect(left,height/2,right-left,0);
        double half=Math.sqrt(radius*radius-edge*edge);
        int top=(int)Math.ceil(height/2d-half), bottom=(int)Math.floor(height/2d+half);
        return new Rect(left,top,right-left,Math.max(0,bottom-top));
    }
    static Rect button(int width,int height,double top,double buttonHeight,double maxWidth) {
        double diameter=Math.min(width,height),cy=height/2d,inner=diameter/2d-10*diameter/233;
        int y=(int)Math.round((height-diameter)/2+top*diameter/233),h=(int)Math.round(buttonHeight*diameter/233);
        double far=Math.max(Math.abs(cy-y),Math.abs(cy-y-h));
        int w=(int)Math.floor(Math.min(maxWidth*diameter/233,2*Math.sqrt(Math.max(0,inner*inner-far*far))))-2;
        return new Rect((width-w)/2,y,Math.max(1,w),h);
    }
}
