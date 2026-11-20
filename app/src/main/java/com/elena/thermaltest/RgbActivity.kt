package com.elena.thermaltest

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import kotlin.math.abs

/**
 * Skrin hitam sekejap: ThermVue berhenti seketika dan lepaskan kamera biasa,
 * kita ambil satu gambar RGB, hantar ke SnapService, kemudian kembali ke ThermVue.
 */
class RgbActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var h: Handler? = null
    private var cam: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var prevReader: ImageReader? = null
    private var attempts = 0
    private var started = false
    private var done = false
    private var sensorOri = 90

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Mengambil gambar kamera biasa...\nPegang telefon tetap."
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            textSize = 18f
        })
        main.postDelayed({ finishWith(null) }, 9000)
    }

    override fun onResume() {
        super.onResume()
        if (started) return
        started = true
        thread = HandlerThread("rgb").also { it.start() }
        h = Handler(thread!!.looper)
        // beri masa ThermVue lepaskan kamera
        h?.postDelayed({ tryOpen() }, 500)
    }

    private fun tryOpen() {
        if (done) return
        attempts++
        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val ch = mgr.getCameraCharacteristics("0")
            sensorOri = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            val pick = jpegSizes.filter { it.width * 3 == it.height * 4 && it.width <= 2600 }
                .maxByOrNull { it.width }
                ?: jpegSizes.minByOrNull { abs(it.width - 2048) }
            val jw = pick?.width ?: 1920
            val jh = pick?.height ?: 1440
            val jr = ImageReader.newInstance(jw, jh, ImageFormat.JPEG, 2)
            jpegReader = jr
            jr.setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
                if (img != null) {
                    var bmp: Bitmap? = null
                    try {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (raw != null) {
                            val m = Matrix()
                            m.postRotate(sensorOri.toFloat())
                            bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                        }
                    } catch (_: Exception) {
                    } finally {
                        img.close()
                    }
                    finishWith(bmp)
                }
            }, h)
            val pr = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
            prevReader = pr
            pr.setOnImageAvailableListener({ r ->
                try { r.acquireLatestImage()?.close() } catch (_: Exception) {}
            }, h)
            mgr.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    cam = cd
                    startSession(cd, pr, jr)
                }
                override fun onDisconnected(cd: CameraDevice) { cd.close(); cam = null }
                override fun onError(cd: CameraDevice, error: Int) {
                    cd.close(); cam = null
                    retry()
                }
            }, h)
        } catch (e: Exception) {
            retry()
        }
    }

    private fun retry() {
        if (done) return
        try { jpegReader?.close() } catch (_: Exception) {}
        try { prevReader?.close() } catch (_: Exception) {}
        if (attempts < 8) h?.postDelayed({ tryOpen() }, 400) else finishWith(null)
    }

    @Suppress("DEPRECATION")
    private fun startSession(cd: CameraDevice, pr: ImageReader, jr: ImageReader) {
        try {
            cd.createCaptureSession(listOf(pr.surface, jr.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val prev = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        prev.addTarget(pr.surface)
                        prev.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        s.setRepeatingRequest(prev.build(), null, h)
                        // beri masa pendedahan dan fokus stabil
                        h?.postDelayed({
                            try {
                                val still = cd.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                still.addTarget(jr.surface)
                                still.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                still.set(CaptureRequest.JPEG_QUALITY, 92.toByte())
                                s.capture(still.build(), null, h)
                            } catch (e: Exception) { finishWith(null) }
                        }, 1000)
                    } catch (e: Exception) { finishWith(null) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { finishWith(null) }
            }, h)
        } catch (e: Exception) { finishWith(null) }
    }

    private fun finishWith(bmp: Bitmap?) {
        main.post {
            if (done) return@post
            done = true
            try { session?.close() } catch (_: Exception) {}
            try { cam?.close() } catch (_: Exception) {}
            try { jpegReader?.close() } catch (_: Exception) {}
            try { prevReader?.close() } catch (_: Exception) {}
            session = null; cam = null
            SnapService.instance?.onRgb(bmp)
            packageManager.getLaunchIntentForPackage(SnapService.THERMVUE)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(it) } catch (_: Exception) {}
            }
            finish()
            overridePendingTransition(0, 0)
            main.postDelayed({ thread?.quitSafely(); thread = null }, 500)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!done) finishWith(null)
    }
}
