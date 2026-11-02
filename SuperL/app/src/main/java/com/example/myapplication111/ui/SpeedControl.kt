package com.example.myapplication111.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication111.data.SettingsRepository
import kotlin.math.roundToInt

/**
 * 语速控件。滑杆拖动时即时生效 —— 正在播的音频立刻变速，不用重新合成。
 *
 * 两个回调分开是有意的：[onSpeedChange] 在拖动过程中每帧触发，只应该用来更新
 * 内存状态和喂给播放器；[onSpeedChangeFinished] 在手指抬起时触发一次，
 * 适合做落盘（写 SharedPreferences）这类稍重的操作。
 */
@Composable
fun SpeedControl(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSpeedChangeFinished: ((Float) -> Unit)? = null
) {
    // 记录滑杆最新的值：onValueChangeFinished 触发时外层的 speed 参数可能还没重组到最新，
    // 直接读参数会把上一次的值写进设置里。
    var latest by remember { mutableStateOf(speed) }
    LaunchedEffect(speed) {
        if (latest != speed) latest = speed
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "语速",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatSpeed(speed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (speed != 1.0f) {
                    TextButton(
                        onClick = {
                            latest = 1.0f
                            onSpeedChange(1.0f)
                            onSpeedChangeFinished?.invoke(1.0f)
                        },
                        enabled = enabled,
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Text("复位")
                    }
                }
            }
        }

        Slider(
            value = speed,
            onValueChange = { raw ->
                // 吸附到 0.05 的整数倍：既好读（1.05x 而不是 1.0473192x），
                // 也让"刚好 1.0x"成为一个能稳定命中的值。
                val snapped = snapSpeed(raw)
                latest = snapped
                onSpeedChange(snapped)
            },
            onValueChangeFinished = {
                onSpeedChangeFinished?.invoke(latest)
            },
            valueRange = SettingsRepository.MIN_SPEED..SettingsRepository.MAX_SPEED,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${formatSpeed(SettingsRepository.MIN_SPEED)} 慢",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "原速 1.0x",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${formatSpeed(SettingsRepository.MAX_SPEED)} 快",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 把任意浮点语速吸附到 0.05 的整数倍，并钳制到合法区间。 */
fun snapSpeed(raw: Float): Float {
    val clamped = raw.coerceIn(SettingsRepository.MIN_SPEED, SettingsRepository.MAX_SPEED)
    val snapped = (clamped * 20f).roundToInt() / 20f
    return snapped.coerceIn(SettingsRepository.MIN_SPEED, SettingsRepository.MAX_SPEED)
}

/** 显示成 1.0x / 0.85x / 1.25x 这样的形式。 */
fun formatSpeed(speed: Float): String {
    val rounded = (speed * 100f).roundToInt() / 100f
    return if (rounded == rounded.toInt().toFloat()) {
        "${rounded.toInt()}.0x"
    } else {
        "${rounded}x"
    }
}
