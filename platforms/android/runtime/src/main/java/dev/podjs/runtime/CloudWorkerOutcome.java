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
        "cloud_sync_deadline_exceeded","cloud_sync_suspended","cloud_operation_superseded","cloud_snapshot_progress_stalled","cloud_host_capability_unavailable"
    )));
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
