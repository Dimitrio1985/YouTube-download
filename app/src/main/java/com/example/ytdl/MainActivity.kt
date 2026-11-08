package com.example.ytdl

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val QUALITIES = listOf("Лучшее", "1080p", "720p", "480p", "Только аудио (mp3)")

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Downloads.setFolder(uri.toString())
        }
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        handle(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppUi(sharedText, { sharedText = null }, { pickFolder.launch(null) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            sharedText = Regex("https?://\\S+").find(t)?.value ?: t
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUi(shared: String?, clearShared: () -> Unit, pick: () -> Unit) {
    var settings by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(false) }
    val list by Downloads.items.collectAsState()
    val folder by Downloads.folder.collectAsState()

    LaunchedEffect(shared) { if (shared != null) dialog = true }
    BackHandler(settings) { settings = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (settings) "Настройки" else "Загрузки") },
                actions = { if (!settings) TextButton(onClick = { settings = true }) { Text("Настройки") } }
            )
        },
        floatingActionButton = {
            if (!settings) FloatingActionButton(onClick = { dialog = true }) { Text("+", fontSize = 28.sp) }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            if (settings) SettingsScreen(folder, pick) else ListScreen(list)
        }
    }

    if (dialog) {
        AddDialog(
            initial = shared ?: "",
            onDismiss = { dialog = false; clearShared() },
            onOk = { url, q -> Downloads.add(url, q); dialog = false; clearShared() }
        )
    }
}

@Composable
fun ListScreen(list: List<Item>) {
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Пока пусто. Нажмите «+»") }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(list, key = { it.id }) { ItemCard(it) }
    }
}

@Composable
fun ItemCard(item: Item) {
    var confirm by remember { mutableStateOf(false) }
    val done = item.status == Status.DONE

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(statusText(item), style = MaterialTheme.typography.bodySmall)
            if (item.status == Status.DOWNLOADING || item.status == Status.PAUSED) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
            }
            Row {
                when (item.status) {
                    Status.DOWNLOADING, Status.QUEUED ->
                        TextButton(onClick = { Downloads.pause(item.id) }) { Text("Пауза") }
                    Status.PAUSED, Status.ERROR ->
                        TextButton(onClick = { Downloads.start(item.id) }) { Text("Продолжить") }
                    else -> {}
                }
                TextButton(onClick = { confirm = true }) { Text("Удалить") }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Удалить загрузку?") },
            confirmButton = {
                TextButton(onClick = { Downloads.remove(item.id, true); confirm = false }) {
                    Text(if (done) "Вместе с файлом" else "Удалить")
                }
            },
            dismissButton = {
                if (done) TextButton(onClick = { Downloads.remove(item.id, false); confirm = false }) {
                    Text("Только из списка")
                } else TextButton(onClick = { confirm = false }) { Text("Отмена") }
            }
        )
    }
}

fun statusText(i: Item): String = when (i.status) {
    Status.QUEUED -> "В очереди"
    Status.DOWNLOADING -> "${(i.progress * 100).toInt()}% · ${i.quality}" + if (i.info.isNotBlank()) " · ${i.info}" else ""
    Status.PAUSED -> "На паузе · ${(i.progress * 100).toInt()}%"
    Status.DONE -> "Готово · ${i.quality}"
    Status.ERROR -> "Ошибка: ${i.info}"
}

@Composable
fun SettingsScreen(folder: String?, pick: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text("Папка сохранения", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(if (folder == null) "Загрузки/YT (по умолчанию)" else Downloads.folderName(folder))
        Spacer(Modifier.height(12.dp))
        Button(onClick = pick) { Text("Выбрать папку") }
        if (folder != null) {
            TextButton(onClick = { Downloads.setFolder(null) }) { Text("Сбросить на «Загрузки»") }
        }
    }
}

@Composable
fun AddDialog(initial: String, onDismiss: () -> Unit, onOk: (String, String) -> Unit) {
    var url by remember { mutableStateOf(initial) }
    var q by remember { mutableStateOf(QUALITIES[0]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая загрузка") },
        text = {
            Column {
                OutlinedTextField(url, { url = it }, label = { Text("Ссылка на видео") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                QUALITIES.forEach { opt ->
                    Row(
                        Modifier.fillMaxWidth().clickable { q = opt },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = q == opt, onClick = { q = opt })
                        Text(opt)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (url.isNotBlank()) onOk(url, q) }) { Text("Скачать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
