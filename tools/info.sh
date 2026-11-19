#!/system/bin/sh
cd /data/local/tmp/lp
export LD_LIBRARY_PATH=/data/local/tmp/lp
export LPAC_APDU=at
export LPAC_HTTP=stdio
export LPAC_APDU_AT_DEVICE=/dev/at_mdm0
echo "===== chip info ====="
./lpac chip info
echo "===== profile list ====="
./lpac profile list
