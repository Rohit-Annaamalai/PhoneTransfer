package com.example.phonetransfer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvSummary: TextView
    private lateinit var btnStart: Button

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        tvUrl = findViewById(R.id.tvUrl)
        tvSummary = findViewById(R.id.tvSummary)
        btnStart = findViewById(R.id.btnStart)

        findViewById<Button>(R.id.btnHotspot).setOnClickListener { openHotspotSettings() }
        btnStart.setOnClickListener { if (Shared.running) stopSharing() else startSharing() }
        findViewById<Button>(R.id.btnPick).setOnClickListener { pickFiles() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            Shared.files.clear()
            Shared.received.clear()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ------------------------------------------------------------ actions

    private fun startSharing() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, TransferService::class.java))
    }

    private fun stopSharing() {
        startService(Intent(this, TransferService::class.java).setAction(TransferService.ACTION_STOP))
    }

    private fun openHotspotSettings() {
        val attempts = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (i in attempts) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, "Open Settings > Hotspot & tethering", Toast.LENGTH_LONG).show()
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, 2)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2 || resultCode != RESULT_OK || data == null) return
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) addFile(clip.getItemAt(i).uri)
        } else {
            data.data?.let { addFile(it) }
        }
        refresh()
    }

    private fun addFile(uri: Uri) {
        var name = "file"
        var size = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {
        }
        Shared.addFile(name, size, uri)
    }

    // ------------------------------------------------------------ screen

    private fun refresh() {
        val addrs = findAddresses()
        val err = Shared.error
        if (Shared.running) {
            btnStart.text = "Stop sharing"
            tvStatus.text = "Sharing is ON"
            tvUrl.text = if (addrs.isEmpty()) "No network found.\nTurn on Hotspot (Step 1) first."
            else addrs.joinToString("\n") { "http://$it:${Shared.PORT}" }
        } else {
            btnStart.text = "Start sharing"
            tvStatus.text = err ?: "Sharing is OFF"
            tvUrl.text = "(start sharing to see the address)"
        }
        tvSummary.text = "Files ready for laptop to download: ${Shared.files.size}\n" +
            "Files received from laptop: ${Shared.received.size}"
    }

    /** Best guess at this phone's address on the hotspot / Wi-Fi, hotspot first. */
    private fun findAddresses(): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                val n = ni.name.lowercase()
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("v4-") ||
                    n.startsWith("dummy") || n.startsWith("tun")
                ) continue
                val score = when {
                    n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n == "wlan1" -> 0
                    n.startsWith("wlan") -> 1
                    else -> 2
                }
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) found.add(score to a.hostAddress!!)
                }
            }
        } catch (_: Exception) {
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }
}
