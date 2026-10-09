package com.arcxya.doudizhu

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.json.JSONObject

/** Networking has its own queue: game scheduling routinely clears the table's Handler. */
internal class UpdateController(
    private val activity: Activity,
    private val changed: () -> Unit = {},
    private val source: UpdateSource = UpdateRepository(),
    private val prefs: SharedPreferences = activity.getSharedPreferences("updates", 0),
    private val now: () -> Long = System::currentTimeMillis,
    private val verify: (File, UpdateRelease) -> Unit = { file, release -> UpdateInstaller.verify(activity, file, release) }
) {
    companion object { private const val DAY = 24 * 60 * 60 * 1000L }
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var job: Future<*>? = null
    private var generation = 0
    private var destroyed = false
    private var foreground = false
    private var automaticRequest = false
    private var awaitingPermission = false
    private var dialog: AlertDialog? = null
    private var sectionStatus: TextView? = null
    private var checkButton: Button? = null
    private var detailStatus: TextView? = null
    private var progress: ProgressBar? = null
    private var readyFile: File? = null
    private var percent = 0
    internal var release: UpdateRelease? = null; private set
    internal var busy = false; private set
    internal var status = "自动检查新版，也可随时手动检查。"; private set
    internal val currentVersion = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
    internal val autoEnabled get() = prefs.getBoolean("automatic", true)
    internal val hasUpdate get() = release != null
    init {
        release = runCatching {
            val stored = JSONObject(prefs.getString("available_release", null) ?: return@runCatching null)
            UpdateRelease(stored.getString("version"), stored.getString("notes"), stored.getString("page"),
                stored.getString("url"), stored.getString("sha256"), stored.getLong("size"))
                .takeIf { UpdatePolicy.validateDownload(it) && UpdatePolicy.isNewer(it.version, currentVersion) }
        }.getOrNull()
        release?.let { status = "发现新版本 v${it.version}" }
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()
    private fun label(value: String, size: Float = 16f) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); setPadding(0, dp(6), 0, dp(6))
    }
    private fun alive() = !destroyed && !activity.isFinishing && !activity.isDestroyed

    fun onResume() {
        foreground = true
        if (awaitingPermission) {
            awaitingPermission = false
            if (activity.packageManager.canRequestPackageInstalls()) install()
            else { status = "尚未允许安装，可点击“安装更新”重试。"; refresh() }
        } else check(automatic = true)
        refresh()
        notifyAvailable()
    }
    fun onPause() { foreground = false }
    fun close() {
        destroyed = true; generation++; job?.cancel(true); worker.shutdownNow()
        ui.removeCallbacksAndMessages(null); dialog?.dismiss(); dialog = null; unbindSettings()
    }
    fun unbindSettings() { sectionStatus = null; checkButton = null }

    fun settingsView(): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(10))
        }
        column.addView(label("应用更新 · v$currentVersion", 20f).apply { setTextColor(0xffffd97e.toInt()) })
        column.addView(Switch(activity).apply {
            text = "自动检查更新"; tag = "update-auto"; textSize = 18f; minHeight = dp(48)
            setTextColor(Color.WHITE); isChecked = autoEnabled
            setOnCheckedChangeListener { _, enabled -> setAutomatic(enabled) }
        })
        column.addView(label("开启后每天在启动时检查一次。仅更新联网，牌局可离线游玩。", 14f))
        sectionStatus = label(status).apply { tag = "update-status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(sectionStatus)
        checkButton = Button(activity).apply {
            tag = "update-check"; isAllCaps = false; textSize = 17f; minHeight = dp(48)
            setOnClickListener { if (release != null) showDetails() else check(automatic = false) }
        }
        column.addView(checkButton); refresh()
        return column
    }
    internal fun setAutomatic(enabled: Boolean) {
        prefs.edit().putBoolean("automatic", enabled).apply()
        if (!enabled && busy && automaticRequest) {
            generation++; job?.cancel(true); busy = false; status = "已关闭自动检查，可手动检查更新。"; refresh()
        }
        if (enabled && foreground) check(automatic = true)
    }
    internal fun check(automatic: Boolean) {
        if (!alive() || busy || (automatic && (!autoEnabled || readyFile != null))) return
        val timestamp = now()
        val last = prefs.getLong("last_attempt", 0)
        if (automatic && last > 0 && timestamp >= last && timestamp - last < DAY) return
        prefs.edit().putLong("last_attempt", timestamp).apply()
        val ticket = ++generation
        automaticRequest = automatic; busy = true; status = "正在检查新版本…"; refresh()
        job = worker.submit {
            try {
                val found = source.latest(currentVersion)
                post(ticket) {
                    busy = false; release = found; readyFile = null
                    cacheRelease(found)
                    status = if (found == null) "已是最新版本（v$currentVersion）" else "发现新版本 v${found.version}"
                    refresh(); if (automatic) notifyAvailable()
                    if (!automatic && found != null && foreground) showDetails()
                }
            } catch (error: Exception) {
                post(ticket) {
                    busy = false
                    status = if (automatic) "暂时无法检查更新，可稍后手动重试。" else message(error)
                    refresh()
                }
            }
        }
    }
    private fun notifyAvailable() {
        val found = release ?: return
        if (foreground && autoEnabled && prefs.getString("notified_version", null) != found.version) {
            prefs.edit().putString("notified_version", found.version).apply()
            Toast.makeText(activity, "发现新版本 v${found.version}，可在设置中更新", Toast.LENGTH_LONG).show()
        }
    }
    private fun cacheRelease(found: UpdateRelease?) {
        val encoded = found?.let { JSONObject().put("version", it.version).put("notes", it.notes)
            .put("page", it.pageUrl).put("url", it.downloadUrl).put("sha256", it.sha256).put("size", it.sizeBytes).toString() }
        prefs.edit().putString("available_release", encoded).apply()
    }
    private fun message(error: Exception) = error.message?.take(240)?.takeIf { it.isNotBlank() }
        ?: "更新暂时不可用，请稍后重试。"
    private fun post(ticket: Int, action: () -> Unit) {
        ui.post { if (alive() && ticket == generation) action() }
    }
    private fun refresh() {
        sectionStatus?.text = status; detailStatus?.text = status
        checkButton?.text = if (hasUpdate) "查看更新" else if (busy) "正在检查…" else "检查更新"
        checkButton?.isEnabled = !busy || hasUpdate
        progress?.apply { visibility = if (busy) View.VISIBLE else View.GONE; progress = percent }
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            text = if (readyFile != null) "安装更新" else "下载更新"; isEnabled = !busy
        }
        dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = if (busy) "取消下载" else "稍后再说"
        dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = !busy
        if (alive() && foreground) changed()
    }
    private fun showDetails() {
        val found = release ?: return
        if (!foreground || dialog?.isShowing == true) return
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), dp(12)) }
        content.addView(label("v$currentVersion → v${found.version} · ${String.format(Locale.ROOT, "%.1f", found.sizeBytes / 1048576.0)} MB"))
        content.addView(label("下载会使用网络流量。安装由系统确认，正常覆盖安装会保留本地数据。", 14f))
        detailStatus = label(status).apply { tag = "update-detail-status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(detailStatus)
        progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { tag = "update-progress"; max = 100 }
        content.addView(progress)
        val notes = found.notes.take(12000).replace(Regex("(?m)^#{1,6}\\s+"), "")
            .replace(Regex("\\[([^\\]]+)\\]\\(https?://[^)]+\\)"), "$1").replace("**", "").replace("`", "")
        content.addView(label(notes.ifBlank { "此版本包含功能改进与问题修复。" }))
        val scroll = ScrollView(activity).apply { addView(content) }
        dialog = AlertDialog.Builder(activity).setTitle("发现新版本 v${found.version}").setView(scroll)
            .setPositiveButton("下载更新", null).setNegativeButton("稍后再说", null).setNeutralButton("重新检查", null).create().also { popup ->
                popup.setOnCancelListener { if (busy) cancelDownload() }
                popup.setOnDismissListener { dialog = null; detailStatus = null; progress = null }
                popup.show()
                popup.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(0xff283e78.toInt()); cornerRadius = dp(10).toFloat(); setStroke(dp(2), 0xff769cda.toInt()) })
                popup.getButton(AlertDialog.BUTTON_POSITIVE).apply { tag = "update-action"; setOnClickListener { if (readyFile != null) install() else download() } }
                popup.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { if (busy) cancelDownload(); popup.dismiss() }
                popup.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { if (!busy) { popup.dismiss(); check(automatic = false) } }
            }
        refresh()
    }
    internal fun download() {
        val found = release ?: return
        if (busy || !alive()) return
        automaticRequest = false; busy = true; percent = 0; status = "正在下载更新：0%"; refresh()
        val ticket = ++generation
        job = worker.submit {
            var file: File? = null
            try {
                file = source.download(found, File(activity.cacheDir, "updates")) { amount ->
                    post(ticket) { percent = amount.coerceIn(0, 100); status = "正在下载更新：$percent%"; refresh() }
                }
                post(ticket) { status = "下载完成，正在校验安装包…"; refresh() }
                verify(file, found)
                val verified = file
                post(ticket) { readyFile = verified; busy = false; status = "安装包已校验，点击“安装更新”继续。"; refresh() }
            } catch (error: Exception) {
                file?.delete()
                post(ticket) { readyFile = null; busy = false; status = message(error); refresh() }
            }
        }
    }
    internal fun cancelDownload() {
        generation++; job?.cancel(true); busy = false; readyFile = null
        status = "下载已取消，可稍后重试。"; refresh()
    }
    private fun install() {
        val file = readyFile ?: return
        val found = release ?: return
        if (busy || !foreground || !alive()) return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity).setTitle("允许安装更新")
                .setMessage("请在系统设置中允许“闲来斗地主”安装应用，返回后继续此次更新。")
                .setPositiveButton("前往设置") { _, _ ->
                    try {
                        awaitingPermission = true
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    } catch (_: Exception) { awaitingPermission = false; status = "无法打开安装权限设置，请在手机设置中允许安装。"; refresh() }
                }.setNegativeButton("取消", null).show()
            return
        }
        val ticket = ++generation
        automaticRequest = false; busy = true; status = "正在准备安装…"; refresh()
        job = worker.submit {
            try {
                verify(file, found)
                post(ticket) {
                    busy = false
                    if (foreground) {
                        try { activity.startActivity(UpdateInstaller.installIntent(activity, file)); status = "已打开系统安装界面；取消后可再次安装。" }
                        catch (_: Exception) { status = "无法打开系统安装界面，请稍后重试。" }
                    } else status = "安装包已就绪，返回后点击“安装更新”。"
                    refresh()
                }
            } catch (error: Exception) { post(ticket) { busy = false; readyFile = null; status = message(error); refresh() } }
        }
    }
}
