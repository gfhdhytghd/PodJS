package dev.podjs.runtime;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/** Host UI, never a guest-supplied view or permission grant. */
final class PodSyncSettingsDialog implements AutoCloseable {
    private final PodServices services;
    private final Context context;
    private final LinearLayout content;
    private final TextView status;
    private final EditText peer,key,address,port;
    private final AlertDialog dialog;
    private AlertDialog confirmation;
    private boolean closed,ready;
    private final PodSyncBlePanel ble;
    PodSyncSettingsDialog(Context context,PodServices services) {
        this.context=context; this.services=services;
        content=new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int padding=(int)(24*context.getResources().getDisplayMetrics().density); content.setPadding(padding,padding,padding,padding); content.setBackgroundColor(Color.BLACK);
        TextView identity=label("正在读取同步身份…"); identity.setTextIsSelectable(true);
        peer=field("对端设备 ID",false); key=field("共享密钥（64 位十六进制）",true);
        address=field("数字 IP；等待连接可填 0.0.0.0",false); port=field("端口",false); port.setInputType(InputType.TYPE_CLASS_NUMBER); port.setText("20000");
        status=label("只在前台连接，不会自动重连。");
        button("批准配对",this::pair);
        button("连接对端",()->connect(false)); button("等待对端",()->connect(true));
        ble=new PodSyncBlePanel(context,services,content,()->ready,()->peer.getText().toString());
        button("断开连接",()->{services.disconnectSync();ble.disconnected();status.setText("已撤销连接；正在关闭。");});
        ScrollView scroll=new ScrollView(context); scroll.addView(content);
        dialog=new AlertDialog.Builder(context).setTitle("手机同步").setView(scroll).setNegativeButton("返回",(d,w)->close()).create();
        dialog.setOnDismissListener(d->{closed=true;ble.close();key.setText("");if(confirmation!=null)confirmation.dismiss();});
        services.syncIdentity(value->{if(closed)return;ready=value!=null;identity.setText(ready?value:"此应用尚未启用同步能力");if(!ready)status.setText("宿主尚未批准同步，不能配对或连接。");});
    }
    private TextView label(String text) { TextView view=new TextView(context);view.setText(text);view.setTextColor(Color.WHITE);view.setTextSize(16);content.addView(view);return view; }
    private EditText field(String hint,boolean secret) {
        EditText view=new EditText(context);view.setHint(hint);view.setTextColor(Color.WHITE);view.setHintTextColor(0xffaeb7c2);view.setTextSize(16);view.setSingleLine(true);
        view.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        view.setSaveEnabled(false);view.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);content.addView(view);return view;
    }
    private void button(String text,Runnable action) { Button button=new Button(context);button.setText(text);button.setTextColor(0xff70b9ff);button.setMinHeight((int)(48*context.getResources().getDisplayMetrics().density));button.setOnClickListener(v->{if(!closed)action.run();});content.addView(button); }
    void show() { dialog.show();dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); }
    private void pair() {
        if(confirmation!=null && confirmation.isShowing())return;
        if(!ready){status.setText("同步尚未启用。");return;}
        String target=peer.getText().toString().trim();
        if(!target.matches("[A-Za-z0-9_.:-]{1,128}") || !key.getText().toString().matches("[0-9a-fA-F]{64}")) {status.setText("检查设备 ID 和 64 位十六进制密钥。");return;}
        confirmation=new AlertDialog.Builder(context).setTitle("批准此设备？").setMessage(target+"\n请通过可信方式核对共享密钥。")
            .setNegativeButton("取消",(d,w)->key.setText(""))
            .setPositiveButton("批准",(d,w)->{
                if(closed)return;
                String hex=key.getText().toString();key.setText("");
                if(!hex.matches("[0-9a-fA-F]{64}")){status.setText("密钥已清除，请重新填写。");return;}
                byte[] bytes=new byte[32];for(int i=0;i<32;i++)bytes[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
                try { services.approveSyncPair(target,bytes,ok->{if(!closed)status.setText(ok?"配对已保存；尚未连接。":"配对未保存，请检查前台状态和身份。");}); }
                finally {java.util.Arrays.fill(bytes,(byte)0);}
            }).create();
        confirmation.show();confirmation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void connect(boolean listen) {
        if(!ready){status.setText("同步尚未启用。");return;}
        try {
            String target=peer.getText().toString().trim(), raw=address.getText().toString().trim();
            if(!target.matches("[A-Za-z0-9_.:-]{1,128}") || !raw.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException();
            InetAddress ip;
            if(raw.contains(":")) ip=InetAddress.getByName(raw);
            else {String[] fields=raw.split("\\.",-1);if(fields.length!=4)throw new IllegalArgumentException();byte[] octets=new byte[4];for(int i=0;i<4;i++){if(!fields[i].matches("[0-9]{1,3}"))throw new IllegalArgumentException();int n=Integer.parseInt(fields[i]);if(n>255)throw new IllegalArgumentException();octets[i]=(byte)n;}ip=InetAddress.getByAddress(octets);}
            int number=Integer.parseInt(port.getText().toString());if(number<1||number>65535)throw new IllegalArgumentException();
            status.setText(listen?"正在等待并认证…":"正在连接并认证…");
            services.connectSync(target,new InetSocketAddress(ip,number),listen,(phase,detail)->{
                if(closed)return;
                if(phase.equals("connected"))status.setText("已连接；本轮最多两分钟。\n"+detail);
                else if(phase.equals("listening"))status.setText("等待对端连接：\n"+detail);
                else if(phase.equals("stopped"))status.setText("连接已结束，不会自动重连。");
                else status.setText("连接未完成，请检查配对、IP 和端口。");
            });
        } catch(Exception invalid) {status.setText("请填写数字 IP、设备 ID 和 1–65535 端口。");}
    }
    @Override public void close() {key.setText("");closed=true;ble.close();if(confirmation!=null)confirmation.dismiss();dialog.dismiss();}
}
