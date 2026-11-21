package com.aicode.feature.agent.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HtmlPreviewPathsTest {

    @Test
    fun workspacePathYieldsRelativePath() {
        assertEquals("demo/index.html", HtmlPreviewPaths.relativeOf("~/workspace/demo/index.html"))
        assertEquals("", HtmlPreviewPaths.relativeOf("~/workspace"))
    }

    @Test
    fun pathOutsideWorkspaceHasNoRelativeForm() {
        // 工作区外的文件没有同源目录，调用方会退化成 data: 内联
        assertNull(HtmlPreviewPaths.relativeOf("/sdcard/notes/index.html"))
        assertNull(HtmlPreviewPaths.relativeOf("~/other/index.html"))
    }

    @Test
    fun relativePathRoundTrips() {
        val relative = HtmlPreviewPaths.relativeOf("~/workspace/demo/js/app.js")
        assertEquals("~/workspace/demo/js/app.js", HtmlPreviewPaths.containerPathOf(relative!!))
    }

    @Test
    fun encodedPathIsDecoded() {
        assertEquals("~/workspace/a b/c d/index.html", HtmlPreviewPaths.containerPathOf("a%20b/c%20d/index.html"))
    }

    @Test
    fun pathTraversalIsClampedToWorkspaceRoot() {
        // ../ 归一化后收在根内，不会读到工作区之外
        assertEquals("~/workspace/index.html", HtmlPreviewPaths.containerPathOf("../index.html"))
        assertEquals("~/workspace/secret.txt", HtmlPreviewPaths.containerPathOf("demo/../../secret.txt"))
    }

    @Test
    fun blankPathIsRejected() {
        assertNull(HtmlPreviewPaths.containerPathOf(""))
        assertNull(HtmlPreviewPaths.containerPathOf(".."))
    }
}
