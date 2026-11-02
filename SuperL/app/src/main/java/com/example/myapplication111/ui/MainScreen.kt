package com.example.myapplication111.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.myapplication111.data.AudioCache
import com.example.myapplication111.data.FavoriteItem
import com.example.myapplication111.data.FavoritesAudioStore
import com.example.myapplication111.data.FavoritesRepository
import com.example.myapplication111.data.SettingsRepository
import com.example.myapplication111.data.VocabItem
import com.example.myapplication111.data.VocabularyRepository
import com.example.myapplication111.tts.TtsPlaybackService
import com.example.myapplication111.util.FileReader
import com.example.myapplication111.util.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenVocabulary: () -> Unit,
    textContent: String,
    onTextChange: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isPlaying by remember { mutableStateOf(false) }
    var showInstructions by remember { mutableStateOf(false) }
    val synthesis = rememberSynthesisState()

    // 语速：进页面时读设置，拖动即存、即作用于当前播放
    var speed by remember { mutableStateOf(SettingsRepository.getSpeechSpeed(context)) }

    val player = rememberTtsAudioPlayer(
        initialSpeed = speed,
        onPlayingChanged = { isPlaying = it },
        onCompleted = { Toast.makeText(context, "播放完成", Toast.LENGTH_SHORT).show() }
    )
    LaunchedEffect(speed) {
        player.speed = speed
    }

    // 文本框真实状态：TextFieldValue 以便获取选区（"选中词加入生词本"）
    var fieldValue by remember { mutableStateOf(TextFieldValue(textContent, TextRange(textContent.length))) }
    // 外部提升的 textContent 与文本框不一致时（如导入文件）才同步，避免打字时光标乱跳
    LaunchedEffect(textContent) {
        if (textContent != fieldValue.text) {
            fieldValue = TextFieldValue(textContent, TextRange(textContent.length))
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val text = FileReader.readFileContent(context, it)
            if (text != null) {
                onTextChange(text)
            } else {
                Toast.makeText(context, "无法读取文件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SuperL") },
                actions = {
                    IconButton(onClick = { showInstructions = true }) {
                        Icon(Icons.Default.Info, contentDescription = "使用说明")
                    }
                    IconButton(onClick = onOpenFavorites) {
                        Icon(Icons.Default.Folder, contentDescription = "收藏")
                    }
                    IconButton(onClick = onOpenVocabulary) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "生词本")
                    }
                    IconButton(onClick = {
                        if (textContent.isBlank()) {
                            Toast.makeText(context, "请先输入或导入文本", Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }

                        // 检查是否已存在相同文本的收藏
                        val existingFavorites = FavoritesRepository.load(context)
                        if (existingFavorites.any { it.text.trim() == textContent.trim() }) {
                            Toast.makeText(context, "该文本已存在于收藏中", Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }

                        val id = newId()
                        val textSnapshot = TtsPlaybackService.normalizeForSynthesis(textContent).ifBlank { textContent.trim() }
                        val title = textSnapshot.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(24) ?: "未命名收藏"

                        scope.launch {
                            // 只用本地缓存拼音频，绝不联网；缓存不齐时保存文本，听过一遍后自动补上
                            val audioFile = withContext(Dispatchers.IO) {
                                TtsPlaybackService.audioFromCacheOnly(
                                    context,
                                    textSnapshot,
                                    SettingsRepository.getSynthesisConfig(context),
                                    SettingsRepository.effectiveMaxChars(context)
                                )
                            }

                            var audioFileName = ""
                            if (audioFile != null) {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        val destName = FavoritesAudioStore.fileNameFor(id, audioFile.extension)
                                        val dest = File(AudioCache.favoritesAudioDir(context), destName)
                                        audioFile.copyTo(dest, overwrite = true)
                                        audioFileName = destName
                                        // 时间轴 sidecar 一并拷贝：播放页的逐句高亮靠它
                                        val sidecar = TtsPlaybackService.wholeSidecarFile(audioFile)
                                        if (sidecar.exists() && sidecar.length() > 0) {
                                            sidecar.copyTo(
                                                FavoritesAudioStore.timelineFileFor(context, id),
                                                overwrite = true
                                            )
                                        }
                                    }.onFailure { it.printStackTrace() }
                                }
                            }

                            FavoritesRepository.add(
                                context,
                                FavoriteItem(
                                    id = id,
                                    title = title,
                                    text = textSnapshot,
                                    audioFileName = audioFileName,
                                    createdAt = System.currentTimeMillis()
                                )
                            )

                            if (audioFileName.isBlank()) {
                                Toast.makeText(
                                    context,
                                    "已保存文本，完整朗读一遍后会自动带上音频",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(context, "已保存到收藏（含音频）", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = "保存到收藏")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedTextField(
                value = fieldValue,
                onValueChange = { newValue ->
                    fieldValue = newValue
                    onTextChange(newValue.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = { Text("文本内容") },
                placeholder = { Text("粘贴文本或导入 .txt / .pdf 文件...") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 选中文本后，一键加入生词本
            OutlinedButton(
                onClick = {
                    val sel = fieldValue.selection
                    val selectedText = if (!sel.collapsed) {
                        fieldValue.text.substring(sel.min, sel.max).trim()
                    } else ""
                    if (selectedText.isBlank()) {
                        Toast.makeText(context, "请先在文本中选中要添加的词", Toast.LENGTH_SHORT).show()
                    } else {
                        VocabularyRepository.add(
                            context,
                            VocabItem(
                                id = newId(),
                                word = selectedText,
                                note = "",
                                createdAt = System.currentTimeMillis()
                            )
                        )
                        Toast.makeText(context, "已加入生词本：$selectedText", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("选中词加入生词本")
            }

            Spacer(modifier = Modifier.height(12.dp))

            SpeedControl(
                speed = speed,
                onSpeedChange = { speed = it },
                onSpeedChangeFinished = { SettingsRepository.setSpeechSpeed(context, it) },
                enabled = !synthesis.isPreparing
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (synthesis.isPreparing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (synthesis.preparedTotal > 0) {
                        "正在合成整篇音频 ${synthesis.preparedDone}/${synthesis.preparedTotal} 段…"
                    } else "正在准备音频…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { filePickerLauncher.launch(arrayOf("text/plain", "application/pdf")) },
                    enabled = !synthesis.isPreparing,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导入文件")
                }

                Button(
                    onClick = {
                        // 正在播放 -> 停止
                        if (isPlaying) {
                            player.stop()
                            return@Button
                        }

                        // 正在合成 -> 取消（合成一份长文本可能要等十几秒）
                        if (synthesis.isPreparing) {
                            synthesis.cancel(context)
                            return@Button
                        }

                        if (textContent.isBlank()) {
                            Toast.makeText(context, "请先导入或输入文本", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        val textSnapshot = TtsPlaybackService.normalizeForSynthesis(textContent).ifBlank { textContent.trim() }
                        if (textSnapshot != textContent) {
                            onTextChange(textSnapshot)
                        }
                        val config = SettingsRepository.getSynthesisConfig(context)

                        // 先看有没有整篇缓存：有就直接播，不联网、不计费
                        val wholeCached = TtsPlaybackService.findWholeAudioFile(
                            context, config, textSnapshot
                        )
                        if (wholeCached != null) {
                            Toast.makeText(context, "使用本地缓存播放（不消耗费用）", Toast.LENGTH_SHORT).show()
                            player.play(wholeCached.absolutePath, speed)
                            return@Button
                        }

                        if (SettingsRepository.providerMeta(config.providerId).requiresApiKey &&
                            config.apiKey.isBlank()
                        ) {
                            Toast.makeText(context, "请先在设置中填写当前服务商的 API Key", Toast.LENGTH_LONG).show()
                            onOpenSettings()
                            return@Button
                        }

                        synthesis.prepare(context, scope, textSnapshot) { result ->
                            if (result.segmentCount > 1) {
                                Toast.makeText(
                                    context,
                                    "已合并为整篇音频（${result.segmentCount} 段），开始连续播放",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            player.play(result.audioFile.absolutePath, speed)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = when {
                        synthesis.isPreparing -> ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                        isPlaying -> ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                        else -> ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(
                        when {
                            synthesis.isPreparing && synthesis.preparedTotal > 0 ->
                                "取消 ${synthesis.preparedDone}/${synthesis.preparedTotal}"
                            synthesis.isPreparing -> "取消合成"
                            isPlaying -> "停止"
                            else -> "开始朗读"
                        }
                    )
                }
            }
        }
    }

    if (showInstructions) {
        InstructionsDialog(onDismiss = { showInstructions = false })
    }
}

@Composable
fun InstructionsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("如何使用 SuperL") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        "SuperL 基于 Steve Kaufmann 的可理解性输入理论，帮助你通过大量输入提升外语能力。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                val steps = listOf(
                    "1. 导入你感兴趣的文本" to listOf(
                        "点击\"导入文件\"按钮，支持TXT、PDF格式",
                        "或直接粘贴/输入任何外语文本（新闻、故事、歌词...）"
                    ),
                    "2. 朗读与收听" to listOf(
                        "点击\"朗读\"按钮，AI将为你朗读整段文本",
                        "边听边看文字，建立\"声音-意义\"的直接连接",
                        "长文本会先合成再合并成整篇连续播放，语气不会有断点",
                        "拖动\"语速\"滑杆可实时调速，正在播的音频立刻生效"
                    ),
                    "3. 保存到收藏" to listOf(
                        "点击顶部收藏图标，将文本和音频一键保存",
                        "进入\"收藏\"页面随时复习，支持离线播放",
                        "反复听同一材料，直到完全熟悉"
                    ),
                    "4. 生词管理" to listOf(
                        "在文本框中选中不认识的词，点击\"添加到生词本\"",
                        "在\"生词本\"页面集中复习重点词汇"
                    )
                )

                steps.forEach { (title, items) ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            items.forEach { item ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text("•", style = MaterialTheme.typography.bodyMedium)
                                    Text(item, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        "提示：在设置中可切换 TTS 服务商、配置 API 密钥和语音参数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        }
    )
}
