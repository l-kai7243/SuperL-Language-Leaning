package com.example.myapplication111.tts

import com.example.myapplication111.data.SettingsRepository

/** 根据服务商 id 拿到对应实现；新增预设服务商时在 when 里加分支即可。 */
object TtsProviderFactory {

    fun create(providerId: String): TtsProvider = when (providerId) {
        SettingsRepository.PROVIDER_CUSTOM -> CustomTtsProvider()
        SettingsRepository.PROVIDER_DASHSCOPE -> DashScopeTtsProvider()
        SettingsRepository.PROVIDER_EDGE -> EdgeTtsProvider()
        else -> OpenAiCompatTtsProvider()
    }
}
