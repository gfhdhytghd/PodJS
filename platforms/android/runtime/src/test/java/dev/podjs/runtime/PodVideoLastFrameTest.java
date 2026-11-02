package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.TextureView;
import android.widget.FrameLayout;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodVideoLastFrameTest {
    @Test public void retainsOneBoundedDecodedCopyAndRecyclesOnReplacementAndClose(){
        Context context=ApplicationProvider.getApplicationContext();FrameLayout parent=new FrameLayout(context);Bitmap[] copied={null};int[] copies={0};
        TextureView texture=new TextureView(context){@Override public boolean isAvailable(){return true;}@Override public Bitmap getBitmap(int w,int h){copies[0]++;return copied[0]=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);}};
        texture.setLayoutParams(new FrameLayout.LayoutParams(1080,1920));texture.layout(0,0,1080,1920);
        Object owner=new Object();PodVideoLastFrame held=new PodVideoLastFrame();held.capture(owner,1,texture,parent);Bitmap first=copied[0];
        assertTrue(held.present());assertTrue(held.belongsTo(owner,1));assertTrue(held.bytes()<=466L*466*4);assertEquals(1,parent.getChildCount());
        held.capture(owner,1,texture,parent);assertEquals(1,copies[0]);held.capture(owner,2,texture,parent);
        assertTrue(first.isRecycled());assertEquals(1,parent.getChildCount());assertTrue(held.belongsTo(owner,2));
        Bitmap second=copied[0];held.close();assertTrue(second.isRecycled());assertEquals(0,held.bytes());assertEquals(0,parent.getChildCount());
    }
}
