package com.example.myapplication111

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.myapplication111.ui.theme.MyApplication111Theme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            MyApplication111Theme {
                AppNavigation()
            }
        }
    }
}

// ===== Navigation =====

enum class Screen { MAIN, SETTINGS, FAVORITES, FAVORITE_DETAIL, VOCABULARY }

@Composable
fun AppNavigation() {
    // 用屏幕枚举管理导航，可容纳更多页面
    var currentScreen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    // 状态提升：文本内容保存在导航层，使用 rememberSaveable 保证切换页面/配置变化后仍能保留
    var textContent by rememberSaveable { mutableStateOf("") }
    // 当前选中的收藏项 id，用于传递给详情页
    var selectedFavoriteId by rememberSaveable { mutableStateOf<String?>(null) }

    // 处理系统返回键：在子页面按返回键回到主页（详情页返回到收藏列表），而不是退出 App
    BackHandler(enabled = currentScreen != Screen.MAIN) {
        currentScreen = when (currentScreen) {
            Screen.FAVORITE_DETAIL -> Screen.FAVORITES
            else -> Screen.MAIN
        }
    }

    when (currentScreen) {
        Screen.MAIN -> MainScreen(
            onOpenSettings = { currentScreen = Screen.SETTINGS },
            onOpenFavorites = { currentScreen = Screen.FAVORITES },
            onOpenVocabulary = { currentScreen = Screen.VOCABULARY },
            textContent = textContent,
            onTextChange = { textContent = it }
        )
        Screen.SETTINGS -> SettingsScreen(onBack = { currentScreen = Screen.MAIN })
        Screen.FAVORITES -> FavoritesScreen(
            onBack = { currentScreen = Screen.MAIN },
            onOpenDetail = { id ->
                selectedFavoriteId = id
                currentScreen = Screen.FAVORITE_DETAIL
            }
        )
        Screen.FAVORITE_DETAIL -> FavoriteDetailScreen(
            favoriteId = selectedFavoriteId,
            onBack = { currentScreen = Screen.FAVORITES }
        )
        Screen.VOCABULARY -> VocabularyScreen(onBack = { currentScreen = Screen.MAIN })
    }
}

// ===== Main Screen =====

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
    var isLoading by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var showInstructions by remember { mutableStateOf(false) }
    
    // 长文本分段播放相关状态
    var currentSegment by remember { mutableStateOf(0) }
    var totalSegments by remember { mutableStateOf(0) }
    var isCancelled by remember { mutableStateOf(false) }

    // 文本框的真实状态：使用 TextFieldValue 以便获取选区（selection），用于"选中词加入生词本"
    var fieldValue by remember { mutableStateOf(TextFieldValue(textContent, TextRange(textContent.length))) }
    // 当外部提升的 textContent 与文本框内容不一致时（如导入文件），才同步到 fieldValue，避免打字时光标乱跳
    LaunchedEffect(textContent) {
        if (textContent != fieldValue.text) {
            fieldValue = TextFieldValue(textContent, TextRange(textContent.length))
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val text = readFileContent(context, it)
            if (text != null) {
                onTextChange(text)
            } else {
                Toast.makeText(context, "无法读取文件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
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
                        val existingFavorites = loadFavorites(context)
                        if (existingFavorites.any { it.text.trim() == textContent.trim() }) {
                            Toast.makeText(context, "该文本已存在于收藏中", Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        
                        val modelName = getModelName(context)
                        val voiceName = getVoiceName(context)
                        val language = getLanguageType(context)
                        val id = System.currentTimeMillis().toString() + "_" +
                            UUID.randomUUID().toString().take(8)
                        val title = textContent.trim().take(20).ifBlank { "未命名收藏" }
                        
                        scope.launch {
                            var audioFileName = ""
                            withContext(Dispatchers.IO) {
                                // 智能检测：短文本查找单个缓存，长文本合并分段缓存
                                val segments = splitTextIntoSegments(textContent, maxChars = 500)
                                
                                if (segments.size == 1) {
                                    // 短文本：查找单个缓存文件
                                    val cacheKey = cacheKeyFor(textContent, modelName, voiceName, language)
                                    val cacheFile = cachedAudioFile(context, cacheKey)
                                    if (cacheFile.exists() && cacheFile.length() > 0) {
                                        val destName = "$id.wav"
                                        val dest = File(favoritesAudioDir(context), destName)
                                        try {
                                            cacheFile.copyTo(dest, overwrite = true)
                                            audioFileName = destName
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                } else {
                                    // 长文本：合并所有分段缓存
                                    val segmentFiles = mutableListOf<File>()
                                    var allSegmentsCached = true
                                    
                                    for (segment in segments) {
                                        val cacheKey = cacheKeyFor(segment, modelName, voiceName, language)
                                        val cacheFile = cachedAudioFile(context, cacheKey)
                                        if (cacheFile.exists() && cacheFile.length() > 0) {
                                            segmentFiles.add(cacheFile)
                                        } else {
                                            allSegmentsCached = false
                                            break
                                        }
                                    }
                                    
                                    if (allSegmentsCached && segmentFiles.isNotEmpty()) {
                                        // 合并音频文件
                                        val destName = "$id.wav"
                                        val dest = File(favoritesAudioDir(context), destName)
                                        try {
                                            mergeAudioFiles(segmentFiles, dest)
                                            audioFileName = destName
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                }
                            }
                            
                            addFavorite(
                                context,
                                FavoriteItem(
                                    id = id,
                                    title = title,
                                    text = textContent,
                                    audioFileName = audioFileName,
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                            
                            if (audioFileName.isBlank()) {
                                Toast.makeText(
                                    context,
                                    "已保存文本，朗读一次后可保存对应音频",
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
            // Text content area
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

            // 选中文本后，一键把选中的词加入生词本（备注留空，之后再补充释义）
            OutlinedButton(
                onClick = {
                    val sel = fieldValue.selection
                    val selectedText = if (!sel.collapsed) {
                        fieldValue.text.substring(sel.min, sel.max).trim()
                    } else ""
                    if (selectedText.isBlank()) {
                        Toast.makeText(context, "请先在文本中选中要添加的词", Toast.LENGTH_SHORT).show()
                    } else {
                        val id = System.currentTimeMillis().toString() + "_" +
                            UUID.randomUUID().toString().take(8)
                        addVocab(
                            context,
                            VocabItem(
                                id = id,
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

            Spacer(modifier = Modifier.height(16.dp))

            // Loading indicator
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Import file button
                Button(
                    onClick = {
                        filePickerLauncher.launch(arrayOf("text/plain", "application/pdf"))
                    },
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导入文件")
                }

                // Play/Stop button
                Button(
                    onClick = {
            
                        // 正在播放 -> 停止
                        if (isPlaying) {
                                mediaPlayer?.let {
                                try {
                                    it.stop()
                                } catch (e: Exception) {
                                            }
                                it.release()
                            }
                            mediaPlayer = null
                            isPlaying = false
                            isCancelled = true
                            currentSegment = 0
                            totalSegments = 0
                            return@Button
                        }

                        // 正在处理中 -> 明确提示，避免用户以为"没反应"
                        if (isLoading) {
                            Toast.makeText(context, "正在处理中，请稍候...", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        // 文本为空 -> 提示
                        if (textContent.isBlank()) {
                            Toast.makeText(context, "请先导入或输入文本", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        // 读取当前配置
                        val apiUrl = getApiUrl(context)
                        val modelName = getModelName(context)
                        val voiceName = getVoiceName(context)
                        val language = getLanguageType(context)

                        // 检查是否需要分段
                        val segments = splitTextIntoSegments(textContent, maxChars = 500)

                        if (segments.size == 1) {
                            // 单段模式：保持原有逻辑
                            // 先算缓存键并查本地缓存：命中则直接播放本地文件，无需联网、无需 API Key
                            val cacheKey = cacheKeyFor(textContent, modelName, voiceName, language)
                            val cacheFile = cachedAudioFile(context, cacheKey)
                            if (cacheFile.exists() && cacheFile.length() > 0) {
                                Toast.makeText(context, "使用本地缓存播放（不消耗费用）", Toast.LENGTH_SHORT).show()
                                startPlayback(
                                    context = context,
                                    url = cacheFile.absolutePath,
                                    currentPlayer = mediaPlayer,
                                    onPlayerCreated = { mediaPlayer = it },
                                    onPlayingChanged = { isPlaying = it }
                                )
                                return@Button
                            }

                            // 未命中缓存 -> 需要调用 API，此时才要求填写 API Key
                            val apiKey = getApiKey(context)
                            if (apiKey.isBlank()) {
                                Toast.makeText(context, "请先在设置中填写 API Key", Toast.LENGTH_LONG).show()
                                onOpenSettings()
                                return@Button
                            }

                            // 立即给出反馈
                            isLoading = true
                            Toast.makeText(context, "正在合成语音...", Toast.LENGTH_SHORT).show()

                            scope.launch {
                                try {
                                    val result = callTtsApi(
                                        apiKey, apiUrl, modelName, voiceName, language, textContent
                                    )

                                    if (result.audioUrl != null) {
                                        // 拿到远程音频地址后，先下载保存到本地缓存，再播放本地文件
                                        val client = OkHttpClient()
                                        val downloaded = downloadToFile(client, result.audioUrl, cacheFile)
                                        val playUrl = if (downloaded) cacheFile.absolutePath else result.audioUrl
                                        startPlayback(
                                            context = context,
                                            url = playUrl,
                                            currentPlayer = mediaPlayer,
                                            onPlayerCreated = { mediaPlayer = it },
                                            onPlayingChanged = { isPlaying = it }
                                        )
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "语音合成失败: ${result.errorMessage ?: "请检查API设置"}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        "朗读失败: ${e.message ?: "未知错误"}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } finally {
                                    // 无论成功、失败还是异常，都重置 loading 状态
                                    isLoading = false
                                }
                            }
                        } else {
                            // 多段模式：使用分段播放
                            val apiKey = getApiKey(context)
                            if (apiKey.isBlank()) {
                                Toast.makeText(context, "请先在设置中填写 API Key", Toast.LENGTH_LONG).show()
                                onOpenSettings()
                                return@Button
                            }

                            isCancelled = false
                            currentSegment = 0
                            totalSegments = segments.size
                            Toast.makeText(context, "检测到长文本，将分 ${segments.size} 段播放", Toast.LENGTH_SHORT).show()

                            scope.launch {
                                try {
                                    playSegmentsSequentially(
                                        context = context,
                                        segments = segments,
                                        apiKey = apiKey,
                                        apiUrl = apiUrl,
                                        modelName = modelName,
                                        voiceName = voiceName,
                                        language = language,
                                        onSegmentStart = { index -> currentSegment = index + 1 },
                                        onPlayerCreated = { mediaPlayer = it },
                                        onPlayingChanged = { isPlaying = it },
                                        onLoadingChanged = { isLoading = it },
                                        isCancelledProvider = { isCancelled }
                                    )
                                } finally {
                                    currentSegment = 0
                                    totalSegments = 0
                                }
                            }
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f),
                    colors = if (isPlaying) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ) else ButtonDefaults.buttonColors()
                ) {
                    Text(
                        when {
                            isLoading && totalSegments > 0 -> "处理中 $currentSegment/$totalSegments"
                            isLoading -> "处理中..."
                            isPlaying -> "停止"
                            else -> "开始朗读"
                        }
                    )
                }
            }
        }
    }
    
    // 使用说明对话框
    if (showInstructions) {
        InstructionsDialog(onDismiss = { showInstructions = false })
    }
}

/**
 * 使用 MediaPlayer 流式播放远程音频 URL。
 * 播放前先 release 旧实例，setDataSource 后使用 prepareAsync + 监听器。
 */
private fun startPlayback(
    context: Context,
    url: String,
    currentPlayer: MediaPlayer?,
    onPlayerCreated: (MediaPlayer?) -> Unit,
    onPlayingChanged: (Boolean) -> Unit
) {
    // 释放旧实例
    currentPlayer?.let {
        try {
            it.reset()
        } catch (e: Exception) {
        }
        it.release()
    }
    onPlayerCreated(null)
    onPlayingChanged(false)

    try {
        val player = MediaPlayer()
        player.setDataSource(url)

        player.setOnPreparedListener {
            it.start()
            onPlayingChanged(true)
        }

        player.setOnCompletionListener {
            it.release()
            onPlayerCreated(null)
            onPlayingChanged(false)
        }

        player.setOnErrorListener { mp, what, _ ->
            mp.release()
            onPlayerCreated(null)
            onPlayingChanged(false)
            Toast.makeText(context, "播放失败 (错误码 $what)", Toast.LENGTH_SHORT).show()
            true
        }

        onPlayerCreated(player)
        player.prepareAsync()
    } catch (e: Exception) {
        onPlayerCreated(null)
        onPlayingChanged(false)
        Toast.makeText(context, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

// ===== Settings Screen =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf(getApiKey(context)) }
    var apiUrl by remember { mutableStateOf(getApiUrl(context)) }
    var modelName by remember { mutableStateOf(getModelName(context)) }
    var voiceName by remember { mutableStateOf(getVoiceName(context)) }
    var language by remember { mutableStateOf(getLanguageType(context)) }
    var cacheBytes by remember { mutableStateOf(cacheSizeBytes(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Settings, contentDescription = "返回")
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
            Text(
                text = "本应用默认对接阿里云百炼平台（DashScope）的千问 TTS 服务。请在阿里云百炼平台开通服务并获取 API Key 后填入下方。",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("DashScope API Key") },
                placeholder = { Text("输入你在阿里云百炼平台获取的 API Key") },
                singleLine = true
            )

            OutlinedTextField(
                value = apiUrl,
                onValueChange = { apiUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API 地址") },
                placeholder = { Text("输入 TTS API 地址") },
                singleLine = true
            )

            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("模型") },
                placeholder = { Text("例如 qwen3-tts-instruct-flash") },
                singleLine = true
            )

            OutlinedTextField(
                value = voiceName,
                onValueChange = { voiceName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("音色") },
                placeholder = { Text("例如 Cherry") },
                singleLine = true
            )

            OutlinedTextField(
                value = language,
                onValueChange = { language = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("语言") },
                placeholder = { Text("语言类型，如 English 或 Chinese") },
                singleLine = true
            )

            Text(
                text = "提示：模型默认使用 qwen3-tts-instruct-flash，音色默认使用 Cherry，语言默认使用 English。如无特殊需求可保持默认。",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    saveApiKey(context, apiKey)
                    saveApiUrl(context, apiUrl)
                    saveModelName(context, modelName)
                    saveVoiceName(context, voiceName)
                    saveLanguageType(context, language)
                    Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存设置")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ===== 音频缓存管理 =====
            Text(
                text = "当前缓存：${"%.1f".format(cacheBytes / 1024.0 / 1024.0)} MB",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    clearAudioCache(context)
                    cacheBytes = 0L
                    Toast.makeText(context, "已清除缓存", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清除音频缓存")
            }
        }
    }
}

// ===== Preferences =====

private const val PREFS_NAME = "ai_reader_prefs"
private const val KEY_API_KEY = "dashscope_api_key"
private const val KEY_API_URL = "api_url"
private const val KEY_MODEL = "model_name"
private const val KEY_VOICE = "voice_name"
private const val KEY_LANGUAGE = "language_type"
private const val DEFAULT_API_URL =
    "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
private const val DEFAULT_MODEL = "qwen3-tts-instruct-flash"
private const val DEFAULT_VOICE = "Cherry"
private const val DEFAULT_LANGUAGE = "English"

fun getApiKey(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(KEY_API_KEY, "") ?: ""
}

fun saveApiKey(context: Context, apiKey: String) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_API_KEY, apiKey).apply()
}

fun getApiUrl(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(KEY_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
}

fun saveApiUrl(context: Context, apiUrl: String) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_API_URL, apiUrl.ifBlank { DEFAULT_API_URL }).apply()
}

fun getModelName(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
}

fun saveModelName(context: Context, modelName: String) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_MODEL, modelName.ifBlank { DEFAULT_MODEL }).apply()
}

fun getVoiceName(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
}

fun saveVoiceName(context: Context, voiceName: String) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_VOICE, voiceName.ifBlank { DEFAULT_VOICE }).apply()
}

fun getLanguageType(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
}

fun saveLanguageType(context: Context, language: String) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_LANGUAGE, language.ifBlank { DEFAULT_LANGUAGE }).apply()
}

// ===== File reading =====

fun readFileContent(context: Context, uri: Uri): String? {
    val mimeType = context.contentResolver.getType(uri)
    return if (mimeType == "application/pdf" || uri.toString().endsWith(".pdf")) {
        readPdfFromUri(context, uri)
    } else {
        readTextFromUri(context, uri)
    }
}

fun readTextFromUri(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.readText()
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun readPdfFromUri(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val document = PDDocument.load(inputStream)
            val stripper = PDFTextStripper()
            val text = stripper.getText(document)
            document.close()
            text
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// ===== TTS API =====

data class TtsResult(val audioUrl: String?, val errorMessage: String?)

/**
 * 调用千问 TTS API。
 * 请求体为千问格式：model + input.text + input.voice + input.language_type。
 * audioUrl 非空表示成功，errorMessage 用于失败时给用户提示。
 * 网络请求在 IO 线程执行，返回音频 URL，交由主线程用 MediaPlayer 流式播放。
 * 
 * 当文本较长时，API 会返回任务 ID 而非直接返回音频 URL，此时需要轮询任务状态。
 */
suspend fun callTtsApi(
    apiKey: String,
    apiUrl: String,
    modelName: String,
    voiceName: String,
    language: String,
    text: String
): TtsResult {
    return withContext(Dispatchers.IO) {
        try {
            val resolvedUrl = resolveTtsEndpoint(apiUrl)

            val client = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            val jsonBody = JSONObject().apply {
                put("model", modelName)
                put("input", JSONObject().apply {
                    put("text", text)
                    put("voice", voiceName)
                    put("language_type", language.ifBlank { DEFAULT_LANGUAGE })
                })
            }

            val requestBodyString = jsonBody.toString()
            val requestBody = requestBodyString
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(resolvedUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""

                if (bodyString.isBlank()) {
                    return@withContext TtsResult(
                        null,
                        "服务器返回为空 (HTTP ${response.code})"
                    )
                }

                val json = try {
                    JSONObject(bodyString)
                } catch (e: Exception) {
                    // 无法解析为 JSON，把原始返回内容前 500 字符作为错误信息
                    return@withContext TtsResult(
                        null,
                        "无法解析服务器响应 (HTTP ${response.code}): ${bodyString.take(500)}"
                    )
                }

                // 尝试提取 output.audio.url（同步模式）
                val audioUrl = json.optJSONObject("output")
                    ?.optJSONObject("audio")
                    ?.optString("url")
                    ?.takeIf { it.isNotBlank() }

                if (audioUrl != null) {
                    return@withContext TtsResult(audioUrl, null)
                }

                // 如果没有直接的音频 URL，检查是否为异步任务模式
                val taskId = json.optJSONObject("output")
                    ?.optString("task_id")
                    ?.takeIf { it.isNotBlank() }

                if (taskId != null) {
                    // 异步模式：轮询任务状态直到完成
                    return@withContext pollTaskStatus(client, apiKey, resolvedUrl, taskId)
                }

                // 既没有音频 URL 也没有任务 ID，返回错误
                TtsResult(
                    null,
                    "未找到音频地址或任务ID (HTTP ${response.code}): ${bodyString.take(500)}"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            TtsResult(null, e.message ?: "网络请求异常")
        }
    }
}

/**
 * 轮询异步任务状态，直到获取音频 URL。
 * 最多轮询 30 次，每次间隔 2 秒。
 */
private suspend fun pollTaskStatus(
    client: OkHttpClient,
    apiKey: String,
    baseUrl: String,
    taskId: String
): TtsResult {
    return withContext(Dispatchers.IO) {
        val maxRetries = 30
        val delayMillis = 2000L

        // 构造查询任务状态的 URL（通常是在基础 URL 后加上 task_id 参数）
        val queryUrl = "$baseUrl?task_id=$taskId"

        repeat(maxRetries) {
            try {
                kotlinx.coroutines.delay(delayMillis)

                val request = Request.Builder()
                    .url(queryUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string() ?: ""
                    if (bodyString.isBlank()) {
                        return@repeat
                    }

                    val json = try {
                        JSONObject(bodyString)
                    } catch (e: Exception) {
                        return@repeat
                    }

                    // 检查任务状态
                    val status = json.optJSONObject("output")
                        ?.optString("task_status")

                    when (status) {
                        "SUCCEEDED", "COMPLETED" -> {
                            // 任务完成，提取音频 URL
                            val audioUrl = json.optJSONObject("output")
                                ?.optJSONObject("audio")
                                ?.optString("url")
                                ?.takeIf { it.isNotBlank() }

                            if (audioUrl != null) {
                                return@withContext TtsResult(audioUrl, null)
                            }
                        }
                        "FAILED" -> {
                            val errorMsg = json.optString("message", "任务失败")
                            return@withContext TtsResult(null, "任务失败: $errorMsg")
                        }
                        "RUNNING", "PENDING" -> {
                            // 继续等待
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 超时
        TtsResult(null, "任务处理超时（已轮询 $maxRetries 次）")
    }
}

/**
 * 智能补全 TTS endpoint。
 * - 如果 apiUrl 为空，使用 DEFAULT_API_URL
 * - 如果已包含 "multimodal-generation"，直接使用
 * - 否则去掉末尾斜杠，若以 "/api/v1" 结尾则拼接完整路径，其他情况使用 DEFAULT_API_URL
 */
fun resolveTtsEndpoint(apiUrl: String): String {
    val trimmed = apiUrl.trim()
    if (trimmed.isBlank()) return DEFAULT_API_URL
    if (trimmed.contains("multimodal-generation")) return trimmed

    val noTrailingSlash = trimmed.trimEnd('/')
    return if (noTrailingSlash.endsWith("/api/v1")) {
        "$noTrailingSlash/services/aigc/multimodal-generation/generation"
    } else {
        DEFAULT_API_URL
    }
}

// ===== 音频本地缓存 =====

/**
 * 缓存目录：filesDir/tts_cache，不存在则自动创建。
 */
private fun ttsCacheDir(context: Context): File {
    val dir = File(context.filesDir, "tts_cache")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}

/**
 * 用 "文本 + model + voice + language" 生成唯一缓存键（SHA-256 十六进制字符串）。
 * 相同文本与相同配置会命中同一个缓存文件，从而避免重复调用 API。
 */
fun cacheKeyFor(text: String, model: String, voice: String, language: String): String {
    val raw = "$text|$model|$voice|$language"
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * 返回缓存音频文件：filesDir/tts_cache/<key>.wav
 */
fun cachedAudioFile(context: Context, key: String): File {
    return File(ttsCacheDir(context), "$key.wav")
}

/**
 * 在 IO 线程用 OkHttp 下载 url 内容写入 targetFile。
 * 成功返回 true；失败返回 false 并清理不完整文件，保证功能不中断。
 */
suspend fun downloadToFile(client: OkHttpClient, url: String, targetFile: File): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext false
                }
                val bytes = response.body?.bytes() ?: return@withContext false
                if (bytes.isEmpty()) {
                    return@withContext false
                }
                targetFile.outputStream().use { it.write(bytes) }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 清理可能残留的不完整文件
            if (targetFile.exists()) {
                targetFile.delete()
            }
            false
        }
    }
}

/**
 * 统计缓存目录占用的总字节数。
 */
fun cacheSizeBytes(context: Context): Long {
    val dir = ttsCacheDir(context)
    return dir.listFiles()?.sumOf { it.length() } ?: 0L
}

/**
 * 清除所有缓存音频文件。
 */
fun clearAudioCache(context: Context) {
    ttsCacheDir(context).listFiles()?.forEach { it.delete() }
}

// ===== 数据层：收藏（Favorites） =====

/**
 * 收藏项。
 * @param id 唯一标识（时间戳 + UUID）
 * @param title 标题（默认取文本前 20 字）
 * @param text 全文
 * @param audioFileName 对应音频文件名（位于 favorites_audio 目录，为空表示无音频）
 * @param createdAt 创建时间戳
 */
data class FavoriteItem(
    val id: String,
    val title: String,
    val text: String,
    val audioFileName: String,
    val createdAt: Long
)

private const val KEY_FAVORITES = "favorites_list"

/**
 * 收藏音频目录：filesDir/favorites_audio，与 tts_cache 分开，避免"清除缓存"误删收藏音频。
 */
fun favoritesAudioDir(context: Context): File {
    val dir = File(context.filesDir, "favorites_audio")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}

/**
 * 从 SharedPreferences 读取收藏列表（存储为 JSON 数组字符串）。
 */
fun loadFavorites(context: Context): List<FavoriteItem> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_FAVORITES, "") ?: ""
    if (raw.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        val list = mutableListOf<FavoriteItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                FavoriteItem(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    text = o.optString("text"),
                    audioFileName = o.optString("audioFileName"),
                    createdAt = o.optLong("createdAt")
                )
            )
        }
        list
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}

/**
 * 保存收藏列表到 SharedPreferences。
 */
fun saveFavorites(context: Context, list: List<FavoriteItem>) {
    val arr = JSONArray()
    list.forEach { item ->
        val o = JSONObject()
        o.put("id", item.id)
        o.put("title", item.title)
        o.put("text", item.text)
        o.put("audioFileName", item.audioFileName)
        o.put("createdAt", item.createdAt)
        arr.put(o)
    }
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
}

/**
 * 新增一条收藏（插入到列表头部，最新在前）。
 */
fun addFavorite(context: Context, item: FavoriteItem) {
    val list = loadFavorites(context).toMutableList()
    list.add(0, item)
    saveFavorites(context, list)
}

/**
 * 删除指定收藏，同时删除其音频文件。
 */
fun deleteFavorite(context: Context, id: String) {
    val list = loadFavorites(context).toMutableList()
    val target = list.firstOrNull { it.id == id }
    if (target != null && target.audioFileName.isNotBlank()) {
        val f = File(favoritesAudioDir(context), target.audioFileName)
        if (f.exists()) f.delete()
    }
    list.removeAll { it.id == id }
    saveFavorites(context, list)
}

// ===== 数据层：生词本（Vocabulary） =====

/**
 * 生词项。
 */
data class VocabItem(
    val id: String,
    val word: String,
    val note: String,
    val createdAt: Long
)

private const val KEY_VOCAB = "vocab_list"

/**
 * 从 SharedPreferences 读取生词列表。
 */
fun loadVocab(context: Context): List<VocabItem> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_VOCAB, "") ?: ""
    if (raw.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        val list = mutableListOf<VocabItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                VocabItem(
                    id = o.optString("id"),
                    word = o.optString("word"),
                    note = o.optString("note"),
                    createdAt = o.optLong("createdAt")
                )
            )
        }
        list
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}

/**
 * 保存生词列表到 SharedPreferences。
 */
fun saveVocab(context: Context, list: List<VocabItem>) {
    val arr = JSONArray()
    list.forEach { item ->
        val o = JSONObject()
        o.put("id", item.id)
        o.put("word", item.word)
        o.put("note", item.note)
        o.put("createdAt", item.createdAt)
        arr.put(o)
    }
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_VOCAB, arr.toString()).apply()
}

/**
 * 新增一条生词（插入到列表头部）。
 */
fun addVocab(context: Context, item: VocabItem) {
    val list = loadVocab(context).toMutableList()
    list.add(0, item)
    saveVocab(context, list)
}

/**
 * 删除指定生词。
 */
fun deleteVocab(context: Context, id: String) {
    val list = loadVocab(context).toMutableList()
    list.removeAll { it.id == id }
    saveVocab(context, list)
}

/**
 * 格式化时间戳为可读字符串。
 */
fun formatTimestamp(ts: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}

// ===== 收藏列表页 =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit
) {
    val context = LocalContext.current
    var favorites by remember { mutableStateOf(loadFavorites(context)) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的收藏") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (favorites.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无收藏")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(favorites, key = { it.id }) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenDetail(item.id) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    formatTimestamp(item.createdAt),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(onClick = { pendingDeleteId = item.id }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除")
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
            title = { Text("删除收藏") },
            text = { Text("确定要删除这条收藏吗？对应音频也会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteFavorite(context, deleteId)
                    favorites = loadFavorites(context)
                    pendingDeleteId = null
                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
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

// ===== 收藏详情页 =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteDetailScreen(
    favoriteId: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var item by remember { mutableStateOf(favoriteId?.let { id -> loadFavorites(context).firstOrNull { it.id == id } }) }
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(item?.text ?: "") }

    var isPlaying by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    
    // 长文本分段播放相关状态
    var currentSegment by remember { mutableStateOf(0) }
    var totalSegments by remember { mutableStateOf(0) }
    var isCancelled by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { mediaPlayer?.release() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.title ?: "收藏详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!isEditing) {
                        IconButton(onClick = {
                            editText = item?.text ?: ""
                            isEditing = true
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        val current = item
        if (current == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("收藏不存在")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = if (isEditing) editText else current.text,
                onValueChange = { if (isEditing) editText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                readOnly = !isEditing,
                label = { Text(if (isEditing) "编辑文本" else "文本内容") }
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isEditing) {
                    Button(
                        onClick = {
                            val newText = editText.trim()
                            if (newText.isBlank()) {
                                Toast.makeText(context, "文本不能为空", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            // 文本变了，原音频不再匹配：清空 audioFileName 并删除旧音频
                            if (current.audioFileName.isNotBlank()) {
                                val old = File(favoritesAudioDir(context), current.audioFileName)
                                if (old.exists()) old.delete()
                            }
                            val updated = current.copy(
                                text = newText,
                                title = newText.take(20).ifBlank { "未命名收藏" },
                                audioFileName = ""
                            )
                            val list = loadFavorites(context).toMutableList()
                            val idx = list.indexOfFirst { it.id == updated.id }
                            if (idx >= 0) {
                                list[idx] = updated
                                saveFavorites(context, list)
                            }
                            item = updated
                            isEditing = false
                            Toast.makeText(context, "已保存修改", Toast.LENGTH_SHORT).show()
                        },
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("保存修改")
                    }
                    OutlinedButton(
                        onClick = {
                            isEditing = false
                            editText = current.text
                        },
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("取消")
                    }
                } else {
                    Button(
                        onClick = {
                            if (isPlaying) {
                                mediaPlayer?.let {
                                    try {
                                        it.reset()
                                    } catch (e: Exception) {
                                    }
                                    it.release()
                                }
                                mediaPlayer = null
                                isPlaying = false
                                isCancelled = true
                                currentSegment = 0
                                totalSegments = 0
                                return@Button
                            }
                            if (isLoading) {
                                Toast.makeText(context, "正在处理中，请稍候...", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            // 有本地音频则直接播放，不联网不花钱
                            if (current.audioFileName.isNotBlank()) {
                                val f = File(favoritesAudioDir(context), current.audioFileName)
                                if (f.exists() && f.length() > 0) {
                                    startPlayback(
                                        context = context,
                                        url = f.absolutePath,
                                        currentPlayer = mediaPlayer,
                                        onPlayerCreated = { mediaPlayer = it },
                                        onPlayingChanged = { isPlaying = it }
                                    )
                                    return@Button
                                }
                            }
                            // 无本地音频，调用 API 朗读
                            val apiKey = getApiKey(context)
                            val apiUrl = getApiUrl(context)
                            val modelName = getModelName(context)
                            val voiceName = getVoiceName(context)
                            val language = getLanguageType(context)
                            if (apiKey.isBlank()) {
                                Toast.makeText(context, "请先在设置中填写 API Key", Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            
                            // 检查是否需要分段
                            val segments = splitTextIntoSegments(current.text, maxChars = 500)
                            
                            if (segments.size == 1) {
                                // 单段模式
                                isLoading = true
                                Toast.makeText(context, "正在合成语音...", Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    try {
                                        val result = callTtsApi(
                                            apiKey, apiUrl, modelName, voiceName, language, current.text
                                        )
                                        if (result.audioUrl != null) {
                                            val destName = "${current.id}.wav"
                                            val dest = File(favoritesAudioDir(context), destName)
                                            val client = OkHttpClient()
                                            val downloaded = downloadToFile(client, result.audioUrl, dest)
                                            if (downloaded) {
                                                val updated = current.copy(audioFileName = destName)
                                                val list = loadFavorites(context).toMutableList()
                                                val idx = list.indexOfFirst { it.id == updated.id }
                                                if (idx >= 0) {
                                                    list[idx] = updated
                                                    saveFavorites(context, list)
                                                }
                                                item = updated
                                            }
                                            val playUrl = if (downloaded) dest.absolutePath else result.audioUrl
                                            startPlayback(
                                                context = context,
                                                url = playUrl,
                                                currentPlayer = mediaPlayer,
                                                onPlayerCreated = { mediaPlayer = it },
                                                onPlayingChanged = { isPlaying = it }
                                            )
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "语音合成失败: ${result.errorMessage ?: "请检查API设置"}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            "播放失败: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            } else {
                                // 多段模式：使用分段播放
                                isCancelled = false
                                currentSegment = 0
                                totalSegments = segments.size
                                Toast.makeText(context, "检测到长文本，将分 ${segments.size} 段播放", Toast.LENGTH_SHORT).show()
                                
                                scope.launch {
                                    try {
                                        playSegmentsSequentially(
                                            context = context,
                                            segments = segments,
                                            apiKey = apiKey,
                                            apiUrl = apiUrl,
                                            modelName = modelName,
                                            voiceName = voiceName,
                                            language = language,
                                            onSegmentStart = { index -> currentSegment = index + 1 },
                                            onPlayerCreated = { mediaPlayer = it },
                                            onPlayingChanged = { isPlaying = it },
                                            onLoadingChanged = { isLoading = it },
                                            isCancelledProvider = { isCancelled }
                                        )
                                        
                                        // 播放完成后，合并音频并保存
                                        if (!isCancelled) {
                                            withContext(Dispatchers.IO) {
                                                val segmentFiles = mutableListOf<File>()
                                                var allSegmentsCached = true
                                                
                                                for (segment in segments) {
                                                    val cacheKey = cacheKeyFor(segment, modelName, voiceName, language)
                                                    val cacheFile = cachedAudioFile(context, cacheKey)
                                                    if (cacheFile.exists() && cacheFile.length() > 0) {
                                                        segmentFiles.add(cacheFile)
                                                    } else {
                                                        allSegmentsCached = false
                                                        break
                                                    }
                                                }
                                                
                                                if (allSegmentsCached && segmentFiles.isNotEmpty()) {
                                                    val destName = "${current.id}.wav"
                                                    val dest = File(favoritesAudioDir(context), destName)
                                                    try {
                                                        mergeAudioFiles(segmentFiles, dest)
                                                        val updated = current.copy(audioFileName = destName)
                                                        val list = loadFavorites(context).toMutableList()
                                                        val idx = list.indexOfFirst { it.id == updated.id }
                                                        if (idx >= 0) {
                                                            list[idx] = updated
                                                            saveFavorites(context, list)
                                                        }
                                                        withContext(Dispatchers.Main) {
                                                            item = updated
                                                        }
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                    }
                                                }
                                            }
                                        }
                                    } finally {
                                        currentSegment = 0
                                        totalSegments = 0
                                    }
                                }
                            }
                        },
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f),
                        colors = if (isPlaying) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ) else ButtonDefaults.buttonColors()
                    ) {
                        Text(
                            when {
                                isLoading && totalSegments > 0 -> "处理中 $currentSegment/$totalSegments"
                                isLoading -> "处理中..."
                                isPlaying -> "停止"
                                else -> "播放"
                            }
                        )
                    }
                }
            }
        }
    }
}

// ===== 生词本页 =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabularyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var vocab by remember { mutableStateOf(loadVocab(context)) }
    var wordInput by remember { mutableStateOf("") }
    var noteInput by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    var isLoading by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        onDispose { mediaPlayer?.release() }
    }

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
                    val id = System.currentTimeMillis().toString() + "_" +
                        UUID.randomUUID().toString().take(8)
                    addVocab(
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
                    vocab = loadVocab(context)
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
                                        val apiKey = getApiKey(context)
                                        val apiUrl = getApiUrl(context)
                                        val modelName = getModelName(context)
                                        val voiceName = getVoiceName(context)
                                        val language = getLanguageType(context)
                                        if (apiKey.isBlank()) {
                                            Toast.makeText(context, "请先在设置中填写 API Key", Toast.LENGTH_LONG).show()
                                            return@IconButton
                                        }
                                        isLoading = true
                                        scope.launch {
                                            try {
                                                val cacheKey = cacheKeyFor(entry.word, modelName, voiceName, language)
                                                val cacheFile = cachedAudioFile(context, cacheKey)
                                                if (cacheFile.exists() && cacheFile.length() > 0) {
                                                    startPlayback(
                                                        context = context,
                                                        url = cacheFile.absolutePath,
                                                        currentPlayer = mediaPlayer,
                                                        onPlayerCreated = { mediaPlayer = it },
                                                        onPlayingChanged = { }
                                                    )
                                                    return@launch
                                                }
                                                val result = callTtsApi(
                                                    apiKey, apiUrl, modelName, voiceName, language, entry.word
                                                )
                                                if (result.audioUrl != null) {
                                                    val client = OkHttpClient()
                                                    val downloaded = downloadToFile(client, result.audioUrl, cacheFile)
                                                    val playUrl = if (downloaded) cacheFile.absolutePath else result.audioUrl
                                                    startPlayback(
                                                        context = context,
                                                        url = playUrl,
                                                        currentPlayer = mediaPlayer,
                                                        onPlayerCreated = { mediaPlayer = it },
                                                        onPlayingChanged = { }
                                                    )
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
                                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "朗读")
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
                    deleteVocab(context, deleteId)
                    vocab = loadVocab(context)
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

// ===== 使用说明对话框 =====

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
                        "长文本自动分段播放，无需手动操作"
                    ),
                    "3. 保存到收藏" to listOf(
                        "点击顶部\"📌\"图标，将文本和音频一键保存",
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
                        "提示：在设置中可配置 API 密钥和语音参数。",
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

// ===== 长文本分段处理 =====

/**
 * 智能分割文本为多个段落，每段不超过 maxChars 字符。
 * 优先在句子边界（句号、问号、感叹号、换行符）处分割。
 */
fun splitTextIntoSegments(text: String, maxChars: Int = 500): List<String> {
    if (text.length <= maxChars) {
        return listOf(text)
    }

    val segments = mutableListOf<String>()
    var remaining = text

    while (remaining.isNotBlank()) {
        if (remaining.length <= maxChars) {
            segments.add(remaining.trim())
            break
        }

        // 在 maxChars 范围内找最后一个句子边界
        val chunk = remaining.substring(0, maxChars)
        var splitPos = -1

        // 优先查找句子结束符
        val sentenceEnders = listOf(". ", "。", "? ", "？", "! ", "！", "\n")
        for (ender in sentenceEnders) {
            val pos = chunk.lastIndexOf(ender)
            if (pos > splitPos) {
                splitPos = pos + ender.length
            }
        }

        // 如果找不到句子边界，查找逗号或空格
        if (splitPos == -1) {
            val fallbacks = listOf(", ", "，", " ")
            for (sep in fallbacks) {
                val pos = chunk.lastIndexOf(sep)
                if (pos > splitPos) {
                    splitPos = pos + sep.length
                }
            }
        }

        // 如果还是找不到，就强制在 maxChars 处切断
        if (splitPos == -1) {
            splitPos = maxChars
        }

        segments.add(remaining.substring(0, splitPos).trim())
        remaining = remaining.substring(splitPos)
    }

    return segments.filter { it.isNotBlank() }
}

/**
 * 顺序播放多个文本段落。
 * 每段先检查缓存，未命中则调用 API 合成，然后播放。
 * 播放完一段后自动播放下一段，直到全部完成或用户取消。
 */
suspend fun playSegmentsSequentially(
    context: Context,
    segments: List<String>,
    apiKey: String,
    apiUrl: String,
    modelName: String,
    voiceName: String,
    language: String,
    onSegmentStart: (Int) -> Unit,
    onPlayerCreated: (MediaPlayer?) -> Unit,
    onPlayingChanged: (Boolean) -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    isCancelledProvider: () -> Boolean
) {
    withContext(Dispatchers.Main) {
        for ((index, segment) in segments.withIndex()) {
            if (isCancelledProvider()) {
                onLoadingChanged(false)
                onPlayingChanged(false)
                return@withContext
            }

            onSegmentStart(index)

            // 检查缓存
            val cacheKey = cacheKeyFor(segment, modelName, voiceName, language)
            val cacheFile = cachedAudioFile(context, cacheKey)
            var audioPath: String? = null

            if (cacheFile.exists() && cacheFile.length() > 0) {
                // 使用缓存
                audioPath = cacheFile.absolutePath
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "播放第 ${index + 1}/${segments.size} 段（缓存）",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                // 调用 API 合成
                withContext(Dispatchers.Main) {
                    onLoadingChanged(true)
                    Toast.makeText(
                        context,
                        "正在合成第 ${index + 1}/${segments.size} 段...",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                try {
                    val result = callTtsApi(
                        apiKey, apiUrl, modelName, voiceName, language, segment
                    )

                    if (result.audioUrl != null) {
                        val client = OkHttpClient()
                        val downloaded = downloadToFile(client, result.audioUrl, cacheFile)
                        audioPath = if (downloaded) cacheFile.absolutePath else result.audioUrl
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "第 ${index + 1} 段合成失败: ${result.errorMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                            onLoadingChanged(false)
                        }
                        continue
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "第 ${index + 1} 段合成失败: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        onLoadingChanged(false)
                    }
                    continue
                }

                withContext(Dispatchers.Main) {
                    onLoadingChanged(false)
                }
            }

            // 播放这一段
            if (audioPath != null && !isCancelledProvider()) {
                val playbackComplete = suspendCancellableCoroutine<Boolean> { continuation ->
                    try {
                        val player = MediaPlayer()
                        player.setDataSource(audioPath)

                        player.setOnPreparedListener {
                            it.start()
                            onPlayingChanged(true)
                        }

                        player.setOnCompletionListener {
                            it.release()
                            onPlayerCreated(null)
                            onPlayingChanged(false)
                            continuation.resumeWith(Result.success(true))
                        }

                        player.setOnErrorListener { mp, _, _ ->
                            mp.release()
                            onPlayerCreated(null)
                            onPlayingChanged(false)
                            continuation.resumeWith(Result.success(false))
                            true
                        }

                        onPlayerCreated(player)
                        player.prepareAsync()

                        continuation.invokeOnCancellation {
                            try {
                                player.stop()
                            } catch (e: Exception) {
                            }
                            player.release()
                            onPlayerCreated(null)
                            onPlayingChanged(false)
                        }
                    } catch (e: Exception) {
                        continuation.resumeWith(Result.success(false))
                    }
                }

                if (!playbackComplete) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "第 ${index + 1} 段播放失败",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        // 全部播放完成
        withContext(Dispatchers.Main) {
            onLoadingChanged(false)
            onPlayingChanged(false)
            if (!isCancelledProvider()) {
                Toast.makeText(context, "全部播放完成", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * 合并多个 WAV 音频文件为一个文件。
 * 简单实现：逐个读取文件内容，拼接到目标文件。
 * 注意：此方法假设所有 WAV 文件格式相同（采样率、声道数等）。
 */
fun mergeAudioFiles(sourceFiles: List<File>, destFile: File) {
    if (sourceFiles.isEmpty()) {
        return
    }

    destFile.outputStream().use { outputStream ->
        for ((index, file) in sourceFiles.withIndex()) {
            file.inputStream().use { inputStream ->
                if (index == 0) {
                    // 第一个文件：复制完整内容（包含 WAV 头）
                    inputStream.copyTo(outputStream)
                } else {
                    // 后续文件：跳过 WAV 头（通常是 44 字节），只复制音频数据
                    inputStream.skip(44)
                    inputStream.copyTo(outputStream)
                }
            }
        }
    }
}

