package com.aicode.feature.agent.presentation

import java.net.URLDecoder
import java.nio.file.Paths

/**
 * 工作区路径与 HTML 预览 URL 之间的映射规则。
 *
 * 单独抽成纯函数对象：这里全是路径归一化逻辑（`..` 必须收在工作区内），
 * 既是最需要单测覆盖的地方，也不该为了测试去构造带 Android 依赖的 ViewModel。
 */
internal object HtmlPreviewPaths {

    /** 工作区在容器内的根路径，与 [com.aicode.feature.workspace.domain.WorkspacePathMapper] 一致。 */
    const val WORKSPACE_ROOT = "~/workspace"
    const val WORKSPACE_ROOT_PREFIX = "$WORKSPACE_ROOT/"

    /** 工作区根下的相对路径；不在工作区内时返回 null。 */
    fun relativeOf(containerPath: String): String? = when {
        containerPath == WORKSPACE_ROOT -> ""
        containerPath.startsWith(WORKSPACE_ROOT_PREFIX) -> containerPath.removePrefix(WORKSPACE_ROOT_PREFIX)
        else -> null
    }

    /** 相对工作区根的路径还原成容器路径；空路径与归一化后越界的请求返回 null。 */
    fun containerPathOf(relativePath: String): String? {
        val decoded = runCatching { URLDecoder.decode(relativePath, "UTF-8") }.getOrNull() ?: return null
        if (decoded.isEmpty()) return null
        // 归一化把 .. 收在根内，避免请求跳出工作区
        val normalized = runCatching { Paths.get("/$decoded").normalize().toString() }.getOrNull() ?: return null
        val trimmed = normalized.trimStart('/')
        if (trimmed.isEmpty()) return null
        return "$WORKSPACE_ROOT/$trimmed"
    }
}
