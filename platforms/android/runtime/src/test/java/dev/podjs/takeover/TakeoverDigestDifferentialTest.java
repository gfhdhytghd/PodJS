package dev.podjs.takeover;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class TakeoverDigestDifferentialTest {
    @Test public void frozenBaselineMatchesBytesAndErrors()throws Exception {DigestDifferential.verify();}
    @Test public void hostRobolectricMicrobenchmark()throws Exception {DigestDifferential.benchmark("Robolectric-sdk33-host");}
}
