# DuckUSB (中文增强版 / Whitelist Edition)

> 本仓库是基于上游 [Bouteillepleine/DuckUSB](https://github.com/Bouteillepleine/DuckUSB) 的中文增强分支，新增了 **应用白名单支持** 与 **完整简体中文本地化**。

## ✨ 分支特色 (Features)

* 🇨🇳 **完整简体中文界面**：包括 Xposed 模块、Zygisk 管理器与所有配置项说明。
* 🛡️ **应用白名单支持 (Xposed 模块)**：
  * 支持自定义白名单，允许特定应用（如 **Shizuku**、**MT 管理器**、**各类开发调试工具**）获取真实的 USB 调试及开发者选项状态。
  * 拦截与白名单逻辑直接在 `system_server` 中执行，无需向目标应用注入代码；配置修改通过 IPC 实时生效，**无需重启手机**。
* ⚡ **同步官方最新核心**：已同步上游最新改进（Xposed v2.1.7 / Zygisk v2.1.8），包括优化的 USB 属性屏蔽与强化的 Zygisk 注入引擎。
* 📦 **开箱即用构建**：已内置开发签名密钥及通用 Gradle 配置，一键编译即可生成带有效签名的安装包。

---

# Original DuckUSB Introduction

[![Build APK](https://github.com/Bouteillepleine/DuckUSB/actions/workflows/build.yml/badge.svg)](https://github.com/Bouteillepleine/DuckUSB/actions/workflows/build.yml)

Makes apps read **USB debugging, wireless debugging and Developer Options as OFF while they stay really ON**, and hides the persistent *"USB debugging enabled"* notification.

Detectors don't read any real adb state — they ask the settings provider for `adb_enabled`, `adb_wifi_enabled` and `development_settings_enabled`.

Two builds, same behaviour. Pick one:

| | |
|---|---|
| **Xposed** (`:app`) | APK. Enable in LSPosed, tick the **`system`** scope entry, reboot. |
| **Zygisk** (`:zygisk`) | Flashable zip for KernelSU / Magisk, plus a manager APK. Flash, reboot. |

## How

One hook in `system_server`, on the settings provider's `call` **and** `query`, so the getter and a direct cursor read agree. Results are rewritten per caller by uid.

Nothing is injected into the apps being fooled — they have no hook residue in their own memory to find. Callers below uid 10000 (root, shell, system) and the OS file-transfer components always read the truth, so `adb`, MTP and the Settings toggle keep working.

## What it changes

| | |
|---|---|
| `adb_enabled`, `adb_wifi_enabled`, `development_settings_enabled` | `0` |
| `persist.sys.usb.config` | `mtp`, written to the property area only — the persisted value stays `adb`, so a reboot restores it and USB can never come up without adb |
| USB debugging notification | swallowed, and the one posted during boot is cancelled |

The manager shows the live hook count and every caller lied to since boot, and compares what it reads against the true values served from `system_server`.

## Build

```
git submodule update --init --recursive
./gradlew :app:assembleRelease            # Xposed APK
./gradlew :zygisk:zygote:assembleRelease  # Zygisk module zip
./gradlew :zygisk:app:assembleRelease     # Zygisk manager APK
```

JDK 21. The Zygisk half needs NDK 29 and CMake 3.31.6 (LSPlant + Dobby). Signing comes from a git-ignored `key.properties` or from `DUCKUSB_STORE_FILE` / `DUCKUSB_STORE_PASSWORD` / `DUCKUSB_KEY_ALIAS` / `DUCKUSB_KEY_PASSWORD`; without either it builds unsigned.

Don't rotate the signing key — Android identifies an app by its signature, so a differently signed build cannot update an installed one.

## Releases

The two variants ship independently, from prefixed tags: `xposed-v2.0.0` publishes the Xposed APK, `zygisk-v2.0.0` the module zip and its manager. A fix to one never forces a version bump on the other.

## Tested on

OnePlus 15 (CPH2747), OxygenOS, Android 16, KernelSU Next + ReZygisk + LSPosed. Both variants report clean on Duck Detector. Other ROMs are unknown, though the provider is matched by authority and the guards key off uid rather than OEM package names.
