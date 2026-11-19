# Changelog — samsung-esim (绕过国行 CHC 区域锁写入境外 eSIM)

本项目用于在一台**硬件为台版、固件被刷成国行 CHC** 的三星 Galaxy Fold8 Ultra (SM-F9760 国行)上，
绕过三星国行 LPA 的区域限制，直接通过 modem 的 APDU 通道向 eUICC 芯片写入境外 eSIM profile。
机主确认设备为本人所有、eSIM 为本人所有，属正当使用，非破解。

## [Unreleased] — 2026-11-19

### 结论（排查得到的事实）
- 设备型号 SM-F9760，当前固件 F9760ZCU3AZJ8，Android 17，CSC=CHC（国行单 CSC）。
  证据：`ro.csc.sales_code=CHC`、`/optics/configs/carriers/` 与 `/prism/etc/carriers/` 下仅 CHC。
- **区域锁位置**：eSIM 的「境内不许加境外运营商」限制在三星私有组件
  `cn.com.samsung.euicc.phoneserver`(EuiccServerCN) + CHC 的 OMC 策略里，**按设备 CSC 硬判**，
  不看插入 SIM 的 MCC、也不看当前网络/定位/IP。
  证据：公开 `carrier_config` 无区域下载开关；LPA 换成了 CN 专版（海外版是 `com.samsung.android.euicc`）。
  推论（未在芯片层面二次验证）：限制仅作用于「新增下载/激活」环节，芯片里已存在的 profile 不受影响——
  日志里存在一张 disabled 的 Google Fi profile(iccid 608F, mccMnc=130042) 可佐证。
- 因为锁在三星 app 层、不在 eUICC 芯片层，故**用开源 LPA 直接走 APDU 通道即可绕过**。
- 固件是纯 CHC 单 CSC 包，`*#272#` 在系统内切 CSC 到台湾区**不可行**（菜单只列固件内打包的 CSC）。
- 刷国行固件覆盖了 boot/kernel，使此前台版下的 KernelSU root 失效（内核无 KSU 补丁）。
  机主改用**临时 root**（uname 内核为原厂 abogki；su 由临时手段提供，context u:r:ksu），已授予 shell。
- 可用 APDU 通道：`/dev/at_mdm0`（modem AT 口，CCHO/CCHC/CGLA）。无 QRTR dev 节点、无 qmux socket。

### 变更（本次产出）
- 用 Android NDK r27c 交叉编译 lpac v2.3.0(源码 estkme-group/lpac) 为 Android arm64：
  开 AT 后端(at + at_csim)、关 pcsc/libcurl、STANDALONE_MODE。
  产物（9 个文件）已 push 到设备 `/data/local/tmp/lp/`：
  lpac 主程序 + libeuicc.so + 各 driver .so。
  为什么不用官方 release：官方只有 glibc 版，Android bionic 跑不起来，必须 NDK 重编。
  为什么不编 libcurl：改用 lpac 的 `stdio` HTTP 后端，HTTPS 由本机(mac)代发，省去 Android 侧 curl 依赖。
- 脚本：
  - `dl.sh`（设备侧）：设好 env，`lpac profile download -a <激活码>`，stderr 入 err.log。
  - `relay.py`（mac 侧）：读 lpac stdout 的 http 请求 JSON，用本机 urllib 代发 HTTPS(默认不校验证书，
    与 lpac 默认 LPAC_HTTP_SSL_VERIFY=false 一致，ES9+ 的鉴权由 eUICC 自身完成)，回写 rcode/rx。
  - `info.sh`（设备侧）：`chip info` + `profile list`，用于验证 AT 通道是否打通。

### 当前状态（未完成项）
- **尚未实机验证**：读 eUICC（chip info）与下载两步需机主本人在设备上执行
  。成败取决于 `at` 后端能否在该机型
  通过 CCHO 打开 ISD-R 逻辑通道；若失败，备选 `at_csim` 后端（已一并编入）。
- 待机主提供境外 eSIM 的激活码后执行下载。

### 回滚 / 清理
- 全部改动仅在设备 `/data/local/tmp/lp/`，`rm -rf` 即可清除，不触碰系统分区、不改 CSC、不动 BL。

## [Unreleased] 追加 — 2026-11-19 晚

### 结论（已实机验证）
- AT 后端整条路**不可用**：9 个节点(/dev/at_mdm0、at_usb0-2、smd7/8/11、smdcntl8、ttyGS0) × {at, at_csim}
  全部失败——modem 的 AT 口不支持 SIM APDU（CCHO/CSIM）。/dev/at_mdm0 非 tty，已把 termios 改为可选。
- **QMI over QRTR 可行且已打通**：QRTR 上存在 UIM 服务(service=0x0b, node=0, port=220)。
- **eUICC 在 slot 2**（不是 slot 1）。实证：slot=2 成功读出
  EID=89033023427110100100083972913522，EUICCInfo2 完整（profileVersion 2.3.1，
  freeNonVolatileMemory=839160）。slot=1 报 QMI result=1 error=80，slot=0 error=48，slot=3 error=94。
- 至此确认：国行 CHC 区域锁只在三星 LPA 应用层，**绕开它走裸 QMI/QRTR 直连 eUICC 完全不受限**。

### 变更
- 新增自写 lpac APDU 后端 `driver/apdu/qrtr.c`（CMake 开关 LPAC_WITH_APDU_QRTR）：
  纯 AF_QIPCRTR + QMI UIM 消息(OPEN_LOGICAL_CHANNEL 0x0042 / SEND_APDU 0x003B /
  LOGICAL_CHANNEL 0x003F)，**不依赖 glib/libqmi**，规避了 Android 上交叉编译 glib 的难题。
  TLV 定义取自 libqmi qmi-service-uim.json，QMI 服务消息帧按标准(flags+txn16+msgid16+len16+TLV)自行封装。
  slot 可用 LPAC_APDU_QRTR_SLOT 指定，默认 1，本机须设 2。
- 设备脚本改用 qrtr+slot2：dl.sh(下载)、q.sh(通用)；qrtr_probe(QRTR 服务枚举，诊断用)。

### 待办
- 机主提供境外 eSIM 激活码后，用 relay.py 走 stdio-HTTP 中转完成 download → enable → notification。

## [Unreleased] 追加 — 2026-11-19 深夜：打包成 Android App

### 变更
- 新增 Android App `esim-app/`（applicationId com.gao.esimlocal，Kotlin + 原生视图）：
  把已编好的 lpac + qrtr/http_stdio 等驱动打进 assets/bin，首次运行释放到 filesDir 并 chmod，
  以 root(su) 执行。功能：芯片信息(EID/剩余空间)、profile 列表、下载(扫码/粘贴激活码)、
  启用/停用/删除/改名、自动 notification process。
  - 下载走 lpac 的 stdio HTTP 后端，HTTPS 由 App 内 OkHttp 代发（Lpac.download 的中转循环），
    Android 侧不需 libcurl。
  - 自动探测 slot（试 2/1/0/3，chip info 返回 code=0 即命中），本机命中 slot 2。
  - 二维码用 zxing-android-embedded；需要 CAMERA + INTERNET 权限。
- 工具链：本机装 openjdk@17 + gradle(wrapper 锁 8.7) + AGP 8.5.2 + Android SDK 34。
- 正式签名：keystore esim-app/esim-release.jks（alias esim；口令见 app/build.gradle.kts，
  为本地自签个人证书，非线上密钥）。APK 产物 app/build/outputs/apk/release/app-release.apk，
  已 v1+v2+v3 签名并安装验证通过。

### 实测结论（App）
- 在本机真机验证：App 自动获 root、探到 slot 2、读出 EID=…083972913522、剩余 797KB，
  正确列出两张 profile（Google Fi 已启用；ESIM_ACCESS_CARRIER/Carrier 未启用，ICCID …4157）。
- 全流程不依赖三星 LPA，国行 CHC 区域锁不参与。

### 边界
- **必须有 root**（KernelSU 临时或常驻），否则 App 首屏提示「未获得 root」。
- 目前仅 arm64-v8a、高通 QRTR UIM 机型；非高通或无 root 不可用。

### Bug 修复 — 2026-11-19：App 下载 TLS 失败
- 现象：App 下载 eSIM 卡在 es9p_initiate_authentication，日志报
  `java.security.cert.CertPathValidatorException: Trust anchor for certification path not found`。
- 根因：lpac 侧虽设 LPAC_HTTP_SSL_VERIFY=false，但真正发 HTTPS 的是 App 内 OkHttp，
  它默认做完整证书校验；SM-DP+(rsp-eu.simlessly.com) 证书链不在 Android 系统信任库。
  命令行版 relay.py 用 CERT_NONE 不校验所以无此问题。
- 修复：Lpac.insecureHttpClient() 用信任全部证书的 TrustManager + hostnameVerifier=true，
  与命令行一致。eSIM 下载真正鉴权由 eUICC 的 ES9+ 签名保证，传输层放宽安全。
  已重编重装，待复测下载成功。

### Bug 修复 2 — 2026-11-19：App 下载卡在 initiateAuthentication（根因）
- 现象：TLS 修好后，下载到 es9p_initiate_authentication 仍失败，SM-DP+ 返回
  HTTP 200 但 body 为 {"status":"Failed","statusCodeData":{"subjectCode":"0.0.0",
  "reasonCode":"0.0","message":"Unknown error"}}。两张全新激活码表现一致，
  排除「码已用过」。
- 根因：Lpac.doHttp 用 `body.toRequestBody("application/octet-stream")` 把请求
  Content-Type 强设为 octet-stream，而 SM-DP+ 的 ES9+ 要求 application/json。
  命令行版 relay.py 直接透传 lpac 给的 header(application/json) 所以能成；App 覆盖掉了。
- 修复：doHttp 改为从 lpac 传来的 headers 里取 Content-Type 作为 RequestBody 的 mediaType，
  其余 header 照传且不重复加 Content-Type。
- 复测：用新码 LPA:1$rsp-eu.simlessly.com$13253D75... 下载，四步握手
  (initiate/authenticate/getBPP/loadBPP) 全 Executed-Success，
  **结果 success，新 profile ESIM_ACCESS_CARRIER(ICCID 8932042000024917716) 已写入**，
  列表三张、剩余空间 797→775KB。App 端到端闭环验证通过。

### 诊断增强
- MainActivity.logln 落盘到 filesDir/applog.txt（切屏不丢日志，su cat 可取）。
- doHttp 对含 functionExecutionStatus/statusCode 的响应打印 body 前 600 字符。

### Bug 修复 3 + 行为说明 — 2026-11-19：新 profile 不进系统 SIM 管理器
- 现象：App 下载的 profile 在系统「SIM 管理器」看不到；命令行脚本下载的能看到。
- 根因：`lpac profile enable [iccid] [refreshflag]` 的 refreshflag 默认 false。
  App 原来调 `profile enable $iccid` 未带标志 → eUICC 启用但不向 modem 发 REFRESH
  → 基带不重读 → Android 订阅库/SIM 管理器不更新。三星 LPA 永远带 refresh。
- 修复：enable/disable 改为带 refreshflag=1（`profile enable $iccid 1`）。
  复测：带 refresh enable 后，Android dumpsys isub 新增 id=3(…4157,HK mcc454)、
  id=4(…7716,BE mcc206) 两条订阅，系统已识别。
- MEP 注意：这颗 eUICC 支持 MEP(多启用 profile)。enable 的 REFRESH 会短暂重置卡，
  期间 QMI 对所有 slot 返回 error=80(busy/incompatible)，属临时态，稍后自愈(实测 slot2 恢复)。
  无需重启。
- App 加固：refresh() 在缓存 slot 读失败时自动重置 slot 并重新探测；下载成功后等 2s 再刷新，
  避免读到空列表。
- QMI UIM 错误观察：slot 不对/卡忙时 error 码随 slot 不同(48/80/94)，正确 slot 才返回 eidValue。
