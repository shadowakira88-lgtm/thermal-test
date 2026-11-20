package com.elena.thermaltest

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Ujian: buka kamera thermal dalaman (Camera2 ID 2 pada Ulefone Armor Pad 4 Ultra),
 * tunjuk frame mentah, dan simpan frame + maklumat penuh ke ZIP dalam Download/ThermalTest.
 */
class ProbeActivity : Activity() {
    // v0.2: lalai kepada YUY2 160x120 (format yang ThermVue guna untuk modul thermal)

    private data class Option(val format: Int, val w: Int, val h: Int) {
        override fun toString() = "${formatName(format)}  ${w}x$h"
    }

    private lateinit var idEdit: EditText
    private lateinit var spinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var imageView: ImageView
    private lateinit var logText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var options: List<Option> = emptyList()
    private var currentOption: Option? = null

    @Volatile private var lastResult: TotalCaptureResult? = null
    @Volatile private var framesToSave = 0
    private val savedFrames = ArrayList<Pair<String, ByteArray>>()
    private val logBuf = StringBuilder()
    private var frameCounter = 0L
    private var lastUiUpdate = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        } else {
            loadOptions()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) loadOptions()
        else log("Kebenaran kamera ditolak.")
    }

    override fun onPause() {
        super.onPause()
        stopCamera()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(TextView(this).apply { text = "Camera ID: " })
        idEdit = EditText(this).apply { setText("2"); minEms = 3 }
        row1.addView(idEdit)
        val btnLoad = Button(this).apply { text = "Muat format"; setOnClickListener { loadOptions() } }
        row1.addView(btnLoad)
        root.addView(row1)

        spinner = Spinner(this)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentOption = options.getOrNull(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(spinner)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(Button(this).apply { text = "Mula"; setOnClickListener { startCamera() } })
        row2.addView(Button(this).apply { text = "Henti"; setOnClickListener { stopCamera() } })
        row2.addView(Button(this).apply { text = "Simpan ZIP"; setOnClickListener { requestSave() } })
        root.addView(row2)

        statusText = TextView(this).apply { text = "Sedia." }
        root.addView(statusText)

        imageView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF222222.toInt())
        }
        root.addView(imageView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (320 * resources.displayMetrics.density).toInt()))

        logText = TextView(this).apply { textSize = 11f }
        val scroll = ScrollView(this)
        scroll.addView(logText)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun log(s: String) {
        val line = "[" + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "] " + s
        synchronized(logBuf) { logBuf.append(line).append('\n') }
        mainHandler.post { logText.text = synchronized(logBuf) { logBuf.toString() } }
    }

    private fun setStatus(s: String) = mainHandler.post { statusText.text = s }

    // ------------------------------------------------------------------ options

    private fun loadOptions() {
        try {
            val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
            log("Senarai camera ID: " + mgr.cameraIdList.joinToString())
            val id = idEdit.text.toString().trim()
            val ch = mgr.getCameraCharacteristics(id)
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val list = ArrayList<Option>()
            if (map != null) {
                for (f in map.outputFormats) {
                    if (f == 34 /* PRIVATE: tak boleh dibaca */) continue
                    val sizes = map.getOutputSizes(f) ?: continue
                    for (s in sizes) list.add(Option(f, s.width, s.height))
                }
            }
            options = list.sortedWith(compareBy({ if (it.format == 20) 0 else if (it.format == 35) 1 else 2 }, { it.format }, { -it.w }))
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
            currentOption = options.firstOrNull()
            log("Camera $id: ${options.size} pilihan format/saiz dijumpai.")
        } catch (e: Exception) {
            log("loadOptions gagal: $e")
        }
    }

    // ------------------------------------------------------------------ camera

    private fun startCamera() {
        stopCamera()
        val opt = currentOption
        if (opt == null) { log("Tiada format dipilih."); return }
        val id = idEdit.text.toString().trim()
        thread = HandlerThread("cam").also { it.start() }
        handler = Handler(thread!!.looper)
        frameCounter = 0

        val rd = ImageReader.newInstance(opt.w, opt.h, opt.format, 4)
        reader = rd
        rd.setOnImageAvailableListener({ r -> onImage(r, opt) }, handler)

        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            log("Buka camera $id dengan ${opt}")
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    camera = cd
                    createSession(cd, rd)
                }
                override fun onDisconnected(cd: CameraDevice) { log("Camera disconnected"); cd.close() }
                override fun onError(cd: CameraDevice, error: Int) { log("Camera error code=$error"); cd.close() }
            }, handler)
        } catch (e: SecurityException) {
            log("SecurityException: $e")
        } catch (e: CameraAccessException) {
            log("CameraAccessException: $e")
        } catch (e: Exception) {
            log("openCamera gagal: $e")
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
                        s.setRepeatingRequest(req.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                sess: CameraCaptureSession, rq: CaptureRequest, result: TotalCaptureResult
                            ) { lastResult = result }
                        }, handler)
                        log("Streaming bermula.")
                    } catch (e: Exception) {
                        log("setRepeatingRequest gagal: $e")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { log("Konfigurasi session GAGAL") }
            }, handler)
        } catch (e: Exception) {
            log("createCaptureSession gagal: $e")
        }
    }

    private fun stopCamera() {
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; camera = null; reader = null
        thread?.quitSafely(); thread = null; handler = null
    }

    // ------------------------------------------------------------------ frames

    private fun onImage(r: ImageReader, opt: Option) {
        val img: Image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            frameCounter++
            val bmp = toBitmap(img, opt)

            if (framesToSave > 0) {
                val idx = 5 - framesToSave
                val planes = img.planes
                for (pi in planes.indices) {
                    val b = planes[pi].buffer.duplicate()
                    b.rewind()
                    val bytes = ByteArray(b.remaining())
                    b.get(bytes)
                    val info = "rowStride=${planes[pi].rowStride} pixelStride=${planes[pi].pixelStride}"
                    log("frame$idx plane$pi: ${bytes.size} bytes, $info")
                    savedFrames.add("frame${idx}_plane$pi.bin" to bytes)
                }
                framesToSave--
                if (framesToSave == 0) writeZip()
            }

            val now = System.currentTimeMillis()
            if (bmp != null && now - lastUiUpdate > 100) {
                lastUiUpdate = now
                mainHandler.post { imageView.setImageBitmap(bmp) }
            }
        } catch (e: Exception) {
            log("onImage ralat: $e")
        } finally {
            img.close()
        }
    }

    /** Tukar frame kepada bitmap kelabu (auto-kontras) supaya nampak apa data mentah tu. */
    private fun toBitmap(img: Image, opt: Option): Bitmap? {
        val w = img.width
        val h = img.height
        val planes = img.planes
        if (planes.isEmpty()) return null
        val p0 = planes[0]
        val buf = p0.buffer.duplicate()
        buf.rewind()
        val rs = p0.rowStride
        val ps = p0.pixelStride

        if (opt.format == 256) { // JPEG
            val b = ByteArray(buf.remaining()); buf.get(b)
            return BitmapFactory.decodeByteArray(b, 0, b.size)
        }

        val is16 = opt.format == 540422489 /* Y16 */ || opt.format == 32 /* RAW_SENSOR */ ||
            opt.format == 20 /* YUY2: ThermVue guna ini, data suhu 16-bit */
        val vals = IntArray(w * h)
        var mn = Int.MAX_VALUE
        var mx = Int.MIN_VALUE
        if (is16) {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until h) for (x in 0 until w) {
                val pos = y * rs + x * 2
                if (pos + 1 >= buf.limit()) continue
                val v = buf.getShort(pos).toInt() and 0xFFFF
                vals[y * w + x] = v
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
        } else {
            for (y in 0 until h) for (x in 0 until w) {
                val pos = y * rs + x * ps
                if (pos >= buf.limit()) continue
                val v = buf.get(pos).toInt() and 0xFF
                vals[y * w + x] = v
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
        }
        val range = (mx - mn).coerceAtLeast(1)
        val px = IntArray(w * h)
        for (i in px.indices) {
            val g = ((vals[i] - mn) * 255L / range).toInt()
            px[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        setStatus("Frame #$frameCounter  ${opt}  min=$mn max=$mx  planes=${planes.size}")
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------------ simpan

    private fun requestSave() {
        if (camera != null && reader != null) {
            savedFrames.clear()
            framesToSave = 5
            log("Akan simpan 5 frame seterusnya...")
        } else {
            log("Kamera tak berjalan, simpan maklumat sahaja.")
            writeZip()
        }
    }

    private fun writeZip() {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "thermal_test_$ts.zip")
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ThermalTest")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri == null) { log("Gagal cipta fail ZIP"); return }
            contentResolver.openOutputStream(uri)!!.use { os ->
                ZipOutputStream(os).use { z ->
                    z.putNextEntry(ZipEntry("info.txt"))
                    z.write(buildInfo().toByteArray())
                    z.closeEntry()
                    z.putNextEntry(ZipEntry("log.txt"))
                    z.write(synchronized(logBuf) { logBuf.toString() }.toByteArray())
                    z.closeEntry()
                    for ((name, bytes) in savedFrames) {
                        z.putNextEntry(ZipEntry(name))
                        z.write(bytes)
                        z.closeEntry()
                    }
                }
            }
            log("ZIP disimpan: Download/ThermalTest/thermal_test_$ts.zip (${savedFrames.size} fail frame)")
        } catch (e: Exception) {
            log("writeZip gagal: $e")
        }
    }

    // ------------------------------------------------------------------ info

    private fun v(x: Any?): String = when (x) {
        null -> "null"
        is IntArray -> x.contentToString()
        is LongArray -> x.contentToString()
        is FloatArray -> x.contentToString()
        is DoubleArray -> x.contentToString()
        is ByteArray -> x.contentToString()
        is BooleanArray -> x.contentToString()
        is Array<*> -> x.contentDeepToString()
        else -> x.toString()
    }

    private fun buildInfo(): String {
        val sb = StringBuilder()
        sb.append("Model: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("Fingerprint: ${Build.FINGERPRINT}\n\n")
        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            sb.append("cameraIdList: ${mgr.cameraIdList.joinToString()}\n")
            for (id in mgr.cameraIdList) {
                val ch = mgr.getCameraCharacteristics(id)
                sb.append("\n===== CAMERA $id =====\n")
                sb.append("facing=${ch.get(CameraCharacteristics.LENS_FACING)} ")
                sb.append("level=${ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)}\n")
                sb.append("pixelArray=${ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)} ")
                sb.append("activeArray=${ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)} ")
                sb.append("physSize=${ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)}\n")
                sb.append("focalLengths=${v(ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS))} ")
                sb.append("physicalIds=${ch.physicalCameraIds}\n")
                sb.append("capabilities=${v(ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES))}\n")
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (map != null) {
                    for (f in map.outputFormats) {
                        val sizes = map.getOutputSizes(f)?.joinToString { "${it.width}x${it.height}" } ?: "-"
                        sb.append("  format ${formatName(f)} ($f): ${if (sizes.length > 300) sizes.take(300) + "..." else sizes}\n")
                    }
                }
                sb.append("characteristicKeys:\n")
                for (k in ch.keys) sb.append("  ${k.name} = ${v(ch.get(k)).take(200)}\n")
                sb.append("availableRequestKeys:\n")
                for (k in ch.availableCaptureRequestKeys) sb.append("  ${k.name}\n")
                sb.append("availableResultKeys:\n")
                for (k in ch.availableCaptureResultKeys) sb.append("  ${k.name}\n")
            }
        } catch (e: Exception) {
            sb.append("\nralat info: $e\n")
        }
        val res = lastResult
        if (res != null) {
            sb.append("\n===== LAST CAPTURE RESULT =====\n")
            for (k: CaptureResult.Key<*> in res.keys) {
                sb.append("  ${k.name} = ${v(res.get(k)).take(300)}\n")
            }
        }
        sb.append(probeDevices())
        return sb.toString()
    }


    private fun sh(cmd: String): String = try {
        val pr = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = pr.inputStream.bufferedReader().readText() + pr.errorStream.bufferedReader().readText()
        pr.waitFor(); out.take(4000)
    } catch (e: Exception) { "ralat: $e" }

    private fun probeDevices(): String {
        val sb = StringBuilder("\n===== PROBE PERANTI / SISTEM =====\n")
        sb.append(sh("id; ls -lZ /dev/i2c* /dev/video* /dev/shutter /dev/v4l-subdev* 2>&1 | head -40"))
        sb.append("\n-- getprop (eco/cam/infi/thermal) --\n")
        sb.append(sh("getprop | grep -i -E 'eco|infi|thermal|cam' | head -40"))
        val paths = listOf("/dev/i2c-2", "/dev/i2c-6", "/dev/i2c-8", "/dev/i2c-9", "/dev/video0", "/dev/shutter")
        sb.append("\n-- akses fail --\n")
        for (p in paths) {
            val f = java.io.File(p)
            sb.append("$p exists=${f.exists()} canRead=${f.canRead()} canWrite=${f.canWrite()}\n")
        }
        val eeproms = listOf(
            "/sys/devices/platform/soc/11db3000.i2c/i2c-8/8-0050/eeprom",
            "/sys/devices/platform/11009000.i2c/i2c-2/2-0050/eeprom",
            "/sys/devices/platform/soc/11eb0000.i2c/i2c-2/2-0050/eeprom",
            "/sys/devices/platform/soc/11ed1000.i2c/i2c-2/2-0050/eeprom",
            "/sys/devices/platform/11d03000.i2c9/i2c-9/9-0050/eeprom")
        sb.append("\n-- eeprom --\n")
        for (p in eeproms) {
            val f = java.io.File(p)
            sb.append("$p exists=${f.exists()} canRead=${f.canRead()}")
            try {
                java.io.FileInputStream(f).use { ins ->
                    val b = ByteArray(64); val n = ins.read(b)
                    sb.append(" first$n=" + b.take(maxOf(n, 0)).joinToString("") { "%02x".format(it) })
                }
            } catch (e: Exception) { sb.append(" baca-gagal=${e.javaClass.simpleName}") }
            sb.append("\n")
        }
        return sb.toString()
    }

    companion object {
        fun formatName(f: Int): String = when (f) {
            35 -> "YUV_420_888"
            32 -> "RAW_SENSOR"
            37 -> "RAW10"
            38 -> "RAW12"
            256 -> "JPEG"
            17 -> "NV21"
            20 -> "YUY2"
            540422489 -> "Y16"
            0x20203859 -> "Y8"
            842094169 -> "YV12"
            else -> "FMT_$f"
        }
    }
}
