package com.aicode.feature.agent.presentation.component

import android.annotation.SuppressLint
import android.view.ContextThemeWrapper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.HtmlPreviewViewModel
import com.aicode.feature.workspace.domain.FileAccessProvider
import compose.icons.FeatherIcons
import compose.icons.feathericons.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Maximize

/** 一次 HTML 预览请求。只带渲染所需的最小信息，不持有文件内容。 */
@Immutable
data class HtmlPreviewRequest(
    val containerPath: String,
    val fileName: String
)

/**
 * 会话内预览需要按容器路径读同目录资源。附件行渲染在多层中间件里，
 * 用 CompositionLocal 注入比一路加参数更省改动（与 LocalImageViewer 同一套路）。
 */
val LocalFileAccess = staticCompositionLocalOf<FileAccessProvider?> { null }

/** 打开 HTML 预览的句柄：调用方只管 show。 */
@Stable
fun interface HtmlPreview {
    fun show(request: HtmlPreviewRequest)
}

/**
 * 默认空实现：没挂 [HtmlPreviewHost] 的组合树里点预览静默无反应，不会崩。
 * 与 `LocalImageViewer` 同一套路——聊天卡片渲染在多处中间层，用 CompositionLocal
 * 比一路加回调参数更省改动。
 */
val LocalHtmlPreview = staticCompositionLocalOf<HtmlPreview> { HtmlPreview { } }

@Stable
class HtmlPreviewState : HtmlPreview {
    var request by mutableStateOf<HtmlPreviewRequest?>(null)
        private set

    override fun show(request: HtmlPreviewRequest) {
        this.request = request
    }

    fun dismiss() {
        request = null
    }
}

@Composable
fun rememberHtmlPreviewState(): HtmlPreviewState = remember { HtmlPreviewState() }

/**
 * 会话内直接渲染的网页预览：消息里 `.html` 附件下方直接展开一个 WebView，而不是点按钮再跳全屏。
 *
 * 高度固定 [INLINE_PREVIEW_HEIGHT] 并可折叠：聊天是纵向列表，页面高度不定，
 * 让它把整页铺开会把后续消息挤到很远，固定视口 + 可展开更符合浏览消息流。
 * 顶栏保留「展开」按钮可切到全屏，页面内部滚动交给 WebView 自己处理。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun InlineHtmlPreview(
    request: HtmlPreviewRequest,
    fileAccess: FileAccessProvider,
    modifier: Modifier = Modifier,
    viewModel: HtmlPreviewViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val fullscreen = LocalHtmlPreview.current

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bgColor = if (dark) BG_DARK else BG_LIGHT

    // 工作区外的文件没有同源目录，退化为 data: 内联；与全屏预览保持同一套规则。
    val inlined = !request.containerPath.startsWith(HtmlPreviewViewModel.WORKSPACE_ROOT_PREFIX)
    var html by remember(request.containerPath) { mutableStateOf<String?>(null) }
    if (inlined && html == null) {
        LaunchedEffect(request.containerPath) {
            html = withContext(Dispatchers.IO) {
                runCatching { fileAccess.readFile(request.containerPath) }.getOrNull()
            }
        }
    }
    val url = when {
        inlined && html == null -> null
        inlined -> viewModel.inlineUrl(html.orEmpty())
        else -> viewModel.urlFor(request.containerPath)
    }

    val webView = remember {
        WebView(ContextThemeWrapper(context, webViewThemeRes(dark))).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // 只经虚拟域名读工作区文件：放开 file:// 会绕过同源策略。
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(bgColor)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = viewModel.assetLoader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    if (viewModel.containerPathForUrl(target) != null) return false
                    if (target.scheme == "http" || target.scheme == "https") {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, target))
                        }
                        return true
                    }
                    return false
                }
            }
        }
    }

    LaunchedEffect(dark) { webView.setBackgroundColor(bgColor) }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    LaunchedEffect(url) {
        val target = url ?: return@LaunchedEffect
        loading = true
        webView.loadUrl(target)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(chatMutedSurfaceColor())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = request.fileName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 折叠 / 展开视口：长页面在消息流里始终保留一个可用的小窗。
            Icon(
                if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                contentDescription = stringResource(
                    if (expanded) R.string.chat_html_preview_collapse else R.string.chat_html_preview_expand
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { expanded = !expanded }
            )
            Icon(
                FeatherIcons.Maximize,
                contentDescription = stringResource(R.string.chat_video_fullscreen),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { fullscreen.show(request) }
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (expanded) INLINE_PREVIEW_EXPANDED_HEIGHT else INLINE_PREVIEW_HEIGHT)
                .background(Color(bgColor))
        ) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/**
 * HTML 预览宿主：点击后以全屏 [Dialog] 打开 WebView 渲染页面。
 *
 * 用 Dialog 而不是组合内覆盖层，理由与全屏图片查看器一致：平板上聊天只占半屏，
 * 覆盖层盖不住整屏；Dialog 自带窗口焦点与返回键关闭，优先级也天然高于既有的返回键处理。
 */
@Composable
fun HtmlPreviewHost(
    state: HtmlPreviewState,
    fileAccess: FileAccessProvider,
    viewModel: HtmlPreviewViewModel = hiltViewModel()
) {
    val request = state.request ?: return
    val dismiss = { state.dismiss() }

    // 工作区外的文件没有可用的同源目录，退化成内联 data: 文档，先把内容读出来。
    val inlined = request.containerPath.startsWith(HtmlPreviewViewModel.WORKSPACE_ROOT_PREFIX).not()
    var html by remember(request.containerPath) { mutableStateOf<String?>(null) }
    if (inlined && html == null) {
        LaunchedEffect(request.containerPath) {
            html = withContext(Dispatchers.IO) {
                runCatching { fileAccess.readFile(request.containerPath) }.getOrNull()
            }
        }
    }

    val url = when {
        inlined && html == null -> null
        inlined -> viewModel.inlineUrl(html.orEmpty())
        else -> viewModel.urlFor(request.containerPath)
    }

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        HtmlPreviewDialogContent(
            fileName = request.fileName,
            url = url,
            viewModel = viewModel,
            onClose = dismiss
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPreviewDialogContent(
    fileName: String,
    url: String?,
    viewModel: HtmlPreviewViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bgColor = if (dark) BG_DARK else BG_LIGHT
    var loading by remember { mutableStateOf(true) }
    val close by rememberUpdatedState(onClose)

    // WebView 需要非 Application Context，这里取组合树里的 Activity 包装。
    val webView = remember {
        WebView(ContextThemeWrapper(context, webViewThemeRes(dark))).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // 页面只经虚拟域名读工作区文件：放开 file:// 会绕过同源策略，必须关掉。
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(bgColor)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = viewModel.assetLoader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false
                }

                // 站外链接交给系统浏览器，不在预览窗口里顶掉当前页面。
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    if (viewModel.containerPathForUrl(target) != null) return false
                    if (target.scheme == "http" || target.scheme == "https") {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, target))
                        }
                        return true
                    }
                    return false
                }
            }
        }
    }

    LaunchedEffect(dark) {
        (webView.context as? ContextThemeWrapper)?.setTheme(webViewThemeRes(dark))
        webView.setBackgroundColor(bgColor)
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    LaunchedEffect(url) {
        val target = url ?: return@LaunchedEffect
        loading = true
        webView.loadUrl(target)
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else close()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        // 顶栏悬浮在页面之上：网页自带导航栏时也不会被挤走。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                        RoundedCornerShape(Radius.pill)
                    )
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            )
            IconButton(
                onClick = close,
                modifier = Modifier.background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                    CircleShape
                )
            ) {
                Icon(
                    FeatherIcons.X,
                    contentDescription = stringResource(R.string.chat_html_preview_close),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private fun webViewThemeRes(dark: Boolean): Int =
    if (dark) R.style.Theme_AICode_WebView_Dark else R.style.Theme_AICode_WebView

/** 会话内预览的默认视口高度：够看首屏，又不至于把后续消息推得太远。 */
private val INLINE_PREVIEW_HEIGHT = 220.dp

/** 展开后的高度上限：再高就该用全屏了。 */
private val INLINE_PREVIEW_EXPANDED_HEIGHT = 420.dp

private const val BG_DARK = 0xFF0D1117.toInt()
private const val BG_LIGHT = 0xFFFFFFFF.toInt()
