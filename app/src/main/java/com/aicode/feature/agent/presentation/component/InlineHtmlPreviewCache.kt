package com.aicode.feature.agent.presentation.component

import android.view.ViewGroup
import android.webkit.WebView

/**
 * 会话内联网页预览的 WebView 缓存。
 *
 * 聊天是 `LazyColumn`，内联预览滚出视口就会被组合销毁；每次回来都新建 WebView 意味着
 * 拉起一次 Chromium、重新解析页面、重跑 JS，多条 HTML 消息来回滚动会明显卡顿。
 *
 * 做法与设置页的 `PluginWebViewCache` 一致：按文件路径保留最近的实例，滚回来时直接 attach。
 * 销毁时机交给「脱离容器 + 超出上限」两个条件同时满足，避免把正在显示的实例拆掉。
 *
 * 与插件页缓存的关键差别：这里没有页面桥，也不需要跨页保持 JS 状态，
 * 所以只需记录**已加载的 URL**，命中缓存且 URL 未变就跳过 loadUrl。
 */
internal object InlineHtmlPreviewCache {

    /** 同时保留的实例上限：聊天里通常只有一两条网页消息，留 3 个足够覆盖来回滚动。 */
    private const val MAX_ENTRIES = 3

    private class CachedPage(val view: WebView) {
        /** 当前已加载完成的 URL；null 表示还在加载或尚未加载。 */
        var loadedUrl: String? = null
    }

    /** 访问顺序 LRU：[take] 命中会把该实例排到队尾。 */
    private val pages = LinkedHashMap<String, CachedPage>(4, 0.75f, true)

    /** 被顶替但可能仍挂在容器里的实例：脱离容器后再销毁。 */
    private val superseded = mutableListOf<CachedPage>()

    /** 是否已有该 key 的实例（不改变 LRU 顺序，可在组合阶段调用）。 */
    fun has(key: String): Boolean = pages.containsKey(key)

    /** 取实例；命中时保留其已加载状态，调用方据此决定是否重新 loadUrl。 */
    fun take(key: String): WebView? = pages[key]?.view

    /** 该实例是否已经完整加载过 [url]（用于跳过重复加载）。 */
    fun isLoaded(key: String, url: String): Boolean = pages[key]?.loadedUrl == url

    fun markLoaded(key: String, url: String) {
        pages[key]?.loadedUrl = url
    }

    fun put(key: String, view: WebView) {
        val existing = pages[key]
        if (existing?.view === view) return
        if (existing != null) {
            pages.remove(key)
            retire(existing)
        }
        pages[key] = CachedPage(view)
        trim()
    }

    /** 消息项离开组合：只从父容器摘掉，实例与已加载内容留着。 */
    fun release(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        trim()
        val retired = superseded.firstOrNull { it.view === view } ?: return
        superseded.remove(retired)
        destroy(retired)
    }

    /** 宿主销毁（聊天页关闭 / Activity 结束）时统一回收，避免 WebView 长期持有 Activity。 */
    fun clear() {
        val cached = pages.values.toList()
        pages.clear()
        cached.forEach { destroy(it) }
        val retired = superseded.toList()
        superseded.clear()
        retired.forEach { destroy(it) }
    }

    /** 淘汰超出上限、且已经脱离容器的实例；仍在显示的留到 [release] 再处理。 */
    private fun trim() {
        if (pages.size <= MAX_ENTRIES) return
        val iterator = pages.entries.iterator()
        while (pages.size > MAX_ENTRIES && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.view.parent != null) continue
            iterator.remove()
            destroy(entry.value)
        }
    }

    /** 把旧实例移出 LRU，等它脱离容器后销毁。 */
    private fun retire(entry: CachedPage) {
        if (entry.view.parent == null) {
            destroy(entry)
        } else {
            superseded += entry
        }
    }

    private fun destroy(entry: CachedPage) {
        (entry.view.parent as? ViewGroup)?.removeView(entry.view)
        runCatching {
            entry.view.stopLoading()
            entry.view.destroy()
        }
    }
}
