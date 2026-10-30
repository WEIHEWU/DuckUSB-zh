# DuckUSB (中文黑名单增强版 / Blacklist Edition)

> 本仓库是基于上游 [Bouteillepleine/DuckUSB](https://github.com/Bouteillepleine/DuckUSB) 的中文增强分支，重构为 **纯黑名单模式**，并包含 **完整简体中文本地化**。

## ✨ 分支特色 (Features)

* 🇨🇳 **完整简体中文界面**：包括 Xposed 模块、Zygisk 管理器与所有配置项说明。
* 🎯 **纯黑名单模式 (Xposed 模块)**：
  * **默认不隐藏**：未勾选的应用不受任何干扰，正常感知真实的 USB 调试及开发者选项状态。
  * **精准定向隐藏**：仅对用户在黑名单中勾选的目标应用（如银行、打卡、游戏等检测类软件）隐藏 USB 调试。
  * **免重启即时生效**：拦截判定统一在 `system_server` 中执行，无需向目标应用注入代码；黑名单修改通过 IPC 实时同步，**即刻生效，无需重启设备**。
* ⚡ **同步官方最新核心**：已同步上游最新改进（Xposed v2.1.8 / Zygisk v2.1.9），包括优化的 USB 属性屏蔽与强化的 Zygisk 注入引擎。
* 📦 **开箱即用构建**：已内置开发签名密钥及通用 Gradle 配置，一键编译即可生成带有效签名的安装包。

## 📥 最新预编译 APK 下载 (Direct GitHub Downloads)

| 版本 | 说明 | 下载直达 |
| :--- | :--- | :--- |
| **Xposed 模块 (推荐)** | v2.1.8 纯黑名单汉化版 | 🔗 [下载 DuckUSB-v2.1.8-blacklist-zh.apk](https://github.com/WEIHEWU/DuckUSB-zh/raw/main/release/DuckUSB-v2.1.8-blacklist-zh.apk) |
| **Zygisk 管理器** | v2.1.9 汉化版 | 🔗 [下载 DuckUSB-Zygisk-Manager-v2.1.9-zh.apk](https://github.com/WEIHEWU/DuckUSB-zh/raw/main/release/DuckUSB-Zygisk-Manager-v2.1.9-zh.apk) |
| **GitHub Releases 页面** | 官方发行版与历史版本 | 📦 [前往 Releases 查看发布包](https://github.com/WEIHEWU/DuckUSB-zh/releases) |

---

# Original DuckUSB Introduction

[![Build APK](https://github.com/Bouteillepleine/DuckUSB/actions/workflows/build.yml/badge.svg)](https://github.com/Bouteillepleine/DuckUSB/actions/workflows/build.yml)

Apps read USB debugging, wireless debugging and Developer Options as OFF while they stay ON. The "USB debugging enabled" notification is hidden.

| | |
|---|---|
| **Xposed** (`:app`) | APK. Enable in LSPosed, tick the `system` scope, reboot. |
| **Zygisk** (`:zygisk`) | Zip for KernelSU / Magisk plus a manager APK. Flash, reboot. |

## How

One hook in `system_server` on the settings provider's `call` and `query`, rewritten per caller uid. Nothing is injected into the fooled apps. Uids below 10000 and the OS file-transfer components read the truth, so adb, MTP and the Settings toggle keep working.

| | |
|---|---|
| `adb_enabled`, `adb_wifi_enabled`, `development_settings_enabled` | `0` |
| `persist.sys.usb.config` | `mtp` in the property area only; the persisted `adb` survives reboot |
| USB debugging notification | swallowed, boot-time one cancelled |

## Build

```
git submodule update --init --recursive
./gradlew :app:assembleRelease            # Xposed APK
./gradlew :zygisk:zygote:assembleRelease  # Zygisk module zip
./gradlew :zygisk:app:assembleRelease     # Zygisk manager APK
```

JDK 21; Zygisk needs NDK 29 and CMake 3.31.6. Unsigned without signing material.

## Releases

Tags `xposed-vX.Y.Z` and `zygisk-vX.Y.Z` publish each variant independently.

## Tested on

OnePlus 15 (CPH2747), OxygenOS, Android 16, KernelSU Next + ReZygisk + LSPosed. Clean on Duck Detector. Other ROMs untested; the provider is matched by authority and the guards by uid, not OEM package names.
