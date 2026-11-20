package com.elena.thermaltest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.AppOpsManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
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
import android.net.Uri
import android.widget.ImageView
import android.widget.LinearLayout
import android.util.Size
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
import kotlin.math.max

/** Servis latar depan: rakam skrin (MediaProjection) + butang SNAP terapung. */
class SnapService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        const val CH = "snap"
        @Volatile var running = false
        @Volatile var instance: SnapService? = null
        const val THERMVUE = "com.infisense.eco"

        fun hasUsageAccess(ctx: android.content.Context): Boolean {
            val ao = ctx.getSystemService(APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val m = ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
            return m == AppOpsManager.MODE_ALLOWED
        }
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
    private var bar: LinearLayout? = null
    private var thumb: ImageView? = null
    private var lastUri: Uri? = null
    private var sw = 0
    private var sh = 0
    private var busy = false
    private var hiddenForCapture = false
    private var fgOk = true
    private var lastPoll = 0L
    private var pendingThermal: Bitmap? = null
    private val poller = object : Runnable {
        override fun run() {
            pollForeground()
            main.postDelayed(this, 700)
        }
    }

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
            instance = this
            if (hasUsageAccess(this)) {
                fgOk = false
                lastPoll = System.currentTimeMillis() - 15000
                applyVisibility()
                main.post(poller)
            }
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
        val size = (60 * d).toInt()
        val gap = (10 * d).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val th = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply {
                cornerRadius = 10 * d
                setColor(Color.argb(200, 40, 40, 40))
                setStroke((2 * d).toInt(), Color.WHITE)
            }
            setPadding((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
        }
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
        container.addView(th, LinearLayout.LayoutParams(size, size).apply { rightMargin = gap })
        container.addView(tv, LinearLayout.LayoutParams(size, size))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        val sp = getSharedPreferences("snap", MODE_PRIVATE)
        lp.x = sp.getInt("bx", (sw - 2 * size - gap - 12 * d).toInt())
        lp.y = sp.getInt("by", (sh * 0.86f).toInt())

        fun attach(v: View, onClick: () -> Unit, onLong: () -> Unit) {
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
            var downTime = 0L; var moved = false
            v.setOnTouchListener { _, e ->
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
                            try { wm?.updateViewLayout(container, lp) } catch (_: Exception) {}
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            sp.edit().putInt("bx", lp.x).putInt("by", lp.y).apply()
                        } else if (System.currentTimeMillis() - downTime > 800) {
                            onLong()
                        } else {
                            onClick()
                        }
                    }
                }
                true
            }
        }
        attach(tv, { snap() }, { toast("SNAP dihentikan."); stopSelf() })
        attach(th, { openLast() }, { openLast() })
        wm?.addView(container, lp)
        button = tv
        thumb = th
        bar = container
        sp.getString("lastUri", null)?.let { u ->
            val uri = Uri.parse(u)
            bg?.post { loadThumb(uri) }
        }
    }

    private fun loadThumb(uri: Uri) {
        try {
            val d = resources.displayMetrics.density
            val px = (60 * d).toInt()
            val b = contentResolver.loadThumbnail(uri, Size(px * 2, px * 2), null)
            lastUri = uri
            main.post { thumb?.setImageBitmap(b) }
        } catch (_: Exception) {
            // fail mungkin dah dipadam
        }
    }

    private fun openLast() {
        val u = lastUri
        if (u == null) { toast("Belum ada gambar."); return }
        try {
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(u, "image/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(i)
        } catch (e: Exception) {
            toast("Tak dapat buka galeri: $e")
        }
    }

    private fun applyVisibility() {
        bar?.visibility = if (!hiddenForCapture && fgOk) View.VISIBLE else View.GONE
    }

    /** Papar butang hanya bila ThermVue di depan (perlu akses penggunaan). */
    private fun pollForeground() {
        try {
            val um = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val ev = um.queryEvents(lastPoll - 1000, now)
            lastPoll = now
            val e = UsageEvents.Event()
            var top: String? = null
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) top = e.packageName
            }
            if (top != null) {
                val ok = top == THERMVUE
                if (ok != fgOk) { fgOk = ok; applyVisibility() }
            }
        } catch (_: Exception) {}
    }

    private fun snap() {
        if (busy) return
        busy = true
        hiddenForCapture = true
        applyVisibility()
        // tunggu skrin dilukis semula tanpa butang
        bg?.postDelayed({
            val img = latest
            latest = null
            var thermal: Bitmap? = null
            if (img != null) {
                try {
                    thermal = crop(toBitmap(img))
                } catch (e: Exception) {
                    toast("Tangkap gagal: $e")
                } finally {
                    img.close()
                }
            } else {
                toast("Tiada imej skrin lagi. Cuba sekali lagi.")
            }
            val rgb = getSharedPreferences("snap", MODE_PRIVATE).getBoolean("rgb", false)
            if (thermal != null && rgb) {
                pendingThermal = thermal
                main.post {
                    try {
                        startActivity(Intent(this, RgbActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                    } catch (e: Exception) {
                        bg?.post { onRgb(null) }
                    }
                }
            } else {
                finishSnap(thermal)
            }
        }, 400)
    }

    /** Dipanggil oleh RgbActivity (null jika kamera biasa gagal). */
    fun onRgb(rgb: Bitmap?) {
        bg?.post {
            val t = pendingThermal
            pendingThermal = null
            if (t == null) { finishSnap(null); return@post }
            if (rgb == null) {
                toast("Kamera biasa gagal, gambar haba sahaja disimpan.")
                finishSnap(t)
            } else {
                finishSnap(combine(t, rgb))
            }
        }
    }

    private fun combine(t: Bitmap, r: Bitmap): Bitmap {
        val vertical = getSharedPreferences("snap", MODE_PRIVATE).getBoolean("stack", false)
        val gap = (t.width * 0.01f).toInt().coerceAtLeast(6)
        return if (vertical) {
            val rh = r.height * t.width / r.width
            val rs = Bitmap.createScaledBitmap(r, t.width, rh, true)
            val out = Bitmap.createBitmap(t.width, t.height + gap + rh, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.BLACK)
            c.drawBitmap(t, 0f, 0f, null)
            c.drawBitmap(rs, 0f, (t.height + gap).toFloat(), null)
            out
        } else {
            val rw = r.width * t.height / r.height
            val rs = Bitmap.createScaledBitmap(r, rw, t.height, true)
            val out = Bitmap.createBitmap(t.width + gap + rw, t.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.BLACK)
            c.drawBitmap(t, 0f, 0f, null)
            c.drawBitmap(rs, (t.width + gap).toFloat(), 0f, null)
            out
        }
    }

    private fun finishSnap(bmp: Bitmap?) {
        var ok = false
        if (bmp != null) {
            try {
                save(bmp)
                val small = Bitmap.createScaledBitmap(bmp, max(1, bmp.width / 6), max(1, bmp.height / 6), true)
                ok = true
                main.post { thumb?.setImageBitmap(small) }
            } catch (e: Exception) {
                toast("Simpan gagal: $e")
            }
        }
        main.post {
            hiddenForCapture = false
            applyVisibility()
            busy = false
            if (ok) {
                button?.text = "OK"
                main.postDelayed({ button?.text = "SNAP" }, 900)
            }
        }
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

    /** Potong bar alat ThermVue: simpan dari bar nilai suhu hingga atas baris alat bawah. */
    private fun crop(src: Bitmap): Bitmap {
        val sp = getSharedPreferences("snap", MODE_PRIVATE)
        if (!sp.getBoolean("crop", true)) return src
        val top = (src.height * sp.getFloat("cropTop", SnapActivity.DEF_TOP) / 100f).toInt().coerceIn(0, src.height - 10)
        val bot = (src.height * sp.getFloat("cropBot", SnapActivity.DEF_BOT) / 100f).toInt().coerceIn(top + 10, src.height)
        return Bitmap.createBitmap(src, 0, top, src.width, bot - top)
    }

    private fun save(src: Bitmap): String {
        val sp = getSharedPreferences("snap", MODE_PRIVATE)
        val stamp = sp.getBoolean("stamp", false)
        val now = Date()
        val out: Bitmap
        if (stamp) {
            val strip = (src.width * 0.06f).toInt().coerceAtLeast(40)
            out = Bitmap.createBitmap(src.width, src.height + strip, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.BLACK)
            c.drawBitmap(src, 0f, 0f, null)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = strip * 0.5f
                typeface = Typeface.DEFAULT_BOLD
            }
            c.drawText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now),
                strip * 0.3f, src.height + strip * 0.68f, p)
        } else {
            out = src
        }
        val name = "thermvue_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now) + ".png"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ThermalElena")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            ?: throw IllegalStateException("tak dapat cipta fail")
        contentResolver.openOutputStream(uri)!!.use { os -> out.compress(Bitmap.CompressFormat.PNG, 100, os) }
        lastUri = uri
        sp.edit().putString("lastUri", uri.toString()).apply()
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
        instance = null
        main.removeCallbacks(poller)
        try { bar?.let { wm?.removeView(it) } } catch (_: Exception) {}
        button = null; bar = null; thumb = null
        try { vd?.release() } catch (_: Exception) {}
        try { latest?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        vd = null; reader = null; projection = null; latest = null
        thread?.quitSafely(); thread = null
        super.onDestroy()
    }
}
