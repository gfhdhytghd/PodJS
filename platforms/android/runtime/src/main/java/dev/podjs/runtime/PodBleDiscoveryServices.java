package dev.podjs.runtime;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONObject;
import java.io.Closeable;
import java.util.*;

/** Guest-owned, bounded BLE scan followed by one GATT characteristic read. */
@SuppressLint("MissingPermission")
final class PodBleDiscoveryServices implements Closeable {
    private final Context context;
    private final Set<Read> reads = new HashSet<>();
    private boolean closed;
    PodBleDiscoveryServices(Context context) { this.context=context; }
    static String[] permissions() {
        return PodBlePermissions.required(Build.VERSION.SDK_INT,true,false);
    }
    String permissionStatus() {
        for (String permission : permissions()) if (context.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED) return "required";
        return "granted";
    }
    private BluetoothAdapter adapter() {
        BluetoothManager manager=context.getSystemService(BluetoothManager.class);
        return manager==null?null:manager.getAdapter();
    }
    JSONObject execute(String method, JSONObject args, CancellationSignal cancellation) throws Exception {
        synchronized (this) { if(closed)throw new IllegalStateException("BLE discovery closed"); }
        cancellation.throwIfCanceled();
        BluetoothAdapter adapter=adapter();
        if ("discovery.status".equals(method)) return new JSONObject().put("available",adapter!=null).put("permission",permissionStatus()).put("enabled",adapter!=null&&"granted".equals(permissionStatus())&&adapter.isEnabled());
        if (!"discovery.bleReadCharacteristic".equals(method)) throw new IllegalArgumentException("Unknown BLE discovery method");
        UUID advertised=UUID.fromString(args.getString("advertisedServiceUuid")), service=UUID.fromString(args.getString("serviceUuid")), characteristic=UUID.fromString(args.getString("characteristicUuid"));
        int manufacturer=PodDiscoveryServices.bounded(args,"manufacturerId",0,65535), scanTimeout=PodDiscoveryServices.bounded(args,"scanTimeoutMs",1,30000), readTimeout=PodDiscoveryServices.bounded(args,"readTimeoutMs",1,30000);
        String encoded=args.getString("manufacturerDataBase64");if(encoded.length()>2048)throw new IllegalArgumentException("Manufacturer marker too large");byte[] marker=Base64.decode(encoded,Base64.DEFAULT);if(marker.length==0||marker.length>512)throw new IllegalArgumentException("Invalid manufacturer marker");
        if(!"granted".equals(permissionStatus()))throw new SecurityException("BLE discovery permission required");
        if(adapter==null||!adapter.isEnabled())throw new IllegalStateException("Bluetooth unavailable or disabled");
        BluetoothLeScanner scanner=adapter.getBluetoothLeScanner();if(scanner==null)throw new IllegalStateException("BLE scanner unavailable");
        Read read=new Read(scanner,advertised,manufacturer,marker,service,characteristic,args.optBoolean("bondedOnly",true));
        synchronized(this){if(closed)throw new IllegalStateException("BLE discovery closed");if(reads.size()>=4)throw new IllegalStateException("Too many BLE discovery requests");reads.add(read);}
        cancellation.setOnCancelListener(read::close);
        long began=SystemClock.elapsedRealtime(),scanned=began;String stage="scan";
        try{
            cancellation.throwIfCanceled();read.startScan();
            BluetoothDevice device;
            try{device=read.awaitDevice(scanTimeout);}finally{read.stopScan();}
            scanned=SystemClock.elapsedRealtime();stage="gatt";cancellation.throwIfCanceled();read.startGatt(device);byte[] bytes=read.awaitBytes(readTimeout);cancellation.throwIfCanceled();
            diagnostic("complete",read,SystemClock.elapsedRealtime()-began,bytes.length);
            return new JSONObject().put("address",device.getAddress()).put("bonded",device.getBondState()==BluetoothDevice.BOND_BONDED).put("dataBase64",Base64.encodeToString(bytes,Base64.NO_WRAP));
        }catch(Exception error){diagnostic(stage,read,SystemClock.elapsedRealtime()-began,0);throw error;}finally{read.close();cancellation.setOnCancelListener(null);synchronized(this){reads.remove(read);}}
    }
    private void diagnostic(String stage,Read read,long elapsed,int bytes){
        if((context.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0)return;
        android.util.Log.i("PodBleDiscovery","stage="+stage+" elapsedMs="+elapsed+" results="+read.results+" markerMatches="+read.markerMatches+" serviceMatches="+read.serviceMatches+" bondedMatches="+read.bondedMatches+" bytes="+bytes+" gattStatus="+read.gattStatus+" mtu="+read.mtu);
    }
    public void close(){List<Read> pending;synchronized(this){closed=true;pending=new ArrayList<>(reads);reads.clear();}for(Read read:pending)read.close();}

    private final class Read implements Closeable {
        final BluetoothLeScanner scanner;final UUID advertised,service,characteristic;final int manufacturer;final byte[] marker;final boolean bondedOnly;
        int results,markerMatches,serviceMatches,bondedMatches,gattStatus=-1;
        boolean scanning,ended,readStarted;int mtu=23;BluetoothGattCharacteristic readField;BluetoothDevice device;BluetoothGatt gatt;byte[] value;RuntimeException failure;
        Read(BluetoothLeScanner scanner,UUID advertised,int manufacturer,byte[] marker,UUID service,UUID characteristic,boolean bondedOnly){this.scanner=scanner;this.advertised=advertised;this.manufacturer=manufacturer;this.marker=marker;this.service=service;this.characteristic=characteristic;this.bondedOnly=bondedOnly;}
        private void check(){if(ended)throw new IllegalStateException("BLE discovery closed");if(failure!=null)throw failure;}
        private synchronized void fail(String message){if(!ended&&failure==null){failure=new IllegalStateException(message);notifyAll();}}
        synchronized void startScan(){
            check();
            // Vendor stacks can drop every result with a controller UUID filter.
            // Validate all fields in the callback instead.
            scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);scanning=true;
        }
        synchronized void stopScan(){if(scanning){scanning=false;try{scanner.stopScan(scanCallback);}catch(RuntimeException ignored){}}}
        synchronized BluetoothDevice awaitDevice(int timeout)throws InterruptedException{
            long end=SystemClock.elapsedRealtime()+timeout;
            while(device==null){check();long left=end-SystemClock.elapsedRealtime();if(left<=0)throw new IllegalStateException("BLE scan timed out");wait(left);}check();return device;
        }
        synchronized void startGatt(BluetoothDevice device){check();gatt=device.connectGatt(context.getApplicationContext(),false,gattCallback,BluetoothDevice.TRANSPORT_LE);if(gatt==null)throw new IllegalStateException("GATT connection unavailable");}
        synchronized byte[] awaitBytes(int timeout)throws InterruptedException{
            long end=SystemClock.elapsedRealtime()+timeout;
            while(value==null){check();long left=end-SystemClock.elapsedRealtime();if(left<=0)throw new IllegalStateException("GATT read timed out");wait(left);}check();return value;
        }
        final ScanCallback scanCallback=new ScanCallback(){
            public void onScanResult(int callbackType,ScanResult result){accept(result);}
            public void onBatchScanResults(List<ScanResult> results){for(ScanResult result:results)accept(result);}
            public void onScanFailed(int code){fail("BLE scan failed: "+code);}
            private void accept(ScanResult result){synchronized(Read.this){
                if(ended||device!=null||result==null)return;
                try{
                    results++;ScanRecord record=result.getScanRecord();BluetoothDevice candidate=result.getDevice();if(record==null||candidate==null||!Arrays.equals(marker,record.getManufacturerSpecificData(manufacturer)))return;markerMatches++;
                    boolean matches=false;if(record.getServiceUuids()!=null)for(android.os.ParcelUuid uuid:record.getServiceUuids())if(advertised.equals(uuid.getUuid()))matches=true;
                    if(matches){serviceMatches++;if(bondedOnly&&candidate.getBondState()!=BluetoothDevice.BOND_BONDED)return;bondedMatches++;device=candidate;Read.this.notifyAll();}
                }catch(SecurityException error){fail("BLE discovery permission revoked");}
            }}
        };
        final BluetoothGattCallback gattCallback=new BluetoothGattCallback(){
            public void onConnectionStateChange(BluetoothGatt connection,int status,int state){synchronized(Read.this){
                if(ended)return;gattStatus=status;
                if(status==BluetoothGatt.GATT_SUCCESS&&state==BluetoothProfile.STATE_CONNECTED){try{if(!connection.discoverServices())fail("GATT service discovery rejected");}catch(RuntimeException error){fail("GATT service discovery failed");}}
                else if(status!=BluetoothGatt.GATT_SUCCESS||state==BluetoothProfile.STATE_DISCONNECTED)fail("GATT disconnected: "+status);
            }}
            public void onServicesDiscovered(BluetoothGatt connection,int status){synchronized(Read.this){
                if(ended)return;if(status!=BluetoothGatt.GATT_SUCCESS){fail("GATT service discovery failed: "+status);return;}
                BluetoothGattService found=connection.getService(service);BluetoothGattCharacteristic field=found==null?null:found.getCharacteristic(characteristic);
                if(field==null){fail("GATT characteristic unavailable");return;}
                readField=field;
                // A changing legacy peripheral value should fit one ATT response.
                // Stable peripherals retain long-read fallback if MTU is rejected.
                try{if(!connection.requestMtu(517))startRead(connection);}catch(RuntimeException error){startRead(connection);}
            }}
            public void onMtuChanged(BluetoothGatt connection,int negotiated,int status){synchronized(Read.this){
                if(ended)return;mtu=negotiated;startRead(connection);
            }}
            private void startRead(BluetoothGatt connection){
                if(ended||readStarted||readField==null)return;readStarted=true;
                try{if(!connection.readCharacteristic(readField))fail("GATT read rejected");}catch(RuntimeException error){fail("GATT read failed");}
            }
            @SuppressWarnings("deprecation") public void onCharacteristicRead(BluetoothGatt connection,BluetoothGattCharacteristic field,int status){complete(field,field.getValue(),status);}
            public void onCharacteristicRead(BluetoothGatt connection,BluetoothGattCharacteristic field,byte[] bytes,int status){complete(field,bytes,status);}
            private void complete(BluetoothGattCharacteristic field,byte[] bytes,int status){synchronized(Read.this){
                if(ended||value!=null||!characteristic.equals(field.getUuid()))return;gattStatus=status;
                if(status!=BluetoothGatt.GATT_SUCCESS||bytes==null||bytes.length==0||bytes.length>65536){fail("GATT characteristic read failed: "+status);return;}
                value=bytes.clone();Read.this.notifyAll();
            }}
        };
        public synchronized void close(){if(ended)return;ended=true;stopScan();if(gatt!=null){try{gatt.disconnect();}catch(RuntimeException ignored){}try{gatt.close();}catch(RuntimeException ignored){}gatt=null;}notifyAll();}
    }
}
