package dev.podjs.runtime;
import java.util.*;
/** Closed diagnostics and scheduling decisions. No guest error messages. */
final class CloudWorkerOutcome {
    enum Decision { SUCCESS, RETRY, FAILURE }
    static final Set<String> CODES=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "completed","already_running","yielded","invalid_options","registry_unavailable","runtime_unavailable","context_unavailable","sandbox_setup_failed","invalid_payload","clock_unavailable","bundle_exception","handler_missing","handler_exception","handler_rejected","service_disconnected","service_reply_failed","unresolved_handler","invalid_result","cancelled","deadline_exceeded",
        "worker_started","invalid_manual_token","local_transfer_held","stale_request","takeover_pending","queue_failed","host_exception","stopped","active_writer","unknown_outcome"
    )));
    static String code(String value){return CODES.contains(value)?value:"unknown_outcome";}
    static final Set<String> GUEST_STAGES=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "secure-read","secure-write","decrypt","encrypt","file-read","file-write","file-commit","transform","network","host",
        "checkpoint-read","checkpoint-write","checkpoint-range-piece","checkpoint-invalid","download","chunk","object","overlay","inventory","manifest","source-hash","inflate","plan","export","apply"
    )));
    static final Set<String> GUEST_CODES=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "cloud_sync_deadline_exceeded","cloud_sync_suspended","cloud_operation_superseded","cloud_snapshot_progress_stalled","cloud_host_capability_unavailable",
        "cloud_snapshot_object_missing","cloud_snapshot_object_unauthorized","cloud_snapshot_object_timeout","cloud_snapshot_object_rate_limited",
        "cloud_snapshot_object_redirect","cloud_snapshot_object_integrity","cloud_snapshot_download_failed","cloud_snapshot_work_timeout"
    )));
    static final List<String> CPU_STAGE_IDS=Collections.unmodifiableList(Arrays.asList(
        "crc32","byte-compare","utf8-encode","utf8-decode","base64-encode","base64-decode","sha256","gunzip","json-scan","json-parse","json-stringify","canonical-json","journal-rebuild","journal-diff","sort","archive-index","manifest-validate","manifest-plan","records-validate","records-parse","projection-rebuild","record-diff","record-merge","book-validate","book-match","alias-match","overlay-index"
    ));
    static final Set<String> CPU_STAGES=Collections.unmodifiableSet(new HashSet<>(CPU_STAGE_IDS));
    static String cpuStageId(double id){return Double.isFinite(id)&&id==Math.floor(id)&&id>=0&&id<CPU_STAGE_IDS.size()?CPU_STAGE_IDS.get((int)id):null;}
    /** Only code-phase names and coarse/numeric values reach the release log. */
    static String cpuDiagnostic(String stage,String scale,double sizeClass,double elapsed,String outcome){
        if(!CPU_STAGES.contains(stage)||!Arrays.asList("bytes","chars","items").contains(scale)||!Double.isFinite(sizeClass)||sizeClass!=Math.floor(sizeClass)||sizeClass<0||sizeClass>5||!Double.isFinite(elapsed)||elapsed!=Math.floor(elapsed)||elapsed<0||elapsed>300000||!Arrays.asList("started","progress","complete","failed").contains(outcome))return null;
        return "cpuStage="+stage+" scale="+scale+" sizeClass="+(int)sizeClass+" elapsedMs="+(long)elapsed+" outcome="+outcome;
    }
    static String guestCode(String value){return GUEST_CODES.contains(value)?value:null;}
    static String guestStage(String value){return GUEST_STAGES.contains(value)?value:null;}
    static String stage(String method){
        if("http.download".equals(method))return "network";
        if("secure.get".equals(method))return "secure-read";
        if(method.startsWith("secure."))return "secure-write";
        if(method.endsWith("AesGcmDecrypt")||method.equals("crypto.openSealedParts"))return "decrypt";
        if(method.endsWith("AesGcmEncrypt"))return "encrypt";
        if("file.commit".equals(method))return "file-commit";
        if("file.write".equals(method))return "file-write";
        if("file.read".equals(method))return "file-read";
        if("file.gzip".equals(method)||"file.gunzip".equals(method))return "transform";
        if(method.startsWith("sql."))return "sql";
        return "host";
    }
    static boolean benign(String code){return "already_running".equals(code)||"yielded".equals(code)||"active_writer".equals(code);}
    static Decision decide(String code,String status,int attempts){
        if(benign(code))return Decision.SUCCESS;
        if("deadline_exceeded".equals(code))return Decision.RETRY;
        if(!"completed".equals(code))return Decision.FAILURE;
        if("success".equals(status))return Decision.SUCCESS;
        if("retry".equals(status))return Decision.RETRY;
        return Decision.FAILURE;
    }
}
