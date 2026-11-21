package com.aicode.core.ui

/**
 * 附件类型判定：聊天里决定「图片铺开显示 / 视频内嵌播放 / 网页可预览 / 普通文件卡片」。
 *
 * 只看文件名与 mime 两路信息：文件来自 `sendFile` 工具时 mime 由工具侧猜测，
 * 用户上传时由 ContentResolver 给出，两者都可能缺失或与实际不符（例如 `.mp4` 被标成
 * `application/octet-stream`），所以扩展名优先、mime 兜底。
 */
enum class AttachmentKind {
    IMAGE,
    VIDEO,
    HTML,
    OTHER
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")

private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "ts")

private val HTML_EXTENSIONS = setOf("html", "htm")

/** 扩展名（小写，不含点）。 */
fun fileExtension(name: String): String =
    name.substringAfterLast('.', "").substringBefore('?').lowercase()

fun attachmentKindOf(fileName: String, mimeType: String, isImage: Boolean): AttachmentKind {
    val ext = fileExtension(fileName)
    val mime = mimeType.lowercase()
    return when {
        isImage || mime.startsWith("image/") || ext in IMAGE_EXTENSIONS -> AttachmentKind.IMAGE
        mime.startsWith("video/") || ext in VIDEO_EXTENSIONS -> AttachmentKind.VIDEO
        mime == "text/html" || ext in HTML_EXTENSIONS -> AttachmentKind.HTML
        else -> AttachmentKind.OTHER
    }
}
