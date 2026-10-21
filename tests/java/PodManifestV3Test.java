import dev.podjs.runtime.PodCloudCrypto;
import java.util.*;
import java.nio.charset.StandardCharsets;
public class PodManifestV3Test {
 public static void main(String[] a)throws Exception{
  byte[] key=HexFormat.of().parseHex(a[0]),salt=HexFormat.of().parseHex(a[1]),nonce=HexFormat.of().parseHex(a[2]);
  byte[] derived=PodCloudCrypto.hkdf(key,salt,a[3].getBytes(StandardCharsets.UTF_8));
  if(!HexFormat.of().formatHex(derived).equals(a[4]))throw new AssertionError("HKDF");
  byte[] sealed=PodCloudCrypto.aes(true,derived,nonce,a[6].getBytes(StandardCharsets.UTF_8),a[5].getBytes(StandardCharsets.UTF_8));
  if(!HexFormat.of().formatHex(sealed).equals(a[7]))throw new AssertionError("AES");
 }
}
