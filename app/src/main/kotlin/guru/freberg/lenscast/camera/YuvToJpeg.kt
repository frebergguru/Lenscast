package guru.freberg.lenscast.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Converts a CameraX [ImageProxy] in YUV_420_888 format to a JPEG byte array.
 *
 * Two operations happen here:
 *   1. Plane data is copied into a tight NV21 buffer accounting for row & pixel strides.
 *      Skipping this step lets [YuvImage] silently emit corrupted output on Android 12+
 *      whenever stride != width — which is common at non-standard resolutions.
 *   2. The buffer is rotated by [ImageProxy.imageInfo.rotationDegrees] so that downstream
 *      consumers (e.g. OBS) always receive an upright frame regardless of how the phone
 *      is physically held.
 *
 * **Threading:** the shared scratch buffers below are NOT synchronized, so this object
 * must only ever be driven by one thread at a time. There are two callers:
 *   - CameraX's `ImageAnalysis` analyzer (MJPEG path), on its dedicated single-thread
 *     executor (see `CameraController.analysisExecutor`);
 *   - the RTSP/SRT MJPEG-sidecar listener (`StreamingService.makeSidecarListener`), on its
 *     own HandlerThread.
 * These never run concurrently because the RTSP path unbinds the CameraX `ImageAnalysis`
 * use case before the sidecar starts — the single-threaded invariant is enforced by that
 * sequencing, not by this class. If you ever keep the analyzer alive alongside a sidecar
 * session, add a lock here or the shared `jpegOut`/buffers will corrupt across threads.
 */
object YuvToJpeg {

    // Scratch buffers reused across frames. Re-allocated only when the requested size
    // changes (e.g. when the user picks a new resolution). At 1080p this saves ~6 MB
    // of allocation per frame.
    private var nv21Buf: ByteArray = ByteArray(0)
    private var rotatedBuf: ByteArray = ByteArray(0)
    private var yRowBuf: ByteArray = ByteArray(0)
    private var uRowBuf: ByteArray = ByteArray(0)
    private var vRowBuf: ByteArray = ByteArray(0)
    private val jpegOut = ByteArrayOutputStream(64 * 1024)

    fun encode(image: ImageProxy, quality: Int, mirror: Boolean = false, watermark: String = ""): ByteArray {
        val w = image.width
        val h = image.height
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val nv21 = planesToNv21(
            w, h,
            yP.buffer, yP.rowStride, yP.pixelStride,
            uP.buffer, uP.rowStride, uP.pixelStride,
            vP.buffer, vP.rowStride, vP.pixelStride,
        )
        return jpegFromNv21(nv21, w, h, image.imageInfo.rotationDegrees, quality, mirror, watermark)
    }

    /**
     * JPEG-encode an `android.media.Image` from an ImageReader (YUV_420_888). Same NV21
     * conversion path as [encode], used by the RTSP-with-MJPEG-sidecar mode where Camera2
     * owns the camera directly and CameraX isn't in the pipeline.
     */
    fun encodeImage(
        image: android.media.Image,
        rotationDegrees: Int,
        quality: Int,
        mirror: Boolean = false,
        watermark: String = "",
    ): ByteArray {
        val w = image.width
        val h = image.height
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val nv21 = planesToNv21(
            w, h,
            yP.buffer, yP.rowStride, yP.pixelStride,
            uP.buffer, uP.rowStride, uP.pixelStride,
            vP.buffer, vP.rowStride, vP.pixelStride,
        )
        return jpegFromNv21(nv21, w, h, rotationDegrees, quality, mirror, watermark)
    }

    private val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setShadowLayer(3f, 1f, 1f, Color.argb(180, 0, 0, 0))
    }
    private val watermarkClock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun jpegFromNv21(
        nv21: ByteArray, w: Int, h: Int, rotationDegrees: Int, quality: Int,
        mirror: Boolean, watermark: String,
    ): ByteArray {
        val (rotated, rw, rh) = if (rotationDegrees == 0) Triple(nv21, w, h) else rotateNv21(nv21, w, h, rotationDegrees)
        if (mirror) mirrorNv21(rotated, rw, rh)
        jpegOut.reset()
        val q = quality.coerceIn(10, 100)
        YuvImage(rotated, ImageFormat.NV21, rw, rh, null)
            .compressToJpeg(Rect(0, 0, rw, rh), q, jpegOut)
        val bare = jpegOut.toByteArray()
        if (watermark.isEmpty()) return bare
        return overlayWatermark(bare, watermark, q) ?: bare
    }

    /**
     * Decodes [src], draws [text] (with `%t` expanded to a wall-clock `HH:mm:ss`) in the
     * bottom-left, re-encodes JPEG. Returns null if decode fails — caller falls back to the
     * un-watermarked bytes so a transient failure doesn't drop the frame.
     */
    private fun overlayWatermark(src: ByteArray, text: String, quality: Int): ByteArray? {
        val opts = BitmapFactory.Options().apply { inMutable = true }
        val bm = BitmapFactory.decodeByteArray(src, 0, src.size, opts) ?: return null
        try {
            val resolved = if ("%t" in text) text.replace("%t", watermarkClock.format(Date())) else text
            // Scale text size with frame height — 4% of height keeps it legible from 720p..1080p.
            watermarkPaint.textSize = (bm.height * 0.04f).coerceAtLeast(18f)
            val pad = (bm.height * 0.02f).coerceAtLeast(8f)
            val baseline = bm.height - pad
            Canvas(bm).drawText(resolved, pad, baseline, watermarkPaint)
            jpegOut.reset()
            bm.compress(Bitmap.CompressFormat.JPEG, quality, jpegOut)
            return jpegOut.toByteArray()
        } finally {
            bm.recycle()
        }
    }

    /**
     * Horizontal flip of an NV21 buffer in place. Y plane reverses each row; the interleaved
     * VU plane reverses each chroma row as (V,U) pairs (we must keep the pair order intact,
     * just swap their column positions, otherwise the colours desaturate / shift).
     */
    private fun mirrorNv21(buf: ByteArray, w: Int, h: Int) {
        // Y plane.
        for (row in 0 until h) {
            val base = row * w
            var l = 0
            var r = w - 1
            while (l < r) {
                val t = buf[base + l]
                buf[base + l] = buf[base + r]
                buf[base + r] = t
                l++; r--
            }
        }
        // VU plane: 2 bytes per chroma sample, w/2 samples per row, h/2 rows.
        val ySize = w * h
        val chromaW = w / 2
        val chromaH = h / 2
        for (row in 0 until chromaH) {
            val base = ySize + row * w  // chroma row stride == w (semi-planar)
            var l = 0
            var r = chromaW - 1
            while (l < r) {
                val lb = base + l * 2
                val rb = base + r * 2
                val t0 = buf[lb]; val t1 = buf[lb + 1]
                buf[lb] = buf[rb]; buf[lb + 1] = buf[rb + 1]
                buf[rb] = t0;      buf[rb + 1] = t1
                l++; r--
            }
        }
    }

    /**
     * Converts the planes of a YUV_420_888 image into a tight NV21 buffer suitable for
     * [YuvImage]. The copy is mandatory: `YuvImage` silently emits corrupted output on
     * Android 12+ when row/pixel stride don't match width.
     */
    private fun planesToNv21(
        width: Int, height: Int,
        yBuffer: ByteBuffer, yRowStride: Int, @Suppress("UNUSED_PARAMETER") yPixelStride: Int,
        uBuffer: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        vBuffer: ByteBuffer, vRowStride: Int, vPixelStride: Int,
    ): ByteArray {
        val ySize = width * height
        val uvSize = ySize / 2
        val totalSize = ySize + uvSize
        if (nv21Buf.size != totalSize) nv21Buf = ByteArray(totalSize)
        val nv21 = nv21Buf

        // Y plane: copy row by row honoring rowStride.
        if (yRowStride == width) {
            yBuffer.position(0)
            yBuffer.get(nv21, 0, ySize)
        } else {
            if (yRowBuf.size != yRowStride) yRowBuf = ByteArray(yRowStride)
            val row = yRowBuf
            var pos = 0
            for (r in 0 until height) {
                yBuffer.position(r * yRowStride)
                yBuffer.get(row, 0, yRowStride)
                System.arraycopy(row, 0, nv21, pos, width)
                pos += width
            }
        }

        // VU plane: NV21 interleaves V then U. The U/V planes may share storage with
        // pixelStride=2 (semi-planar) — but ordering varies, so do the manual loop.
        val chromaHeight = height / 2
        val chromaWidth = width / 2

        if (vRowBuf.size != vRowStride) vRowBuf = ByteArray(vRowStride)
        if (uRowBuf.size != uRowStride) uRowBuf = ByteArray(uRowStride)
        val vRow = vRowBuf
        val uRow = uRowBuf

        var offset = ySize
        for (r in 0 until chromaHeight) {
            vBuffer.position(r * vRowStride)
            val vLen = minOf(vRowStride, vBuffer.remaining())
            vBuffer.get(vRow, 0, vLen)
            uBuffer.position(r * uRowStride)
            val uLen = minOf(uRowStride, uBuffer.remaining())
            uBuffer.get(uRow, 0, uLen)
            for (c in 0 until chromaWidth) {
                nv21[offset++] = vRow[c * vPixelStride]
                nv21[offset++] = uRow[c * uPixelStride]
            }
        }
        return nv21
    }

    /**
     * In-memory rotation of an NV21 buffer by 90, 180, or 270 degrees clockwise.
     * Returns (rotatedBuffer, newWidth, newHeight). The destination buffer is the
     * pooled [rotatedBuf]; the caller must finish using it before the next frame.
     */
    private fun rotateNv21(src: ByteArray, w: Int, h: Int, degrees: Int): Triple<ByteArray, Int, Int> {
        if (rotatedBuf.size != src.size) rotatedBuf = ByteArray(src.size)
        val out = rotatedBuf
        val ySize = w * h
        when (degrees) {
            90 -> {
                // Y
                var i = 0
                for (x in 0 until w) {
                    for (y in h - 1 downTo 0) {
                        out[i++] = src[y * w + x]
                    }
                }
                // VU interleaved chroma
                val chromaH = h / 2
                val chromaW = w / 2
                var dst = ySize
                for (x in 0 until chromaW) {
                    for (y in chromaH - 1 downTo 0) {
                        val srcIdx = ySize + y * w + x * 2
                        out[dst++] = src[srcIdx]
                        out[dst++] = src[srcIdx + 1]
                    }
                }
                return Triple(out, h, w)
            }
            180 -> {
                var i = 0
                for (y in h - 1 downTo 0) {
                    for (x in w - 1 downTo 0) {
                        out[i++] = src[y * w + x]
                    }
                }
                val chromaH = h / 2
                val chromaW = w / 2
                var dst = ySize
                for (y in chromaH - 1 downTo 0) {
                    for (x in chromaW - 1 downTo 0) {
                        val srcIdx = ySize + y * w + x * 2
                        out[dst++] = src[srcIdx]
                        out[dst++] = src[srcIdx + 1]
                    }
                }
                return Triple(out, w, h)
            }
            270 -> {
                var i = 0
                for (x in w - 1 downTo 0) {
                    for (y in 0 until h) {
                        out[i++] = src[y * w + x]
                    }
                }
                val chromaH = h / 2
                val chromaW = w / 2
                var dst = ySize
                for (x in chromaW - 1 downTo 0) {
                    for (y in 0 until chromaH) {
                        val srcIdx = ySize + y * w + x * 2
                        out[dst++] = src[srcIdx]
                        out[dst++] = src[srcIdx + 1]
                    }
                }
                return Triple(out, h, w)
            }
            else -> return Triple(src, w, h)
        }
    }
}
