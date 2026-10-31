package io.github.mangi.eta.agent.phone.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * App 侧的虚拟屏代理：画面输出到本进程的 ImageReader，截图不依赖无障碍截图接口，
 * 也不含真屏上的敏感窗口（掩码机制天然不适用）。自 mika 的 AgentDisplay 移植。
 * 帧处理限速 ≤10 帧/秒：来帧时若离上次处理不足 100ms 就延后到期再取最新一帧，
 * 期间旧帧由 acquireLatestImage 丢弃。
 */
internal class AgentDisplay(
    private val shell: IPhoneShell,
    val width: Int,
    val height: Int,
    dpi: Int,
) {
    private val thread = HandlerThread("eta-vdisplay").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

    val displayId: Int

    private val _frames = MutableStateFlow<Bitmap?>(null)
    /** 实时画面：限速 ≤10 帧/秒，缩到宽 ≤360。 */
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

    private val frameLock = Any()
    private var fullFrame: Bitmap? = null // 带行填充的整帧，宽 = rowStride / pixelStride
    private var hasFrame = false
    private var lastProcessedAt = 0L
    private var pending = false
    @Volatile private var released = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val previewWidth = minOf(PREVIEW_MAX_WIDTH, width)
    private val previewHeight = maxOf(1, height * previewWidth / width)

    /** 接管小窗激活时，画面切到小窗的 Surface，本 reader 不再收到帧。 */
    @Volatile
    var takeover: Boolean = false
        private set

    private val process = Runnable {
        pending = false
        lastProcessedAt = SystemClock.uptimeMillis()
        processLatest()
    }

    init {
        reader.setOnImageAvailableListener({ schedule() }, handler)
        val id = try {
            shell.createDisplay(reader.surface, width, height, dpi, "eta-agent")
        } catch (t: Throwable) {
            closeLocal()
            throw t
        }
        if (id < 0) {
            closeLocal()
            throw IllegalStateException("createDisplay failed")
        }
        displayId = id
    }

    /** 把虚拟屏输出切到接管小窗的 Surface；null 切回本 reader。 */
    fun setTakeoverSurface(surface: android.view.Surface?) {
        takeover = surface != null
        runCatching { shell.setDisplaySurface(displayId, surface) }
        if (surface == null) {
            // 切回后丢掉陈旧预览，等新帧
            synchronized(frameLock) { hasFrame = false }
        }
    }

    private fun schedule() {
        if (pending || released) return
        pending = true
        val wait = lastProcessedAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
    }

    private fun processLatest() {
        if (released) return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            synchronized(frameLock) {
                var bmp = fullFrame
                if (bmp == null || bmp.width != strideWidth) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                bmp!!.copyPixelsFromBuffer(plane.buffer.rewind())
                hasFrame = true
                val preview = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
                Canvas(preview).drawBitmap(bmp, Rect(0, 0, width, height), Rect(0, 0, previewWidth, previewHeight), paint)
                _frames.value = preview
            }
        } finally {
            image.close()
        }
    }

    /** 最新一帧的整幅 PNG；还没收到画面（或已被接管小窗切走）时返回 null。 */
    fun latestFramePng(): ByteArray? {
        val crop = latestFrameBitmap() ?: return null
        return try {
            ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            crop.recycle()
        }
    }

    /** 原始尺寸的画面副本，调用方负责 recycle；帧差分检测使用。 */
    fun latestFrameBitmap(): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null) null else Bitmap.createBitmap(bmp, 0, 0, width, height)
    }

    /** 上一帧的亮度网格快照，供 [FrameMath] 判断画面是否变化。 */
    fun lumaSnapshot(): IntArray? = latestFrameBitmap()?.let { bmp ->
        try {
            FrameMath.lumaGrid(width, height) { x, y -> bmp.getPixel(x, y) }
        } finally {
            bmp.recycle()
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { shell.releaseDisplay(displayId) }
        closeLocal()
        _frames.value = null
    }

    private fun closeLocal() {
        released = true
        handler.removeCallbacksAndMessages(null)
        // 在帧线程上收尾，避免与正在处理的帧并发
        handler.post {
            reader.close()
            synchronized(frameLock) {
                fullFrame?.recycle()
                fullFrame = null
                hasFrame = false
            }
        }
        thread.quitSafely()
    }

    private companion object {
        const val PREVIEW_MAX_WIDTH = 360
        const val FRAME_INTERVAL_MS = 100L
    }
}
