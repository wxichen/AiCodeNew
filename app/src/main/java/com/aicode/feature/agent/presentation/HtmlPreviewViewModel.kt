package com.aicode.feature.agent.presentation

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import androidx.lifecycle.ViewModel
import androidx.webkit.WebViewAssetLoader
import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import javax.inject.Inject

/**
 * 聊天消息里预览 HTML 文件的后端。
 *
 * 把用户/AI 产出的网页直接以 `file://` 加载有两条硬约束：
 * 1. `file://` 页面 origin 是 `null`，`fetch`/XHR 与 localStorage 全部失效，不少生成页面因此白屏；
 * 2. 不能为了让页面跑起来就放开 `allowFileAccess`——那等于把整个文件系统交给页面脚本。
 *
 * 所以工作区内的文件用 [WebViewAssetLoader] 挂到 https 虚拟域名下：页面以
 * `https://<虚拟域名>/workspace/x.html` 的身份运行，同目录的 css/js/图片是相对路径请求，
 * 由 [WorkspacePathHandler] 经 [FileAccessProvider] 读出（远程模式自动走 SFTP）。
 * 页面看到的是一个正常的同源站点，同时脚本碰不到虚拟域名以外的任何文件。
 */
@HiltViewModel
class HtmlPreviewViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider
) : ViewModel() {

    companion object {
        /** [WebViewAssetLoader] 的默认虚拟域名。 */
        const val HOST = "aicode.local"

        const val WORKSPACE_ROOT = HtmlPreviewPaths.WORKSPACE_ROOT
        const val WORKSPACE_ROOT_PREFIX = HtmlPreviewPaths.WORKSPACE_ROOT_PREFIX

        private const val WORKSPACE_MOUNT = "/workspace/"
        private const val TAG = "HtmlPreview"
    }

    val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler(WORKSPACE_MOUNT, WorkspacePathHandler())
        .build()

    /**
     * 工作区内文件的预览 URL：映射到 `/workspace/<相对工作区根的路径>`，页面里的相对资源
     * 请求因此会落到同一挂载点下。
     */
    fun urlFor(containerPath: String): String {
        val relative = HtmlPreviewPaths.relativeOf(containerPath).orEmpty()
        return "https://$HOST$WORKSPACE_MOUNT${Uri.encode(relative, "/")}"
    }

    /** 工作区外的文件没有同源目录可用，退化为把内容内联成 data: 文档。 */
    fun inlineUrl(html: String): String = "data:text/html;charset=utf-8,${Uri.encode(html)}"

    /** 预览 URL 指向工作区文件时返回其容器路径；非本站 URL 返回 null。 */
    fun containerPathForUrl(url: Uri): String? {
        if (url.host != HOST) return null
        val path = url.path ?: return null
        if (!path.startsWith(WORKSPACE_MOUNT)) return null
        return HtmlPreviewPaths.containerPathOf(path.removePrefix(WORKSPACE_MOUNT))
    }

    private inner class WorkspacePathHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val containerPath = HtmlPreviewPaths.containerPathOf(path) ?: return notFound()
            val bytes = runCatching { fileAccess.readBytes(containerPath) }
                .onFailure { FileLogger.w(TAG, "读取预览资源失败: $containerPath", it) }
                .getOrNull() ?: return notFound()
            return WebResourceResponse(mimeOf(containerPath), null, ByteArrayInputStream(bytes))
        }
    }

    /** 命中挂载点却读不到内容：返回 404，避免 WebView 当作未拦截而回退去访问网络。 */
    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun mimeOf(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "text/javascript"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "wasm" -> "application/wasm"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
