package com.example.myapplication111

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.myapplication111.data.SettingsRepository
import com.example.myapplication111.ui.AppNavigation
import com.example.myapplication111.ui.theme.MyApplication111Theme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        // 旧版本设置（无前缀 key）迁移到按服务商分前缀的新结构
        SettingsRepository.migrateLegacyPrefs(applicationContext)
        enableEdgeToEdge()
        setContent {
            MyApplication111Theme {
                AppNavigation()
            }
        }
    }
}
