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
import android.hardware.camera2.CameraCharacteristics
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
import android.widget.SeekBar
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
 * Thermal Elena v2: data mentah 16-bit kamera ID 2 (YUY2 160x120), NUC + piksel rosak + buang jalur,
 * gabungan tepi kamera biasa (emboss) dengan penjajaran manual, kalibrasi linear ke deg C,
 * titik suhu dibakar ke PNG.
 */
class MainActivity : Activity() {

    companion object {
        const val W = 160
        const val H = 120
        const val DW = 320 // ruang paparan (sama dengan sudut pandang kamera biasa, 4:3)
        const val DH = 240
        const val MAXP = 6
        const val FLAT_N = 40
    }

    private val lock = Any()

    // data thermal
    private val offset = FloatArray(W * H)
    private val bad = BooleanArray(W * H)
    private val smooth = FloatArray(W * H)
    private val tmp = FloatArray(W * H)
    private val fixed = FloatArray(W * H)
    private val sortBuf = FloatArray(W * H)
    private val rawBuf = IntArray(W * H)
    private val flatAcc = FloatArray(W * H)
    private val colSm = FloatArray(W)
    private var smoothInit = false
    private var hasNuc = false
    private var flatLeft = 0
    private var rangeInit = false
    private var rLo = 0f
    private var rHi = 1f

    // kamera biasa (untuk tepi)
    private val visY = IntArray(DW * DH)
    @Volatile private var visReady = false

    // paparan gabungan
    private val dispPix = IntArray(DW * DH)
    private var bmp: Bitmap? = null

    // penjajaran
    private var zoom = 1.0f
    private var ox = 0f
    private var oy = 0f
    private var detail = 1.0f
    private var fusionOn = true

    // titik + kalibrasi
    private val points = ArrayList<FloatArray>() // dalam ruang paparan (DW x DH)
    private var selected = -1
    private val samples = ArrayList<FloatArray>() // [counts, suhu]
    private var calA = 0f
    private var calB = 0f
    private var calValid = false
    @Volatile private var rot = 0

    // UI
    private lateinit var thermalView: ThermalView
    private lateinit var statusText: TextView
    private lateinit var fusionBtn: Button
    private lateinit var alignPanel: LinearLayout
    private val mainHandler = Handler(Looper.getMainLooper())
    private var density = 1f
    private var lastStatus = 0L
    @Volatile private var camState = "belum mula"
    @Volatile private var visState = "-"
    @Volatile private var frameCount = 0
    @Volatile private var lastErr = ""

    // kamera
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var visCamera: CameraDevice? = null
    private var visSession: CameraCaptureSession? = null
    private var visReader: ImageReader? = null

    private val lut = IntArray(256)
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
        statusText = TextView(this).apply { textSize = 11f; text = "Memulakan..." }
        root.addView(statusText)

        thermalView = ThermalView(this)
        root.addView(thermalView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // panel penjajaran
        alignPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fun seek(label: String, maxVal: Int, init: Int, onChange: (Int) -> Unit) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(TextView(this).apply { text = label; textSize = 12f },
                LinearLayout.LayoutParams((72 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            val sb = SeekBar(this)
            sb.max = maxVal
            sb.progress = init
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) onChange(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { saveState() }
            })
            r.addView(sb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            alignPanel.addView(r)
        }
        seek("Zum", 150, ((zoom - 0.5f) * 100f).toInt().coerceIn(0, 150)) { synchronized(lock) { zoom = 0.5f + it / 100f } }
        seek("Geser X", 100, (ox + 50f).toInt().coerceIn(0, 100)) { synchronized(lock) { ox = it - 50f } }
        seek("Geser Y", 100, (oy + 50f).toInt().coerceIn(0, 100)) { synchronized(lock) { oy = it - 50f } }
        seek("Butiran", 300, (detail * 100f).toInt().coerceIn(0, 300)) { synchronized(lock) { detail = it / 100f } }
        root.addView(alignPanel)

        fun row(vararg items: Pair<String, () -> Unit>): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, action) in items) {
                val b = Button(this).apply {
                    text = label
                    textSize = 12f
                    isAllCaps = false
                    setOnClickListener { action() }
                }
                if (label.startsWith("Gabung")) fusionBtn = b
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
        root.addView(row(
            "Gabung: ${if (fusionOn) "ON" else "OFF"}" to {
                synchronized(lock) { fusionOn = !fusionOn }
                fusionBtn.text = "Gabung: ${if (fusionOn) "ON" else "OFF"}"
                saveState()
            },
            "Laras" to {
                alignPanel.visibility = if (alignPanel.visibility == View.GONE) View.VISIBLE else View.GONE
            }
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
                synchronized(lock) { Arrays.fill(flatAcc, 0f); flatLeft = FLAT_N }
                toast("Merakam rujukan...")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun askCalibrate() {
        val sel = synchronized(lock) { if (selected < points.size) selected else -1 }
        if (sel < 0) {
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
                        val c = countsAtDispLocked(points[sel][0], points[sel][1])
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

    // ------------------------------------------------------------------ pemetaan + kalibrasi

    private fun thermalXY(u: Float, v: Float): FloatArray = floatArrayOf(
        ((u - DW / 2f) / (DW / 2f)) * 80f / zoom + 80f + ox,
        ((v - DH / 2f) / (DH / 2f)) * 60f / zoom + 60f + oy
    )

    private fun countsAtLocked(x: Float, y: Float): Float {
        val cx = x.toInt().coerceIn(1, W - 2)
        val cy = y.toInt().coerceIn(1, H - 2)
        var s = 0f
        for (dy in -1..1) for (dx in -1..1) s += smooth[(cy + dy) * W + (cx + dx)]
        return s / 9f
    }

    private fun countsAtDispLocked(u: Float, v: Float): Float {
        val t = thermalXY(u, v)
        return countsAtLocked(t[0], t[1])
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
        if (sxx < 400.0) return
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
        zoom = sp.getFloat("zoom", 1.0f)
        ox = sp.getFloat("ox", 0f)
        oy = sp.getFloat("oy", 0f)
        detail = sp.getFloat("detail", 1.0f)
        fusionOn = sp.getBoolean("fusion", true)
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
        val fb = File(filesDir, "bad.bin")
        if (fb.exists() && fb.length() == (W * H).toLong()) {
            val b = fb.readBytes()
            for (i in bad.indices) bad[i] = b[i].toInt() != 0
        }
    }

    private fun saveState() {
        val sb = StringBuilder()
        var z = 1f
        var x = 0f
        var y = 0f
        var d = 1f
        var fu = true
        synchronized(lock) {
            for (s in samples) sb.append(s[0]).append('|').append(s[1]).append(';')
            z = zoom; x = ox; y = oy; d = detail; fu = fusionOn
        }
        getSharedPreferences("thermal", MODE_PRIVATE).edit()
            .putInt("rot", rot).putString("samples", sb.toString())
            .putFloat("zoom", z).putFloat("ox", x).putFloat("oy", y)
            .putFloat("detail", d).putBoolean("fusion", fu).apply()
    }

    private fun saveNuc() {
        try {
            val bb = ByteBuffer.allocate(W * H * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in offset) bb.putFloat(v)
            File(filesDir, "nuc.bin").writeBytes(bb.array())
            val b = ByteArray(W * H)
            for (i in bad.indices) b[i] = if (bad[i]) 1 else 0
            File(filesDir, "bad.bin").writeBytes(b)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ kamera

    private fun startCamera() {
        if (thread != null) return
        camState = "membuka kamera 2..."
        visState = "-"
        updateStatus()
        thread = HandlerThread("cam").also { it.start() }
        handler = Handler(thread!!.looper)
        val rd = ImageReader.newInstance(W, H, 20 /* YUY2 */, 3)
        reader = rd
        rd.setOnImageAvailableListener({ r -> onImage(r) }, handler)
        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            mgr.openCamera("2", object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    camera = cd; camState = "kamera dibuka"; updateStatus(); createSession(cd, rd)
                }
                override fun onDisconnected(cd: CameraDevice) {
                    cd.close(); camera = null; camState = "kamera terputus"; updateStatus()
                }
                override fun onError(cd: CameraDevice, error: Int) {
                    cd.close(); camera = null; camState = "RALAT kamera kod $error"; updateStatus()
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
                        openVisible()
                    } catch (e: Exception) { camState = "GAGAL streaming: $e"; updateStatus() }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    camState = "konfigurasi sesi GAGAL"; updateStatus()
                }
            }, handler)
        } catch (e: Exception) { camState = "GAGAL cipta sesi: $e"; updateStatus() }
    }

    /** Kamera biasa (ID 0) untuk tepi/emboss. Jika gagal, thermal tetap jalan tanpa gabungan. */
    @Suppress("DEPRECATION")
    private fun openVisible() {
        try {
            val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
            val ch = mgr.getCameraCharacteristics("0")
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(35)
            var w = 640
            var h = 480
            if (sizes != null && sizes.isNotEmpty()) {
                val cands = sizes.filter { it.width * 3 == it.height * 4 && it.width in 640..1280 }
                val pick = cands.minByOrNull { it.width }
                    ?: sizes.minByOrNull { abs(it.width - 640) + abs(it.height - 480) }!!
                w = pick.width
                h = pick.height
            }
            val rd = ImageReader.newInstance(w, h, 35, 3)
            visReader = rd
            rd.setOnImageAvailableListener({ r -> onVisImage(r) }, handler)
            visState = "membuka kamera 0 (${w}x$h)"
            mgr.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    visCamera = cd
                    try {
                        cd.createCaptureSession(listOf(rd.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                visSession = s
                                try {
                                    val req = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    req.addTarget(rd.surface)
                                    s.setRepeatingRequest(req.build(), null, handler)
                                    visState = "kamera 0 jalan"
                                } catch (e: Exception) { visState = "kamera 0 GAGAL: $e" }
                                updateStatus()
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                visState = "kamera 0 konfigurasi GAGAL"; updateStatus()
                            }
                        }, handler)
                    } catch (e: Exception) { visState = "kamera 0 GAGAL: $e"; updateStatus() }
                }
                override fun onDisconnected(cd: CameraDevice) {
                    cd.close(); visCamera = null; visState = "kamera 0 terputus"; updateStatus()
                }
                override fun onError(cd: CameraDevice, error: Int) {
                    cd.close(); visCamera = null; visState = "kamera 0 RALAT $error (tanpa gabungan)"; updateStatus()
                }
            }, handler)
        } catch (e: Exception) {
            visState = "kamera 0 GAGAL: $e"; updateStatus()
        }
    }

    private fun stopCamera() {
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { visSession?.close() } catch (_: Exception) {}
        try { visCamera?.close() } catch (_: Exception) {}
        try { visReader?.close() } catch (_: Exception) {}
        session = null; camera = null; reader = null
        visSession = null; visCamera = null; visReader = null
        visReady = false
        thread?.quitSafely(); thread = null; handler = null
        camState = "dihentikan"
    }

    private fun onVisImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val p = img.planes[0]
            val buf = p.buffer.duplicate()
            val rs = p.rowStride
            val ps = p.pixelStride
            val iw = img.width
            val ih = img.height
            synchronized(lock) {
                for (v in 0 until DH) {
                    val y0 = min(v * ih / DH, ih - 2)
                    for (u in 0 until DW) {
                        val x0 = min(u * iw / DW, iw - 2)
                        var s = 0
                        for (dy in 0..1) for (dx in 0..1) {
                            val pos = (y0 + dy) * rs + (x0 + dx) * ps
                            if (pos < buf.limit()) s += buf.get(pos).toInt() and 0xFF
                        }
                        visY[v * DW + u] = s / 4
                    }
                }
                visReady = true
            }
        } catch (e: Exception) {
            lastErr = "vis: $e"
        } finally {
            img.close()
        }
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
            lut[i] = Color.rgb(
                (a[1] + (b[1] - a[1]) * f).toInt(),
                (a[2] + (b[2] - a[2]) * f).toInt(),
                (a[3] + (b[3] - a[3]) * f).toInt()
            )
        }
    }

    private fun mean8(a: FloatArray, i: Int): Float =
        (a[i - 1] + a[i + 1] + a[i - W] + a[i + W] + a[i - W - 1] + a[i - W + 1] + a[i + W - 1] + a[i + W + 1]) / 8f

    private fun finishFlatLocked() {
        val f = FloatArray(W * H)
        var sum = 0.0
        for (i in f.indices) { f[i] = flatAcc[i] / FLAT_N; sum += f[i] }
        val mean = (sum / f.size).toFloat()
        for (i in offset.indices) offset[i] = f[i] - mean
        // cari piksel rosak/tersangkut: sisihan besar berbanding jiran
        val res = FloatArray(W * H)
        val absRes = ArrayList<Float>()
        for (y in 1 until H - 1) for (x in 1 until W - 1) {
            val i = y * W + x
            res[i] = f[i] - mean8(f, i)
            absRes.add(abs(res[i]))
        }
        absRes.sort()
        val sigma = 1.4826f * absRes[absRes.size / 2]
        val thr = max(4f * sigma, 400f)
        var count = 0
        for (i in bad.indices) bad[i] = false
        for (y in 1 until H - 1) for (x in 1 until W - 1) {
            val i = y * W + x
            if (abs(res[i]) > thr) { bad[i] = true; count++ }
        }
        hasNuc = true
        smoothInit = false
        saveNuc()
        toast("NUC siap. $count piksel rosak dikesan dan ditampal.")
    }

    private fun destripe(a: FloatArray) {
        val colHP = FloatArray(W)
        val pre = FloatArray(W + 1)
        for (y in 0 until H) {
            val rowBase = y * W
            for (x in 0 until W) pre[x + 1] = pre[x] + a[rowBase + x]
            for (x in 0 until W) {
                val lo = max(0, x - 4)
                val hi = min(W - 1, x + 4)
                val avg = (pre[hi + 1] - pre[lo]) / (hi - lo + 1)
                colHP[x] += a[rowBase + x] - avg
            }
        }
        for (x in 0 until W) {
            val v = (colHP[x] / H).coerceIn(-120f, 120f)
            colSm[x] += 0.1f * (v - colSm[x])
        }
        for (y in 0 until H) for (x in 0 until W) a[y * W + x] -= colSm[x]
    }

    private fun processFrame(raw: IntArray) {
        synchronized(lock) {
            // baris pertama dan dua baris terakhir ialah artifak sensor
            System.arraycopy(raw, W, raw, 0, W)
            System.arraycopy(raw, (H - 3) * W, raw, (H - 2) * W, W)
            System.arraycopy(raw, (H - 3) * W, raw, (H - 1) * W, W)
            if (flatLeft > 0) {
                for (i in raw.indices) flatAcc[i] += raw[i].toFloat()
                flatLeft--
                if (flatLeft == 0) finishFlatLocked()
            }
            for (i in raw.indices) tmp[i] = raw[i] - offset[i]
            System.arraycopy(tmp, 0, fixed, 0, tmp.size)
            // piksel rosak: ganti dengan purata jiran (senarai tetap + pencilan besar)
            for (y in 1 until H - 1) for (x in 1 until W - 1) {
                val i = y * W + x
                val m = mean8(tmp, i)
                if (bad[i] || abs(tmp[i] - m) > 900f) fixed[i] = m
            }
            destripe(fixed)
            if (!smoothInit) {
                System.arraycopy(fixed, 0, smooth, 0, fixed.size)
                smoothInit = true
            } else {
                for (i in smooth.indices) smooth[i] += 0.35f * (fixed[i] - smooth[i])
            }
            System.arraycopy(smooth, 0, sortBuf, 0, smooth.size)
            Arrays.sort(sortBuf)
            var lo = sortBuf[(sortBuf.size * 0.005f).toInt()]
            var hi = sortBuf[(sortBuf.size * 0.999f).toInt()]
            if (hi - lo < 80f) { val c = (hi + lo) / 2f; lo = c - 40f; hi = c + 40f }
            if (!rangeInit) { rLo = lo; rHi = hi; rangeInit = true }
            else { rLo += 0.2f * (lo - rLo); rHi += 0.2f * (hi - rHi) }
            composeLocked(max(1f, rHi - rLo))
        }
    }

    /** Bina bitmap paparan DWxDH: warna thermal (diinterpolasi) + emboss dari kamera biasa. */
    private fun composeLocked(span: Float) {
        val fusion = fusionOn && visReady
        val zx = 1f / zoom
        for (v in 0 until DH) {
            val ty = (((v + 0.5f - DH / 2f) / (DH / 2f)) * 60f * zx + 60f + oy).coerceIn(0f, H - 1.001f)
            val y0 = ty.toInt()
            val fy = ty - y0
            for (u in 0 until DW) {
                val tx = (((u + 0.5f - DW / 2f) / (DW / 2f)) * 80f * zx + 80f + ox).coerceIn(0f, W - 1.001f)
                val x0 = tx.toInt()
                val fx = tx - x0
                val i = y0 * W + x0
                val c = smooth[i] * (1 - fx) * (1 - fy) + smooth[i + 1] * fx * (1 - fy) +
                    smooth[i + W] * (1 - fx) * fy + smooth[i + W + 1] * fx * fy
                val idx = (((c - rLo) / span) * 255f).toInt().coerceIn(0, 255)
                var col = lut[idx]
                if (fusion && u >= 1 && u < DW - 1 && v >= 1 && v < DH - 1) {
                    val d = ((visY[(v + 1) * DW + u + 1] - visY[(v - 1) * DW + u - 1]) * detail / 160f)
                        .coerceIn(-0.8f, 0.8f)
                    val f = 1f + d
                    val r = (Color.red(col) * f + d * 40f).toInt().coerceIn(0, 255)
                    val g = (Color.green(col) * f + d * 40f).toInt().coerceIn(0, 255)
                    val b = (Color.blue(col) * f + d * 40f).toInt().coerceIn(0, 255)
                    col = Color.rgb(r, g, b)
                }
                dispPix[v * DW + u] = col
            }
        }
        bmp = Bitmap.createBitmap(dispPix, DW, DH, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------------ lukisan

    private fun fitMatrix(w: Int, availH: Int): Matrix {
        val r = rot
        val m = Matrix()
        m.postTranslate(-DW / 2f, -DH / 2f)
        m.postRotate(r * 90f)
        val rw = if (r % 2 == 0) DW else DH
        val rh = if (r % 2 == 0) DH else DW
        val s = min(w.toFloat() / rw, availH.toFloat() / rh)
        m.postScale(s, s)
        m.postTranslate(w / 2f, availH / 2f)
        return m
    }

    private fun statsLineLocked(): String {
        var mn = Float.MAX_VALUE
        var mxv = -Float.MAX_VALUE
        for (y in 1 until H - 1) for (x in 1 until W - 1) {
            val v = smooth[y * W + x]
            if (v < mn) mn = v
            if (v > mxv) mxv = v
        }
        return if (calValid) {
            val a = tempFromCounts(mn)
            val b = tempFromCounts(mxv)
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
                val c = countsAtDispLocked(p[0], p[1])
                labels.add(if (calValid) String.format(Locale.US, "P%d %.1f C", i + 1, tempFromCounts(c))
                else String.format(Locale.US, "P%d %d", i + 1, c.toInt()))
                pts.add(floatArrayOf(p[0], p[1]))
            }
            stats = statsLineLocked()
        }
        for ((i, p) in pts.withIndex()) {
            m.mapPoints(p)
            val x = p[0]
            val y = p[1]
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
        val ix = pt[0].coerceIn(0f, DW - 1f)
        val iy = pt[1].coerceIn(0f, DH - 1f)
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
            sb.append('\n').append("Biasa: ").append(visState)
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
