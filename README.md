# eSIM Local Manager（本地 eSIM 管理 App）

一个 **root 下直接管理手机内置 eUICC** 的 Android App：通过裸 QMI/QRTR 跟高通 modem 的
UIM 服务对话，用内置的 [lpac](https://github.com/estkme-group/lpac) 完成 eSIM 的
**下载 / 启用 / 停用 / 删除 / 改名**，不经过厂商自带的 LPA。

因为完全走底层 eUICC 通道，所以**不受厂商 LPA 的区域/运营商限制**——
例如某些机型「在某地区不允许添加境外运营商 eSIM」这类应用层限制，对本工具不适用。

> ⚠️ 仅供在**自己拥有的设备、管理自己拥有的 eSIM** 时使用。请遵守当地法律法规与运营商条款。

## 运行条件

- **必须有 root**（Magisk / KernelSU / APatch，临时或常驻均可，App 需要能调起 `su`）
- **高通平台 + arm64**，且 modem 在 QRTR 上暴露 UIM 服务（绝大多数高通 Android 机满足）
- Android 8.0+（minSdk 26）

## 功能

- 读取芯片信息（EID / 剩余空间）
- 列出所有 profile（运营商 / ICCID / 启用状态）
- 下载 eSIM：**粘贴或扫码**激活码 `LPA:1$地址$MatchingID`
- 启用 / 停用 / 删除 / 改名（启用带 refresh，基带会重读）
- 自动探测 eUICC 所在 slot

## 使用小贴士（常见问题）

- **下载完成后如果「读取芯片失败」**：这是刚下载完 eUICC 还在收尾/短暂忙导致的，
  **重启一下 App 即可解决**（重开会自动重新探测并读取）。
- **SIM 卡管理器里看不到对应卡片**：新下载的 profile 默认是「未启用」，系统 SIM 管理器
  只显示已启用/已识别的卡。**在本 App 里对那张卡点一下「启用」即可**——启用会触发基带
  重读，之后它就会出现在系统 SIM 管理器中。

## 安装

从 [Releases](../../releases) 下载 APK 安装；首次运行会请求 root 授权（请在你的 root 管理器里放行）。

## 自行构建

App 为纯 Kotlin 壳，已内置编好的 lpac arm64 二进制（见 `esim-app/app/src/main/assets/bin/`），
直接构建即可：

```bash
cd esim-app
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

- 想用自己的签名：在 `esim-app/` 下建 `keystore.properties`（参考仓库内示例），
  指向你的 keystore；不建则 release 自动回退 debug 签名。
- 想重新编译 lpac + QMI/QRTR 后端：见 [`lpac-qrtr/`](lpac-qrtr/)。

## 命令行版（无需装 App）

`tools/` 下有纯命令行方案：把 lpac 二进制 push 到 `/data/local/tmp`，用 root 执行；
下载时用 `relay.py` 在电脑侧代发 HTTPS（手机侧只做 APDU）。详见 `tools/` 内脚本。

## 目录

| 路径 | 说明 |
|---|---|
| `esim-app/` | Android App（Kotlin），内置 lpac 二进制 |
| `lpac-qrtr/` | 自写的 lpac QMI/QRTR APDU 后端（`qrtr.c`）+ 构建说明 |
| `tools/` | 命令行脚本、QRTR 服务探测工具 |
| `CHANGELOG.md` | 完整的排查与实现记录 |

## 致谢

- [lpac](https://github.com/estkme-group/lpac) —— 本项目用它作为 eUICC 操作内核。

## 许可

本仓库自有代码以 MIT 许可发布，见 [LICENSE](LICENSE)。
内置的 lpac 二进制版权归其上游项目所有，遵循其各自许可。
