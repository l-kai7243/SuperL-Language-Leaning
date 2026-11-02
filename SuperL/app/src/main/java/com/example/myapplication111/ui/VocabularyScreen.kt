package com.example.myapplication111.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myapplication111.data.SettingsRepository
import com.example.myapplication111.data.VocabItem
import com.example.myapplication111.data.VocabularyRepository
import com.example.myapplication111.tts.TtsPlaybackService
import com.example.myapplication111.tts.TtsProviderFactory
import com.example.myapplication111.util.newId
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabularyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var vocab by remember { mutableStateOf(VocabularyRepository.load(context)) }
    var wordInput by remember { mutableStateOf("") }
    var noteInput by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    var isLoading by remember { mutableStateOf(false) }
    var playingId by remember { mutableStateOf<String?>(null) }

    // 生词朗读沿用全局语速设置：学会了之后想听快一点也是合理的
    val player = rememberTtsAudioPlayer(
        initialSpeed = SettingsRepository.getSpeechSpeed(context),
        onPlayingChanged = { playing -> if (!playing) playingId = null },
        onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("生词本") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = wordInput,
                onValueChange = { wordInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("单词") },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = noteInput,
                onValueChange = { noteInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备注/释义") }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (wordInput.trim().isBlank()) {
                        Toast.makeText(context, "请输入单词", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val id = newId()
                    VocabularyRepository.add(
                        context,
                        VocabItem(
                            id = id,
                            word = wordInput.trim(),
                            note = noteInput.trim(),
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    wordInput = ""
                    noteInput = ""
                    vocab = VocabularyRepository.load(context)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("添加")
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (vocab.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("生词本还是空的，添加你的第一个生词吧")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(vocab, key = { it.id }) { entry ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.word, style = MaterialTheme.typography.titleLarge)
                                    if (entry.note.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            entry.note,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                                IconButton(
                                    enabled = !isLoading,
                                    onClick = {
                                        // 再点一次正在朗读的条目 = 停止
                                        if (playingId == entry.id) {
                                            player.stop()
                                            playingId = null
                                            return@IconButton
                                        }
                                        // 用与朗读一致的配置，否则缓存键对不上、白花钱
                                        val config = SettingsRepository.getSynthesisConfig(context)
                                        isLoading = true
                                        scope.launch {
                                            try {
                                                val cacheFile = TtsPlaybackService.cachedSegmentFile(context, config, entry.word)
                                                if (cacheFile.exists() && cacheFile.length() > 0) {
                                                    playingId = entry.id
                                                    player.play(cacheFile.absolutePath)
                                                    return@launch
                                                }
                                                if (SettingsRepository.providerMeta(config.providerId).requiresApiKey &&
                                                    config.apiKey.isBlank()
                                                ) {
                                                    Toast.makeText(context, "请先在设置中填写当前服务商的 API Key", Toast.LENGTH_LONG).show()
                                                    return@launch
                                                }
                                                val provider = TtsProviderFactory.create(config.providerId)
                                                val result = provider.synthesize(config, entry.word)
                                                if (result.hasAudio) {
                                                    val materialized = TtsPlaybackService.materializeResult(
                                                        context, result, cacheFile
                                                    )
                                                    if (materialized != null) {
                                                        playingId = entry.id
                                                        player.play(materialized)
                                                    } else {
                                                        Toast.makeText(
                                                            context,
                                                            "音频写入失败",
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    }
                                                } else {
                                                    Toast.makeText(
                                                        context,
                                                        "语音合成失败: ${result.errorMessage ?: "请检查API设置"}",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "播放失败: ${e.message}", Toast.LENGTH_LONG).show()
                                            } finally {
                                                isLoading = false
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        if (playingId == entry.id) Icons.Default.Stop
                                        else Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = if (playingId == entry.id) "停止" else "朗读"
                                    )
                                }
                                IconButton(onClick = { pendingDeleteId = entry.id }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val deleteId = pendingDeleteId
    if (deleteId != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("删除生词") },
            text = { Text("确定要删除这个生词吗？") },
            confirmButton = {
                TextButton(onClick = {
                    VocabularyRepository.delete(context, deleteId)
                    vocab = VocabularyRepository.load(context)
                    pendingDeleteId = null
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("取消")
                }
            }
        )
    }
}
