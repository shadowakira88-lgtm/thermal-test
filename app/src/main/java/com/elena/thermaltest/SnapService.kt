package com.elena.thermaltest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Servis latar depan: rakam skrin (MediaProjection) + butang SNAP terapung. */
class SnapService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        const val CH = "snap"
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null
    private var wm: WindowManager? = null
    private var button: TextView? = null
    private var sw = 0
    private var sh = 0
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data)
            projection = mp
            thread = HandlerThread("snap").also { it.start() }
            bg = Handler(thread!!.looper)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopSelf() } }
            }, bg)
            startCapture(mp)
            showButton()
            running = true
        } catch (e: Exception) {
            toast("Gagal mula: $e")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startCapture(mp: MediaProjection) {
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = w
        val dpi: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = w.currentWindowMetrics.bounds
            sw = b.width(); sh = b.height()
            dpi = resources.displayMetrics.densityDpi
        } else {
            val dm = DisplayMetrics()
            w.defaultDisplay.getRealMetrics(dm)
            sw = dm.widthPixels; sh = dm.heightPixels; dpi = dm.densityDpi
        }
        val rd = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 3)
        reader = rd
        rd.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) {
                latest?.close()
                latest = img
            }
        }, bg)
        vd = mp.createVirtualDisplay("ThermalSnap", sw, sh, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, bg)
    }

    private fun showButton() {
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = "SNAP"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(200, 220, 40, 40))
                setStroke((2 * d).toInt(), Color.WHITE)
            }
        }
        val size = (64 * d).toInt()
        val lp = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (sw - size - 12 * d).toInt()
        lp.y = (sh * 0.45f).toInt()

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var downTime = 0L; var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    downTime = System.currentTimeMillis(); moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 12 * d || abs(dy) > 12 * d) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (System.currentTimeMillis() - downTime > 800) {
                            toast("SNAP dihentikan.")
                            stopSelf()
                        } else {
                            snap()
                        }
                    }
                }
            }
            true
        }
        wm?.addView(tv, lp)
        button = tv
    }

    private fun snap() {
        if (busy) return
        busy = true
        button?.visibility = View.INVISIBLE
        // tunggu skrin dilukis semula tanpa butang
        bg?.postDelayed({
            val img = latest
            latest = null
            var ok = false
            var name = ""
            if (img != null) {
                try {
                    val bmp = toBitmap(img)
                    name = save(bmp)
                    ok = true
                } catch (e: Exception) {
                    toast("Simpan gagal: $e")
                } finally {
                    img.close()
                }
            } else {
                toast("Tiada imej skrin lagi. Cuba sekali lagi.")
            }
            main.post {
                button?.visibility = View.VISIBLE
                busy = false
                if (ok) {
                    button?.text = "OK"
                    main.postDelayed({ button?.text = "SNAP" }, 900)
                    toast("Disimpan: Pictures/ThermalElena/$name")
                }
            }
        }, 350)
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val ps = p.pixelStride
        val rs = p.rowStride
        val w = img.width
        val h = img.height
        val full = Bitmap.createBitmap(rs / ps, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(p.buffer)
        return if (full.width == w) full else Bitmap.createBitmap(full, 0, 0, w, h)
    }

    private fun save(src: Bitmap): String {
        val strip = (src.width * 0.07f).toInt().coerceAtLeast(48)
        val out = Bitmap.createBitmap(src.width, src.height + strip, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        c.drawBitmap(src, 0f, 0f, null)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = strip * 0.45f
            typeface = Typeface.DEFAULT_BOLD
        }
        val now = Date()
        c.drawText("ThermVue  " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now),
            strip * 0.3f, src.height + strip * 0.65f, p)
        val name = "thermvue_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now) + ".png"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ThermalElena")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            ?: throw IllegalStateException("tak dapat cipta fail")
        contentResolver.openOutputStream(uri)!!.use { os -> out.compress(Bitmap.CompressFormat.PNG, 100, os) }
        return name
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Thermal Snap", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0,
            Intent(this, SnapService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1,
            Intent(this, SnapActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Thermal Snap aktif")
            .setContentText("Ketik SNAP untuk simpan. Tekan lama untuk berhenti.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Henti", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun toast(s: String) {
        main.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        running = false
        try { button?.let { wm?.removeView(it) } } catch (_: Exception) {}
        button = null
        try { vd?.release() } catch (_: Exception) {}
        try { latest?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        vd = null; reader = null; projection = null; latest = null
        thread?.quitSafely(); thread = null
        super.onDestroy()
    }
}
