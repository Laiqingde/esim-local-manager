# lpac QMI/QRTR APDU 后端（无 glib）

`qrtr.c` 是一个 lpac 的 APDU 后端，直接用内核 `AF_QIPCRTR` 套接字跟高通 modem 的
QMI UIM 服务对话（OPEN_LOGICAL_CHANNEL 0x42 / SEND_APDU 0x3B / LOGICAL_CHANNEL 0x3F），
**不依赖 glib / libqmi**，因此很容易用 Android NDK 交叉编译。

## 为什么需要它

高通 Android 机上，modem 的 AT 口通常不暴露 SIM 逻辑通道命令（CCHO/CSIM），
而 lpac 官方的 `qmi`/`qmi_qrtr` 后端依赖 qmi-glib（glib），在 Android 上交叉编译很麻烦。
这个后端绕开这些，直接发裸 QMI 消息。

## 构建（Android arm64）

```bash
git clone --recurse-submodules https://github.com/estkme-group/lpac
cp qrtr.c lpac/driver/apdu/qrtr.c
# 在 lpac/driver/CMakeLists.txt 里加一个 target（见下方片段）

cmake -S lpac -B build \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
  -DBUILD_TESTING=OFF \
  -DLPAC_WITH_APDU_PCSC=OFF -DLPAC_WITH_HTTP_CURL=OFF \
  -DLPAC_WITH_APDU_QRTR=ON -DSTANDALONE_MODE=ON
cmake --build build
```

CMakeLists 片段（加到 `driver/CMakeLists.txt`）：

```cmake
if (LPAC_WITH_APDU_QRTR)
    add_library(driver_apdu_qrtr SHARED apdu/qrtr.c)
    target_link_libraries(driver_apdu_qrtr PRIVATE euicc euicc-drivers cjson-ext lpac-utils)
    set_target_properties(driver_apdu_qrtr PROPERTIES PREFIX "")
    install(TARGETS driver_apdu_qrtr LIBRARY DESTINATION ${DRIVER_INSTALL_PATH})
endif()
```

## 运行

```bash
LPAC_APDU=qrtr \
LPAC_APDU_QRTR_SLOT=2 \   # eUICC 所在物理 slot（1 基；折叠屏常为 2），不确定就逐个试
LPAC_HTTP=stdio \
LD_LIBRARY_PATH=. ./lpac chip info
```

环境变量：
- `LPAC_APDU_QRTR_SLOT`：物理 slot（默认 1）。读不到就试 1/2/0/3，返回 `eidValue` 的即为正确值。

> 注意：这颗芯片若支持 MEP（多启用 profile），`profile enable ... 1`（带 refresh）后
> 卡会短暂重置，期间 QMI 可能对所有 slot 返回 error；稍等会自愈。
