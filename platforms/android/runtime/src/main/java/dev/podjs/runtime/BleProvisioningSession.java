package dev.podjs.runtime;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Foreground, QR-authorized encrypted configuration channel. No business data is logged.
 * The QR secret is an out-of-band authorization, never an advertised BLE value.
 * Packet receipt is NOT durable application success: applications must respond after saving.
 */
public final class BleProvisioningSession implements ProvisioningSession {
    public static final String PROTOCOL="watchrss-ble-setup-v1";
    public static final UUID SERVICE=UUID.fromString("7e57c401-1f7d-4f0b-9f3d-2d7d3a65b401");
    public static final UUID WRITE=UUID.fromString("7e57c401-1f7d-4f0b-9f3d-2d7d3a65b402");
    public static final UUID READ=UUID.fromString("7e57c401-1f7d-4f0b-9f3d-2d7d3a65b403");
    public static final int MAX_BYTES=16384;
    private final Context context;
    private final byte[] secret=new byte[32];
    private final SecureRandom random=new SecureRandom();
    private final String sessionId;
    private final JSONObject descriptor;
    private final long deadline=SystemClock.elapsedRealtime()+300000;
    private final ArrayBlockingQueue<JSONObject> requests=new ArrayBlockingQueue<>(1);
    private final ByteArrayOutputStream incoming=new ByteArrayOutputStream();
    private final CountDownLatch started=new CountDownLatch(1);
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothDevice central;
    private byte[] response,lastPacket;
    private int expectedPacket,totalPackets,responsePacket,receiveSequence;
    private volatile boolean closed,advertising;
    private final android.os.Handler expiry=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable expire=this::close;
    private boolean awaitingResponse;

    public BleProvisioningSession(Context context,String deviceId,String installId) throws Exception {
        this.context=context.getApplicationContext(); random.nextBytes(secret);
        byte[] id=new byte[16];random.nextBytes(id);sessionId=Base64.encodeToString(id,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        descriptor=new JSONObject().put("protocol",PROTOCOL).put("sessionId",sessionId)
            .put("secret",Base64.encodeToString(secret,Base64.NO_WRAP)).put("deviceId",deviceId)
            .put("installId",installId).put("expiresAt",System.currentTimeMillis()+300000);
    }
    @Override public String sessionId(){return sessionId;}
    public JSONObject descriptor() throws Exception { check();return new JSONObject(descriptor.toString()); }
    public String qr() throws Exception {return "wrss-pair:"+Base64.encodeToString(descriptor().toString().getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    private void check(){if(closed||SystemClock.elapsedRealtime()>=deadline){close();throw new IllegalStateException("配对已结束，请在手表重新打开配对页面");}}
    @Override public void checkActive(){check();}
    public void start() throws Exception {
        check();expiry.postDelayed(expire,300000);BluetoothManager manager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null||!adapter.isEnabled()||!adapter.isMultipleAdvertisementSupported())throw new IllegalStateException("当前手表未开启蓝牙或不支持 BLE 配置服务");
        advertiser=adapter.getBluetoothLeAdvertiser();if(advertiser==null)throw new IllegalStateException("手表 BLE 广播不可用");
        server=manager.openGattServer(context,callback);if(server==null)throw new IllegalStateException("无法启动手表配对服务");
        BluetoothGattService service=new BluetoothGattService(SERVICE,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(new BluetoothGattCharacteristic(WRITE,BluetoothGattCharacteristic.PROPERTY_WRITE,BluetoothGattCharacteristic.PERMISSION_WRITE));
        service.addCharacteristic(new BluetoothGattCharacteristic(READ,BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ));
        if(!server.addService(service)||!started.await(8,TimeUnit.SECONDS)||!advertising){close();throw new IllegalStateException("手表配对服务启动失败，请重试");}
    }
    private final AdvertiseCallback ads=new AdvertiseCallback(){
        @Override public void onStartSuccess(AdvertiseSettings settings){advertising=true;started.countDown();}
        @Override public void onStartFailure(int error){started.countDown();close();}
    };
    private final BluetoothGattServerCallback callback=new BluetoothGattServerCallback(){
        @Override public void onServiceAdded(int status,BluetoothGattService service){
            if(closed||status!=BluetoothGatt.GATT_SUCCESS){started.countDown();close();return;}
            try{advertiser.startAdvertising(new AdvertiseSettings.Builder().setConnectable(true).setTimeout(180000).build(),new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(SERVICE)).build(),ads);}catch(Exception e){started.countDown();close();}
        }
        @Override public void onConnectionStateChange(BluetoothDevice device,int status,int state){
            synchronized(BleProvisioningSession.this){
                if(closed)return;
                if(state==BluetoothProfile.STATE_CONNECTED){if(central==null)central=device;else if(!central.equals(device))server.cancelConnection(device);}
                else if(state==BluetoothProfile.STATE_DISCONNECTED&&device.equals(central))close();
            }
        }
        @Override public void onCharacteristicWriteRequest(BluetoothDevice device,int id,BluetoothGattCharacteristic characteristic,boolean prepared,boolean needsResponse,int offset,byte[] value){
            int status=BluetoothGatt.GATT_FAILURE;
            synchronized(BleProvisioningSession.this){
                try{check();if(prepared||offset!=0||!device.equals(central)||!WRITE.equals(characteristic.getUuid()))throw new IllegalArgumentException();acceptPacket(value);status=BluetoothGatt.GATT_SUCCESS;}catch(Exception e){close();}
                if(needsResponse&&server!=null)server.sendResponse(device,id,status,0,null);
            }
        }
        @Override public void onCharacteristicReadRequest(BluetoothDevice device,int id,int offset,BluetoothGattCharacteristic characteristic){
            synchronized(BleProvisioningSession.this){
                try{check();if(offset!=0||!device.equals(central)||!READ.equals(characteristic.getUuid()))throw new IllegalArgumentException();
                    byte[] packet=new byte[]{0};
                    if(response!=null){int from=responsePacket*14,n=Math.min(14,response.length-from),count=(response.length+13)/14;
                        packet=new byte[6+n];packet[0]=0x57;packet[1]=1;put16(packet,2,responsePacket);put16(packet,4,count);System.arraycopy(response,from,packet,6,n);responsePacket++;
                        if(responsePacket==count){Arrays.fill(response,(byte)0);response=null;responsePacket=0;}
                    }
                    server.sendResponse(device,id,BluetoothGatt.GATT_SUCCESS,0,packet);
                }catch(Exception e){if(server!=null)server.sendResponse(device,id,BluetoothGatt.GATT_FAILURE,0,null);close();}
            }
        }
    };
    private static int u16(byte[] p,int i){return (p[i]&255)*256+(p[i+1]&255);}
    private static void put16(byte[] p,int i,int n){p[i]=(byte)(n>>>8);p[i+1]=(byte)n;}
    private void acceptPacket(byte[] value) throws Exception {
        if(awaitingResponse||response!=null||value==null||value.length<7||value.length>20||value[0]!=0x57||value[1]!=1)throw new IllegalArgumentException();
        int seq=u16(value,2),count=u16(value,4);
        if(lastPacket!=null&&seq==expectedPacket-1&&Arrays.equals(lastPacket,value))return;
        if(seq!=expectedPacket||count<1||count>1171||(seq>0&&count!=totalPackets)||seq>=count||incoming.size()+value.length-6>MAX_BYTES)throw new IllegalArgumentException();
        totalPackets=count;lastPacket=value.clone();incoming.write(value,6,value.length-6);expectedPacket++;
        if(expectedPacket==count){
            JSONObject wire=new JSONObject(incoming.toString("UTF-8"));int sequence=wire.getInt("seq");if(sequence!=receiveSequence||sequence>15)throw new IllegalArgumentException();
            byte[] plain=crypt(Cipher.DECRYPT_MODE,"phone",sequence,Base64.decode(wire.getString("n"),Base64.DEFAULT),Base64.decode(wire.getString("c"),Base64.DEFAULT));
            try{JSONObject message=new JSONObject(new String(plain,StandardCharsets.UTF_8));message.put("_sequence",sequence);if(!requests.offer(message))throw new IllegalStateException();}finally{Arrays.fill(plain,(byte)0);}
            receiveSequence++;awaitingResponse=true;incoming.reset();expectedPacket=0;totalPackets=0;lastPacket=null;
        }
    }
    private byte[] crypt(int mode,String direction,int sequence,byte[] nonce,byte[] bytes) throws Exception {
        return BleSetupCrypto.crypt(secret,sessionId,mode,direction,sequence,nonce,bytes);
    }

    public JSONObject receive() throws Exception {
        while(true){check();JSONObject request=requests.poll(250,TimeUnit.MILLISECONDS);if(request!=null)return request;}
    }
    public synchronized void respond(int sequence,JSONObject result) throws Exception {
        check();if(!awaitingResponse||sequence!=receiveSequence-1||response!=null)throw new IllegalStateException("配对响应次序错误");
        byte[] nonce=new byte[12];random.nextBytes(nonce);byte[] plain=result.toString().getBytes(StandardCharsets.UTF_8);
        try{response=new JSONObject().put("seq",sequence).put("n",Base64.encodeToString(nonce,Base64.NO_WRAP)).put("c",Base64.encodeToString(crypt(Cipher.ENCRYPT_MODE,"watch",sequence,nonce,plain),Base64.NO_WRAP)).toString().getBytes(StandardCharsets.UTF_8);if(response.length>MAX_BYTES){close();throw new IllegalArgumentException("配置消息过大");}awaitingResponse=false;}finally{Arrays.fill(plain,(byte)0);}
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;expiry.removeCallbacks(expire);started.countDown();Arrays.fill(secret,(byte)0);descriptor.remove("secret");
        if(response!=null)Arrays.fill(response,(byte)0);response=null;incoming.reset();requests.clear();
        try{if(advertiser!=null)advertiser.stopAdvertising(ads);}catch(Exception ignored){}
        try{if(server!=null){server.close();server=null;}}catch(Exception ignored){}
    }
}
