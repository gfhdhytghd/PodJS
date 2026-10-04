package dev.podjs.runtime;

import org.junit.Test;
import static org.junit.Assert.*;

/** Pure path checks can also run on the JVM without invoking Android graphics. */
public class PodFontAssetNamesTest {
    @Test public void namesStayInsideThePackagedAssetNamespace() {
        for(String name:new String[]{"font.ttf","fonts/body.ttc","fonts/Body-v2_0.ttf","1/2/3.bin"})
            assertEquals("pod-assets/"+name,PodFontServices.packagedAssetPath(name));
    }
    @Test public void traversalAliasesAndNonportableNamesAreRejected() {
        for(String name:new String[]{null,"","/body.ttf","../body.ttf","fonts/../body.ttf","./font.ttf","fonts//font.ttf","fonts/./font.ttf","fonts/","fonts\\font.ttf","C:/font.ttf","a\u0000.ttf","a\n.ttf","font .ttf","font.ttf.","CON","fonts/prn.ttf","AUX.otf","COM1.ttf","LPT9.otf","fonts/.hidden.ttf","字体.ttf"}){
            try{PodFontServices.packagedAssetPath(name);fail("Accepted unsafe name: "+name);}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void nameAndComponentLengthsAreBounded() {
        StringBuilder part=new StringBuilder();for(int i=0;i<255;i++)part.append('a');
        assertEquals("pod-assets/"+part,PodFontServices.packagedAssetPath(part.toString()));
        try{PodFontServices.packagedAssetPath(part+"a");fail("Accepted 256-byte component");}catch(IllegalArgumentException expected){}
        try{PodFontServices.packagedAssetPath(part+"/"+part+"/a");fail("Accepted 513-byte name");}catch(IllegalArgumentException expected){}
    }
}
