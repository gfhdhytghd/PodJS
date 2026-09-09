package dev.podjs.runtime;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Explicit native-host BLE actions. Candidate selection never approves a peer. */
final class PodSyncBlePanel implements AutoCloseable {
    private final Context context;
    private final PodServices services;
    private final BooleanSupplier ready;
    private final Supplier<String> peer;
    private final TextView status;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    private PodBleDiscovery scan;
    private BluetoothDevice selected;
    private AlertDialog choices;
    private boolean closed;
    PodSyncBlePanel(Context context,PodServices services,LinearLayout content,BooleanSupplier ready,Supplier<String> peer) {
        this.context=context;this.services=services;this.ready=ready;this.peer=peer;
        status=new TextView(context);status.setTextColor(android.graphics.Color.WHITE);status.setTextSize(16);
        status.setText("蓝牙：先授权，再扫描并选择设备。选择不会批准配对。");content.addView(status);
        button(content,"允许蓝牙权限",this::permissions);
        button(content,"扫描蓝牙设备",this::discover);
        button(content,"取消扫描",()->{cancelScan();status.setText("扫描已取消。");});
        button(content,"连接所选蓝牙设备",()->connect(false));
        button(content,"等待蓝牙连接",()->connect(true));
    }
    private void button(LinearLayout content,String title,Runnable action) {
        Button button=new Button(context);button.setText(title);button.setTextColor(0xff70b9ff);
        button.setMinHeight((int)(48*context.getResources().getDisplayMetrics().density));
        button.setOnClickListener(v->{if(!closed)action.run();});content.addView(button);
    }
    private boolean available() {
        if(closed)return false;
        if(!ready.getAsBoolean()){status.setText("同步尚未启用。");return false;}
        return true;
    }
    private boolean granted(boolean discovery,boolean advertise) {
        if(PodBlePermissions.missing(context,discovery,advertise).length==0)return true;
        status.setText("权限不足，请先允许蓝牙权限，再重新选择操作。");return false;
    }
    private void permissions() {
        if(!available())return;
        cancelScan();
        String[] missing=PodBlePermissions.missing(context,true,true);
        if(missing.length==0){status.setText("权限已具备，请选择扫描或等待连接。");return;}
        if(!(context instanceof Activity)){status.setText("请从应用设置允许蓝牙权限。");return;}
        status.setText("授权后请重新打开手机同步；不会自动开始连接。");
        ((Activity)context).requestPermissions(missing,0x5054);
    }
    private void discover() {
        if(!available() || !granted(true,false))return;
        if(scan!=null){status.setText("正在扫描，请等待或取消。");return;}
        selected=null;
        if(choices!=null)choices.dismiss();
        PodBleDiscovery pending=new PodBleDiscovery(context);scan=pending;
        status.setText("正在扫描，最多十秒…");
        worker.execute(()->{
            try {
                List<PodBleDiscovery.Entry> found=pending.scan(10000);
                main.post(()->{if(closed || scan!=pending)return;scan=null;showChoices(found);});
            } catch(Exception error) {
                main.post(()->{if(closed || scan!=pending)return;scan=null;status.setText("扫描未完成，请检查蓝牙开关、权限和系统定位开关。");});
            }
        });
    }
    private void showChoices(List<PodBleDiscovery.Entry> found) {
        if(found.isEmpty()){status.setText("未发现设备，请让对端进入等待蓝牙连接后重试。");return;}
        try {
            String[] labels=new String[found.size()];
            for(int i=0;i<labels.length;i++)labels[i]=found.get(i).device.getAddress()+"  "+found.get(i).rssi+" dBm";
            choices=new AlertDialog.Builder(context).setTitle("选择连接设备（不是配对批准）")
                .setItems(labels,(dialog,index)->{if(closed || dialog!=choices)return;selected=found.get(index).device;status.setText("已选 "+labels[index]+"，请核对对端 ID 后点击连接。");})
                .setNegativeButton("取消",null).create();
            choices.setOnDismissListener(dialog->{if(dialog==choices)choices=null;});
            choices.show();choices.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        } catch(SecurityException revoked) {selected=null;status.setText("蓝牙权限已撤销，请重新授权。");}
    }
    private void connect(boolean listen) {
        if(!available() || !granted(false,listen))return;
        if(scan!=null){status.setText("请先取消扫描或等待扫描完成。");return;}
        String target=peer.get().trim();
        if(!target.matches("[A-Za-z0-9_.:-]{1,128}")){status.setText("请填写已批准的对端设备 ID。");return;}
        if(!listen && selected==null){status.setText("请先扫描并选择设备。");return;}
        status.setText(listen?"正在等待蓝牙连接并认证…":"正在连接蓝牙并认证…");
        services.connectSyncBle(target,listen?null:selected,listen,(phase,detail)->{
            if(closed)return;
            if(phase.equals("connected"))status.setText("蓝牙已认证连接；本轮最多两分钟。");
            else if(phase.equals("stopped"))status.setText("连接已结束，不会自动重连。");
            else status.setText("连接未完成，请检查配对、权限和对端等待状态。");
        });
    }
    private void cancelScan() {
        PodBleDiscovery pending=scan;scan=null;selected=null;
        if(choices!=null){choices.dismiss();choices=null;}
        if(pending!=null)new Thread(pending::close,"podjs-ble-scan-stop").start();
    }
    void disconnected() { if(!closed)status.setText("连接已撤销，不会自动重连。"); }
    @Override public void close() {if(closed)return;closed=true;cancelScan();worker.shutdownNow();}
}
