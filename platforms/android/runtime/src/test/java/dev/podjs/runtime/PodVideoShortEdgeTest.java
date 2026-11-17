package dev.podjs.runtime;

import org.junit.Test;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class PodVideoShortEdgeTest {
    @Test public void shortEdgeUsesBothEncodedDimensions() {
        assertTrue(PodVideoShortEdge.exceeds(720, 3840, 2160));
        assertTrue(PodVideoShortEdge.exceeds(720, 1080, 1920));
        assertTrue(PodVideoShortEdge.exceeds(720, 721, 1280));
        assertFalse(PodVideoShortEdge.exceeds(720, 1280, 720));
        assertFalse(PodVideoShortEdge.exceeds(720, 0, 1920));
        assertFalse(PodVideoShortEdge.exceeds(0, 3840, 2160));
    }

    @Test public void mp4TrackDimensionsAreAvailableBeforeSamples() {
        assertTrue(PodMediaPrefixSamples.exceedsShortEdge(movie(1080,1920),720));
        assertTrue(PodMediaPrefixSamples.exceedsShortEdge(movie(3840,2160),720));
        assertFalse(PodMediaPrefixSamples.exceedsShortEdge(movie(1280,720),720));
        assertFalse(PodMediaPrefixSamples.exceedsShortEdge(new byte[0],720));
    }

    static byte[] movie(int width,int height) {
        byte[] handler=new byte[12];System.arraycopy(new byte[]{'v','i','d','e'},0,handler,8,4);
        byte[] visual=new byte[78];ByteBuffer.wrap(visual).putShort(24,(short)width).putShort(26,(short)height);
        return box("moov",box("trak",box("mdia",box("hdlr",handler),
            box("minf",box("stbl",box("stsd",ByteBuffer.allocate(8).putInt(4,1).array(),box("avc1",visual)))))));
    }

    static byte[] box(String type,byte[]... children) {
        int size=8;for(byte[] child:children)size+=child.length;
        ByteBuffer b=ByteBuffer.allocate(size).putInt(size).put(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for(byte[] child:children)b.put(child);
        return b.array();
    }
}
