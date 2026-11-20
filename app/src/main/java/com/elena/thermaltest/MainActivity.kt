package com.elena.thermaltest

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Paparan thermal: lukis bitmap + titik suhu. Semua logik ada dalam MainActivity. */
class ThermalView(a: MainActivity) : View(a) {
    private val act = a
    override fun onDraw(canvas: Canvas) {
        act.render(canvas, width, height, false, 0)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean = act.handleTouch(this, e)
}

/**
 * Thermal Elena v1: baca data mentah 16-bit dari kamera ID 2 (YUY2 160x120),
 * betulkan corak tetap (NUC satu titik), kalibrasi linear sendiri ke deg C,
 * letak titik suhu dan bakar nilainya ke dalam PNG.
 */
class MainActivity : Activity() {

    companion object {
        const val W = 160
        const val H = 120
        const val MAXP = 6
        const val FLAT_N = 40
    }

    private val lock = Any()

    // data
    private val offset = FloatArray(W * H)
    private val smooth = FloatArray(W * H)
    private val tmp = FloatArray(W * H)
    private val fixed = FloatArray(W * H)
    private val sortBuf = FloatArray(W * H)
    private val pix = IntArray(W * H)
    private val rawBuf = IntArray(W * H)
    private val flatAcc = FloatArray(W * H)
    private var smoothInit = false
    private var hasNuc = false
    private var flatLeft = 0
    private var rangeInit = false
    private var rLo = 0f
    private var rHi = 1f
    private var bmp: Bitmap? = null

    // titik + kalibrasi
    private val points = ArrayList<FloatArray>()
    private var selected = -1
    private val samples = ArrayList<FloatArray>() // [counts, suhu]
    private var calA = 0f
    private var calB = 0f
    private var calValid = false
    @Volatile private var rot = 0

    // paparan
    private lateinit var thermalView: ThermalView
    private lateinit var statusText: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var density = 1f
    private var lastStatus = 0L

    // kamera
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    private val lut = IntArray(256)
    @Volatile private var camState = "belum mula"
    @Volatile private var frameCount = 0
    @Volatile private var lastErr = ""

    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBg = Paint()
    private val paintBmp = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        density = resources.displayMetrics.density
        buildLut()
        loadState()
        buildUi()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startCamera()
        else toast("Kebenaran kamera diperlukan.")
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
    }

    override fun onPause() {
        super.onPause()
        stopCamera()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val pad = (8 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        statusText = TextView(this).apply { textSize = 12f; text = "Memulakan..." }
        root.addView(statusText)

        thermalView = ThermalView(this)
        root.addView(thermalView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        fun row(vararg items: Pair<String, () -> Unit>): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, action) in items) {
                val b = Button(this).apply {
                    text = label
                    textSize = 12f
                    isAllCaps = false
                    setOnClickListener { action() }
                }
                r.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            return r
        }
        root.addView(row(
            "Rata (NUC)" to { askFlat() },
            "Kalibrasi" to { askCalibrate() },
            "Putar" to { rot = (rot + 1) % 4; saveState(); thermalView.invalidate() }
        ))
        root.addView(row(
            "Simpan PNG" to { savePng() },
            "Reset titik" to { resetPoints() },
            "Reset kalib" to { resetCalibration() }
        ))
        setContentView(root)
    }

    private fun toast(s: String) {
        mainHandler.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
    }

    private fun resetPoints() {
        synchronized(lock) { points.clear(); selected = -1 }
        thermalView.invalidate()
        updateStatus()
    }

    private fun resetCalibration() {
        synchronized(lock) { samples.clear(); calValid = false }
        saveState()
        updateStatus()
        thermalView.invalidate()
    }

    private fun askFlat() {
        AlertDialog.Builder(this)
            .setTitle("Betulkan corak tetap (NUC)")
            .setMessage("Tutup lensa thermal dengan permukaan rata dan seragam (kad atau kertas tebal, rapat), " +
                "kemudian tekan Mula. App rakam $FLAT_N frame. Jangan gerakkan.")
            .setPositiveButton("Mula") { _, _ ->
                synchronized(lock) { java.util.Arrays.fill(flatAcc, 0f); flatLeft = FLAT_N }
                toast("Merakam rujukan...")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun askCalibrate() {
        val sel = synchronized(lock) { selected }
        if (sel < 0 || sel >= synchronized(lock) { points.size }) {
            toast("Ketik imej untuk letak titik, pilih satu titik, kemudian tekan Kalibrasi.")
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = "cth 33.2"
        }
        AlertDialog.Builder(this)
            .setTitle("Suhu sebenar di P${sel + 1} (deg C)")
            .setMessage("Guna bacaan ThermVue atau termometer pada tempat yang sama. " +
                "Perlu sekurang-kurangnya 2 sampel pada suhu berbeza.")
            .setView(input)
            .setPositiveButton("Tambah") { _, _ ->
                val t = input.text.toString().toFloatOrNull()
                if (t == null) { toast("Nombor tak sah."); return@setPositiveButton }
                synchronized(lock) {
                    if (sel < points.size) {
                        val c = countsAtLocked(points[sel][0], points[sel][1])
                        samples.add(floatArrayOf(c, t))
                        refitLocked()
                    }
                }
                saveState()
                updateStatus()
                thermalView.invalidate()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ------------------------------------------------------------------ kalibrasi

    private fun countsAtLocked(x: Float, y: Float): Float {
        val cx = x.toInt().coerceIn(1, W - 2)
        val cy = y.toInt().coerceIn(1, H - 2)
        var s = 0f
        for (dy in -1..1) for (dx in -1..1) s += smooth[(cy + dy) * W + (cx + dx)]
        return s / 9f
    }

    private fun refitLocked() {
        calValid = false
        val n = samples.size
        if (n < 2) return
        var sx = 0.0; var sy = 0.0
        for (s in samples) { sx += s[0]; sy += s[1] }
        val mx = sx / n; val my = sy / n
        var sxx = 0.0; var sxy = 0.0
        for (s in samples) { sxx += (s[0] - mx) * (s[0] - mx); sxy += (s[0] - mx) * (s[1] - my) }
        if (sxx < 400.0) return // counts terlalu dekat antara sampel
        val a = sxy / sxx
        calA = a.toFloat()
        calB = (my - a * mx).toFloat()
        calValid = true
    }

    private fun tempFromCounts(c: Float): Float = calA * c + calB

    // ------------------------------------------------------------------ simpan / muat

    private fun loadState() {
        val sp = getSharedPreferences("thermal", MODE_PRIVATE)
        rot = sp.getInt("rot", 0)
        val s = sp.getString("samples", "") ?: ""
        for (part in s.split(";")) {
            val ab = part.split("|")
            if (ab.size == 2) {
                val c = ab[0].toFloatOrNull(); val t = ab[1].toFloatOrNull()
                if (c != null && t != null) samples.add(floatArrayOf(c, t))
            }
        }
        refitLocked()
        val f = File(filesDir, "nuc.bin")
        if (f.exists() && f.length() == (W * H * 4).toLong()) {
            val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            for (i in offset.indices) offset[i] = bb.getFloat()
            hasNuc = true
        }
    }

    private fun saveState() {
        val sb = StringBuilder()
        synchronized(lock) { for (s in samples) sb.append(s[0]).append('|').append(s[1]).append(';') }
        getSharedPreferences("thermal", MODE_PRIVATE).edit()
            .putInt("rot", rot).putString("samples", sb.toString()).apply()
    }

    private fun saveNuc() {
        try {
            val bb = ByteBuffer.allocate(W * H * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in offset) bb.putFloat(v)
            File(filesDir, "nuc.bin").writeBytes(bb.array())
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ kamera

    private fun startCamera() {
        if (thread != null) return
        camState = "membuka kamera 2..."
        updateStatus()
        thread = HandlerThread("cam").also { it.start() }
        handler = Handler(thread!!.looper)
        val rd = ImageReader.newInstance(W, H, 20 /* YUY2 */, 3)
        reader = rd
        rd.setOnImageAvailableListener({ r -> onImage(r) }, handler)
        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            mgr.openCamera("2", object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) { camera = cd; camState = "kamera dibuka"; updateStatus(); createSession(cd, rd) }
                override fun onDisconnected(cd: CameraDevice) { cd.close(); camera = null; camState = "kamera terputus"; updateStatus() }
                override fun onError(cd: CameraDevice, error: Int) {
                    cd.close(); camera = null
                    camState = "RALAT kamera kod $error"; updateStatus()
                }
            }, handler)
        } catch (e: Exception) {
            camState = "GAGAL buka: $e"; updateStatus()
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(cd: CameraDevice, rd: ImageReader) {
        try {
            cd.createCaptureSession(listOf(rd.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val req = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        req.addTarget(rd.surface)
                        s.setRepeatingRequest(req.build(), null, handler)
                        camState = "streaming, tunggu frame"; updateStatus()
                    } catch (e: Exception) { camState = "GAGAL streaming: $e"; updateStatus() }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { camState = "konfigurasi sesi GAGAL"; updateStatus() }
            }, handler)
        } catch (e: Exception) { camState = "GAGAL cipta sesi: $e"; updateStatus() }
    }

    private fun stopCamera() {
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; camera = null; reader = null
        thread?.quitSafely(); thread = null; handler = null
        camState = "dihentikan"
    }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val p = img.planes[0]
            val buf = p.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val rs = p.rowStride
            val ps = p.pixelStride
            for (y in 0 until H) for (x in 0 until W) {
                val pos = y * rs + x * ps
                rawBuf[y * W + x] = if (pos + 1 < buf.limit()) (buf.getShort(pos).toInt() and 0xFFFF) else 0
            }
            processFrame(rawBuf)
            frameCount++
            thermalView.postInvalidate()
            val now = System.currentTimeMillis()
            if (now - lastStatus > 250) { lastStatus = now; updateStatus() }
        } catch (e: Exception) {
            lastErr = e.toString()
        } finally {
            img.close()
        }
    }

    // ------------------------------------------------------------------ pemprosesan

    private fun buildLut() {
        val stops = arrayOf(
            floatArrayOf(0.00f, 0f, 0f, 30f),
            floatArrayOf(0.25f, 90f, 0f, 140f),
            floatArrayOf(0.50f, 210f, 30f, 50f),
            floatArrayOf(0.75f, 255f, 150f, 0f),
            floatArrayOf(0.90f, 255f, 230f, 80f),
            floatArrayOf(1.00f, 255f, 255f, 255f)
        )
        for (i in 0 until 256) {
            val t = i / 255f
            var k = 0
            while (k < stops.size - 2 && t > stops[k + 1][0]) k++
            val a = stops[k]; val b = stops[k + 1]
            val f = ((t - a[0]) / (b[0] - a[0])).coerceIn(0f, 1f)
            val r = (a[1] + (b[1] - a[1]) * f).toInt()
            val g = (a[2] + (b[2] - a[2]) * f).toInt()
            val bl = (a[3] + (b[3] - a[3]) * f).toInt()
            lut[i] = Color.rgb(r, g, bl)
        }
    }

    private fun finishFlatLocked() {
        var sum = 0.0
        for (v in flatAcc) sum += v
        val mean = (sum / flatAcc.size / FLAT_N).toFloat()
        for (i in offset.indices) offset[i] = flatAcc[i] / FLAT_N - mean
        hasNuc = true
        smoothInit = false
        saveNuc()
        toast("NUC siap. Corak tetap dibetulkan.")
    }

    private fun processFrame(raw: IntArray) {
        synchronized(lock) {
            // baris pertama ialah artifak sensor: salin dari baris kedua
            System.arraycopy(raw, W, raw, 0, W)
            if (flatLeft > 0) {
                for (i in raw.indices) flatAcc[i] += raw[i].toFloat()
                flatLeft--
                if (flatLeft == 0) finishFlatLocked()
            }
            for (i in raw.indices) tmp[i] = raw[i] - offset[i]
            System.arraycopy(tmp, 0, fixed, 0, tmp.size)
            // piksel rosak: ganti dengan purata jiran jika terpencil
            for (y in 1 until H - 1) for (x in 1 until W - 1) {
                val i = y * W + x
                val m = (tmp[i - 1] + tmp[i + 1] + tmp[i - W] + tmp[i + W] +
                    tmp[i - W - 1] + tmp[i - W + 1] + tmp[i + W - 1] + tmp[i + W + 1]) / 8f
                if (abs(tmp[i] - m) > 900f) fixed[i] = m
            }
            if (!smoothInit) {
                System.arraycopy(fixed, 0, smooth, 0, fixed.size)
                smoothInit = true
            } else {
                for (i in smooth.indices) smooth[i] += 0.35f * (fixed[i] - smooth[i])
            }
            System.arraycopy(smooth, 0, sortBuf, 0, smooth.size)
            Arrays.sort(sortBuf)
            var lo = sortBuf[(sortBuf.size * 0.01f).toInt()]
            var hi = sortBuf[(sortBuf.size * 0.99f).toInt()]
            if (hi - lo < 80f) { val c = (hi + lo) / 2f; lo = c - 40f; hi = c + 40f }
            if (!rangeInit) { rLo = lo; rHi = hi; rangeInit = true }
            else { rLo += 0.2f * (lo - rLo); rHi += 0.2f * (hi - rHi) }
            val span = max(1f, rHi - rLo)
            for (i in pix.indices) {
                val idx = (((smooth[i] - rLo) / span) * 255f).toInt().coerceIn(0, 255)
                pix[i] = lut[idx]
            }
            bmp = Bitmap.createBitmap(pix, W, H, Bitmap.Config.ARGB_8888)
        }
    }

    // ------------------------------------------------------------------ lukisan

    private fun fitMatrix(w: Int, availH: Int): Matrix {
        val r = rot
        val m = Matrix()
        m.postTranslate(-W / 2f, -H / 2f)
        m.postRotate(r * 90f)
        val rw = if (r % 2 == 0) W else H
        val rh = if (r % 2 == 0) H else W
        val s = min(w.toFloat() / rw, availH.toFloat() / rh)
        m.postScale(s, s)
        m.postTranslate(w / 2f, availH / 2f)
        return m
    }

    private fun statsLineLocked(): String {
        var mn = Float.MAX_VALUE; var mxv = -Float.MAX_VALUE
        for (y in 1 until H - 1) for (x in 1 until W - 1) {
            val v = smooth[y * W + x]
            if (v < mn) mn = v
            if (v > mxv) mxv = v
        }
        return if (calValid) {
            val a = tempFromCounts(mn); val b = tempFromCounts(mxv)
            String.format(Locale.US, "Min %.1f  Max %.1f C", min(a, b), max(a, b))
        } else {
            "Belum dikalibrasi (nilai mentah)"
        }
    }

    fun render(canvas: Canvas, w: Int, h: Int, forSave: Boolean, strip: Int) {
        canvas.drawColor(Color.BLACK)
        val b = synchronized(lock) { bmp } ?: return
        val m = fitMatrix(w, h - strip)
        canvas.drawBitmap(b, m, paintBmp)

        val ts = if (forSave) max(16f, w / 30f) else 14f * density
        paintText.textSize = ts
        paintText.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paintLine.style = Paint.Style.STROKE
        paintBg.color = Color.argb(170, 0, 0, 0)
        val arm = ts * 0.8f

        val labels = ArrayList<String>()
        val pts = ArrayList<FloatArray>()
        var selIdx = -1
        var stats = ""
        synchronized(lock) {
            selIdx = selected
            for ((i, p) in points.withIndex()) {
                val c = countsAtLocked(p[0], p[1])
                labels.add(if (calValid) String.format(Locale.US, "P%d %.1f C", i + 1, tempFromCounts(c))
                else String.format(Locale.US, "P%d %d", i + 1, c.toInt()))
                pts.add(floatArrayOf(p[0], p[1]))
            }
            stats = statsLineLocked()
        }
        for ((i, p) in pts.withIndex()) {
            m.mapPoints(p)
            val x = p[0]; val y = p[1]
            paintLine.color = Color.BLACK; paintLine.strokeWidth = ts * 0.22f
            canvas.drawLine(x - arm, y, x + arm, y, paintLine)
            canvas.drawLine(x, y - arm, x, y + arm, paintLine)
            paintLine.color = Color.WHITE; paintLine.strokeWidth = ts * 0.1f
            canvas.drawLine(x - arm, y, x + arm, y, paintLine)
            canvas.drawLine(x, y - arm, x, y + arm, paintLine)
            if (i == selIdx && !forSave) {
                paintLine.color = Color.YELLOW; paintLine.strokeWidth = ts * 0.1f
                canvas.drawCircle(x, y, arm * 1.2f, paintLine)
            }
            val tw = paintText.measureText(labels[i])
            var lx = x + arm * 0.6f
            var ly = y - arm * 0.6f
            if (lx + tw + 8f > w) lx = x - arm * 0.6f - tw - 8f
            if (ly - ts < 0f) ly = y + arm + ts
            canvas.drawRect(lx - 4f, ly - ts, lx + tw + 4f, ly + ts * 0.3f, paintBg)
            paintText.color = Color.WHITE
            canvas.drawText(labels[i], lx, ly, paintText)
        }
        if (forSave && strip > 0) {
            paintText.color = Color.WHITE
            paintText.textSize = strip * 0.32f
            val tsTxt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            canvas.drawText(stats, 12f, (h - strip) + strip * 0.42f, paintText)
            canvas.drawText(tsTxt, 12f, (h - strip) + strip * 0.85f, paintText)
        }
    }

    fun handleTouch(v: View, e: MotionEvent): Boolean {
        val m = fitMatrix(v.width, v.height)
        val inv = Matrix()
        if (!m.invert(inv)) return true
        val pt = floatArrayOf(e.x, e.y)
        inv.mapPoints(pt)
        val ix = pt[0].coerceIn(0f, W - 1f)
        val iy = pt[1].coerceIn(0f, H - 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> synchronized(lock) {
                var best = -1
                var bd = Float.MAX_VALUE
                for ((i, p) in points.withIndex()) {
                    val sp = floatArrayOf(p[0], p[1])
                    m.mapPoints(sp)
                    val d = hypot(sp[0] - e.x, sp[1] - e.y)
                    if (d < bd) { bd = d; best = i }
                }
                if (best >= 0 && bd < 60f * density) {
                    selected = best
                } else if (points.size < MAXP) {
                    points.add(floatArrayOf(ix, iy))
                    selected = points.size - 1
                }
                Unit
            }
            MotionEvent.ACTION_MOVE -> synchronized(lock) {
                if (selected >= 0 && selected < points.size) {
                    points[selected][0] = ix
                    points[selected][1] = iy
                }
                Unit
            }
        }
        v.invalidate()
        updateStatus()
        return true
    }

    private fun updateStatus() {
        val s = synchronized(lock) {
            val sb = StringBuilder()
            sb.append("NUC: ").append(if (hasNuc) "ada" else "belum")
            sb.append(" | Kalib: ")
            if (calValid) sb.append(String.format(Locale.US, "ok (%d sampel, %.4f C/unit)", samples.size, calA))
            else sb.append("belum (").append(samples.size).append(" sampel, perlu 2)")
            sb.append('\n').append(statsLineLocked())
            if (selected >= 0 && selected < points.size) sb.append("  | Dipilih: P").append(selected + 1)
            sb.append('\n').append("Kamera: ").append(camState).append(" | frame: ").append(frameCount)
            if (lastErr.isNotEmpty()) sb.append("\nRalat: ").append(lastErr)
            sb.toString()
        }
        mainHandler.post { statusText.text = s }
    }

    // ------------------------------------------------------------------ simpan PNG

    private fun savePng() {
        try {
            val even = rot % 2 == 0
            val ow = if (even) 960 else 540
            val oh = 720
            val strip = 72
            val out = Bitmap.createBitmap(ow, oh + strip, Bitmap.Config.ARGB_8888)
            render(Canvas(out), ow, oh + strip, true, strip)
            val name = "thermal_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ThermalElena")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            if (uri == null) { toast("Gagal cipta fail."); return }
            contentResolver.openOutputStream(uri)!!.use { os -> out.compress(Bitmap.CompressFormat.PNG, 100, os) }
            toast("Disimpan: Pictures/ThermalElena/$name")
        } catch (e: Exception) {
            toast("Simpan gagal: $e")
        }
    }
}
