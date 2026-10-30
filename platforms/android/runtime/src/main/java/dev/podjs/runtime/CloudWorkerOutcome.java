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
    static boolean benign(String code){return "already_running".equals(code)||"yielded".equals(code)||"active_writer".equals(code);}
    static Decision decide(String code,String status,int attempts){
        if(benign(code))return Decision.SUCCESS;
        if(!"completed".equals(code))return Decision.FAILURE;
        if("success".equals(status))return Decision.SUCCESS;
        if("retry".equals(status)&&attempts<3)return Decision.RETRY;
        return Decision.FAILURE;
    }
}
