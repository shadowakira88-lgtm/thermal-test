package com.elena.thermaltest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Skrin utama Thermal Snap: kebenaran, mula butang terapung, buka ThermVue. */
class SnapActivity : Activity() {

    companion object {
        const val REQ_CAPTURE = 10
        const val THERMVUE = "com.infisense.eco"
        const val DEF_TOP = 5.9f
        const val DEF_BOT = 82.4f
    }

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            textSize = 22f
            text = "Thermal Snap"
        })
        root.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, pad / 2, 0, pad)
            text = "Butang SNAP terapung di atas ThermVue. Tekan SNAP untuk simpan gambar haba " +
                "(dengan semua nilai suhu) ke Pictures/ThermalElena.\n\n" +
                "Ketik SNAP = simpan\nKetik gambar kecil = buka gambar terakhir\nSeret butang = alih kedudukan\nTekan lama SNAP = berhenti\n\n" +
                "Tip: dalam ThermVue tekan kotak ke-5 di bawah supaya semua nilai titik keluar sebelum SNAP."
        })
        status = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, pad) }
        root.addView(status)

        fun btn(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 16f
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        btn("1. Benarkan papar di atas app lain") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("2. Mula SNAP dan buka ThermVue") { start() }
        btn("Buka ThermVue sahaja") { openThermVue() }
        btn("Henti SNAP") {
            stopService(Intent(this, SnapService::class.java))
            refresh()
        }

        // tetapan potongan
        val sp = getSharedPreferences("snap", MODE_PRIVATE)
        root.addView(TextView(this).apply { text = "\nTetapan gambar"; textSize = 16f })
        root.addView(CheckBox(this).apply {
            text = "Potong (buang bar alat ThermVue atas dan bawah)"
            isChecked = sp.getBoolean("crop", true)
            setOnCheckedChangeListener { _, c -> sp.edit().putBoolean("crop", c).apply() }
        })
        root.addView(CheckBox(this).apply {
            text = "Tambah tarikh dan masa di bawah gambar"
            isChecked = sp.getBoolean("stamp", false)
            setOnCheckedChangeListener { _, c -> sp.edit().putBoolean("stamp", c).apply() }
        })
        fun slider(label: String, key: String, def: Float, maxPct: Int) {
            val t = TextView(this).apply { textSize = 13f }
            fun show(v: Float) { t.text = String.format(java.util.Locale.US, "%s: %.1f%% dari atas skrin", label, v) }
            val cur = sp.getFloat(key, def)
            show(cur)
            root.addView(t)
            root.addView(SeekBar(this).apply {
                max = maxPct * 10
                progress = (cur * 10).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) { val v = p / 10f; show(v); sp.edit().putFloat(key, v).apply() }
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
        }
        slider("Mula potong (atas)", "cropTop", DEF_TOP, 50)
        slider("Akhir potong (bawah)", "cropBot", DEF_BOT, 100)
        btn("Set semula potongan asal") {
            sp.edit().putFloat("cropTop", DEF_TOP).putFloat("cropBot", DEF_BOT).apply()
            recreate()
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val ov = Settings.canDrawOverlays(this)
        val installed = packageManager.getLaunchIntentForPackage(THERMVUE) != null
        status.text = "Kebenaran papar atas app: " + (if (ov) "OK" else "BELUM (tekan butang 1)") +
            "\nThermVue: " + (if (installed) "dijumpai" else "tidak dijumpai") +
            "\nSNAP: " + (if (SnapService.running) "sedang jalan" else "tidak jalan")
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Tekan butang 1 dan benarkan dahulu.", Toast.LENGTH_LONG).show()
            return
        }
        if (SnapService.running) { openThermVue(); return }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        Toast.makeText(this, "Pilih 'Seluruh skrin' kemudian Mula.", Toast.LENGTH_LONG).show()
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Kebenaran rakam skrin ditolak.", Toast.LENGTH_LONG).show()
            return
        }
        val i = Intent(this, SnapService::class.java)
            .putExtra(SnapService.EXTRA_CODE, resultCode)
            .putExtra(SnapService.EXTRA_DATA, data)
        startForegroundService(i)
        window.decorView.postDelayed({ openThermVue() }, 600)
    }

    private fun openThermVue() {
        val li = packageManager.getLaunchIntentForPackage(THERMVUE)
        if (li == null) {
            Toast.makeText(this, "ThermVue tidak dijumpai.", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(li)
    }
}
