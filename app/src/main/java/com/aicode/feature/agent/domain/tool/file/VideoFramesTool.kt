package com.aicode.feature.agent.domain.tool.file

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Base64
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 让 AI 看到视频画面：从视频里抽若干帧转成图片送入上下文。
 *
 * 模型协议本身没有视频 part（三个适配器都只认图片），业界通用做法就是把代表帧当图片给模型。
 * 抽帧走 Android 原生 [MediaMetadataRetriever]，不引入 ffmpeg，APK 体积不变。
 *
 * 设计上刻意做成**工具**而不是「发视频自动抽帧」：长视频盲抽很容易错过关键画面，
 * 由 AI 自己决定看第几秒更有效——先抽开场确认内容，再针对具体时段细看。
 *
 * 抽出的帧走与 `viewImage` 相同的图片通道（[ToolResult.Success.images]），
 * 因此所有支持视觉的模型都能直接用。
 */
class VideoFramesTool @Inject constructor(
    private val fileAccess: FileAccessProvider,
    @param:ApplicationContext private val appContext: Context
) : AbstractContextualTool() {

    override val name = "videoFrames"
    override val description =
        "从视频里抽帧查看画面。视频内容无法直接读取，需要先抽帧再分析。两种用法：\n" +
            "- 传 path 与 count：在整段视频上均匀抽 count 帧（默认 4，上限 $MAX_FRAMES），适合先快速了解视频内容；\n" +
            "- 传 path 与 timestamps：精确到指定秒数抽帧（如 [0, 12.5, 30]），适合已经知道要看哪一段。\n" +
            "两个参数可同时用，此时只会采用 timestamps。支持 mp4/webm/mkv/mov/3gp 等常见格式，" +
            "路径支持工作区相对路径、~/workspace/... 或当前执行环境的绝对路径。"

    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val parameters = mapOf(
        "path" to ToolParameter(
            name = "path",
            type = ParameterType.STRING,
            description = "视频文件路径。",
            required = true
        ),
        "count" to ToolParameter(
            name = "count",
            type = ParameterType.INTEGER,
            description = "均匀抽帧数量，1~$MAX_FRAMES，默认 $DEFAULT_FRAMES。传了 timestamps 时忽略。",
            required = false
        ),
        "timestamps" to ToolParameter(
            name = "timestamps",
            type = ParameterType.ARRAY,
            description = "要抽取的时间点（秒，可带小数），最多 $MAX_FRAMES 个。给出后优先于 count。",
            required = false,
            itemsSchema = mapOf("type" to "number")
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult = withContext(Dispatchers.IO) {
        val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (path.isBlank()) {
            return@withContext ToolResult.Error("缺少参数 path。", "MISSING_ARGS")
        }
        if (!fileAccess.exists(path)) {
            return@withContext ToolResult.Error("文件不存在：$path", "FILE_NOT_FOUND")
        }
        if (fileAccess.isDirectory(path)) {
            return@withContext ToolResult.Error("路径是目录，不是视频文件：$path", "NOT_A_FILE")
        }

        val explicit = parseTimestamps(args)
        val count = args["count"]?.jsonPrimitive?.intOrNull ?: DEFAULT_FRAMES
        if (explicit.isEmpty() && (count < 1 || count > MAX_FRAMES)) {
            return@withContext ToolResult.Error("count 取值范围是 1~$MAX_FRAMES，当前 $count。", "INVALID_ARGS")
        }
        if (explicit.size > MAX_FRAMES) {
            return@withContext ToolResult.Error(
                "时间点最多 $MAX_FRAMES 个，当前 ${explicit.size} 个。",
                "TOO_MANY_TIMESTAMPS"
            )
        }

        val local = runCatching { fileAccess.copyToLocal(path) }.getOrNull()
        if (local == null || !local.isFile || local.length() <= 0L) {
            return@withContext ToolResult.Error("无法读取视频文件：$path", "READ_VIDEO_ERROR")
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(local.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: return@withContext ToolResult.Error(
                    "无法读取视频时长，可能不是有效的视频文件：$path",
                    "UNSUPPORTED_VIDEO"
                )

            val targetsMs = if (explicit.isNotEmpty()) {
                explicit.map { (it * 1000).toLong() }.filter { it in 0..durationMs }
            } else {
                evenFrameTimestamps(durationMs, count)
            }
            if (targetsMs.isEmpty()) {
                return@withContext ToolResult.Error(
                    "给出的时间点都超出视频时长（${formatSeconds(durationMs)} 秒）。",
                    "TIMESTAMP_OUT_OF_RANGE"
                )
            }

            val frames = mutableListOf<AgentImage>()
            val captured = mutableListOf<Long>()
            for (ms in targetsMs) {
                val bitmap = retriever.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: continue
                val base64 = runCatching { encodeFrame(bitmap) }.getOrNull()
                bitmap.recycle()
                if (base64 == null) continue
                frames += AgentImage(
                    mimeType = FRAME_MIME,
                    base64Data = base64,
                    path = fileAccess.toDisplayPath(path)
                )
                captured += ms
            }
            if (frames.isEmpty()) {
                return@withContext ToolResult.Error(
                    "未能从视频中取到任何帧，可能编码格式不受支持：$path",
                    "FRAME_EXTRACT_FAILED"
                )
            }

            val durationText = formatSeconds(durationMs)
            val content = buildString {
                append("已从视频中抽取 ${frames.size} 帧（视频总长 $durationText 秒）：")
                captured.forEachIndexed { index, ms ->
                    append("\n- 第 ${index + 1} 帧：${formatSeconds(ms)} 秒")
                }
                append("\n这些帧已作为图片附在本次结果中，可直接观察画面内容。")
                append("如需查看其它时间点，再次调用并传 timestamps。")
            }
            ToolResult.Success(
                data = JsonObject(
                    mapOf(
                        "status" to JsonPrimitive("frames_attached"),
                        "path" to JsonPrimitive(fileAccess.toDisplayPath(path)),
                        "duration_seconds" to JsonPrimitive(durationMs / 1000.0),
                        "frame_count" to JsonPrimitive(frames.size),
                        "timestamps" to JsonArray(captured.map { JsonPrimitive(it / 1000.0) }),
                        "content" to JsonPrimitive(content)
                    )
                ),
                images = frames
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "抽帧失败: $path", e)
            ToolResult.Error(e.message ?: "抽帧失败", "FRAME_EXTRACT_FAILED")
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun parseTimestamps(args: Map<String, JsonElement>): List<Double> {
        val arr = args["timestamps"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { element ->
            element.jsonPrimitive.contentOrNull?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }
        }
    }

    /** 单帧压缩：长边不超过 [FRAME_MAX_EDGE]，逐级降质量直到体积可接受。 */
    private fun encodeFrame(bitmap: Bitmap): String {
        val scaled = scaleToMaxEdge(bitmap, FRAME_MAX_EDGE)
        var quality = FRAME_QUALITY
        var bytes = compress(scaled, quality)
        while (bytes.size > FRAME_MAX_BYTES && quality > 30) {
            quality -= 15
            bytes = compress(scaled, quality)
        }
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longEdge
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun formatSeconds(ms: Long): String =
        if (ms % 1000L == 0L) (ms / 1000L).toString() else String.format(java.util.Locale.US, "%.1f", ms / 1000.0)

    private companion object {
        const val TAG = "VideoFramesTool"

        /** 单次最多返回多少帧：再多会把上下文撑爆，也超出多数模型单次图片数上限。 */
        const val MAX_FRAMES = 8
        const val DEFAULT_FRAMES = 4

        /** 帧图长边上限，与 viewImage 的高清档一致。 */
        const val FRAME_MAX_EDGE = 1280

        /** 单帧编码体积上限，避免 8 张帧图把请求撑得过大。 */
        const val FRAME_MAX_BYTES = 260 * 1024

        const val FRAME_QUALITY = 85

        /** 抽出的帧统一转成 JPEG：视频帧没有 alpha 通道，JPEG 体积最小且通吃所有视觉模型。 */
        const val FRAME_MIME = "image/jpeg"
    }
}

/**
 * 均匀取帧的时间点（毫秒）。
 *
 * 首尾各留 5%：视频开头的黑场与结尾的落版帧对理解内容没帮助，均匀铺满全长反而容易抽到它们。
 * 单帧退化到正中间，避免只看到片头。
 */
internal fun evenFrameTimestamps(durationMs: Long, count: Int): List<Long> {
    if (count <= 0 || durationMs <= 0) return emptyList()
    if (count == 1) return listOf(durationMs / 2)
    val start = durationMs / 20
    val end = durationMs - durationMs / 20
    val step = (end - start) / (count - 1).toDouble()
    return (0 until count).map { index -> (start + step * index).toLong().coerceIn(0, durationMs) }
}
