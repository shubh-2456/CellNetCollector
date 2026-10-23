package com.cellnet.collector

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class Verdict(val label: String, val color: Int, val note: String)

class MainActivity : Activity() {

    private val bg = Color.parseColor("#0A1020")
    private val cardBg = Color.parseColor("#111A2E")
    private val white = Color.parseColor("#E6EDF7")
    private val muted = Color.parseColor("#8FA3C4")
    private val cGood = Color.parseColor("#22C55E")
    private val cMod = Color.parseColor("#F59E0B")
    private val cPoor = Color.parseColor("#EF4444")
    private val cNa = Color.parseColor("#94A3B8")

    private lateinit var store: Store
    private lateinit var reader: SignalReader
    private lateinit var db: LocalDb
    private lateinit var locationManager: LocationManager

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var localSessionId = -1L
    @Volatile private var serverSessionId = -1
    @Volatile private var activeName = ""
    @Volatile private var running = false
    @Volatile private var opening = false
    @Volatile private var syncing = false
    @Volatile private var lastMessage = ""
    @Volatile private var laptopOnline: Boolean? = null
    private val collected = AtomicInteger(0)
    private val accepted = AtomicInteger(0)
    private val duplicates = AtomicInteger(0)
    private val rejected = AtomicInteger(0)
    private val recent = ArrayList<Int?>()
    private var lastReading: CellReading? = null
    private var lastReadingTime = 0L
    private var lastLocation: Location? = null
    private var previewOn = false
    private var startAfterPerms = false
    private var locationStarted = false

    private lateinit var chart: SignalChart
    private lateinit var tvPair: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var tvVerdictNote: TextView
    private lateinit var tvValues: TextView
    private lateinit var tvCounts: TextView
    private lateinit var tvSaved: TextView
    private lateinit var tvMsg: TextView
    private lateinit var btnStart: Button
    private lateinit var btnUnpair: Button
    private lateinit var etPaste: EditText

    private val requestCode = 42
    private val perms = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    )

    // ------------------------------------------------------------ UI

    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        val d = GradientDrawable()
        d.setColor(cardBg)
        d.cornerRadius = dp(14).toFloat()
        c.background = d
        return c
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun LinearLayout.addSpaced(v: android.view.View, top: Int = 10) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(top)
        addView(v, lp)
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(28), dp(16), dp(28))
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        scroll.addView(root)

        root.addView(label("CellNet Collector", 24f, white, true))
        root.addView(label("Real signal and GPS readings from this phone", 13f, muted, false))

        val signal = card()
        tvVerdict = label("No reading yet", 30f, cNa, true)
        tvVerdictNote = label("", 14f, white, false)
        signal.addView(tvVerdict)
        signal.addView(tvVerdictNote)
        chart = SignalChart(this)
        val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130))
        clp.topMargin = dp(12)
        signal.addView(chart, clp)
        signal.addSpaced(label("RSRP of the last readings (dBm). Green = good, amber = moderate, red = poor.", 11f, muted, false), 4)
        tvValues = label("", 14f, white, false)
        tvValues.typeface = Typeface.MONOSPACE
        signal.addSpaced(tvValues, 12)
        root.addSpaced(signal, 16)

        val session = card()
        tvSession = label("", 14f, white, false)
        session.addView(tvSession)
        btnStart = button("Start session") { onStartStop() }
        session.addSpaced(btnStart)
        tvCounts = label("", 12f, muted, false)
        session.addSpaced(tvCounts)
        root.addSpaced(session, 12)

        val saved = card()
        saved.addView(label("Saved on this phone", 16f, white, true))
        tvSaved = label("", 13f, muted, false)
        saved.addSpaced(tvSaved, 4)
        saved.addSpaced(button("View saved sessions") { showSessions() })
        saved.addSpaced(button("Download all as CSV") { exportCsv(null) })
        saved.addSpaced(button("Delete all saved data") {
            confirm("Delete everything?", "All sessions and readings saved on this phone will be removed.") {
                if (running) say("Stop the session first.") else {
                    db.deleteAll()
                    say("All saved data was deleted.")
                }
            }
        })
        root.addSpaced(saved, 12)

        val connect = card()
        connect.addView(label("Laptop connection (optional)", 16f, white, true))
        tvPair = label("", 14f, white, false)
        connect.addSpaced(tvPair, 4)
        connect.addSpaced(button("Scan QR code from the dashboard") { scan() })
        etPaste = EditText(this)
        etPaste.hint = "Or paste the pairing text here"
        etPaste.setTextColor(white)
        etPaste.setHintTextColor(muted)
        etPaste.textSize = 12f
        connect.addSpaced(etPaste)
        connect.addSpaced(button("Pair with pasted text") { pairFromText(etPaste.text.toString()) })
        btnUnpair = button("Unpair this phone") { unpair() }
        connect.addSpaced(btnUnpair)
        root.addSpaced(connect, 12)

        tvMsg = label("", 13f, Color.parseColor("#7DD3FC"), false)
        root.addSpaced(tvMsg, 12)
        root.addSpaced(label(
            "Keep this app open with the screen on while measuring. For the full " +
                "analysis, pair with the laptop and open Signal Check on the dashboard.", 12f, muted, false), 10)

        setContentView(scroll)
    }

    private fun f(v: Number?, unit: String): String = if (v == null) "Unavailable" else "$v $unit"

    private fun fmtTime(ms: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))

    private fun refreshUi() {
        val paired = store.isPaired()
        val link = when (laptopOnline) {
            true -> "online"
            false -> "not reachable"
            null -> "checking..."
        }
        tvPair.text = if (paired) "Paired with ${store.host}:${store.port}\nLaptop: $link\nReadings are also sent to the laptop."
        else "Not paired. The app works on its own and saves everything on this phone. Pair only if you also want the data on the laptop."
        tvSession.text = when {
            running && serverSessionId > 0 -> "Session running. Saving on this phone and sending to the laptop."
            running && paired -> "Session running. Saving on this phone. The laptop is not connected yet, it will retry."
            running -> "Session running. Saving on this phone only."
            previewOn -> "Live preview: reading the signal on this phone. Nothing is saved or sent."
            else -> "No session running"
        }
        btnStart.text = if (running) "Stop session" else "Start session"
        btnUnpair.isEnabled = paired
        var counts = "Readings this session ${collected.get()}"
        if (paired) {
            counts += "   Sent ${accepted.get()}   Duplicates ${duplicates.get()}   Rejected ${rejected.get()}"
            if (localSessionId > 0) counts += "   Waiting ${db.pendingCount(localSessionId)}"
        }
        tvCounts.text = counts
        val t = db.totals()
        tvSaved.text = "${t.first} sessions, ${t.second} readings stored on this phone."
        tvMsg.text = lastMessage

        val r = lastReading
        if (r == null) {
            tvVerdict.text = "No reading yet"
            tvVerdict.setTextColor(cNa)
            tvVerdictNote.text = if (!hasPerms()) "Allow location and phone permission to see the live signal."
            else "Waiting for the first reading..."
            tvValues.text = ""
            return
        }
        val v = assess(r)
        tvVerdict.text = v.label
        tvVerdict.setTextColor(v.color)
        val stale = (running || previewOn) && System.currentTimeMillis() - lastReadingTime > 15000
        tvVerdictNote.text = (if (stale) "STALE: no new reading for 15 s.\n" else "") + v.note

        val loc = lastLocation
        val gps = if (loc == null) "No fix yet" else
            String.format(Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude) +
                (if (loc.hasAccuracy()) " (+/- ${loc.accuracy.toInt()} m)" else "")
        tvValues.text =
            "Operator : ${r.operator ?: "Unavailable"}\n" +
            "Network  : ${r.networkType ?: "Unavailable"}\n" +
            "RSRP     : ${f(r.rsrp, "dBm")}\n" +
            "RSRQ     : ${f(r.rsrq, "dB")}\n" +
            "SINR     : ${f(r.sinr, "dB")}  (raw: ${r.rawRssnr ?: "n/a"})\n" +
            "RSSI     : ${f(r.rssi, "dBm")}\n" +
            "Level    : ${r.level ?: "Unavailable"} of 4\n" +
            "Cell ID  : ${r.cellId ?: "Unavailable"}\n" +
            "State    : ${r.state ?: "Unavailable"}\n" +
            "GPS      : $gps"
    }

    private fun pushRecent(v: Int?) {
        recent.add(v)
        while (recent.size > 60) recent.removeAt(0)
        chart.setValues(recent)
    }

    // ------------------------------------------------------------ verdict (same rules as the dashboard)

    private fun assess(r: CellReading): Verdict {
        if (r.state == "airplane_mode")
            return Verdict("AIRPLANE MODE", cNa, "Turn airplane mode off to measure the cellular signal.")
        if (r.state == "no_service")
            return Verdict("NO SERVICE", cPoor,
                "The phone reports no cellular service here. Move outdoors or next to a window, " +
                    "or use Wi-Fi calling if available.")

        val cov = Rules.rateRsrp(r.rsrp)
        val qual = if (r.sinr != null) Rules.rateSinr(r.sinr) else Rules.rateRsrq(r.rsrq)
        val overall = maxOf(cov, qual)

        if (overall < 0) {
            val older = r.networkType == "GSM" || r.networkType == "UMTS"
            return Verdict("UNAVAILABLE", cNa,
                if (older) "The phone is on an older network (${r.networkType}), so 4G signal values are not available."
                else "This phone did not report signal values here. Check the Location and Phone permissions.")
        }

        val label = Rules.label(overall)
        val color = listOf(cGood, cMod, cPoor)[overall]
        val note = when {
            cov == 2 && qual == 2 -> "Weak and noisy signal. Move to an open area or near a window, or use Wi-Fi calling."
            cov == 2 -> "Weak coverage: far from the tower or blocked. Try an open area, a window or a higher floor."
            qual == 2 -> "Strong enough but noisy, likely interference or a busy cell (common in crowds). Wait a few minutes or walk 20 to 50 m."
            overall == 1 -> "Usable, but not strong."
            else -> "Signal looks healthy here."
        }
        return Verdict(label, color, note)
    }

    // ------------------------------------------------------------ small helpers

    private fun isPrivateHost(h: String): Boolean {
        val parts = h.split(".")
        if (parts.size != 4) return false
        val n = ArrayList<Int>()
        for (p in parts) {
            val x = p.toIntOrNull() ?: return false
            if (x !in 0..255) return false
            n.add(x)
        }
        return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
    }

    private fun errText(r: ApiResult): String {
        val e = r.body?.optString("error")
        if (!e.isNullOrEmpty()) return e
        return r.error ?: "HTTP ${r.code}"
    }

    private fun say(msg: String) {
        lastMessage = msg
        refreshUi()
    }

    private fun confirm(title: String, msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("No", null)
            .show()
    }

    // ------------------------------------------------------------ pairing (optional)

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { b -> pairFromText(b.rawValue) }
            .addOnFailureListener { e ->
                say("Scan failed: ${e.message}. You can paste the pairing text instead.")
            }
    }

    private fun pairFromText(text: String?) {
        if (text.isNullOrBlank()) {
            say("Nothing to pair with.")
            return
        }
        val obj = try { JSONObject(text.trim()) } catch (e: Exception) {
            say("This is not a CellNet pairing code.")
            return
        }
        val host = obj.optString("host")
        val port = obj.optInt("port", 5000)
        val token = obj.optString("token")
        if (obj.optInt("v") != 1 || host.isEmpty() || token.isEmpty() || port !in 1..65535) {
            say("This pairing code is not valid.")
            return
        }
        if (!isPrivateHost(host)) {
            say("The address $host is not on a local network, so the app will not connect to it.")
            return
        }
        say("Pairing...")
        executor.execute {
            val body = JSONObject()
                .put("pairing_token", token)
                .put("device_uid", store.deviceUid)
                .put("device_name", Build.MODEL.take(60))
            val r = Api.post(host, port, "/api/pair", body, null)
            val tok = r.body?.optString("device_token")
            runOnUiThread {
                if (r.code == 200 && !tok.isNullOrEmpty()) {
                    store.host = host
                    store.port = port
                    store.deviceToken = tok
                    laptopOnline = true
                    say("Paired. Saved sessions can now be sent to the laptop.")
                    if (running && serverSessionId < 0) tryOpenServerSession(localSessionId, activeName)
                } else {
                    say("Pairing failed: ${errText(r)}. Is the receiver running, and are both devices on the same network?")
                }
            }
        }
    }

    private fun unpair() {
        store.unpair()
        db.clearServerSessions()
        serverSessionId = -1
        laptopOnline = null
        say("Unpaired. Your saved data stays on this phone.")
    }

    private fun handleUnauthorized() {
        store.unpair()
        db.clearServerSessions()
        serverSessionId = -1
        laptopOnline = null
        say("This phone was unpaired or revoked on the dashboard. It keeps saving on the phone. Scan a new QR code to reconnect.")
    }

    // ------------------------------------------------------------ permissions and start

    private fun hasPerms(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun onStartStop() {
        if (running) {
            stopSession()
        } else if (!hasPerms()) {
            startAfterPerms = true
            requestPermissions(perms, requestCode)
        } else {
            confirmStart()
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grantResults)
        if (code == requestCode) {
            val go = startAfterPerms
            startAfterPerms = false
            if (hasPerms()) {
                if (go) confirmStart() else startPreview()
            } else {
                say("Precise location permission is needed to read the signal and position. Allow it and try again.")
            }
        }
    }

    private fun confirmStart() {
        val msg = if (store.isPaired())
            "This reads your cellular signal and GPS location, saves them on this phone and sends them to your laptop at " +
                "${store.host}. Nothing is sent anywhere else. You can stop at any time."
        else
            "This reads your cellular signal and GPS location and saves them on this phone only. " +
                "Nothing is sent anywhere. You can stop at any time."
        AlertDialog.Builder(this)
            .setTitle("Start measuring?")
            .setMessage(msg)
            .setPositiveButton("Start") { _, _ -> startSession() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkClock(serverTime: String): String {
        return try {
            val server = OffsetDateTime.parse(serverTime).toInstant().toEpochMilli()
            val diff = abs(System.currentTimeMillis() - server)
            if (diff > 120000) " Warning: this phone's clock differs from the laptop by ${diff / 1000} s. Set both to automatic time."
            else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun startSession() {
        val name = "Phone " + SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date())
        activeName = name
        localSessionId = db.createSession(name, System.currentTimeMillis())
        serverSessionId = -1
        collected.set(0)
        accepted.set(0)
        duplicates.set(0)
        rejected.set(0)
        recent.clear()
        chart.setValues(recent)
        previewOn = false
        handler.removeCallbacks(previewLoop)
        running = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startLocation()
        handler.post(collectLoop)
        handler.postDelayed(sendLoop, 4000)
        handler.postDelayed(uiLoop, 1000)
        if (store.isPaired()) {
            say("Session started. Connecting to the laptop...")
            tryOpenServerSession(localSessionId, name)
        } else {
            say("Session started. Saving on this phone only (not paired).")
        }
    }

    private fun tryOpenServerSession(lid: Long, name: String) {
        val host = store.host ?: return
        val token = store.deviceToken ?: return
        if (opening || lid < 0) return
        opening = true
        executor.execute {
            val body = JSONObject().put("source", "phone").put("session_name", name)
            val r = Api.post(host, store.port, "/api/session/start", body, token)
            val b = r.body
            runOnUiThread {
                opening = false
                if (r.code == 200 && b != null) {
                    val sid = b.optInt("session_id", -1)
                    if (sid > 0) {
                        db.setServerSession(lid, sid)
                        if (localSessionId == lid) serverSessionId = sid
                        lastMessage = "Connected to the laptop. Readings are saved here and sent there." +
                            checkClock(b.optString("server_time"))
                    }
                } else if (r.code == 401) {
                    handleUnauthorized()
                } else {
                    lastMessage = "Laptop not reachable. Recording on this phone only. It will retry, or use Send to laptop later."
                }
                refreshUi()
            }
        }
    }

    private fun stopCollecting() {
        running = false
        handler.removeCallbacks(collectLoop)
        handler.removeCallbacks(sendLoop)
        handler.removeCallbacks(uiLoop)
        stopLocation()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stopSession() {
        val lid = localSessionId
        val sid = serverSessionId
        stopCollecting()
        db.endSession(lid, System.currentTimeMillis())
        localSessionId = -1L
        serverSessionId = -1
        startPreview()
        val count = db.readingCount(lid)
        if (store.isPaired() && sid > 0) {
            say("Stopping...")
            executor.execute {
                val done = flushSession(lid, sid)
                val host = store.host
                val token = store.deviceToken
                if (done && host != null && token != null) {
                    Api.post(host, store.port, "/api/session/stop", JSONObject().put("session_id", sid), token)
                }
                runOnUiThread {
                    say(if (done) "Session saved on this phone ($count readings) and sent to the laptop."
                    else "Session saved on this phone. Some readings are not sent yet. Use Send to laptop in Saved sessions.")
                }
            }
        } else {
            say("Session saved on this phone ($count readings). Open View saved sessions to see or download it.")
        }
    }

    // ------------------------------------------------------------ location

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val cur = lastLocation
            val now = System.currentTimeMillis()
            val better = cur == null ||
                now - cur.time > 10000 ||
                (location.hasAccuracy() && (!cur.hasAccuracy() || location.accuracy <= cur.accuracy))
            if (better) lastLocation = location
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locationStarted) return
        try {
            var any = false
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.requestLocationUpdates(p, 2000L, 0f, locListener)
                    any = true
                }
            }
            locationStarted = any
            if (!any) lastMessage = "Turn on Location in the phone settings, or readings will have no position."
        } catch (e: Exception) {
            lastMessage = "Location is not available: ${e.message}"
        }
    }

    private fun stopLocation() {
        try { locationManager.removeUpdates(locListener) } catch (e: Exception) { }
        locationStarted = false
    }

    // ------------------------------------------------------------ live preview (not saved, not sent)

    private val previewLoop = object : Runnable {
        override fun run() {
            if (running || !previewOn) return
            reader.read { cr ->
                if (!running && previewOn) {
                    lastReading = cr
                    lastReadingTime = System.currentTimeMillis()
                    pushRecent(cr.rsrp)
                    refreshUi()
                }
            }
            handler.postDelayed(this, 3000)
        }
    }

    private fun startPreview() {
        if (running || previewOn || !hasPerms()) return
        previewOn = true
        startLocation()
        handler.post(previewLoop)
        refreshUi()
    }

    private fun stopPreview() {
        previewOn = false
        handler.removeCallbacks(previewLoop)
        if (!running) stopLocation()
    }

    // ------------------------------------------------------------ laptop link check

    private val healthLoop = object : Runnable {
        override fun run() {
            val host = store.host
            if (host != null && store.isPaired()) {
                val port = store.port
                executor.execute {
                    val r = Api.get(host, port, "/health")
                    laptopOnline = r.code == 200
                    runOnUiThread { refreshUi() }
                }
            } else {
                laptopOnline = null
            }
            handler.postDelayed(this, 6000)
        }
    }

    // ------------------------------------------------------------ collect, save and send

    private val collectLoop = object : Runnable {
        override fun run() {
            if (!running) return
            reader.read { cr -> onReading(cr) }
            handler.postDelayed(this, 3000)
        }
    }

    private val sendLoop = object : Runnable {
        override fun run() {
            if (!running) return
            if (store.isPaired()) {
                val lid = localSessionId
                val sid = serverSessionId
                if (sid < 0) tryOpenServerSession(lid, activeName)
                else executor.execute { flushSession(lid, sid) }
            }
            handler.postDelayed(this, 4000)
        }
    }

    private val uiLoop = object : Runnable {
        override fun run() {
            if (!running) return
            refreshUi()
            handler.postDelayed(this, 1000)
        }
    }

    private fun onReading(cr: CellReading) {
        if (!running || localSessionId < 0) return
        lastReading = cr
        val now = System.currentTimeMillis()
        lastReadingTime = now
        var lat: Double? = null
        var lon: Double? = null
        var acc: Double? = null
        val loc = lastLocation
        if (loc != null && now - loc.time < 20000) {
            lat = loc.latitude
            lon = loc.longitude
            if (loc.hasAccuracy()) acc = loc.accuracy.toDouble()
        }
        db.addReading(localSessionId, now, lat, lon, acc, cr)
        collected.incrementAndGet()
        pushRecent(cr.rsrp)
        refreshUi()
    }

    private fun putIf(o: JSONObject, key: String, v: Any?) {
        if (v != null) o.put(key, v)
    }

    private fun toJson(r: ReadingRow): JSONObject {
        val o = JSONObject()
        o.put("measured_at", r.measuredAt)
        putIf(o, "latitude", r.lat)
        putIf(o, "longitude", r.lon)
        putIf(o, "gps_accuracy_m", r.acc)
        putIf(o, "operator", r.operator)
        putIf(o, "network_type", r.network)
        putIf(o, "rsrp_dbm", r.rsrp)
        putIf(o, "rsrq_db", r.rsrq)
        putIf(o, "sinr_db", r.sinr)
        putIf(o, "rssi_dbm", r.rssi)
        putIf(o, "signal_level", r.level)
        putIf(o, "cell_id", r.cellId)
        putIf(o, "connection_state", r.state)
        return o
    }

    // Sends all unsent readings of one local session. Returns true when nothing is left.
    private fun flushSession(localId: Long, serverId: Int): Boolean {
        val host = store.host
        val token = store.deviceToken
        if (host == null || token == null) return false
        while (true) {
            val rows = db.unsent(localId, 50)
            if (rows.isEmpty()) {
                runOnUiThread { refreshUi() }
                return true
            }
            val arr = JSONArray()
            for (r in rows) arr.put(toJson(r))
            val body = JSONObject().put("session_id", serverId).put("measurements", arr)
            val res = Api.post(host, store.port, "/api/measurements", body, token)
            val b = res.body
            if (res.code == 200 && b != null) {
                accepted.addAndGet(b.optInt("accepted"))
                duplicates.addAndGet(b.optInt("duplicates"))
                val rej = b.optJSONArray("rejected")
                if (rej != null && rej.length() > 0) {
                    rejected.addAndGet(rej.length())
                    lastMessage = "Some readings were rejected: " +
                        rej.optJSONObject(0)?.optString("reason", "invalid")
                }
                db.markSent(rows.map { it.id })
            } else if (res.code == 401) {
                runOnUiThread { handleUnauthorized() }
                return false
            } else if (res.code in 400..499 && res.code != 429) {
                lastMessage = "Server refused a batch: ${errText(res)}"
                db.markSent(rows.map { it.id })
            } else {
                lastMessage = "Laptop not reachable (${errText(res)}). Readings are kept and will retry."
                runOnUiThread { refreshUi() }
                return false
            }
        }
    }

    // ------------------------------------------------------------ saved sessions, download, send later

    private fun showSessions() {
        val list = db.sessions()
        if (list.isEmpty()) {
            say("Nothing saved yet. Start a session to record readings.")
            return
        }
        val labels = list.map {
            it.name + "\n" + it.count + " readings, " + fmtTime(it.startedAt, "dd MMM HH:mm") +
                (if (it.unsent > 0 && store.isPaired()) ", not sent to laptop" else "")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Saved sessions")
            .setItems(labels) { _, which -> showSession(list[which].id) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSession(id: Long) {
        val info = db.sessions().firstOrNull { it.id == id } ?: return
        val rows = db.sessionReadings(id, -1, false)
        val counts = IntArray(3)
        val rsrps = ArrayList<Int>()
        for (r in rows) {
            val k = Rules.overall(r.rsrp, r.rsrq, r.sinr)
            if (k in 0..2) counts[k]++
            if (r.rsrp != null) rsrps.add(r.rsrp)
        }
        rsrps.sort()
        val median: Double? =
            if (rsrps.isEmpty()) null
            else if (rsrps.size % 2 == 1) rsrps[rsrps.size / 2].toDouble()
            else (rsrps[rsrps.size / 2 - 1] + rsrps[rsrps.size / 2]) / 2.0
        val rated = counts.sum()
        val sb = StringBuilder()
        sb.append("Started: ").append(fmtTime(info.startedAt, "dd MMM HH:mm"))
        if (info.endedAt != null) sb.append("   Ended: ").append(fmtTime(info.endedAt, "HH:mm"))
        else sb.append("   (still running)")
        sb.append("\nReadings: ").append(rows.size)
        if (median != null) sb.append("   Median RSRP: ").append(String.format(Locale.US, "%.0f", median)).append(" dBm")
        if (rated > 0) {
            sb.append("\nGOOD ").append(counts[0] * 100 / rated).append("%   MODERATE ")
                .append(counts[1] * 100 / rated).append("%   POOR ").append(counts[2] * 100 / rated).append("%")
        }
        if (store.isPaired()) sb.append("\nNot yet sent to laptop: ").append(info.unsent)
        sb.append("\n\nLast readings:")
        for (r in rows.takeLast(12).reversed()) {
            sb.append("\n").append(fmtTime(r.measuredAt, "HH:mm:ss")).append("  ")
            sb.append(if (r.rsrp != null) r.rsrp.toString() + " dBm" else "no RSRP")
            sb.append("  ").append(Rules.label(Rules.overall(r.rsrp, r.rsrq, r.sinr)))
        }
        val b = AlertDialog.Builder(this)
            .setTitle(info.name)
            .setMessage(sb.toString())
            .setPositiveButton("Download CSV") { _, _ -> exportCsv(id) }
            .setNegativeButton("Delete") { _, _ ->
                if (running && localSessionId == id) say("Stop the session first.")
                else confirm("Delete this session?", "Its readings will be removed from this phone.") {
                    db.deleteSession(id)
                    say("Session deleted.")
                }
            }
        if (store.isPaired() && info.unsent > 0 && !(running && localSessionId == id)) {
            b.setNeutralButton("Send to laptop") { _, _ -> syncSession(id) }
        }
        b.show()
    }

    private fun syncSession(id: Long) {
        if (syncing) {
            say("Already sending, please wait.")
            return
        }
        val host = store.host
        val token = store.deviceToken
        if (host == null || token == null) {
            say("Pair with the dashboard first.")
            return
        }
        val info = db.sessions().firstOrNull { it.id == id } ?: return
        syncing = true
        say("Sending ${info.unsent} readings to the laptop...")
        executor.execute {
            var sid = info.serverId ?: -1
            if (sid < 0) {
                val body = JSONObject().put("source", "phone").put("session_name", info.name)
                val r = Api.post(host, store.port, "/api/session/start", body, token)
                val b = r.body
                if (r.code == 200 && b != null) {
                    sid = b.optInt("session_id", -1)
                    if (sid > 0) db.setServerSession(id, sid)
                } else if (r.code == 401) {
                    runOnUiThread {
                        syncing = false
                        handleUnauthorized()
                    }
                    return@execute
                } else {
                    runOnUiThread {
                        syncing = false
                        say("Could not reach the laptop: ${errText(r)}")
                    }
                    return@execute
                }
            }
            val done = if (sid > 0) flushSession(id, sid) else false
            if (done && info.endedAt != null) {
                Api.post(host, store.port, "/api/session/stop", JSONObject().put("session_id", sid), token)
            }
            runOnUiThread {
                syncing = false
                say(if (done) "All readings of this session were sent to the laptop."
                else "Not everything was sent. Check the laptop connection and try again.")
            }
        }
    }

    private fun exportCsv(id: Long?) {
        if (db.totals().second == 0) {
            say("Nothing to export yet.")
            return
        }
        executor.execute {
            val csv = db.exportCsv(id)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val fname = "cellnet_" + (if (id == null) "all" else "session" + id) + "_" + stamp + ".csv"
            val msg = try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues()
                    v.put(MediaStore.Downloads.DISPLAY_NAME, fname)
                    v.put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                    if (uri == null) {
                        "Could not create the file in Downloads."
                    } else {
                        contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                        "Saved to the Downloads folder: $fname"
                    }
                } else {
                    val file = java.io.File(getExternalFilesDir(null), fname)
                    file.writeText(csv)
                    "Saved: " + file.absolutePath
                }
            } catch (e: Exception) {
                "Could not save the file: " + e.message
            }
            runOnUiThread { say(msg) }
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        reader = SignalReader(this)
        db = LocalDb(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        buildUi()
        refreshUi()
        if (!hasPerms()) requestPermissions(perms, requestCode)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(healthLoop)
        handler.post(healthLoop)
        startPreview()
        refreshUi()
    }

    override fun onPause() {
        handler.removeCallbacks(healthLoop)
        if (!running) stopPreview()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopLocation()
        super.onDestroy()
    }
}
