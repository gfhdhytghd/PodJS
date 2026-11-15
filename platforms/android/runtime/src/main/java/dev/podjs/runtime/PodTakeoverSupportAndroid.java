package dev.podjs.runtime;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.*;

/** Native support remains reachable while guest/runtime storage admission is blocked. */
final class PodTakeoverSupportAndroid {
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"podjs-takeover-support");t.setDaemon(true);return t;});
    static PodTakeoverSupport create(Context context)throws Exception {
        int id=context.getResources().getIdentifier("pod_takeover_support","raw",context.getPackageName());
        if(id==0)throw new IOException("diagnostic_not_configured");
        JSONObject config;
        try(InputStream input=context.getResources().openRawResource(id)) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;
            while((n=input.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>8192)throw new IOException("diagnostic_config_invalid");}
            config=new JSONObject(out.toString("UTF-8"));
        }
        File control=PodFileConverter.safe(PodTakeover.sandbox(context),"files/package-takeover");
        File file=PodFileConverter.safe(control,"support-receipt.enc");
        PodStoreAndroidCrypto crypto=new PodStoreAndroidCrypto("_androidx_security_master_key_","podjs.takeover.support.v1");
        PodTakeoverSupport.Receipts receipts=new PodTakeoverSupport.Receipts() {
            public JSONObject read()throws Exception {return Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)?crypto.open(PodTakeoverSupport.bytes(file,64*1024)):null;}
            public void save(JSONObject receipt)throws Exception {PodStoreConverter.write(file,crypto.seal(receipt));}
            public void remove()throws Exception {PodFileConverter.safe(control,file.getName());Files.deleteIfExists(file.toPath());PodTakeoverCoordinator.syncDirectory(control);}
        };
        // No platform cookies, credentials, redirects, retry loops or guest services.
        OkHttpClient client=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build();
        PodTakeoverSupport service=new PodTakeoverSupport(config.getString("endpoint"),config.getString("publicKey"),(method,url,headers,body)->{
            Request.Builder request=new Request.Builder().url(url);headers.forEach(request::header);
            request.method(method,method.equals("POST")?RequestBody.create(body,MediaType.get("application/octet-stream")):null);
            try(Response response=client.newCall(request.build()).execute()) {
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                if(response.body()!=null)try(InputStream input=response.body().byteStream()) {
                    byte[] buffer=new byte[1024];int n;while((n=input.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>16384)throw new IOException("diagnostic_response_invalid");}
                }
                return new PodTakeoverSupport.Response(response.code(),out.toByteArray());
            }
        },receipts);
        service.redactor=new PodFeedbackLogRedactor(config.getString("sensitivePattern"),config.getString("headerPattern"));return service;
    }
    static final class Page extends ScrollView {
        final Activity owner;final boolean manageOnly;final TextView preview,message,upload,delete;final CheckBox consent;
        final Handler main=new Handler(Looper.getMainLooper());
        PodTakeoverSupport service;JSONObject summary;boolean busy;
        Page(Activity owner,Runnable back){this(owner,back,false,null);}
        Page(Activity owner,Runnable back,JSONObject failureStatus){this(owner,back,false,failureStatus);}
        Page(Activity owner,Runnable back,boolean manageOnly){this(owner,back,manageOnly,null);}
        Page(Activity owner,Runnable back,boolean manageOnly,JSONObject failureStatus) {
            super(owner);this.owner=owner;this.manageOnly=manageOnly;setBackgroundColor(Color.BLACK);setFillViewport(true);
            int dp=(int)(getResources().getDisplayMetrics().density*24);
            LinearLayout column=new LinearLayout(owner);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(dp,dp*2,dp,dp*2);addView(column);
            text(column,"上传日志",21);
            preview=text(column,"正在读取脱敏摘要…",12);
            text(column,"只上传下方迁移代际、阶段、原因、文件数量、路径类别与版本。不上传文件内容、账号、令牌或原日志。未知路径段仅显示长度。",12);
            text(column,"摘要在设备端压缩并加密，经 HTTPS 发送至腕上RSS自有服务器（中国大陆），供开发者排障。服务器处理来源 IP 用于限流。日志24小时到期后自动清理；上传后可以立即删除。拒绝上传不改变原数据或阻断状态。",12);
            consent=new CheckBox(owner);consent.setText("我同意本次诊断上传");consent.setTextColor(Color.WHITE);consent.setTextSize(12);column.addView(consent);
            upload=button(column,"上传脱敏诊断",()->upload());upload.setEnabled(false);
            message=text(column,"请核对摘要后选择是否上传。",12);
            delete=button(column,"删除已上传诊断",()->delete());delete.setVisibility(GONE);
            button(column,"返回迁移页面",back);
            consent.setOnCheckedChangeListener((v,checked)->refresh());
            if(manageOnly){consent.setVisibility(GONE);upload.setVisibility(GONE);}
            WORK.execute(()->{
                try {
                    PodTakeoverSupport loaded=create(owner.getApplicationContext());
                    JSONObject data=manageOnly?new JSONObject():PodTakeoverSupport.summary(PodTakeover.sandbox(owner));
                    if(failureStatus!=null&&!manageOnly)data.put("phase",PodTakeoverSupport.phase(failureStatus.optString("phase"))).put("reason",PodTakeoverSupport.reason(failureStatus.optString("reason")));
                    data.put("appVersionCode",owner.getPackageManager().getPackageInfo(owner.getPackageName(),0).getLongVersionCode()).put("appVersionName",owner.getPackageManager().getPackageInfo(owner.getPackageName(),0).versionName);
                    JSONObject receipt=loaded.current();
                    main.post(()->{if(!alive())return;service=loaded;summary=data;preview.setText(manageOnly?"此页仅管理迁移诊断回执，不读取业务数据。":display(data));if(receipt!=null)showReceipt(receipt);else if(manageOnly)message.setText("本机没有迁移诊断回执。");refresh();});
                }catch(Exception failure){main.post(()->{if(alive())message.setText("诊断暂不可用。请保留原数据，安装后续修复版本后重试。未上传任何诊断。");});}
            });
        }
        boolean alive(){return !owner.isFinishing()&&!owner.isDestroyed();}
        private TextView text(LinearLayout column,String value,int sp) {
            TextView view=new TextView(owner);view.setText(value);view.setTextSize(sp);view.setTextColor(Color.WHITE);view.setPadding(0,8,0,8);column.addView(view);return view;
        }
        private TextView button(LinearLayout column,String label,Runnable action) {
            TextView view=text(column,label,14);
            LinearLayout.LayoutParams button=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT);button.gravity=android.view.Gravity.CENTER_HORIZONTAL;view.setLayoutParams(button);
            view.setTextColor(0xffff6026);view.setMinHeight((int)(48*getResources().getDisplayMetrics().density));view.setClickable(true);view.setFocusable(true);view.setOnClickListener(v->action.run());return view;
        }
        static String display(JSONObject data) {
            StringBuilder out=new StringBuilder("原因：").append(data.optString("reason")).append("\n文件数量：");
            int count=data.optInt("unknownFileCount",-1);out.append(count<0?"未获取":Integer.toString(count));
            out.append("\n代际：").append(data.optString("generation")).append("\n阶段：").append(data.optString("phase")).append("\n类别：").append(data.optJSONObject("classes")).append("\n版本：").append(data.optString("appVersionName")).append(" / ").append(data.optLong("appVersionCode")).append(" / ").append(data.optString("fileConverter","未获取"));
            JSONArray paths=data.optJSONArray("paths");if(paths!=null)for(int i=0;i<paths.length();i++)out.append("\n").append(paths.optString(i));
            if(data.optBoolean("pathsTruncated"))out.append("\n仅显示前20条路径。");return out.toString();
        }
        void refresh(){upload.setEnabled(!manageOnly&&!busy&&service!=null&&summary!=null&&consent.isChecked()&&delete.getVisibility()!=VISIBLE);consent.setEnabled(!busy);delete.setEnabled(!busy);}
        void showReceipt(JSONObject receipt) {
            message.setText("日志编号："+receipt.optString("code")+"\n到期时间："+receipt.optString("expiresAt")+"\n请在原反馈渠道把日志编号发给开发者。");delete.setVisibility(VISIBLE);consent.setChecked(false);
        }
        void upload() {
            if(!upload.isEnabled()||busy)return;busy=true;refresh();message.setText("正在加密上传…");
            WORK.execute(()->{
                try {JSONObject receipt=service.upload(summary,true);main.post(()->{if(!alive())return;busy=false;showReceipt(receipt);refresh();});}
                catch(Exception failure){
                    JSONObject pending=null;try{pending=service.current();}catch(Exception ignored){}
                    final JSONObject retained=pending;
                    main.post(()->{if(!alive())return;busy=false;consent.setChecked(false);
                        if(retained!=null){showReceipt(retained);message.append("\n本机回执保存未完成。请留在本页立即删除；退出后仅能等待24小时到期清理。");}
                        else message.setText("上传未完成。请检查网络，再次同意后重试。原数据与迁移状态不变。");refresh();});
                }
            });
        }
        void delete() {
            if(busy||service==null)return;busy=true;refresh();
            WORK.execute(()->{
                try{service.delete();main.post(()->{if(!alive())return;busy=false;delete.setVisibility(GONE);message.setText("已删除这份诊断。");refresh();});}
                catch(Exception failure){main.post(()->{if(!alive())return;busy=false;message.setText("删除未完成。请检查网络后重试；到期时间不变。");refresh();});}
            });
        }
    }
}
