package com.example.myapplication111.data

import android.content.Context
import org.json.JSONObject

/**
 * TTS 服务商设置存储。
 *
 * 支持三类服务商：
 * 1. 通用接口（openai_compat，默认）：只要填 API 地址 + API Key 就能连任意厂商，
 *    兼容 OpenAI /v1/audio/speech 请求格式；响应是音频文件、音频 URL 或 base64 都能处理。
 * 2. 预设服务商（如阿里百炼 DashScope）：代码内置请求格式，用户只需填 Key / 模型 / 音色。
 * 3. 自定义服务商（custom）：用户自己填请求 URL、请求头、请求体模板、响应提取路径，
 *    模板中可用占位符 {{text}} {{model}} {{voice}} {{language}} {{apiKey}}。
 *
 * 每个服务商的配置独立保存，切换服务商不会丢失各自的配置。
 */
object SettingsRepository {

    private const val PREFS_NAME = "ai_reader_prefs"

    // ---- 当前选中的服务商 ----
    private const val KEY_PROVIDER_ID = "tts_provider_id"

    // ---- 预设服务商通用字段（按服务商 id 加前缀存储） ----
    private const val SUFFIX_API_KEY = "_api_key"
    private const val SUFFIX_API_URL = "_api_url"
    private const val SUFFIX_MODEL = "_model_name"
    private const val SUFFIX_VOICE = "_voice_name"
    private const val SUFFIX_LANGUAGE = "_language_type"

    // ---- 自定义服务商专有字段 ----
    private const val KEY_CUSTOM_HEADERS = "custom_headers"          // 每行一个 "Key: Value"
    private const val KEY_CUSTOM_BODY_TEMPLATE = "custom_body_template"
    private const val KEY_CUSTOM_RESPONSE_PATH = "custom_response_path"

    // ---- 播放相关全局设置 ----
    private const val KEY_SPEECH_SPEED = "speech_speed"              // 语速倍率，Float
    private const val KEY_SPEED_MODE = "speech_speed_mode"           // player / api
    private const val KEY_SEGMENT_MAX_CHARS = "segment_max_chars"    // 0 = 用服务商默认上限

    /** 服务商描述。defaults 为该服务商的默认配置。 */
    data class ProviderMeta(
        val id: String,
        val displayName: String,
        val defaults: ProviderConfig,
        val isCustom: Boolean = false,
        val hint: String = "",
        /** 是否显示"语言"字段（部分厂商接口没有该参数）。 */
        val showLanguageField: Boolean = true,
        /** 是否显示 API Key / API 地址 / 模型 三个输入框（Edge 免费服务无需这些）。 */
        val showEndpointFields: Boolean = true,
        /** 朗读前是否要求该服务商已填 API Key（Edge 免费服务不需要，否则播放会被误拦）。 */
        val requiresApiKey: Boolean = true,
        /**
         * 该服务商单次请求能接受的最大字符数。超过就必须分段——这是厂商接口的硬限制。
         * 智谱 GLM-TTS：1024；OpenAI tts-1：4096；阿里千问 TTS：512。
         */
        val maxTextLength: Int = DEFAULT_SEGMENT_CHARS,
        /** 是否支持请求体里的 speed 参数（用于"接口变速"模式）。 */
        val supportsSpeed: Boolean = false
    )

    /** 语速控制方式。 */
    enum class SpeedMode {
        /** 播放器实时变速：改语速即刻生效，不重新合成、不花 API 费用，音调保持不变。 */
        PLAYER,

        /** 合成时变速：把语速写进请求体，语音更自然，但改一次要重新合成一次。 */
        API
    }

    /** 音频语速的取值边界（播放器与多数厂商接口的共同安全区间）。 */
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 2.0f

    /** 没配置过时的分段上限兜底值。 */
    const val DEFAULT_SEGMENT_CHARS = 1024

    /** 一次 TTS 调用所需的全部配置。 */
    data class ProviderConfig(
        val providerId: String,
        val apiKey: String = "",
        val apiUrl: String = "",
        val model: String = "",
        val voice: String = "",
        val language: String = "",
        // 仅自定义服务商使用
        val customHeaders: String = "",
        val customBodyTemplate: String = "",
        val customResponsePath: String = "",
        /**
         * 语速倍率。仅当全局设置为"接口变速"、且该服务商 supportsSpeed 时才写进请求体，
         * 否则恒为 1.0（播放器实时变速时不改请求，避免同样的文本因语速不同而缓存分裂）。
         */
        val speed: Float = 1.0f
    )

    const val PROVIDER_OPENAI_COMPAT = "openai_compat"
    const val PROVIDER_EDGE = "edge"
    const val PROVIDER_DASHSCOPE = "dashscope"
    const val PROVIDER_CUSTOM = "custom"

    private const val DEFAULT_DASHSCOPE_URL =
        "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"

    /**
     * 所有可选服务商（通用 + 预设 + 自定义入口）。
     * 通用接口排在第一位并作为默认：填 URL + API Key 即可接入任意厂商，无需改代码。
     * 后续新增预设平台只需在这里加一项 + 在 TtsProviderFactory 加一个分支。
     */
    val PROVIDERS: List<ProviderMeta> = listOf(
        ProviderMeta(
            id = PROVIDER_OPENAI_COMPAT,
            displayName = "通用接口（填 URL + Key 即可）",
            defaults = ProviderConfig(
                providerId = PROVIDER_OPENAI_COMPAT,
                apiUrl = "",
                model = "",
                voice = "",
                language = "English"
            ),
            hint = "兼容 OpenAI /v1/audio/speech 格式。示例：\n" +
                "· 硅基流动：https://api.siliconflow.cn/v1，模型 FunAudioLLM/CosyVoice2-0.5B\n" +
                "· 智谱 GLM-TTS：https://open.bigmodel.cn/api/paas/v4，模型 glm-tts，音色 tongtong\n" +
                "· OpenAI：https://api.openai.com/v1，模型 tts-1\n" +
                "地址填到域名或版本段即可，会自动补全 /audio/speech。",
            showLanguageField = false,
            // 通用接口无法判断背后是智谱（1024）还是 OpenAI（4096），保守取 1024，
            // 用户在设置里可以调大（自建/OpenAI 场景）。
            maxTextLength = DEFAULT_SEGMENT_CHARS,
            supportsSpeed = true
        ),
        ProviderMeta(
            id = PROVIDER_EDGE,
            displayName = "Edge 免费朗读（无需 Key）",
            defaults = ProviderConfig(
                providerId = PROVIDER_EDGE,
                // voice 存简短形式；EdgeTtsProvider 会归一化成完整音色名
                voice = "en-US-EmmaMultilingualNeural",
                language = "English"
            ),
            hint = "微软 Edge 朗读接口，免费、无需注册。音色形如 en-US-EmmaMultilingualNeural，" +
                "常用：en-US-AndrewMultilingualNeural、en-GB-SoniaNeural、zh-CN-YunxiNeural 等。",
            showLanguageField = false,
            showEndpointFields = false,
            requiresApiKey = false,
            // 服务端单条 SSML 文本上限 4096 UTF-8 字节（转义后）；对中文每字 3 字节，
            // 4096/3 ≈ 1365 字 < 4096 字符，所以按字符上限 1024 最稳（中英都安全）。
            maxTextLength = 1024,
            supportsSpeed = true
        ),
        ProviderMeta(
            id = PROVIDER_DASHSCOPE,
            displayName = "阿里云百炼（千问 TTS）",
            defaults = ProviderConfig(
                providerId = PROVIDER_DASHSCOPE,
                apiUrl = DEFAULT_DASHSCOPE_URL,
                model = "qwen3-tts-instruct-flash",
                voice = "Cherry",
                language = "English"
            ),
            hint = "在阿里云百炼平台开通服务并获取 API Key",
            maxTextLength = 512
        ),
        ProviderMeta(
            id = PROVIDER_CUSTOM,
            displayName = "自定义服务（高级）",
            defaults = ProviderConfig(
                providerId = PROVIDER_CUSTOM,
                language = "English",
                customBodyTemplate = """{"text": "{{text}}"}""",
                customResponsePath = "audio_url"
            ),
            isCustom = true,
            hint = "自行填写请求格式与响应解析路径，适配任意 TTS 接口。模板里可加 {{speed}} 占位符接收语速。",
            supportsSpeed = true
        )
    )

    fun providerMeta(id: String): ProviderMeta =
        PROVIDERS.firstOrNull { it.id == id }
            ?: PROVIDERS.first() // 兜底落到通用接口

    /**
     * 当前服务商单次请求的实际字符上限。
     *
     * 默认取服务商的硬限制（ProviderMeta.maxTextLength）。用户可在设置里调小——
     * 调小有意义（想按段落切），调大没意义（会被厂商拒绝），所以用 min 而不是覆盖。
     * 通用接口例外：用户明确知道自己接的是 OpenAI 时可以调大，但代码无从判断，
     * 因此只有在用户显式改过该值时才会突破 1024 的保守默认。
     */
    fun effectiveMaxChars(context: Context): Int {
        val meta = providerMeta(getCurrentProviderId(context))
        val configured = getSegmentMaxChars(context)
        if (configured <= 0) return meta.maxTextLength
        // 配置值低于硬上限时严格采用；高于硬上限时，只有"通用接口"允许放宽
        return if (meta.id == PROVIDER_OPENAI_COMPAT) configured else minOf(configured, meta.maxTextLength)
    }

    /** 用户配置的分段上限；0 表示"跟随服务商默认"。 */
    fun getSegmentMaxChars(context: Context): Int =
        prefs(context).getInt(KEY_SEGMENT_MAX_CHARS, 0)

    fun setSegmentMaxChars(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_SEGMENT_MAX_CHARS, value.coerceAtLeast(0)).apply()
    }

    /** 语速倍率，钳制在 [MIN_SPEED, MAX_SPEED] 内。 */
    fun getSpeechSpeed(context: Context): Float =
        prefs(context).getFloat(KEY_SPEECH_SPEED, 1.0f).coerceIn(MIN_SPEED, MAX_SPEED)

    fun setSpeechSpeed(context: Context, speed: Float) {
        prefs(context).edit().putFloat(KEY_SPEECH_SPEED, speed.coerceIn(MIN_SPEED, MAX_SPEED)).apply()
    }

    /** 语速控制方式，默认播放器实时变速（省钱、即时）。 */
    fun getSpeedMode(context: Context): SpeedMode {
        val raw = prefs(context).getString(KEY_SPEED_MODE, null) ?: return SpeedMode.PLAYER
        return runCatching { SpeedMode.valueOf(raw) }.getOrDefault(SpeedMode.PLAYER)
    }

    fun setSpeedMode(context: Context, mode: SpeedMode) {
        prefs(context).edit().putString(KEY_SPEED_MODE, mode.name).apply()
    }

    /**
     * 当前服务商是否应该把语速写进请求体。只有"接口变速"模式 + 服务商支持时才为真；
     * 否则请求里不带 speed（值为 1.0），播放器负责实时变速。
     */
    fun shouldSendSpeedInRequest(context: Context): Boolean {
        val speed = getSpeechSpeed(context)
        return getSpeedMode(context) == SpeedMode.API &&
            providerMeta(getCurrentProviderId(context)).supportsSpeed &&
            speed != 1.0f
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 当前选中的服务商。默认取通用接口。
     * 兼容旧版本：老用户没存过该 key 时，若已有千问配置则继续用千问，避免"升级后默认变了"的突兀。
     */
    fun getCurrentProviderId(context: Context): String {
        val stored = prefs(context).getString(KEY_PROVIDER_ID, null)
        if (stored != null) return stored
        val hasDashScopeConfig = prefs(context).contains(PROVIDER_DASHSCOPE + SUFFIX_API_KEY)
        return if (hasDashScopeConfig) PROVIDER_DASHSCOPE else PROVIDER_OPENAI_COMPAT
    }

    fun setCurrentProviderId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_PROVIDER_ID, id).apply()
    }

    /** 读取某个服务商的配置；未保存过的字段回落到该服务商默认值。 */
    fun getProviderConfig(context: Context, providerId: String): ProviderConfig {
        val meta = providerMeta(providerId)
        val p = prefs(context)
        fun get(suffix: String, def: String) = p.getString(providerId + suffix, def) ?: def
        return ProviderConfig(
            providerId = providerId,
            apiKey = get(SUFFIX_API_KEY, meta.defaults.apiKey),
            apiUrl = get(SUFFIX_API_URL, meta.defaults.apiUrl),
            model = get(SUFFIX_MODEL, meta.defaults.model),
            voice = get(SUFFIX_VOICE, meta.defaults.voice),
            language = get(SUFFIX_LANGUAGE, meta.defaults.language),
            customHeaders = p.getString(KEY_CUSTOM_HEADERS, "") ?: "",
            customBodyTemplate = p.getString(KEY_CUSTOM_BODY_TEMPLATE, meta.defaults.customBodyTemplate)
                ?: meta.defaults.customBodyTemplate,
            customResponsePath = p.getString(KEY_CUSTOM_RESPONSE_PATH, meta.defaults.customResponsePath)
                ?: meta.defaults.customResponsePath
        )
    }

    fun saveProviderConfig(context: Context, config: ProviderConfig) {
        val id = config.providerId
        prefs(context).edit()
            .putString(id + SUFFIX_API_KEY, config.apiKey)
            .putString(id + SUFFIX_API_URL, config.apiUrl)
            .putString(id + SUFFIX_MODEL, config.model)
            .putString(id + SUFFIX_VOICE, config.voice)
            .putString(id + SUFFIX_LANGUAGE, config.language)
            .apply()
        if (providerMeta(id).isCustom) {
            prefs(context).edit()
                .putString(KEY_CUSTOM_HEADERS, config.customHeaders)
                .putString(KEY_CUSTOM_BODY_TEMPLATE, config.customBodyTemplate)
                .putString(KEY_CUSTOM_RESPONSE_PATH, config.customResponsePath)
                .apply()
        }
    }

    /** 当前生效服务商的完整配置。 */
    fun getCurrentConfig(context: Context): ProviderConfig =
        getProviderConfig(context, getCurrentProviderId(context))

    /**
     * 用于发起合成的配置：在 [getCurrentConfig] 基础上决定要不要带上 speed。
     *
     * "接口变速"模式才写 speed 进请求体；"播放器变速"模式恒为 1.0，
     * 这样同一段文本不会因为用户拖动过语速滑杆而反复产生新的缓存文件。
     */
    fun getSynthesisConfig(context: Context): ProviderConfig {
        val config = getCurrentConfig(context)
        return if (shouldSendSpeedInRequest(context)) {
            config.copy(speed = getSpeechSpeed(context))
        } else {
            config.copy(speed = 1.0f)
        }
    }

    /**
     * 迁移旧版本（v1.1 及以前）的设置：旧 key 无前缀，统一搬到 dashscope_ 前缀下。
     * 只迁移一次：发现旧 key 存在且新 key 不存在时才搬。
     */
    fun migrateLegacyPrefs(context: Context) {
        val p = prefs(context)
        val editor = p.edit()
        var changed = false
        fun move(oldKey: String, newKey: String) {
            if (p.contains(oldKey) && !p.contains(newKey)) {
                editor.putString(newKey, p.getString(oldKey, ""))
                editor.remove(oldKey)
                changed = true
            }
        }
        move("dashscope_api_key", PROVIDER_DASHSCOPE + SUFFIX_API_KEY)
        move("api_url", PROVIDER_DASHSCOPE + SUFFIX_API_URL)
        move("model_name", PROVIDER_DASHSCOPE + SUFFIX_MODEL)
        move("voice_name", PROVIDER_DASHSCOPE + SUFFIX_VOICE)
        move("language_type", PROVIDER_DASHSCOPE + SUFFIX_LANGUAGE)
        if (changed) editor.apply()
    }
}
