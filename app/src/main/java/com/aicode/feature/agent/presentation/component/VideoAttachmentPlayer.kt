package com.aicode.feature.agent.presentation.component

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.Dp
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
import compose.icons.feathericons.Maximize
import compose.icons.feathericons.Pause
import compose.icons.feathericons.Play
import compose.icons.feathericons.RotateCcw
import java.io.File
import kotlinx.coroutines.delay

/**
 * 消息气泡内的视频播放器。
 *
 * 只用 Media3 的 ExoPlayer 承载解码与播放，控件由 Compose 自绘——引入 media3-ui 会带进
 * 一整套 View 体系与主题样式，在扁平化聊天气泡里既不好看也不好改。
 *
 * 版式：**画面即全部**。播放/暂停按钮居中、进度条贴在画面底部、右上角是全屏键，
 * 三者都是画面内的浮层，不再额外占一行；因此没有独立的文件名行与控制条底栏。
 *
 * 画面比例取视频真实尺寸（`Player.Listener.onVideoSizeChanged`），不写死 16:9：
 * 竖屏、超宽屏、方图都不会被拉伸变形。拿到尺寸前先用 16:9 占位。
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
    /** 播完停在末尾：此时居中按钮换成重播语义，点击先 seekTo(0)。 */
    var ended by remember { mutableStateOf(false) }
    /** 全屏播放时进入独立 Dialog；播放器实例复用，画面切换 SurfaceView 宿主即可。 */
    var fullscreen by remember { mutableStateOf(false) }
    /**
     * Surface 归属版本号：每次退出全屏自增，让小窗的 VideoSurface 重新接管播放器。
     * 只靠「Dialog 关闭」本身不会触发小窗重组，所以需要一个显式的 key。
     */
    var surfaceOwnerKey by remember { mutableIntStateOf(0) }
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

    /** 进度条点击定位。 */
    fun seekToFraction(fraction: Float) {
        val p = player ?: return
        if (p.duration > 0) p.seekTo((p.duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    val ratio = remember(videoWidth, videoHeight) {
        if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight else DEFAULT_RATIO
    }

    BoxWithConstraints(modifier = modifier) {
        // 画面宽高：竖屏按高度上限反推宽度，宽屏按可用宽度铺满。
        // 只显式定 width/height，避免 widthIn + heightIn + aspectRatio 互相约束把画面挤变形。
        val density = LocalDensity.current
        val availWidthDp = maxWidth
        val maxHeightDp = with(density) { VIDEO_MAX_HEIGHT.toPx().toDp() }
        val videoBoxWidth: Dp
        val videoBoxHeight: Dp
        if (ratio >= 1f) {
            videoBoxWidth = availWidthDp
            videoBoxHeight = availWidthDp / ratio
        } else {
            val widthByHeight = maxHeightDp * ratio
            videoBoxWidth = if (widthByHeight <= availWidthDp) widthByHeight else availWidthDp
            videoBoxHeight = videoBoxWidth / ratio
        }

        Box(
            modifier = Modifier
                .width(videoBoxWidth)
                .height(videoBoxHeight)
                .clip(RoundedCornerShape(Radius.md))
                .background(Color.Black)
                .clickable { if (!failed) togglePlayback() }
        ) {
            VideoSurface(player = player, surfaceOwnerKey = surfaceOwnerKey, onViewReady = { videoView = it })

            VideoOverlayContent(
                playing = playing,
                ended = ended,
                buffering = buffering,
                progress = progress,
                failed = failed,
                unavailable = player == null,
                onTogglePlayback = ::togglePlayback,
                onSeek = ::seekToFraction,
                onEnterFullscreen = { fullscreen = true },
                showFullscreenButton = player != null && !failed
            )
        }
    }

    if (fullscreen) {
        VideoFullscreenDialog(
            fileName = fileName,
            player = player,
            playing = playing,
            ended = ended,
            buffering = buffering,
            progress = progress,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            onTogglePlayback = ::togglePlayback,
            onSeek = ::seekToFraction,
            onDismiss = {
                // 顺序很重要：Dialog 还持有 Surface 时先解绑，再让位给底部小窗；
                // 只关 Dialog 而不解绑的话，播放器会继续握着一张即将销毁的 Surface。
                player?.clearVideoSurface()
                fullscreen = false
                // key 变化驱动小窗 VideoSurface 重新接管（并在绑定后补一次定位出帧）。
                surfaceOwnerKey += 1
            },
            onViewReady = { videoView = it }
        )
    }
}

/**
 * 画面本体：SurfaceView 承载解码输出。
 *
 * 绑定放在 [LaunchedEffect] 而不是只写在 factory 里：全屏 Dialog 关闭后小窗的
 * AndroidView 不会重建（factory 不再执行），必须靠 [surfaceOwnerKey] 变化重新把播放器
 * 指回这张 Surface，否则播放器仍握着一张已销毁的全屏 Surface，表现就是「退出全屏后画面卡死」。
 */
@Composable
private fun VideoSurface(
    player: ExoPlayer?,
    surfaceOwnerKey: Int,
    onViewReady: (android.view.SurfaceView?) -> Unit
) {
    if (player == null) return
    val context = LocalContext.current
    val view = remember(context) {
        android.view.SurfaceView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
    // key 变化（退出全屏）时重新接管：播放器必须被指回这张 Surface，
    // 否则它仍握着一张已销毁的全屏 Surface，画面就停住不动。
    LaunchedEffect(player, surfaceOwnerKey) {
        player.setVideoSurfaceView(view)
        onViewReady(view)
        // 重绑后补一次定位：暂停态不会自动出帧，不这么做新 Surface 要等下一次播放才有画面。
        if (!player.isPlaying) player.seekTo(player.currentPosition)
    }
}

/**
 * 全屏里的画面本体。
 *
 * 退出全屏（Dialog 销毁）时必须把播放器的 Surface 交回小窗：否则播放器继续握着一张
 * 已销毁的 Surface，小窗画面就停住不动。这里在 dispose 时调用 [onReady] 传 null，
 * 由调用方把接管权交回小窗的 VideoSurface。
 */
@Composable
private fun FullscreenSurface(
    player: ExoPlayer?,
    onReady: (android.view.SurfaceView?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (player == null) return
    val holder = remember { mutableStateOf<android.view.SurfaceView?>(null) }
    AndroidView(
        factory = { ctx ->
            android.view.SurfaceView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                holder.value = this
            }
        },
        onRelease = { holder.value = null },
        modifier = modifier
    )
    val view = holder.value
    LaunchedEffect(player, view) {
        if (view != null && player != null) {
            player.setVideoSurfaceView(view)
            onReady(view)
        }
    }
}

/**
 * 画面上的浮层控件：播放按钮居中、进度条贴底、全屏键右上。
 *
 * 全部叠在画面内而不是另起一行，是为了让视频本体占据全部可用空间；
 * 控件只覆盖需要的区域，其余部分仍可点按播放/暂停。
 */
@Composable
private fun BoxScope.VideoOverlayContent(
    playing: Boolean,
    ended: Boolean,
    buffering: Boolean,
    progress: Float,
    failed: Boolean,
    unavailable: Boolean,
    onTogglePlayback: () -> Unit,
    onSeek: (Float) -> Unit,
    onEnterFullscreen: () -> Unit,
    showFullscreenButton: Boolean
) {
    if (unavailable || failed) {
        Text(
            text = stringResource(
                if (unavailable) R.string.chat_video_unavailable else R.string.chat_video_play_failed
            ),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.86f),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(Spacing.md)
        )
    }

    if (buffering && !failed && !unavailable) {
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.Center)
                .size(32.dp),
            color = Color.White,
            strokeWidth = 2.dp
        )
    }

    if (!playing && !failed && !unavailable) {
        // 居中播放键：暂停态与播完态都显示，图标随之切换。
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.5f),
            modifier = Modifier
                .align(Alignment.Center)
                .size(56.dp)
                .clickable { onTogglePlayback() }
        ) {
            Box(contentAlignment = Alignment.Center) {
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

    if (showFullscreenButton) {
        // 只要图标：圆形底 + Surface 的投影在深色画面上会显出多余的圆环与阴影。
        // 用 Box 而不是 Surface，避免 Material 默认托起 elevation。
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Spacing.sm)
                .size(24.dp)
                .clickable { onEnterFullscreen() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                FeatherIcons.Maximize,
                contentDescription = stringResource(R.string.chat_video_fullscreen),
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    if (!unavailable && !failed) {
        // 进度条贴在画面底边：细条 + 点按跳转，不占额外高度。
        var barWidth by remember { mutableStateOf(0) }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(20.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (barWidth > 0) onSeek(offset.x / barWidth)
                    }
                }
                .onSizeChanged { barWidth = it.width },
            contentAlignment = Alignment.BottomCenter
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.32f)
            )
        }
    }
}

/**
 * 全屏播放：独立 Dialog 铺满整屏，复用同一个 [player]，只把 SurfaceView 换成全屏宿主。
 *
 * 不重建播放器是刻意的——重建会丢掉当前进度，也容易在两条渲染管线之间抢 Surface。
 */
@Composable
private fun VideoFullscreenDialog(
    fileName: String,
    player: ExoPlayer?,
    playing: Boolean,
    ended: Boolean,
    buffering: Boolean,
    progress: Float,
    videoWidth: Int,
    videoHeight: Int,
    onTogglePlayback: () -> Unit,
    onSeek: (Float) -> Unit,
    onDismiss: () -> Unit,
    onViewReady: (android.view.SurfaceView?) -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { if (player != null) onTogglePlayback() }
        ) {
            // 视频本体按原始比例居中（Fit 语义），不裁切、不拉伸。
            val ratio = if (videoWidth > 0 && videoHeight > 0) {
                videoWidth.toFloat() / videoHeight
            } else {
                DEFAULT_RATIO
            }
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val fitWidth: Dp
                val fitHeight: Dp
                if (maxWidth / ratio <= maxHeight) {
                    fitWidth = maxWidth
                    fitHeight = maxWidth / ratio
                } else {
                    fitHeight = maxHeight
                    fitWidth = maxHeight * ratio
                }
                FullscreenSurface(
                    player = player,
                    onReady = onViewReady,
                    modifier = Modifier
                        .width(fitWidth)
                        .height(fitHeight)
                )
            }

            if (buffering) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }

            if (!playing) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .clickable { onTogglePlayback() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (ended) FeatherIcons.RotateCcw else FeatherIcons.Play,
                            contentDescription = stringResource(
                                if (ended) R.string.chat_video_replay else R.string.chat_video_play
                            ),
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }

            // 顶栏：文件名 + 退出全屏。浮在画面之上，不影响观看区域。
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    FeatherIcons.Maximize,
                    contentDescription = stringResource(R.string.chat_video_exit_fullscreen),
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onDismiss() }
                )
            }

            var barWidth by remember { mutableStateOf(0) }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(28.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            if (barWidth > 0) onSeek(offset.x / barWidth)
                        }
                    }
                    .onSizeChanged { barWidth = it.width },
                contentAlignment = Alignment.BottomCenter
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.32f)
                )
            }
        }
    }
}

/** 拿到视频真实尺寸前的占位比例。 */
private const val DEFAULT_RATIO = 16f / 9f

/** 视频高度上限：竖屏视频靠它压住，避免把气泡拉成一屏多高。 */
private val VIDEO_MAX_HEIGHT = 300.dp

private const val TAG = "VideoPlayer"
