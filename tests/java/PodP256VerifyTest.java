import dev.podjs.runtime.PodCloudCrypto;
import java.util.*;
import java.security.*;
import java.security.spec.*;
public class PodP256VerifyTest {
 static void require(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  byte[] spki=Base64.getDecoder().decode(args[0]),data=Base64.getDecoder().decode(args[1]),signature=Base64.getDecoder().decode(args[2]);
  require(PodCloudCrypto.verifyP256(spki,data,signature));
  data[data.length-1]^=1;require(!PodCloudCrypto.verifyP256(spki,data,signature));data[data.length-1]^=1;
  require(!PodCloudCrypto.verifyP256(spki,data,Arrays.copyOf(signature,63)));
  signature[0]^=1;require(!PodCloudCrypto.verifyP256(spki,data,signature));
  KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp384r1"));require(!PodCloudCrypto.verifyP256(g.generateKeyPair().getPublic().getEncoded(),data,signature));
 }
}
