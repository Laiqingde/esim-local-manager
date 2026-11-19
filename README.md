# 国行 eSIM 解锁 / Samsung China eSIM Unlock

绕过三星**国行（CHC）机型**的 eSIM 限制，让系统**原生「添加 eSIM」**可以添加境外运营商 eSIM，
并解除「最多 2 张 eSIM」的数量上限。提供两种方案，均需 root（手机内置 eUICC 的操作绕不开特权）。

> ⚠️ 仅供在**自己拥有的设备、管理自己拥有的 eSIM** 时使用，请遵守当地法律法规与运营商条款。

## 原理（反编译三星 LPA 得到）

国行 eSIM 的限制全部在三星 **LPA 应用层**按设备区域属性判断，**eUICC 芯片本身无任何限制**：

| 限制 | 判定（三星代码） |
|---|---|
| 境外卡下载硬闸 | `"CHC"==ro.boot.carrierid && "CN"!=ro.csc.countryiso_code` |
| 下载按定位选 TLS 证书 | `"CHC"==ro.csc.sales_code` → 用 GPS 判断在不在大陆 |
| eSIM 数量上限 | `ro.csc.countryiso_code=="CN" ? 2 : 20` |
| 需关开发者选项 | `userBinary && development_settings_enabled` |

## 方案一：区域解锁 App（推荐，用原生 UI）

一键开关，用 root(`resetprop`) **临时**修改设备区域标识，让系统原生 LPA 自己放行。
解锁后用系统「设置 → 连接 → SIM 管理器 → 添加 eSIM」即可添加境外卡，新卡自动进 SIM 管理器。

- APK：[Releases](../../releases) 下载安装，打开开关即可。
- 代码：[`esim-app/`](esim-app/)。
- 自动适配**台版刷国行**与**纯国行**（纯国行会连 `ro.boot.carrierid` 一起处理）。
- 全部为运行时修改，**重启手机自动还原**，不写系统分区、不动 Bootloader。

构建：
```bash
cd esim-app && ./gradlew :app:assembleRelease
```

## 方案二：lpac + QMI/QRTR 直管 eUICC（不经厂商 LPA）

不碰区域属性，直接通过裸 QMI/QRTR 跟 modem 的 UIM 服务对话，用 lpac 下载/启用/删除 profile。
适合不想改区域标识、或想完全脱离厂商 LPA 的场景。

- 自写的不依赖 glib 的 lpac APDU 后端：[`lpac-qrtr/`](lpac-qrtr/)。
- 命令行工具与 QRTR 服务探测：[`tools/`](tools/)。
- 关键 env：`LPAC_APDU=qrtr`、`LPAC_APDU_QRTR_SLOT=<eUICC物理slot>`、`LPAC_HTTP=stdio`。

## 无 root 行不行？

- 下载证书闸基于 **GPS 定位**：**人在境外/港澳**时，原生即可添加境外 eSIM，无需任何修改。
- **在大陆境内无 root 没有干净绕法**（模拟定位需要开发者选项，又会触发另一道拦截）。
  境内要原生体验仍需 root（方案一）；或用方案二（需 root 但不挑地点）。

## 适用

- 三星 eSIM 机型，CSC/固件为国行（CHC）。
- 需要 root：Magisk / KernelSU / APatch。
- 方案二额外要求：高通平台 + arm64、modem 在 QRTR 暴露 UIM 服务。

## 致谢

- [lpac](https://github.com/estkme-group/lpac) —— 方案二的 eUICC 操作内核。

## 许可

自有代码以 MIT 发布，见 [LICENSE](LICENSE)。内置 lpac 二进制版权归其上游项目所有。
