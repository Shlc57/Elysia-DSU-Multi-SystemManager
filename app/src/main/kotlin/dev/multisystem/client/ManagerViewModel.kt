package dev.multisystem.client

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class UiStyle { Miuix, Material }
internal data class Confirmation(val title: String, val message: String, val action: String, val slot: String = "")

/** UI state is independent of either component library. Root operations remain in the Java backend. */
internal class ManagerViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application
    private val prefs = app.getSharedPreferences("settings", 0)
    private val backend = MultiSystemBackend()
    var style by mutableStateOf(if (prefs.getString("compose_style", "miuix") == "material") UiStyle.Material else UiStyle.Miuix)
        private set
    var themeMode by mutableStateOf(loadTheme())
        private set
    var accent by mutableStateOf(prefs.getInt("accent_color", 0xff2196f3.toInt()))
        private set
    var snapshot by mutableStateOf<MultiSystemBackend.Snapshot?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set
    var operationBusy by mutableStateOf(false)
        private set
    var serviceBusy by mutableStateOf(InstallationService.isBusy())
        private set
    var installMessage by mutableStateOf(InstallationService.message.orEmpty())
        private set
    var installProgress by mutableStateOf(InstallationService.progressPercent)
        private set
    var confirmation by mutableStateOf<Confirmation?>(null)
    var logs by mutableStateOf(loadLogs())
        private set
    var pendingNames by mutableStateOf(saved.get<ArrayList<String>>("image_names")?.toList() ?: emptyList())
        private set
    var pendingUris by mutableStateOf(saved.get<ArrayList<String>>("image_uris")?.map(Uri::parse) ?: emptyList())
        private set
    var slotInput by mutableStateOf(saved.get<String>("slot_input") ?: "")
        private set
    var userdataInput by mutableStateOf(saved.get<String>("userdata_input") ?: "8")
        private set
    var slotError by mutableStateOf("")
        private set
    private var pendingSlot = saved.get<String>("pending_slot") ?: ""
    private var pendingData = saved.get<Long>("pending_data") ?: 0L
    private var serviceGeneration = prefs.getInt("service_log_generation", -1)
    private var serviceLogIndex = prefs.getInt("service_log_index", 0)
    private var terminalGeneration = prefs.getInt("compose_terminal_generation", -1)
    val serviceListener = Runnable { syncInstallation() }

    val busy get() = loading || operationBusy || serviceBusy
    val canAdd get() = !busy && pendingUris.isEmpty() && snapshot != null && snapshot?.runningDsu != true
    val canInstall get() = !busy && pendingUris.isNotEmpty() && pendingSlot.isNotEmpty()
            && snapshot?.ready() == true && snapshot?.runningDsu == false
    val deviceLabel get() = backend.deviceLabel()
    val currentSlot get() = snapshot?.let {
        if (it.runningDsu) it.runningSlot.ifEmpty { "DSU（槽位未知）" } else "主系统"
    } ?: "正在检测…"

    init { refresh() }

    private fun loadTheme(): Int {
        if (prefs.getInt("theme_schema", 0) == 0) {
            val old = prefs.getInt("theme", -1)
            val migrated = if (old == 0) 2 else if (old == 1) 1 else 0
            prefs.edit().putInt("theme", migrated).putInt("theme_schema", 1).apply()
            return migrated
        }
        return prefs.getInt("theme", 0).coerceIn(0, 3)
    }

    fun updateStyle(value: UiStyle) {
        style = value
        prefs.edit().putString("compose_style", if (value == UiStyle.Miuix) "miuix" else "material").apply()
    }
    fun setTheme(value: Int) { themeMode = value; prefs.edit().putInt("theme", value).apply() }
    fun updateAccent(value: Int) { accent = value or 0xff000000.toInt(); prefs.edit().putInt("accent_color", accent).apply() }
    fun setSlot(value: String) {
        val accepted = UiInputRules.filterSlot(value)
        slotError = if (value != accepted) "仅允许 1–48 位英文字母、数字和下划线" else ""
        slotInput = accepted
        saved["slot_input"] = accepted
    }
    fun setUserdata(value: String) { userdataInput = value; saved["userdata_input"] = value }

    fun refresh() {
        if (loading || operationBusy || serviceBusy) return
        loading = true
        viewModelScope.launch {
            try {
                snapshot = withContext(Dispatchers.IO) { backend.load() }
                error = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message.orEmpty() }
            finally { loading = false }
        }
    }

    fun requestRoot() = operation("请求 Root") {
        val result = CommandRunner().root("id")
        check(result.exitCode == 0 && result.output.contains("uid=0")) { "Root 管理器未授予本应用权限。" }
    }

    fun validateSelection(): Boolean {
        if (busy || snapshot?.runningDsu == true) { error = "请先回主系统再选择镜像。"; return false }
        val id = MultiSystemBackend.validateSlotId(slotInput)
        if (id == null) { slotError = "槽位名称需为 1–48 位英文字母、数字和下划线"; return false }
        if (snapshot?.slots?.any { it.id == id } == true) { slotError = "该槽位已存在，不会覆盖旧系统"; return false }
        val bytes = UiInputRules.userdataBytes(userdataInput)
        if (bytes == null) { error = "userdata 大小需在 1–64 GiB 之间。"; return false }
        pendingSlot = id
        pendingData = bytes
        saved["pending_slot"] = pendingSlot
        saved["pending_data"] = pendingData
        slotError = ""
        return true
    }

    fun selected(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pendingUris = uris.toList()
        pendingNames = uris.map(::displayName)
        saved["image_uris"] = ArrayList(uris.map(Uri::toString))
        saved["image_names"] = ArrayList(pendingNames)
    }

    fun scanFolder(tree: Uri, onSelected: () -> Unit) {
        if (busy) return
        operationBusy = true
        viewModelScope.launch {
            try {
                val uris = withContext(Dispatchers.IO) {
                    val result = arrayListOf<Uri>()
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(1)
                            if (MultiSystemBackend.partitionFromFilename(name) != null || RomImageImporter.isSuperImage(name)) {
                                result.add(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)))
                            }
                        }
                    }
                    check(result.isNotEmpty()) { "文件夹内没有支持的分区镜像。" }
                    result
                }
                selected(uris)
                onSelected()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "读取文件夹失败：${e.message}" }
            finally { operationBusy = false }
        }
    }

    fun clearSelection() {
        if (serviceBusy) return
        pendingUris = emptyList(); pendingNames = emptyList(); pendingSlot = ""; pendingData = 0L
        saved.remove<ArrayList<String>>("image_uris"); saved.remove<ArrayList<String>>("image_names")
        saved.remove<String>("pending_slot"); saved.remove<Long>("pending_data")
    }

    fun confirmInstall() {
        if (!canInstall) return
        confirmation = Confirmation("安装新的 DSU 系统？", "目标槽：$pendingSlot\nuserdata：${formatBytes(pendingData)}\n"
                + pendingNames.joinToString("\n") + "\n\n将解析并写入新的 DSU 槽。不会覆盖已有槽，安装成功不保证 ROM 可以启动。", "install")
    }
    fun confirmSwitch(slot: SystemSlot) {
        if (!canSwitch(slot)) return
        confirmation = Confirmation("切换启动系统？", "下一次启动目标：${slot.name}。仅设置单次启动，随后重启设备。"
                + "请先备份并准备好 DSU 回退或 Recovery 路径。", "switch", slot.id)
    }
    fun canSwitch(slot: SystemSlot): Boolean {
        val current = snapshot ?: return false
        return !busy && current.ready() && slot.bootable && !slot.active && (!current.runningDsu
                || (current.runningSlot.isNotEmpty() && current.runningSlot == current.activeSlot))
    }
    fun confirmReturn() {
        if (!busy && snapshot?.runningDsu == true) confirmation = Confirmation("重启回主系统？",
            "禁用 DSU 启动并重启。副系统槽位及其数据保留。", "return")
    }
    fun confirmDelete(slot: SystemSlot) {
        if (!busy && snapshot?.runningDsu == false && !slot.active) confirmation = Confirmation("删除副系统？",
            "删除“${slot.name}”及其数据，不可撤销。", "delete", slot.id)
    }
    fun confirmClearLogs() { confirmation = Confirmation("清空日志？", "仅清除应用内操作记录，不修改 DSU 槽位。", "logs") }
    fun showSafety() { confirmation = Confirmation("安全策略", "安装仅创建新命名槽；禁止在 DSU 内安装或删除。"
        + "切槽需单次启动并明确确认。目标 ROM 启动兼容性仍需逐设备验证。", "info") }

    fun executeConfirmation() {
        val request = confirmation ?: return
        confirmation = null
        when (request.action) {
            "install" -> {
                if (!canInstall) return
                val intent = Intent(app, InstallationService::class.java).setAction(InstallationService.PREPARE)
                    .putExtra("slot", pendingSlot).putExtra("userdata", pendingData)
                    .putParcelableArrayListExtra("uris", ArrayList(pendingUris))
                    .putStringArrayListExtra("names", ArrayList(pendingNames))
                try { app.startForegroundService(intent); serviceBusy = true }
                catch (e: Exception) { error = "无法启动安装服务：${e.message}" }
            }
            "switch" -> operation("切换槽位 ${request.slot}") {
                if (backend.load().runningDsu) backend.enableOnceFromDsu(request.slot) else backend.enableOnce(request.slot)
                backend.reboot()
            }
            "return" -> operation("回主系统") { backend.disableAndReboot() }
            "delete" -> operation("删除槽位 ${request.slot}") { backend.delete(request.slot) }
            "logs" -> { logs = emptyList(); persistLogs() }
        }
    }

    private fun operation(label: String, action: () -> Unit) {
        if (busy) return
        operationBusy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                appendLog("$label：完成")
                error = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "$label：${e.message}"; appendLog(error) }
            finally { operationBusy = false }
            refresh()
        }
    }

    fun syncInstallation() {
        val generation = InstallationService.operationGeneration()
        if (generation > 0) {
            if (generation != serviceGeneration) { serviceGeneration = generation; serviceLogIndex = 0 }
            val events = InstallationService.operationEventsFrom(serviceLogIndex)
            events.forEach(::appendLog)
            serviceLogIndex += events.size
            prefs.edit().putInt("service_log_generation", serviceGeneration).putInt("service_log_index", serviceLogIndex).apply()
        }
        serviceBusy = InstallationService.isBusy()
        installMessage = InstallationService.message.orEmpty()
        installProgress = InstallationService.progressPercent.coerceIn(0, 100)
        if (generation > 0 && generation != terminalGeneration &&
            (InstallationService.phase == InstallationService.Phase.SUCCEEDED || InstallationService.phase == InstallationService.Phase.FAILED)) {
            terminalGeneration = generation
            prefs.edit().putInt("compose_terminal_generation", generation).apply()
            appendLog(installMessage)
            if (InstallationService.phase == InstallationService.Phase.SUCCEEDED) { clearSelection(); refresh() }
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment ?: "system.img"
    }
    private fun loadLogs(): List<String> = try {
        val array = JSONArray(prefs.getString("logs", "[]"))
        (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank).takeLast(100).let {
            if (prefs.getBoolean("logs_ascending", false)) it else it.reversed()
        }
    } catch (_: Exception) { emptyList() }
    private fun appendLog(line: String) {
        if (line.isBlank()) return
        logs = (logs + "${DateFormat.getTimeInstance().format(Date())}  ${line.take(6000)}").takeLast(100)
        persistLogs()
    }
    private fun persistLogs() { prefs.edit().putString("logs", JSONArray(logs).toString()).putBoolean("logs_ascending", true).apply() }

    fun exportLogs() {
        if (logs.isEmpty()) { error = "暂无可导出的日志"; return }
        val content = logs.joinToString("\n", postfix = "\n")
        val filename = "elysia-install-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.txt"
        operation("导出日志到 Download/$filename") {
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "无法创建日志文件" }
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { it.write(content.toByteArray(Charsets.UTF_8)) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
        }
    }
}

internal fun formatBytes(bytes: Long): String = String.format(Locale.ROOT, "%.2f GiB", bytes / 1073741824.0)
