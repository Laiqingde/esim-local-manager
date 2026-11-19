#!/system/bin/sh
# 用法: sh q.sh <lpac参数...>   例: sh q.sh profile list
cd /data/local/tmp/lp
export LD_LIBRARY_PATH=/data/local/tmp/lp
export LPAC_APDU=qrtr
export LPAC_HTTP=stdio
export LPAC_APDU_QRTR_SLOT=2
exec ./lpac "$@"
