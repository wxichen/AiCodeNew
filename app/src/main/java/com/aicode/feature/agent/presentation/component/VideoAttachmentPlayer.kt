package com.aicode.feature.agent.presentation.component

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.util.FileLogger
import compose.icons.FeatherIcons
import compose.icons.feathericons.Pause
import compose.icons.feathericons.Play
import compose.icons.feathericons.RotateCcw
import java.io.File
import kotlinx.coroutines.delay

/**
 * 消息气泡内的视频播放器。
 *
 * 只用 Media3 的 ExoPlayer 承载解码与播放，控制条由 Compose 自绘——引入 media3-ui 会带进
 * 一整套 View 体系与主题样式，在扁平化聊天气泡里既不好看也不好改。
 *
 * **画面比例取视频真实尺寸**（`Player.Listener.onVideoSizeChanged`），不写死 16:9：
 * 竖屏手机视频、超宽屏视频、方图都会被拉伸变形。拿到尺寸前先用 16:9 占位。
 *
 * 播放器实例跟随组合生命周期：消息滚出视口即释放，避免列表里堆 N 个解码实例。
 * [localPath] 为空时（远程模式临时文件已失效）只显示占位与提示。
 */
@Composable
internal fun VideoAttachmentPlayer(
    localPath: String,
    fileName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val available = remember(localPath) { localPath.isNotBlank() && File(localPath).isFile }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    var failed by remember { mutableStateOf(false) }
    /** 播完停在末尾：此时播放按钮换成重播语义，点击先 seekTo(0)。 */
    var ended by remember { mutableStateOf(false) }
    // 0 表示还没拿到：此时按 16:9 占位，拿到后立即按真实比例重排。
    var videoWidth by remember(localPath) { mutableStateOf(0) }
    var videoHeight by remember(localPath) { mutableStateOf(0) }
    // SurfaceView 引用：回到前台需要重新绑定 Surface。
    var videoView by remember(localPath) { mutableStateOf<android.view.SurfaceView?>(null) }

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

    // 画面尺寸只在解码出首个视频帧后才有值，用监听器接住而不是轮询：
    // VideoSize 里的 width/height 已按 pixelWidthHeightRatio 换算成显示比例。
    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                }
            }
        }
        p.addListener(listener)
        onDispose {
            p.removeListener(listener)
            runCatching { p.stop() }
            p.release()
        }
    }

    // 切后台再回来时 Surface 已被系统销毁，播放器手里那张也失效了；不重绑就停在黑屏。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val p = player ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val view = videoView ?: return@LifecycleEventObserver
            p.setVideoSurfaceView(view)
            when (p.playbackState) {
                // 后台期间解码器可能已被回收，回来要重新 prepare。
                Player.STATE_IDLE -> p.prepare()
                // 暂停态不会自动出帧，补一次定位让 Surface 重新拿到画面。
                Player.STATE_READY -> if (!p.isPlaying) p.seekTo(p.currentPosition)
                Player.STATE_ENDED -> p.seekTo(p.currentPosition)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 播放器事件没有 Compose 观察点，用轻量轮询同步进度与状态：间隔 200ms，只在可见时运行。
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            playing = p.isPlaying
            buffering = p.playbackState == Player.STATE_BUFFERING
            ended = p.playbackState == Player.STATE_ENDED
            failed = p.playerError != null
            val duration = p.duration
            progress = if (duration > 0) (p.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            delay(200)
        }
    }

    /**
     * 播放 / 暂停 / 重播统一入口。
     *
     * 播完（STATE_ENDED）时进度停在末尾，此时直接 play() 会立刻又结束——必须先 seekTo(0)，
     * 否则用户看到的就是「点播放没反应」。
     */
    fun togglePlayback() {
        val p = player ?: return
        when {
            p.isPlaying -> {
                p.pause()
                playing = false
            }
            p.playbackState == Player.STATE_ENDED -> {
                p.seekTo(0)
                p.play()
                playing = true
            }
            else -> {
                p.play()
                playing = true
            }
        }
    }

    val ratio = remember(videoWidth, videoHeight) {
        if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight else DEFAULT_RATIO
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        // 用可用宽度与比例算出画面尺寸：竖屏按高度上限反推宽度，宽屏按宽度铺满。
        // 只显式定 width/height，避免 widthIn + heightIn + aspectRatio 互相约束把画面挤变形。
        val density = LocalDensity.current
        val availWidthDp = maxWidth
        val maxHeightDp = with(density) { VIDEO_MAX_HEIGHT.toPx().toDp() }
        val boxWidth: androidx.compose.ui.unit.Dp
        val boxHeight: androidx.compose.ui.unit.Dp
        if (ratio >= 1f) {
            boxWidth = availWidthDp
            boxHeight = availWidthDp / ratio
        } else {
            val widthByHeight = maxHeightDp * ratio
            boxWidth = if (widthByHeight <= availWidthDp) widthByHeight else availWidthDp
            boxHeight = boxWidth / ratio
        }

        Box(
            modifier = Modifier
                .width(boxWidth)
                .height(boxHeight)
                .clip(RoundedCornerShape(Radius.md))
                .background(Color.Black)
                .clickable { if (!failed) togglePlayback() },
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
                            videoView = this
                        }
                    },
                    onRelease = { videoView = null },
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
                // 暂停态给一个明确的按钮：点画面任意处也能播，但按钮更符合直觉。
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier
                        .size(56.dp)
                        .clickable { togglePlayback() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // 播完后同一个按钮改成重播语义，图标跟着换。
                        Icon(
                            if (ended) FeatherIcons.RotateCcw else FeatherIcons.Play,
                            contentDescription = stringResource(
                                if (ended) R.string.chat_video_replay else R.string.chat_video_play
                            ),
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            // 底部控制条：播放/暂停 + 进度 + 文件名。聊天里主要是「确认内容」，不做全功能播放器。
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
                        when {
                            playing -> FeatherIcons.Pause
                            ended -> FeatherIcons.RotateCcw
                            else -> FeatherIcons.Play
                        },
                        contentDescription = stringResource(
                            when {
                                playing -> R.string.chat_video_pause
                                ended -> R.string.chat_video_replay
                                else -> R.string.chat_video_play
                            }
                        ),
                        tint = Color.White,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { togglePlayback() }
                    )
                    // 进度条可点按跳转：否则长视频只能从头看。
                    var barWidth by remember { mutableStateOf(0) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(24.dp)
                            .pointerInput(player) {
                                detectTapGestures { offset ->
                                    if (barWidth <= 0) return@detectTapGestures
                                    val target = (offset.x / barWidth).coerceIn(0f, 1f)
                                    player?.let { p ->
                                        if (p.duration > 0) p.seekTo((p.duration * target).toLong())
                                    }
                                }
                            }
                            .onSizeChanged { barWidth = it.width },
                        contentAlignment = Alignment.Center
                    ) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.pill)),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.28f)
                        )
                    }
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
}

/** 拿到视频真实尺寸前的占位比例。 */
private const val DEFAULT_RATIO = 16f / 9f

/** 视频高度上限：竖屏视频靠它压住，避免把气泡拉成一屏多高。 */
private val VIDEO_MAX_HEIGHT = 300.dp

private const val TAG = "VideoPlayer"
