package com.strawing.duckusb

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.strawing.duckusb.service.DuckServiceClient
import com.strawing.duckusb.ui.DuckUi
import com.strawing.duckusb.ui.Health
import com.strawing.duckusb.ui.Theming
import io.github.libxposed.service.XposedService

class MainActivity : AppCompatActivity() {

    private companion object {
        const val PREF_RECORDS_EXPANDED = "svc_records_expanded"
        const val STATE_TAB = "tab"
        val PROP_KEYS =
            listOf("persist.sys.usb.config", "sys.usb.state", "sys.usb.config", "init.svc.adbd")
        val SETTING_KEYS =
            listOf("adb_enabled", "adb_wifi_enabled", "development_settings_enabled")
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var ui: DuckUi

    private var tab = DuckUi.TAB_STATUS
    private var usingRemotePrefs = false
    private var recordsOpen = false

    private var svcState: Bundle? = null
    private var svcRecords: List<Bundle> = emptyList()
    private var rootSettings: Map<String, String> = emptyMap()
    private var serviceSettings: Map<String, String> = emptyMap()
    private var rootProps: Map<String, String> = emptyMap()
    private var rootAvailable = false
    private var propMaskInstalled = false
    private var updateLabel: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        Theming.restore(this)

        prefs = resolvePrefs()
        savedInstanceState?.getInt(STATE_TAB, 0)?.takeIf { it != 0 }?.let { tab = it }
        recordsOpen = prefs.getBoolean(PREF_RECORDS_EXPANDED, false)

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

    private val serviceListener: (XposedService?) -> Unit = { svc ->
        runOnUiThread {
            if ((svc != null) != usingRemotePrefs && !isFinishing) recreate()
        }
    }

    override fun onStart() {
        super.onStart()
        DuckApp.addListener(serviceListener)
    }

    override fun onStop() {
        DuckApp.removeListener(serviceListener)
        super.onStop()
    }

    // ------------------------------------------------------------------ data

    private fun reload() {
        render()
        background {
            val state = DuckServiceClient.get(this)?.let { runCatching { it.state }.getOrNull() }
            val records = DuckServiceClient.get(this)
                ?.let { runCatching { it.records }.getOrNull() }
                .orEmpty()
                .sortedByDescending { it.getInt("count") }
            val truth = DuckServiceClient.get(this)?.let {
                runCatching { it.getTrueSettings(SETTING_KEYS.toTypedArray()) }.getOrNull()
            }
            val snapshot = RootTools.snapshot(SETTING_KEYS, PROP_KEYS)
            runOnUiThread {
                serviceSettings = truth?.let { bundle ->
                    SETTING_KEYS.mapNotNull { key ->
                        bundle.getString(key)?.takeIf { it.isNotEmpty() }?.let { key to it }
                    }.toMap()
                } ?: emptyMap()
                svcState = state
                svcRecords = records
                rootAvailable = snapshot.rootAvailable
                rootSettings = snapshot.settings
                rootProps = snapshot.props
                propMaskInstalled = snapshot.propMaskInstalled
                render()
            }
        }
    }

    private fun background(work: () -> Unit) {
        Thread { runCatching(work) }.apply { isDaemon = true }.start()
    }

    private fun frameworkLive(): Boolean = svcState != null

    private fun flag(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    private fun setFlag(key: String, value: Boolean) {
        prefs.edit()?.putBoolean(key, value)?.apply()
        background {
            DuckServiceClient.pushConfig(
                this,
                flag(Config.KEY_PAUSED, false),
                flag(Config.KEY_SPOOF, true),
                flag(Config.KEY_HIDE_NOTIF, true),
            )
        }
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
                ui.content.addView(ui.sectionLabel("覆盖说明"))
                ui.content.addView(coverageCard())
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
                ui.content.addView(ui.footer("Xposed 模块 · 通知拦截相关的修改需重启生效"))
            }
        }
    }

    private fun statusCard(): View {
        val paused = flag(Config.KEY_PAUSED, false)
        val spoofing = frameworkLive() && !paused && flag(Config.KEY_SPOOF, true)
        val health = when {
            paused -> Health.PAUSED
            spoofing -> Health.GOOD
            else -> Health.BAD
        }
        val title = when {
            !frameworkLive() -> "未激活"
            paused -> "已暂停"
            !flag(Config.KEY_SPOOF, true) -> "设置伪装已关闭"
            else -> "已激活 — 框架模式"
        }
        val detail = when {
            !frameworkLive() -> "请在 LSPosed 中启用 DuckUSB，勾选「系统框架 (System Framework)」作用域，然后重启设备。"
            paused -> "所有项恢复读取真实值。Hook 仍保持加载，取消暂停后立即恢复生效。"
            !flag(Config.KEY_SPOOF, true) -> "Hook 已就绪，但设置伪装开关已关闭。"
            else -> "Hook 在 system_server 中正常运行，已覆盖所有应用"
        }
        return ui.heroCard(title, detail, health, "暂停", paused, true) { checked ->
            setFlag(Config.KEY_PAUSED, checked)
            renderContent()
        }
    }

    private fun infoCard(): View {
        val card = ui.outlinedCard()
        val col = ui.column(10, 10)
        col.addView(ui.infoRow("模块版本", appVersion()))
        col.addView(ui.infoRow("Hook 引擎", frameworkName() ?: "Xposed"))
        col.addView(ui.infoRow("系统框架作用域", when (systemScoped()) {
            true -> "已勾选"
            false -> "未勾选"
            null -> "未知"
        }))
        col.addView(ui.infoRow("Hook 生效状态", if (frameworkLive()) "已生效" else "未生效"))
        col.addView(ui.infoRow("Root 权限", if (rootAvailable) "已获取" else "未获取"))
        col.addView(ui.infoRow("Android 版本", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
        col.addView(ui.infoRow("设备型号", "${Build.MANUFACTURER} ${Build.MODEL}"))
        card.addView(col)
        return card
    }

    private fun controlsCard(): View {
        val card = ui.outlinedCard()
        val col = ui.column(6, 6)
        col.addView(ui.toggleRow(
            DuckUi.Icons.usb, "伪装 USB 调试",
            "adb_enabled · adb_wifi_enabled · 开发者选项 → 0",
            flag(Config.KEY_SPOOF, true),
        ) { setFlag(Config.KEY_SPOOF, it); renderContent() })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.query, "覆盖 Query 查询路径",
            "同时重写对设置数据表的游标 (Cursor) 查询，使直接 query 与 getter 结果完全一致。",
            flag(Config.KEY_COVER_QUERY, true),
        ) { setFlag(Config.KEY_COVER_QUERY, it) })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.notification, "隐藏调试通知",
            "吞掉常驻的「已开启 USB 调试」系统通知。需要重启生效。",
            flag(Config.KEY_HIDE_NOTIF, true),
        ) { setFlag(Config.KEY_HIDE_NOTIF, it) })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.tag, "屏蔽 USB 配置属性",
            "使属性区中的 persist.sys.usb.config 显示为 mtp，init.svc.adbd 显示为 stopped，让所有读取途径保持一致。adbd 仍保持运行。重启后恢复。需要 Root 权限。",
            propMaskInstalled,
        ) { wanted ->
            propMaskInstalled = wanted
            setFlag(Config.KEY_MASK_USB_PROP, wanted)
            renderContent()
            background {
                val ok = if (wanted) RootTools.setPropMask(true, flag(Config.KEY_MASK_USB_STATE, false)) else {
                    RootTools.setPropMask(false)
                    RootTools.restoreProp()
                }
                runOnUiThread {
                    if (!ok) {
                        propMaskInstalled = !wanted
                        Toast.makeText(
                            this@MainActivity,
                            "需要 Root 权限 — 请在授权管理中授予 DuckUSB 超级用户权限，然后重试。",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    reload()
                }
            }
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.allApps, "屏蔽 USB 功能列表",
            "sys.usb.config 与 sys.usb.state 将读取为 mtp，隐藏 adb 功能本身，防御直接读取这俩属性的检测。system_server 自带的 USB 管理服务也会受影响，除非确有需要否则建议保持关闭。",
            flag(Config.KEY_MASK_USB_STATE, false), rootAvailable,
        ) { wanted ->
            setFlag(Config.KEY_MASK_USB_STATE, wanted)
            renderContent()
            background {
                if (propMaskInstalled) RootTools.setPropMask(true, wanted)
                if (!wanted) RootTools.restoreProp()
                runOnUiThread { reload() }
            }
        })
        col.addView(ui.thinDivider())
        col.addView(ui.toggleRow(
            DuckUi.Icons.log, "详细日志",
            "每次伪装读取输出一行 logcat 日志。除非排错否则保持关闭。",
            flag(Config.KEY_VERBOSE_LOG, false),
        ) { setFlag(Config.KEY_VERBOSE_LOG, it) })
        col.addView(ui.thinDivider())
        col.addView(updateRow())
        card.addView(col)
        return card
    }

    private fun updateRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.dp(12), 0, ui.dp(6))
        }
        row.addView(ui.bodyText(updateLabel ?: "检查 GitHub 是否有新版本发布。"))
        row.addView(ui.linkRow("检查更新") {
            updateLabel = "正在检查…"
            renderContent()
            background {
                val result = Updater.check(appVersionCode(), frameworkApi())
                val text = when (result) {
                    is Updater.Result.Available -> "发现新版本 ${result.versionName} 可用。"
                    is Updater.Result.Blocked ->
                        "版本 ${result.versionName} 需要 Xposed API ${result.needsApi}；当前框架版本为 ${result.hasApi}。"
                    Updater.Result.UpToDate -> "已是最新版本。"
                    is Updater.Result.Failed -> "检查更新失败：${result.reason}"
                }
                runOnUiThread { updateLabel = text; renderContent() }
            }
        })
        return row
    }

    private fun coverageCard(): View {
        val card = ui.outlinedCard()
        val col = ui.column(16, 16)
        col.addView(ui.bodyText(
            "设备上的所有应用读取到的 USB 调试状态均为关闭。数据在 " +
                "system_server 中统一伪装，无需向被欺骗的目标应用注入任何代码，也" +
                "无需维护作用域名单。"
        ))
        if (systemScoped() == false) {
            col.addView(ui.noteRow(
                "DuckUSB 未勾选系统框架 (System Framework) 作用域，Hook 无法注入。" +
                    "请在 LSPosed 中勾选并重启手机。"
            ))
        }
        card.addView(col)
        return card
    }

    private fun diagnosticsCard(): View {
        val card = ui.outlinedCard()
        card.setOnClickListener { reload() }
        val col = ui.column()
        val state = svcState
        if (state == null) {
            col.addView(ui.bodyText(
                "system_server 服务未响应。可能是模块未启用、未勾选系统框架作用域，或者安装后尚未重启设备。"
            ))
            card.addView(col)
            return card
        }
        col.addView(ui.statRow("服务版本", state.getInt("version").toString()))
        col.addView(ui.statRow("Provider Hook 数", state.getInt("hookCount").toString()))
        col.addView(ui.statRow("就绪耗时", "开机 ${state.getLong("installedAtRealtimeMs") / 1000} 秒后"))
        col.addView(ui.statRow("已伪装应用数", svcRecords.size.toString()))
        col.addView(ui.thinDivider())
        col.addView(ui.expander("开机以来已被伪装的调用方 (${svcRecords.size})", recordsOpen) {
            recordsOpen = !recordsOpen
            prefs.edit()?.putBoolean(PREF_RECORDS_EXPANDED, recordsOpen)?.apply()
            renderContent()
        })
        if (recordsOpen) {
            if (svcRecords.isEmpty()) {
                col.addView(ui.noteRow("暂无记录。需要有应用读取相应设置项才会触发。"))
            }
            for (record in svcRecords) {
                col.addView(ui.callerRow(
                    record.getString("label") ?: "UID ${record.getInt("uid")}",
                    "${record.getInt("count")}次 ${shortKeys(record.getString("keys").orEmpty())}",
                ))
            }
        }
        card.addView(col)
        return card
    }

    private fun readingsCard(): View {
        val card = ui.outlinedCard()
        val col = ui.column()
        for (key in SETTING_KEYS) {
            col.addView(ui.readingRow(key, globalSetting(key), serviceSettings[key] ?: rootSettings[key]))
        }
        col.addView(ui.thinDivider())
        for (key in PROP_KEYS) {
            col.addView(ui.readingRow(key, systemProperty(key), rootProps[key]))
        }
        col.addView(ui.noteRow(
            "左侧标签为本应用读取到的值，右侧标签为 system_server（或 Root 方式）读取到的系统真实值。" +
                "DuckUSB 从不对自身进行伪装，因此若两边数值不一致，说明设备上有其它模块/工具正在对本应用进行伪装。"
        ))
        card.addView(col)
        return card
    }

    private fun shortKeys(keys: String): String = keys
        .split(", ")
        .joinToString(", ") {
            when (it) {
                "development_settings_enabled" -> "dev"
                "adb_wifi_enabled" -> "adb_wifi"
                "adb_enabled" -> "adb"
                else -> it
            }
        }

    // ------------------------------------------------------------------ plumbing

    private fun resolvePrefs(): SharedPreferences {
        DuckApp.service?.let { svc ->
            runCatching { svc.getRemotePreferences(Config.PREFS_NAME) }.getOrNull()?.let { remote ->
                usingRemotePrefs = true
                importLegacyPrefs(remote)
                return remote
            }
        }
        usingRemotePrefs = false
        return getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun importLegacyPrefs(remote: SharedPreferences) {
        if (remote.getBoolean(Config.KEY_PREFS_IMPORTED, false)) return
        @Suppress("DEPRECATION")
        val legacy = runCatching {
            getSharedPreferences(Config.PREFS_NAME, Context.MODE_WORLD_READABLE)
        }.getOrElse {
            runCatching { getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE) }.getOrNull()
        }
        val edit = remote.edit() ?: return
        legacy?.let {
            for (key in Config.BOOLEAN_KEYS) {
                if (it.contains(key)) edit.putBoolean(key, it.getBoolean(key, false))
            }
        }
        edit.putBoolean(Config.KEY_PREFS_IMPORTED, true).apply()
    }

    private val moduleScope: List<String>? by lazy {
        runCatching { DuckApp.service?.scope }.getOrNull()
    }

    private fun systemScoped(): Boolean? =
        moduleScope?.any { it == "system" || it == "android" }

    private fun frameworkName(): String? = runCatching {
        DuckApp.service?.let { "${it.frameworkName} ${it.frameworkVersion}" }
    }.getOrNull()

    private fun frameworkApi(): Int? = runCatching { DuckApp.service?.apiVersion }.getOrNull()

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

    private fun appVersionCode(): Long = try {
        packageManager.getPackageInfo(packageName, 0).longVersionCode
    } catch (_: Throwable) {
        0L
    }
}
