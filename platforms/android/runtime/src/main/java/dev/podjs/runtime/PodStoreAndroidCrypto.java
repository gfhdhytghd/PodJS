package dev.podjs.runtime;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.google.crypto.tink.*;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.daead.DeterministicAeadConfig;
import org.json.JSONObject;

/** Reads AndroidX encrypted preference keysets directly. No AndroidKeysetManager,
 * MasterKey.Builder, SharedPreferences editor, native account class or alias repair. */
final class PodStoreAndroidCrypto implements PodStoreConverter.Crypto {
    static final String MASTER="_androidx_security_master_key_";
    static final String KEYS="__androidx_security_crypto_encrypted_prefs_key_keyset__";
    static final String VALUES="__androidx_security_crypto_encrypted_prefs_value_keyset__";
    static final String TARGET="podjs.secure.v1";
    static final byte[] AAD="PodJS-device-services-v1".getBytes(StandardCharsets.UTF_8);
    private final String masterAlias,targetAlias;
    private android.content.Context context;
    PodStoreAndroidCrypto(android.content.Context context){this();this.context=context.getApplicationContext();}
    PodStoreAndroidCrypto(){this(MASTER,TARGET);}
    // Separate aliases for isolated instrumentation fixtures, never supplied by JS.
    PodStoreAndroidCrypto(String masterAlias,String targetAlias){this.masterAlias=masterAlias;this.targetAlias=targetAlias;}
    private void unlocked()throws PodStoreConverter.Locked {
        if(context!=null){android.os.UserManager user=(android.os.UserManager)context.getSystemService(android.content.Context.USER_SERVICE);if(user!=null&&!user.isUserUnlocked())throw new PodStoreConverter.Locked();}
    }
    private KeyStore store()throws Exception{return ProcessKeyStore.get();}
    private SecretKey existing(String alias)throws Exception{KeyStore ks=store();if(!ks.containsAlias(alias))throw new IOException("key_absent");return (SecretKey)ks.getKey(alias,null);}
    private static byte[] decrypt(SecretKey k,byte[] all,byte[] aad)throws Exception{if(all.length<28)throw new IOException("ciphertext_invalid");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,k,new GCMParameterSpec(128,Arrays.copyOf(all,12)));c.updateAAD(aad);return c.doFinal(Arrays.copyOfRange(all,12,all.length));}
    private static byte[] unhex(String s)throws Exception{if(s.length()%2!=0)throw new IOException("keyset_invalid");byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++){int a=Character.digit(s.charAt(i*2),16),c=Character.digit(s.charAt(i*2+1),16);if(a<0||c<0)throw new IOException("keyset_invalid");b[i]=(byte)(a*16+c);}return b;}
    public Map<String,Object> decrypt(String name,Map<String,Object> raw)throws Exception{
        unlocked();
        // Structural failures are bugs, never the expected isolated-UID difference.
        for(String key:new String[]{KEYS,VALUES})if(!(raw.get(key) instanceof String))throw new IOException("keyset_invalid");
        byte[] keyset=unhex((String)raw.get(KEYS)),valueKeyset=unhex((String)raw.get(VALUES));
        if(com.google.crypto.tink.proto.EncryptedKeyset.parseFrom(keyset).getEncryptedKeyset().size()<28||
           com.google.crypto.tink.proto.EncryptedKeyset.parseFrom(valueKeyset).getEncryptedKeyset().size()<28)throw new IOException("keyset_invalid");
        for(Map.Entry<String,Object> entry:raw.entrySet()){
            if(entry.getKey().equals(KEYS)||entry.getKey().equals(VALUES))continue;
            if(!(entry.getValue() instanceof String)||android.util.Base64.decode(entry.getKey(),android.util.Base64.DEFAULT).length<16||
                android.util.Base64.decode((String)entry.getValue(),android.util.Base64.DEFAULT).length<16)throw new IOException("encrypted_entry_invalid");
        }
        SecretKey master;
        try{KeyStore ks=store();if(!ks.containsAlias(masterAlias))throw new PodStoreConverter.SourceKeyUnavailable(true);master=(SecretKey)ks.getKey(masterAlias,null);if(master==null)throw new PodStoreConverter.SourceKeyUnavailable(true);}
        catch(PodStoreConverter.SourceKeyUnavailable absent){throw absent;}
        catch(java.security.GeneralSecurityException unavailable){throw new PodStoreConverter.SourceKeyUnavailable(false);}
        AeadConfig.register();DeterministicAeadConfig.register();
        Aead wrapping=new Aead(){public byte[] encrypt(byte[] p,byte[] a)throws java.security.GeneralSecurityException{throw new java.security.GeneralSecurityException("read_only");}public byte[] decrypt(byte[] c,byte[] a)throws java.security.GeneralSecurityException{try{return PodStoreAndroidCrypto.decrypt(master,c,a);}catch(Exception e){throw new java.security.GeneralSecurityException("source_crypto_unavailable");}}};
        DeterministicAead keys;Aead values;
        try{
            keys=KeysetHandle.read(BinaryKeysetReader.withBytes(keyset),wrapping).getPrimitive(DeterministicAead.class);
            values=KeysetHandle.read(BinaryKeysetReader.withBytes(valueKeyset),wrapping).getPrimitive(Aead.class);
        }catch(java.security.GeneralSecurityException unavailable){throw new PodStoreConverter.SourceKeyUnavailable(false);}
        Map<String,Object> out=new TreeMap<>();
        for(Map.Entry<String,Object> entry:raw.entrySet()){
            if(entry.getKey().equals(KEYS)||entry.getKey().equals(VALUES))continue;
            byte[] clearKey,clear;
            try{
                clearKey=keys.decryptDeterministically(android.util.Base64.decode(entry.getKey(),android.util.Base64.DEFAULT),name.getBytes(StandardCharsets.UTF_8));
                clear=values.decrypt(android.util.Base64.decode((String)entry.getValue(),android.util.Base64.DEFAULT),entry.getKey().getBytes(StandardCharsets.UTF_8));
            }catch(java.security.GeneralSecurityException unavailable){throw new PodStoreConverter.SourceKeyUnavailable(false);}
            String key=PodPreferenceReader.utf(clearKey);Arrays.fill(clearKey,(byte)0);ByteBuffer b=ByteBuffer.wrap(clear);Object value;
            switch(b.getInt()){
                case 0:value=string(b);break;
                case 1:Set<String> set=new LinkedHashSet<>();while(b.hasRemaining())if(!set.add(string(b)))throw new IOException("duplicate_set");value=set;break;
                case 2:value=b.getInt();break;case 3:value=b.getLong();break;case 4:value=b.getFloat();break;
                case 5:byte v=b.get();if(v!=0&&v!=1)throw new IOException("invalid_boolean");value=v!=0;break;
                default:throw new IOException("encrypted_type_invalid");
            }
            if(b.hasRemaining()||out.put(key,value)!=null)throw new IOException("encrypted_value_invalid");Arrays.fill(clear,(byte)0);
        }return out;
    }
    private static String string(ByteBuffer b)throws Exception{int n=b.getInt();if(n<0||n>b.remaining())throw new IOException("encrypted_string_invalid");byte[] raw=new byte[n];b.get(raw);String s=PodPreferenceReader.utf(raw);if(s.equals("__NULL__"))throw new IOException("encrypted_null_unsupported");return s;}
    public boolean cloudBindingValid(String userId,String deviceId)throws Exception {
        unlocked();String digest=PodRoomV18Converter.hex(java.security.MessageDigest.getInstance("SHA-256").digest((userId+":"+deviceId).getBytes(StandardCharsets.UTF_8))).substring(0,24);
        String alias="watchrss_cloud_ecdh_"+digest;KeyStore ks=store();
        if(!ks.containsAlias(alias)||ks.getCertificate(alias)==null)return false;
        java.security.Key privateKey=ks.getKey(alias,null);if(!(privateKey instanceof java.security.PrivateKey))return false;
        java.security.PublicKey publicKey=ks.getCertificate(alias).getPublicKey();
        if(!(publicKey instanceof java.security.interfaces.ECPublicKey))return false;
        java.security.KeyPairGenerator generator=java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(((java.security.interfaces.ECPublicKey)publicKey).getParams());java.security.KeyPair ephemeral=generator.generateKeyPair();
        KeyAgreement left=KeyAgreement.getInstance("ECDH"),right=KeyAgreement.getInstance("ECDH");
        left.init(privateKey);left.doPhase(ephemeral.getPublic(),true);right.init(ephemeral.getPrivate());right.doPhase(publicKey,true);
        byte[] a=left.generateSecret(),b=right.generateSecret();boolean valid=java.security.MessageDigest.isEqual(a,b);Arrays.fill(a,(byte)0);Arrays.fill(b,(byte)0);return valid;
    }
    public byte[] seal(JSONObject values)throws Exception{
        unlocked();byte[] plain=SecureStoreLimits.encode(values);
        try {
        KeyStore ks=store();if(!ks.containsAlias(targetAlias)){KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(targetAlias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();}
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,existing(targetAlias));c.updateAAD(AAD);byte[] body=c.doFinal(plain),nonce=c.getIV();if(nonce.length!=12)throw new IOException("iv_invalid");return ByteBuffer.allocate(nonce.length+body.length).put(nonce).put(body).array();
        } finally { Arrays.fill(plain,(byte)0); }
    }
    public JSONObject open(byte[] cipher)throws Exception{unlocked();return PodDeviceServices.decodeSecureBytes(cipher,existing(targetAlias));}
}
