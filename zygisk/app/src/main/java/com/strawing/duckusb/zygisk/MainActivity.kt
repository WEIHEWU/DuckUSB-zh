package com.strawing.duckusb.zygisk

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.strawing.duckusb.common.Bridge
import com.strawing.duckusb.common.Config
import com.strawing.duckusb.common.DuckConfig
import com.strawing.duckusb.ui.DuckUi
import com.strawing.duckusb.ui.Health
import com.strawing.duckusb.ui.Theming

class MainActivity : AppCompatActivity() {

    private companion object {
        const val STATE_TAB = "tab"
        val PROP_KEYS =
            listOf("persist.sys.usb.config", "sys.usb.state", "sys.usb.config", "init.svc.adbd")
    }

    private lateinit var ui: DuckUi

    private var tab = DuckUi.TAB_STATUS
    private var config = DuckConfig()
    private var rootAvailable = false
    private var moduleInstalled = false
    private var hooksKilled = false
    private var live = false
    private var serviceState: Bundle? = null
    private var records: List<Bundle> = emptyList()
    private var recordsOpen = false
    private var zygiskFlavor = "unknown"
    private var moduleVersion: String? = null
    private var rootSettings: Map<String, String> = emptyMap()
    private var serviceSettings: Map<String, String> = emptyMap()
    private var rootProps: Map<String, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theming.restore(this)
        savedInstanceState?.getInt(STATE_TAB, 0)?.takeIf { it != 0 }?.let { tab = it }
        ui = DuckUi(this)
        setContentView(ui.scaffold(onTabSelected = { tab = it; renderContent() }, startTab = tab))
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    // ------------------------------------------------------------------ data

    private fun reload() {
        live = runCatching { System.getProperty(Config.LIVE_PROPERTY) != null }.getOrDefault(false)
        render()
        background {
            val snapshot = Root.snapshot(Config.SPOOF_KEYS.toList(), PROP_KEYS)
            val state = ServiceClient.state(this)
            val callers = ServiceClient.records(this)
                .sortedByDescending { it.getInt(Bridge.REC_COUNT) }
            val truth = ServiceClient.trueSettings(this, Config.SPOOF_KEYS.toTypedArray())
            runOnUiThread {
                rootAvailable = snapshot.rootAvailable
                moduleInstalled = snapshot.moduleInstalled
                hooksKilled = snapshot.hooksKilled
                snapshot.config?.let { config = it }
                moduleVersion = snapshot.moduleVersion
                zygiskFlavor = snapshot.zygisk
                rootSettings = snapshot.settings
                rootProps = snapshot.props
                serviceState = state
                records = callers
                serviceSettings = truth
                render()
            }
        }
    }

    private fun background(work: () -> Unit) {
        Thread { runCatching(work) }.apply { isDaemon = true }.start()
    }

    private fun serviceLive(): Boolean = serviceState != null

    private fun save() {
        val snapshot = config.copy()
        background {
            val installed = moduleInstalled || Root.moduleInstalled()
            if (installed) {
                moduleInstalled = true
                val written = Root.writeConfig(snapshot)
                Root.syncPackages(snapshot)
                Root.refreshDescription()
                if (!written) runOnUiThread { toast("无法写入模块配置") }
            } else {
                runOnUiThread { toast("无法连接模块，设置未保存") }
            }
            ServiceClient.push(this, snapshot)
        }
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        ui.setHeader(getString(R.string.app_name)) { recreate() }
        renderContent()
    }

    private fun renderContent() {
        ui.content.removeAllViews()
        when (tab) {
            DuckUi.TAB_BEHAVIOUR -> {
                ui.content.addView(ui.sectionLabel("行为设置"))
                ui.content.addView(controlsCard())
                ui.content.addView(ui.sectionLabel("作用域"))
                ui.content.addView(scopeCard())
            }
            DuckUi.TAB_DIAGNOSTICS -> {
                ui.content.addView(ui.sectionLabel("诊断信息"))
                ui.content.addView(diagnosticsCard())
                ui.content.addView(ui.sectionLabel("当前应用读取值 vs 设备真实值"))
                ui.content.addView(readingsCard())
            }
            else -> {
                ui.content.addView(statusCard())
                ui.content.addView(ui.sectionLabel("模块信息"))
                ui.content.addView(infoCard())
                ui.content.addView(
                    ui.footer(
                        "模块 ID ${Config.MODULE_ID} · 框架与通知相关修改需重启生效"
                    )
                )
            }
        }
    }

    private fun statusCard(): View {
        val spoofing = serviceLive() && !config.paused && config.spoofSettings
        val healthy = rootAvailable && moduleInstalled && !hooksKilled && spoofing
        val health = when {
            config.paused -> Health.PAUSED
            healthy -> Health.GOOD
            else -> Health.BAD
        }
        val title = when {
            !rootAvailable -> "无 Root 权限"
            !moduleInstalled -> "模块未安装"
            hooksKilled -> "Hook 已停用"
            config.paused -> "已暂停"
            !config.hookSystemServer || !config.frameworkMode -> "框架模式已关闭"
            !serviceLive() -> "需要重启"
            !config.spoofSettings -> "设置伪装已关闭"
            else -> "已激活 — 框架模式"
        }
        val detail = when {
            !rootAvailable -> "请在 Root 管理器中授予 Root 权限，以便应用读写模块配置。"
            !moduleInstalled -> "请在 Magisk / KernelSU 中刷入模块 zip 包，然后重启设备。"
            hooksKilled -> "紧急开关或开机看门狗禁用了所有 Hook。请在下方重新启用，然后重启。"
            config.paused -> "所有配置项恢复读取真实值。Hook 将保持加载直至下次重启。"
            !config.hookSystemServer || !config.frameworkMode -> "当前未生效伪装。请在下方开启框架模式，然后重启。"
            !serviceLive() -> "框架模式已开启，但 system_server 中的 Hook 尚未就绪。重启前伪装不会生效。"
            !config.spoofSettings -> "Hook 已就绪，但设置伪装开关已被关闭。"
            config.frameworkAllApps -> "Hook 在 system_server 中正常运行，已覆盖所有应用"
            config.targets.isEmpty() -> "Hook 已就绪，但未选择目标应用。请在「作用域」中勾选。"
            else -> "Hook 在 system_server 中正常运行，已覆盖 ${config.targets.size} 个应用"
        }
        return ui.heroCard(title, detail, health, "暂停", config.paused, moduleInstalled) { checked ->
            config.paused = checked
            save()
            renderContent()
        }
    }

    private fun infoCard(): View {
        val card = ui.outlinedCard()
        val col = ui.column(10, 10)
        col.addView(ui.infoRow("模块版本", moduleVersion ?: "未安装"))
        col.addView(ui.infoRow("管理器版本", appVersion()))
        col.addView(ui.infoRow("Hook 引擎", "LSPlant + Dobby"))
        col.addView(ui.infoRow("Zygisk 方案", zygiskFlavor))
        col.addView(ui.infoRow("本应用注入状态", if (live) "已注入" else "未注入"))
        col.addView(ui.infoRow("Android 版本", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
        col.addView(ui.infoRow("设备型号", "${Build.MANUFACTURER} ${Build.MODEL}"))
        card.addView(col)
        return card
    }

    private fun diagnosticsCard(): View {
        val card = ui.outlinedCard()
        card.setOnClickListener { reload() }
        val col = ui.column()
        val state = serviceState
        if (state == null) {
            col.addView(
                ui.bodyText(
                    "system_server 服务未响应。可能是框架模式已关闭，或者模块尚未重启加载。"
                )
            )
            card.addView(col)
            return card
        }
        col.addView(ui.statRow("服务版本", state.getInt(Bridge.STATE_VERSION).toString()))
        col.addView(ui.statRow("Provider Hook 数", state.getInt(Bridge.STATE_HOOKS).toString()))
        col.addView(
            ui.statRow("就绪耗时", "开机 ${state.getLong(Bridge.STATE_INSTALLED_AT) / 1000} 秒后")
        )
        col.addView(ui.statRow("已伪装应用数", records.size.toString()))
        col.addView(
            ui.statRow("已拦截通知数", state.getInt(Bridge.STATE_NOTIF_BLOCKED).toString())
        )
        col.addView(ui.thinDivider())
        col.addView(ui.expander("开机以来已被伪装的调用方 (${records.size})", recordsOpen) {
            recordsOpen = !recordsOpen
            renderContent()
        })
        if (recordsOpen) {
            if (records.isEmpty()) {
                col.addView(ui.noteRow("暂无记录。需要有应用读取相应设置项才会触发。"))
            }
            for (record in records) {
                val uid = record.getInt(Bridge.REC_UID)
                val count = record.getInt(Bridge.REC_COUNT)
                val keys = record.getStringArrayList(Bridge.REC_KEYS).orEmpty()
                col.addView(ui.callerRow(labelForUid(uid), "${count}次 ${shortKeys(keys)}"))
            }
            if (records.isNotEmpty()) {
                col.addView(ui.linkRow("清空记录") {
                    records = emptyList()
                    renderContent()
                    background {
                        ServiceClient.clearRecords(this@MainActivity)
                        runOnUiThread { reload() }
                    }
                })
            }
        }
        card.addView(col)
        return card
    }

    private fun readingsCard(): View {
        val card = ui.outlinedCard()
        val col = ui.column()
        for (key in Config.SPOOF_KEYS) {
            col.addView(ui.readingRow(key, globalSetting(key), serviceSettings[key] ?: rootSettings[key]))
        }
        col.addView(ui.thinDivider())
        for (key in PROP_KEYS) {
            col.addView(ui.readingRow(key, systemProperty(key), rootProps[key]))
        }
        col.addView(
            ui.noteRow(
                "左侧标签为本应用读取到的值，右侧标签为 system_server（或 Root 方式）读取到的系统真实值。" +
                    "DuckUSB 从不对自身进行伪装，因此若两边数值不一致，说明设备上有其它模块/工具正在对本应用进行伪装。"
            )
        )
        card.addView(col)
        return card
    }

    private fun controlsCard(): View {
        val card = ui.outlinedCard()
        val col = ui.column(6, 6)
        col.addView(ui.toggleRow(
            DuckUi.Icons.framework, "框架模式",
            "在 system_server 中统一处理伪装，无需向被欺骗的目标应用注入代码。需要重启生效。",
            config.frameworkMode,
        ) {
            config.frameworkMode = it
            config.hookSystemServer = it || config.hideNotif
            save()
            renderContent()
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.allApps, "覆盖所有应用",
            "无需配置作用域名单。Shell、系统 UID 和文件传输应用仍读取真实值。",
            config.frameworkAllApps,
        ) {
            config.frameworkAllApps = it
            save()
            renderContent()
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.usb, "伪装 USB 调试",
            "adb_enabled · adb_wifi_enabled · 开发者选项 → 0",
            config.spoofSettings,
        ) {
            config.spoofSettings = it
            save()
            renderContent()
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.query, "覆盖 Query 查询路径",
            "同时重写对设置数据表的游标 (Cursor) 查询，使直接 query 与 getter 结果完全一致。",
            config.coverQueryPath,
        ) {
            config.coverQueryPath = it
            save()
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.notification, "隐藏调试通知",
            "吞掉常驻的「已开启 USB 调试」系统通知。需要重启生效。",
            config.hideNotif,
        ) {
            config.hideNotif = it
            config.hookSystemServer = it || config.frameworkMode
            save()
            renderContent()
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.tag, "屏蔽 USB 配置属性",
            "使属性区中的 persist.sys.usb.config 显示为 mtp，init.svc.adbd 显示为 stopped，让所有读取途径保持一致。adbd 仍保持运行。重启后恢复。",
            config.spoofProps,
        ) {
            config.spoofProps = it
            save()
            renderContent()
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.allApps, "屏蔽 USB 功能列表",
            "仅在作用域应用内通过 libc 读取 Hook 将 sys.usb.config 和 sys.usb.state 伪装为 mtp。真实属性值不受影响，因此 USB adb 功能正常工作。代价：Hook 仅存在于注入进程中，开启此项会将目标应用加入注入列表。单开框架模式则完全不注入目标进程。",
            config.spoofUsbState,
        ) {
            config.spoofUsbState = it
            save()
            renderContent()
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.log, "详细日志",
            "每次伪装读取输出一行 logcat 日志。除非排错否则保持关闭。",
            config.verboseLog,
        ) {
            config.verboseLog = it
            save()
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.power, "紧急开关 (Kill Switch)",
            "下次开机时禁用所有 Hook（无需卸载模块）。",
            hooksKilled,
        ) {
            hooksKilled = it
            renderContent()
            background {
                Root.setHooksKilled(it)
                Root.refreshDescription()
            }
        })
        card.addView(col)
        return card
    }

    private fun scopeCard(): View {
        val card = ui.outlinedCard()
        val col = ui.column(16, 16)
        col.addView(ui.bodyText(when {
            config.frameworkAllApps -> "设备上的所有应用读取到的 USB 调试状态均为关闭，无需维护名单。"
            config.targets.isEmpty() -> "尚未选择任何应用。请勾选想要欺骗的检测应用，或开启「覆盖所有应用」。"
            else -> "已覆盖 ${config.targets.size} 个应用，设备上的其它应用读取真实状态。"
        }))
        if (!config.frameworkAllApps) {
            col.addView(ui.linkRow("选择应用") {
                startActivity(Intent(this@MainActivity, ScopeActivity::class.java))
            })
        }
        card.addView(col)
        return card
    }

    // ------------------------------------------------------------------ plumbing

    private fun shortKeys(keys: List<String>): String = keys.joinToString(", ") {
        when (it) {
            "development_settings_enabled" -> "dev"
            "adb_wifi_enabled" -> "adb_wifi"
            "adb_enabled" -> "adb"
            "adb_allowed_connection_time" -> "adb_timeout"
            "verifier_verify_adb_installs" -> "adb_verify"
            "stay_on_while_plugged_in" -> "stay_awake"
            else -> it
        }
    }

    private fun labelForUid(uid: Int): String {
        val packages = runCatching { packageManager.getPackagesForUid(uid) }.getOrNull()
        val pkg = packages?.firstOrNull() ?: return "uid $uid"
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private fun globalSetting(key: String): String = try {
        Settings.Global.getString(contentResolver, key) ?: "—"
    } catch (_: Throwable) {
        "—"
    }

    private fun systemProperty(key: String): String = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String).orEmpty().ifEmpty { "—" }
    } catch (_: Throwable) {
        "—"
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }
}
