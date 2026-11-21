package com.aicode.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentKindTest {

    @Test
    fun imageDetectedByExtensionEvenWithoutMime() {
        assertEquals(AttachmentKind.IMAGE, attachmentKindOf("shot.PNG", "", isImage = false))
        assertEquals(AttachmentKind.IMAGE, attachmentKindOf("photo.jpeg", "application/octet-stream", false))
    }

    @Test
    fun imageDetectedByMimeWhenExtensionUnknown() {
        assertEquals(AttachmentKind.IMAGE, attachmentKindOf("blob", "image/webp", false))
    }

    @Test
    fun isImageFlagWins() {
        // sendFile 的 is_image 与 mime 冲突时以显式标记为准，避免刚发出去的图片被渲染成文件行。
        assertEquals(AttachmentKind.IMAGE, attachmentKindOf("data.bin", "application/octet-stream", isImage = true))
    }

    @Test
    fun videoDetectedByExtensionOrMime() {
        assertEquals(AttachmentKind.VIDEO, attachmentKindOf("clip.mp4", "", false))
        assertEquals(AttachmentKind.VIDEO, attachmentKindOf("recording.MOV", "", false))
        assertEquals(AttachmentKind.VIDEO, attachmentKindOf("stream", "video/webm", false))
    }

    @Test
    fun htmlDetectedByExtensionOrMime() {
        assertEquals(AttachmentKind.HTML, attachmentKindOf("index.html", "", false))
        assertEquals(AttachmentKind.HTML, attachmentKindOf("page.HTM", "", false))
        assertEquals(AttachmentKind.HTML, attachmentKindOf("page", "text/html", false))
    }

    @Test
    fun otherForPlainFiles() {
        assertEquals(AttachmentKind.OTHER, attachmentKindOf("report.pdf", "application/pdf", false))
        assertEquals(AttachmentKind.OTHER, attachmentKindOf("README", "", false))
    }

    @Test
    fun fileExtensionHandlesDotlessAndUppercase() {
        assertEquals("mp4", fileExtension("a.MP4"))
        assertEquals("", fileExtension("noext"))
        assertEquals("", fileExtension("trailing."))
    }
}
