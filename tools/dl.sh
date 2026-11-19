#!/system/bin/sh
cd /data/local/tmp/lp
export LD_LIBRARY_PATH=/data/local/tmp/lp
export LPAC_APDU=qrtr
export LPAC_HTTP=stdio
export LPAC_APDU_QRTR_SLOT=2
export LPAC_HTTP_SSL_VERIFY=false
CODE=$(cat /data/local/tmp/lp/code.txt)
exec ./lpac profile download -a "$CODE" 2>/data/local/tmp/lp/err.log
