package com.example.myapplication111.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myapplication111.data.FavoritesAudioStore
import com.example.myapplication111.data.FavoritesRepository

/**
 * 收藏编辑页：只有在这里能修改收藏的文本。
 *
 * 文本一旦改动，原来的整篇音频（和时间轴）就与文本对不上了，
 * 保存时会一并作废 —— 下次播放重新合成，并按新文本重建时间轴。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteEditScreen(
    favoriteId: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var item by remember {
        mutableStateOf(favoriteId?.let { id -> FavoritesRepository.load(context).firstOrNull { it.id == id } })
    }
    var editText by remember { mutableStateOf(item?.text ?: "") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑收藏") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            if (current.audioFileName.isNotBlank()) {
                Text(
                    text = "注意：修改文本后，原有的音频将失效，下次播放会重新合成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = editText,
                onValueChange = { editText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = { Text("文本内容") }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val newText = editText.trim()
                        if (newText.isBlank()) {
                            Toast.makeText(context, "文本不能为空", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        // 文本变了，原音频与时间轴都不再匹配：一并作废
                        if (newText != current.text) {
                            FavoritesAudioStore.deleteFor(context, current.id, "")
                        }
                        val updated = current.copy(
                            text = newText,
                            title = newText.take(20).ifBlank { "未命名收藏" },
                            audioFileName = if (newText == current.text) current.audioFileName else ""
                        )
                        FavoritesRepository.update(context, updated)
                        Toast.makeText(context, "已保存修改", Toast.LENGTH_SHORT).show()
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存修改")
                }
                Spacer(modifier = Modifier.weight(0.2f))
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消")
                }
            }
        }
    }
}
