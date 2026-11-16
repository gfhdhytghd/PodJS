#!/usr/bin/env bash
# Keep the first failure, but execute every independent unit program.
PODJS_UNIT_FIRST_FAILURE=0
record_unit() {
    local unit_phase=$1 unit_name=$2 unit_started=$EPOCHREALTIME unit_code=0
    shift 2
    if timeout --signal=TERM --kill-after=5s 1800s "$@"; then
        unit_code=0
    else
        unit_code=$?
    fi
    if (( unit_code != 0 && PODJS_UNIT_FIRST_FAILURE == 0 )); then
        PODJS_UNIT_FIRST_FAILURE=$unit_code
    fi
    printf '[unit-chain] phase=%s name=%s exit=%s\n' "$unit_phase" "$unit_name" "$unit_code" >&2
    if [[ -n ${PODJS_UNIT_CHAIN_REPORT:-} ]]; then
        python3 - "$unit_phase" "$unit_name" "$unit_started" "$EPOCHREALTIME" "$unit_code" "$@" <<'PY'
import fcntl,json,os,sys
row={'tool':'native-chain','phase':sys.argv[1],'name':sys.argv[2],'seconds':max(0,float(sys.argv[4])-float(sys.argv[3])),'exit':int(sys.argv[5]),'argv':sys.argv[6:],'command':os.environ.get('PODJS_COMMAND_NAME')}
with open(os.environ['PODJS_UNIT_CHAIN_REPORT'],'a') as stream:
    fcntl.flock(stream,fcntl.LOCK_EX)
    stream.write(json.dumps(row)+'\n')
PY
    fi
    return "$unit_code"
}
