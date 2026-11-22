package dev.multisystem.client

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels

/** Only Compose renders the app; the platform file picker still uses Android intents. */
class MainActivity : ComponentActivity() {
    private val model: ManagerViewModel by viewModels()
    private var selectionReady: () -> Unit = {}
    private val files = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(::persistPermission)
            model.selected(uris)
            selectionReady()
        }
    }
    private val folder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistPermission(uri)
            model.scanFolder(uri) { selectionReady() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ElysiaApp(model, onFiles = { if (model.validateSelection()) files.launch(arrayOf("*/*")) },
                onFolder = { if (model.validateSelection()) folder.launch(null) },
                onSelectionReady = { selectionReady = it }, onOpenUrl = ::openUrl, onJoinGroup = ::joinGroup)
        }
    }
    override fun onStart() {
        super.onStart()
        InstallationService.listen(model.serviceListener)
        model.syncInstallation()
    }
    override fun onStop() {
        InstallationService.unlisten(model.serviceListener)
        super.onStop()
    }
    private fun persistPermission(uri: Uri) {
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { }
    }
    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show() }
    }
    private fun joinGroup() {
        val urls = listOf("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=811309060&card_type=group&source=qrcode",
            "mqqwpa://im/chat?chat_type=group&uin=811309060")
        for (url in urls) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); return }
            catch (_: Exception) { }
        }
        Toast.makeText(this, "无法调起 QQ，请安装 QQ 后重试", Toast.LENGTH_LONG).show()
    }
}
