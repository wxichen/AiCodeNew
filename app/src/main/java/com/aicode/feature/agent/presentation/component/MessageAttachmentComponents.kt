package com.aicode.feature.agent.presentation.component

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AttachmentKind
import com.aicode.core.ui.LocalImageViewer
import com.aicode.core.ui.ThumbnailCache
import com.aicode.core.ui.attachmentKindOf
import com.aicode.core.ui.decodeSampledBitmap
import com.aicode.core.util.ApkInstaller
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.workspace.domain.FileAccessProvider
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Image
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 附件缩略图边长：非图片文件行左侧的小图标尺寸。 */
private val AttachmentThumbSize = 44.dp

/** 图片墙体感：单张时按原比例铺满气泡宽度，上限 260dp 高，太长的长截图也不至于占满整屏。 */
private val ImageMaxHeight = 260.dp

/** 消息内图片解码的像素上限：与 Markdown 正文同档，再大在手机屏上看不出差别。 */
private const val IMAGE_DECODE_MAX_EDGE = 1024

/** 占位阶段容器尺寸未知时的兜底下限，避免退化成缩略图档位。 */
private const val IMAGE_DECODE_MIN_EDGE = 480

/** 用户消息里单图的宽度上限：小图不该被拉满整行，与右侧气泡的观感保持一致。 */
private val USER_SINGLE_IMAGE_MAX_WIDTH = 260.dp

/**
 * 已发送 / 已生成附件的列表。
 *
 * 按类型分流渲染：
 * - **图片**：按原比例铺开显示，多张走双列缩略图墙，点任一进全屏查看器；
 * - **视频**：气泡内直接可播放，不完整的临时文件退化为一行提示卡片；
 * - **HTML**：一行文件卡片 + 「预览」按钮，点了在应用内全屏渲染页面；
 * - **其余**：一行一个文件（图标 + 文件名 + 大小 · 容器路径），点击交给调用方。
 *
 * [fromUser] 决定较窄的媒体（竖屏视频、多图方格）往哪边靠：用户消息在右、助手消息在左，
 * 与气泡本身的方向一致。文件行始终占满宽度，不受影响。
 *
 * 点击语义：图片一律走内置全屏查看器（隐式依赖组合树上层提供 [LocalImageViewer]，聊天区都满足）；
 * 非图片用调用方给的 [onClick] —— 工具卡片传的是「系统 app 打开」，助手气泡不传即不可点。
 */
@Composable
internal fun MessageAttachmentList(
    attachments: List<AgentAttachment>,
    onClick: ((AgentAttachment) -> Unit)? = null,
    fromUser: Boolean = false
) {
    if (attachments.isEmpty()) return
    val kinds = remember(attachments) {
        attachments.map { attachmentKindOf(it.fileName, it.mimeType, it.isImage) }
    }
    val horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        val images = attachments.filterIndexed { index, _ -> kinds[index] == AttachmentKind.IMAGE }
        if (images.isNotEmpty()) {
            MessageImageGrid(images = images, fromUser = fromUser)
        }
        attachments.forEachIndexed { index, attachment ->
            when (kinds[index]) {
                AttachmentKind.IMAGE -> Unit
                AttachmentKind.VIDEO -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = horizontalArrangement
                ) {
                    VideoAttachmentCard(attachment = attachment)
                }
                else -> MessageAttachmentRow(
                    attachment = attachment,
                    onClick = onClick,
                    previewable = kinds[index] == AttachmentKind.HTML
                )
            }
        }
    }
}

/**
 * 图片墙：一张时按原比例铺开，多张时双列缩略图。
 *
 * 单图高度上限 [ImageMaxHeight]：长截图（4000×300 这类）按原比例铺满会把气泡拉成一屏多，
 * 反而看不到上下文；限高后靠点击进全屏查看器看细节。
 *
 * [fromUser] 为真时，单图与多图墙都靠右（与用户气泡方向一致）。
 */
@Composable
private fun MessageImageGrid(images: List<AgentAttachment>, fromUser: Boolean) {
    if (images.size == 1) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
        ) {
            MessageImageTile(
                attachment = images.first(),
                modifier = if (fromUser) Modifier.widthIn(max = USER_SINGLE_IMAGE_MAX_WIDTH) else Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
                singleImage = true
            )
        }
        return
    }
    // 双列缩略图：按行分组，最后一行不足两个时用 Spacer 占位，保持与上方对齐。
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        images.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                row.forEach { attachment ->
                    MessageImageTile(
                        attachment = attachment,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        contentScale = ContentScale.Crop,
                        singleImage = false
                    )
                }
                // 补齐占位，避免奇数张时最后一张被拉成整行宽
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 单张图片：按容器宽度解码、原比例铺开，点击进全屏查看器。
 *
 * 解码档位必须跟实际显示尺寸挂钩：这里以前复用了 [ThumbnailCache] 的 180px 缩略图，
 * 图被放大到整个气泡宽度后就发虚（小图尤其明显——原图才 1000px 宽，却被缩到 180 再放大）。
 * 现在按容器宽度 × 屏幕密度算出需要的像素宽，并按长宽比推出长边，再解码。
 */
@Composable
private fun MessageImageTile(
    attachment: AgentAttachment,
    modifier: Modifier,
    contentScale: ContentScale,
    /** 单图需要按原比例展示（限高），多图是固定方格裁切。 */
    singleImage: Boolean
) {
    val viewer = LocalImageViewer.current
    val label = stringResource(R.string.chat_attachment_preview, attachment.fileName)
    BoxWithConstraints(
        modifier = if (singleImage) modifier.heightIn(max = ImageMaxHeight) else modifier
    ) {
        val density = LocalDensity.current
        // 容器像素宽，封顶到与 Markdown 正文同档：再大在手机屏上也看不出差别，反而费内存。
        val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceAtMost(IMAGE_DECODE_MAX_EDGE)
        val maxEdge = widthPx.coerceAtLeast(IMAGE_DECODE_MIN_EDGE)
        val bitmap by produceState<ImageBitmap?>(initialValue = null, attachment.localPath, maxEdge) {
            value = withContext(Dispatchers.IO) { decodeSampledBitmap(attachment.localPath, maxEdge) }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (singleImage) Modifier else Modifier.fillMaxHeight())
                .clip(RoundedCornerShape(Radius.md))
                .background(chatMutedSurfaceColor())
                .clickable(onClickLabel = label) { viewer.show(attachment.toViewerRequest()) },
            contentAlignment = Alignment.Center
        ) {
            val image = bitmap
            if (image != null) {
                if (singleImage) {
                    // 单图按位图自身比例排布（限高由外层 heightIn 生效），
                    // 用 fillMaxSize 会把它拉满限高，矮图被纵向拉伸。
                    ComposeImage(
                        bitmap = image,
                        contentDescription = label,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    ComposeImage(
                        bitmap = image,
                        contentDescription = label,
                        contentScale = contentScale,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Box(modifier = Modifier.height(160.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        FeatherIcons.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

/**
 * 视频附件：直接内嵌播放器，可看可播。
 *
 * [AgentAttachment.localPath] 在远程模式下是临时副本，进程重启或缓存被清掉后失效；
 * 失效时播放器自己会退化成一行提示，不在这里预先判断（避免组合阶段做文件 IO）。
 */
@Composable
private fun VideoAttachmentCard(attachment: AgentAttachment) {
    VideoAttachmentPlayer(
        localPath = attachment.localPath,
        fileName = attachment.fileName
    )
}

@Composable
private fun MessageAttachmentRow(
    attachment: AgentAttachment,
    onClick: ((AgentAttachment) -> Unit)? = null,
    /** HTML 附件多给一个「预览」按钮，与「用系统 App 打开」并存。 */
    previewable: Boolean = false
) {
    val viewer = LocalImageViewer.current
    val htmlPreview = LocalHtmlPreview.current
    val handler: (() -> Unit)? = if (attachment.isImage) {
        { viewer.show(attachment.toViewerRequest()) }
    } else if (onClick != null) {
        { onClick(attachment) }
    } else {
        null
    }
    val fallbackName = stringResource(R.string.common_file)
    val name = attachment.fileName.ifBlank { fallbackName }
    // 读屏与长按提示用：图片是「预览」，其它是「打开」，两者落到的地方不一样。
    val actionLabel = if (attachment.isImage) {
        stringResource(R.string.chat_attachment_preview, name)
    } else {
        stringResource(R.string.chat_attachment_open, name)
    }
    // 次要信息：大小 + 容器路径。路径对「AI 发过来的文件」是关键信息（能直接在终端/编辑器里找到），
    // 整行放不下时靠省略号截断，不换行。
    val meta = remember(attachment.sizeBytes, attachment.containerPath) {
        listOf(formatBytes(attachment.sizeBytes), attachment.containerPath)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = chatMutedSurfaceColor(),
        modifier = Modifier
            .fillMaxWidth()
            // 先裁圆角再挂 clickable：涟漪才会被裁在圆角内，不会溢出成直角方块
            .clip(RoundedCornerShape(Radius.md))
            .then(
                if (handler != null) {
                    Modifier.clickable(onClickLabel = actionLabel, onClick = handler)
                } else {
                    Modifier
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            AttachmentThumb(attachment = attachment)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 有动作才给指示箭头：不可点的行不该长得像能点（如助手气泡里的非图片附件）
            if (previewable) {
                FilledTonalButton(
                    onClick = { htmlPreview.show(HtmlPreviewRequest(attachment.containerPath, name)) },
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(text = stringResource(R.string.chat_html_preview), style = MaterialTheme.typography.labelSmall)
                }
            } else if (handler != null) {
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = Brand.IconGray,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachmentThumb(attachment: AgentAttachment) {
    Surface(
        shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.size(AttachmentThumbSize)
    ) {
        if (attachment.isImage) {
            AttachmentImageThumb(attachment = attachment)
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    FeatherIcons.FileText,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachmentImageThumb(attachment: AgentAttachment) {
    // 读盘 + 解码放 IO 线程：以前在 remember 里同步 decodeFile，滚到带图消息时会卡主线程。
    // 采样与 OOM 兜底统一走 decodeSampledBitmap。
    val path = attachment.localPath
    val bitmap by produceState<ImageBitmap?>(ThumbnailCache.peek(path), path) {
        value = ThumbnailCache.peek(path) ?: withContext(Dispatchers.IO) { ThumbnailCache.load(path) }
    }
    val loaded = bitmap
    if (loaded != null) {
        ComposeImage(
            bitmap = loaded,
            contentDescription = attachment.fileName.ifBlank { stringResource(R.string.common_image_preview) },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                FeatherIcons.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 打开已发送的非图片附件（用系统 app）。默认空实现，与 `LocalImageViewer` 同一套路 ——
 * 卡片渲染在 `ToolMessageBody` 这类中间层，用 CompositionLocal 避免一路加回调参数、
 * 免去中间层为转发 lambda 改签名。
 */
fun interface AttachmentOpener {
    fun open(attachment: AgentAttachment)
}

internal val LocalAttachmentOpener = staticCompositionLocalOf<AttachmentOpener> { AttachmentOpener { } }

/**
 * 用系统对应 app 打开附件文件（FileProvider 授权 URI）。
 *
 * 卡片记录的 [AgentAttachment.localPath] 不保证仍然有效：远程 SSH 模式下它是 `copyToLocal` 的临时副本
 * （进程重启或系统清缓存后消失），外部本地目录工作区、自定义挂载源的文件又落在 FileProvider 声明的目录树之外。
 * 因此失效时退回 [AgentAttachment.containerPath] 经 [fileAccess] 重取，重取到的路径仍无法授权时
 * 再复制进 cacheDir（已由 cache-path 覆盖）分享。文件确实不存在与最终无法分享分别提示，不再混用一句文案。
 */
internal suspend fun openSentAttachment(
    context: Context,
    attachment: AgentAttachment,
    fileAccess: FileAccessProvider
) {
    val local = withContext(Dispatchers.IO) { resolveLocalFile(attachment, fileAccess) }
    if (local == null) {
        Toast.makeText(context, context.getString(R.string.chat_open_file_missing), Toast.LENGTH_SHORT).show()
        return
    }
    // APK 安装包：统一走 ApkInstaller（含「允许安装未知应用」的授权判定与设置页引导）。
    // 外部本地目录工作区、自定义挂载源的文件不在 FileProvider 目录树内，先拷进 cacheDir 再授权。
    if (isApk(attachment)) {
        val installable = withContext(Dispatchers.IO) {
            if (isShareable(context, local)) local else copyToShareCache(context, local)
        }
        if (installable == null) {
            Toast.makeText(context, context.getString(R.string.chat_open_file_unshareable), Toast.LENGTH_SHORT).show()
            return
        }
        when (ApkInstaller.install(context, installable)) {
            ApkInstaller.Result.STARTED -> Unit
            ApkInstaller.Result.NEED_PERMISSION -> {
                Toast.makeText(context, context.getString(R.string.chat_open_apk_permission), Toast.LENGTH_LONG).show()
                if (!ApkInstaller.openInstallPermissionSettings(context)) {
                    Toast.makeText(context, context.getString(R.string.chat_open_file_no_app), Toast.LENGTH_SHORT).show()
                }
            }
            ApkInstaller.Result.NO_HANDLER ->
                Toast.makeText(context, context.getString(R.string.chat_open_file_no_app), Toast.LENGTH_SHORT).show()
        }
        return
    }
    val shareable = withContext(Dispatchers.IO) {
        if (isShareable(context, local)) local else copyToShareCache(context, local)
    }
    val uri = shareable?.let {
        runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }.getOrNull()
    }
    if (uri == null) {
        Toast.makeText(context, context.getString(R.string.chat_open_file_unshareable), Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, attachment.mimeType.ifBlank { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.chat_open_file_no_app), Toast.LENGTH_SHORT).show()
    }
}

/** 卡片记录的宿主路径失效（远程临时副本被清、文件被删或移动）时，退回容器路径重取。 */
private suspend fun resolveLocalFile(attachment: AgentAttachment, fileAccess: FileAccessProvider): File? {
    attachment.localPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }?.let { return it }
    val containerPath = attachment.containerPath
    if (containerPath.isBlank()) return null
    return runCatching { fileAccess.copyToLocal(containerPath) }.getOrNull()?.takeIf { it.isFile }
}

/** 路径落在 FileProvider 声明的目录树内时可直接授权；不能就抛异常，故按能否取到 URI 判定。 */
private fun isShareable(context: Context, file: File): Boolean =
    runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) }.isSuccess

/**
 * 把无法直接授权的文件（外部本地目录工作区、自定义挂载源、SD 卡等）复制进 cacheDir 再分享。
 * 目标目录按源路径散列分隔：既避开同名文件互相覆盖，也不把散列值挂进分享出去的文件名。
 */
private fun copyToShareCache(context: Context, source: File): File? = runCatching {
    val dir = File(File(context.cacheDir, SHARE_CACHE_DIR), source.absolutePath.hashCode().toString())
    dir.mkdirs()
    val target = File(dir, source.name.ifBlank { "file" })
    source.copyTo(target, overwrite = true)
    target
}.getOrNull()

private const val SHARE_CACHE_DIR = "sent_files"

private fun isApk(attachment: AgentAttachment): Boolean {
    if (attachment.mimeType.equals("application/vnd.android.package-archive", ignoreCase = true)) return true
    val name = attachment.fileName.lowercase()
    return name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".xapk")
}
