package com.aicode.feature.agent.presentation.component

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.util.FileLogger
import compose.icons.FeatherIcons
import compose.icons.feathericons.Pause
import compose.icons.feathericons.Play
import kotlinx.coroutines.delay
import java.io.File

/**
 * 消息气泡内的视频播放器。
 *
 * 只用 Media3 的 ExoPlayer 承载解码与播放，控制条由 Compose 自绘——引入 media3-ui 会带进
 * 一整套 View 体系与主题样式，在扁平化聊天气泡里既不好看也不好改。
 *
 * 播放器实例跟随组合生命周期：消息滚出视口即释放，避免列表里堆 N 个解码实例。
 * [localPath] 为空时（远程模式临时文件已失效）只显示占位与提示，点击交给外层用系统 App 打开。
 */
@Composable
internal fun VideoAttachmentPlayer(
    localPath: String,
    fileName: String,
    modifier: Modifier = Modifier,
    maxHeight: Int = 260
) {
    val context = LocalContext.current
    val available = remember(localPath) { localPath.isNotBlank() && File(localPath).isFile }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    var failed by remember { mutableStateOf(false) }

    val player = remember(localPath, available) {
        if (!available) return@remember null
        runCatching {
            ExoPlayer.Builder(context)
                // 默认数据源只能处理网络与 content://；本地绝对路径必须显式挂 FileDataSource。
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(DefaultDataSource.Factory(context, FileDataSource.Factory()))
                )
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(File(localPath).toURI().toString()))
                    prepare()
                    playWhenReady = false
                    repeatMode = Player.REPEAT_MODE_OFF
                }
        }.onFailure { FileLogger.w(TAG, "创建播放器失败: $localPath", it) }.getOrNull()
    }

    DisposableEffect(player) {
        onDispose {
            player?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
    }

    // 播放器事件没有 Compose 观察点，用轻量轮询同步进度与状态：间隔 200ms，只在可见时运行。
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            playing = p.isPlaying
            buffering = p.playbackState == Player.STATE_BUFFERING
            failed = p.playerError != null
            val duration = p.duration
            progress = if (duration > 0) (p.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            delay(200)
        }
    }

    val ratio = if (maxHeight > 0) 16f / 9f else 16f / 9f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(Radius.md))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    android.view.SurfaceView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        player.setVideoSurfaceView(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (player == null || failed) {
            Text(
                text = stringResource(
                    if (player == null) R.string.chat_video_unavailable else R.string.chat_video_play_failed
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.86f),
                modifier = Modifier.padding(Spacing.md)
            )
        }

        if (buffering && player != null && !failed) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        }

        if (player != null && !playing && !failed) {
            // 暂停态给一个明确的播放按钮：点画面任意处也能播，但按钮更符合直觉。
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .size(56.dp)
                    .clickable {
                        player.play()
                        playing = true
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        FeatherIcons.Play,
                        contentDescription = stringResource(R.string.chat_video_play),
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        // 底部控制条：播放/暂停 + 进度 + 文件名。视频在聊天里主要是「确认内容」，不做全功能播放器。
        if (player != null && !failed) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Icon(
                    if (playing) FeatherIcons.Pause else FeatherIcons.Play,
                    contentDescription = stringResource(
                        if (playing) R.string.chat_video_pause else R.string.chat_video_play
                    ),
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable {
                            if (player.isPlaying) {
                                player.pause()
                                playing = false
                            } else {
                                player.play()
                                playing = true
                            }
                        }
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Radius.pill)),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.28f)
                )
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = Spacing.xs)
                )
            }
        }
    }
}

private const val TAG = "VideoPlayer"
