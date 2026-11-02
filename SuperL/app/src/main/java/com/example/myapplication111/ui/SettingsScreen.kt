package com.example.myapplication111.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myapplication111.data.AudioCache
import com.example.myapplication111.data.FavoritesAudioStore
import com.example.myapplication111.data.SettingsRepository
import com.example.myapplication111.tts.TtsProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedProviderId by remember {
        mutableStateOf(SettingsRepository.getCurrentProviderId(context))
    }
    var providerDropdownExpanded by remember { mutableStateOf(false) }

    // 当前正在编辑的服务商配置（切服务商时从磁盘重新加载，互不覆盖）
    var config by remember(selectedProviderId) {
        mutableStateOf(SettingsRepository.getProviderConfig(context, selectedProviderId))
    }
    var cacheBytes by remember { mutableStateOf(AudioCache.cacheSizeBytes(context)) }

    // 朗读设置
    var speed by remember { mutableStateOf(SettingsRepository.getSpeechSpeed(context)) }
    var speedMode by remember { mutableStateOf(SettingsRepository.getSpeedMode(context)) }
    var segmentMaxChars by remember { mutableStateOf(SettingsRepository.getSegmentMaxChars(context)) }

    val meta = SettingsRepository.providerMeta(selectedProviderId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ===== 服务商选择 =====
            Text("TTS 服务商", style = MaterialTheme.typography.titleMedium)

            ExposedDropdownMenuBox(
                expanded = providerDropdownExpanded,
                onExpandedChange = { providerDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = meta.displayName,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    label = { Text("选择服务商") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerDropdownExpanded) }
                )
                ExposedDropdownMenu(
                    expanded = providerDropdownExpanded,
                    onDismissRequest = { providerDropdownExpanded = false }
                ) {
                    SettingsRepository.PROVIDERS.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.displayName) },
                            onClick = {
                                selectedProviderId = provider.id
                                SettingsRepository.setCurrentProviderId(context, provider.id)
                                providerDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            if (meta.hint.isNotBlank()) {
                Text(
                    text = meta.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ===== 通用配置 =====
            if (meta.showEndpointFields) {
                OutlinedTextField(
                    value = config.apiKey,
                    onValueChange = { config = config.copy(apiKey = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    placeholder = { Text("输入 ${meta.displayName} 的 API Key") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = config.apiUrl,
                    onValueChange = { config = config.copy(apiUrl = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API 地址") },
                    placeholder = {
                        Text(
                            if (meta.isCustom) "例如 https://api.example.com/tts"
                            else "例如 https://api.siliconflow.cn/v1"
                        )
                    },
                    singleLine = true
                )

                OutlinedTextField(
                    value = config.model,
                    onValueChange = { config = config.copy(model = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("模型") },
                    placeholder = {
                        Text(
                            if (selectedProviderId == SettingsRepository.PROVIDER_OPENAI_COMPAT)
                                "例如 FunAudioLLM/CosyVoice2-0.5B 或 tts-1"
                            else "例如 qwen3-tts-instruct-flash"
                        )
                    },
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = config.voice,
                onValueChange = { config = config.copy(voice = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("音色") },
                placeholder = {
                    Text(
                        when {
                            selectedProviderId == SettingsRepository.PROVIDER_EDGE ->
                                "例如 en-US-EmmaMultilingualNeural、zh-CN-YunxiNeural"
                            selectedProviderId == SettingsRepository.PROVIDER_OPENAI_COMPAT ->
                                "例如 tongtong / alloy，部分厂商可留空"
                            else -> "例如 Cherry"
                        }
                    )
                },
                singleLine = true
            )

            if (selectedProviderId == SettingsRepository.PROVIDER_EDGE) {
                // Edge 免费朗读：输出格式固定 mp3，无语言/格式可配，不显示该字段
            } else if (meta.showLanguageField) {
                OutlinedTextField(
                    value = config.language,
                    onValueChange = { config = config.copy(language = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("语言") },
                    placeholder = { Text("语言类型，如 English 或 Chinese") },
                    singleLine = true
                )
            } else {
                // 通用接口：该字段复用为音频格式
                OutlinedTextField(
                    value = config.language,
                    onValueChange = { config = config.copy(language = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("音频格式（response_format）") },
                    placeholder = { Text("wav / mp3 / pcm / flac / opus，留空默认 wav") },
                    supportingText = {
                        Text("注意厂商支持范围不同：智谱只支持 wav/pcm，OpenAI 常用 mp3")
                    },
                    singleLine = true
                )
            }

            // ===== 自定义服务商专有配置 =====
            if (meta.isCustom) {
                HorizontalDivider()
                Text("自定义请求格式", style = MaterialTheme.typography.titleMedium)
                Text(
                    "占位符：{{text}} {{model}} {{voice}} {{language}} {{apiKey}} {{speed}}，会替换为实际值。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = config.customHeaders,
                    onValueChange = { config = config.copy(customHeaders = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("请求头（每行一个 Key: Value）") },
                    placeholder = { Text("Authorization: Bearer {{apiKey}}") },
                    minLines = 2
                )

                OutlinedTextField(
                    value = config.customBodyTemplate,
                    onValueChange = { config = config.copy(customBodyTemplate = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("请求体模板（JSON）") },
                    placeholder = { Text("""{"model": "{{model}}", "input": {"text": "{{text}}"}}""") },
                    minLines = 3
                )

                OutlinedTextField(
                    value = config.customResponsePath,
                    onValueChange = { config = config.copy(customResponsePath = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("响应中音频 URL 的路径") },
                    placeholder = { Text("例如 output.audio.url 或 data[0].url") },
                    singleLine = true
                )
            }

            // ===== 测试连接 =====
            var testing by remember { mutableStateOf(false) }
            var testResult by remember { mutableStateOf<String?>(null) }

            OutlinedButton(
                onClick = {
                    SettingsRepository.saveProviderConfig(context, config)
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = try {
                            val provider = TtsProviderFactory.create(selectedProviderId)
                            val result = provider.synthesize(config, "Hello, this is a test.")
                            if (result.hasAudio) {
                                val kind = when {
                                    result.audioBytes != null ->
                                        "收到音频数据 ${result.audioBytes.size} 字节（格式 ${result.audioFormat}）"
                                    else -> "收到音频链接"
                                }
                                "连接成功：$kind"
                            } else {
                                "失败：${result.errorMessage}"
                            }
                        } catch (e: Exception) {
                            "测试异常：${e.message}"
                        }
                    }.invokeOnCompletion { testing = false }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (testing) "正在测试..." else "测试连接")
            }

            testResult?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.startsWith("连接成功"))
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = {
                    SettingsRepository.saveProviderConfig(context, config)
                    SettingsRepository.setCurrentProviderId(context, selectedProviderId)
                    SettingsRepository.setSpeechSpeed(context, speed)
                    SettingsRepository.setSpeedMode(context, speedMode)
                    SettingsRepository.setSegmentMaxChars(context, segmentMaxChars)
                    Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存设置")
            }

            HorizontalDivider()

            // ===== 朗读设置 =====
            Text("朗读", style = MaterialTheme.typography.titleMedium)

            SpeedControl(
                speed = speed,
                onSpeedChange = { speed = it }
            )

            Text(
                text = "语速调整在拖动时立即生效（含正在播放的音频），不会重新合成、不产生费用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("变速方式", style = MaterialTheme.typography.bodyMedium)
            SettingsRepository.SpeedMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = speedMode == mode,
                        onClick = { speedMode = mode }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (mode) {
                                SettingsRepository.SpeedMode.PLAYER -> "播放器实时变速（推荐）"
                                SettingsRepository.SpeedMode.API -> "合成时变速"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = when (mode) {
                                SettingsRepository.SpeedMode.PLAYER ->
                                    "拖动滑杆即刻生效，音调不变，不额外计费"
                                SettingsRepository.SpeedMode.API ->
                                    "把语速写进请求，语音更自然；改语速需重新合成，智谱 GLM-TTS / Edge 朗读支持"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider()

            // ===== 长文本分段 =====
            Text("长文本处理", style = MaterialTheme.typography.titleMedium)

            Text(
                text = "超过服务商单次请求上限的文本必须分多次合成，应用会自动合并成" +
                    "一整段连续音频再播放，衔接处没有断点。${meta.displayName} 的上限是 " +
                    "${meta.maxTextLength} 字符。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = if (segmentMaxChars <= 0) "" else segmentMaxChars.toString(),
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(5)
                        segmentMaxChars = digits.toIntOrNull() ?: 0
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("每次合成最大字符数") },
                    placeholder = { Text("留空 = 用服务商默认（${meta.maxTextLength}）") },
                    singleLine = true
                )
            }

            Text(
                text = buildString {
                    append("调小可以让每段更短（比如按段落切），调大能减少请求次数。")
                    if (selectedProviderId == SettingsRepository.PROVIDER_OPENAI_COMPAT) {
                        append("通用接口无法自动判断背后厂商的上限，默认按 1024 保守处理——")
                        append("如果你确定接的是 OpenAI（上限 4096）或自建服务，可以在这里调大。")
                    } else {
                        append("超过 ${meta.displayName} 上限的值会被忽略，因为接口会直接拒绝。")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // ===== 音频缓存管理 =====
            Text(
                text = "当前缓存：${"%.1f".format(cacheBytes / 1024.0 / 1024.0)} MB",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedButton(
                onClick = {
                    AudioCache.clear(context)
                    cacheBytes = 0L
                    Toast.makeText(context, "已清除缓存", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清除音频缓存")
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) {
                            FavoritesAudioStore.pruneOrphans(context)
                        }
                        Toast.makeText(
                            context,
                            if (removed > 0) "已清理 $removed 个失效音频文件" else "没有需要清理的文件",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清理收藏里的失效音频")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
