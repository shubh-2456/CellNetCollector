package com.cellnet.collector

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
import android.os.Handler
import android.os.Looper
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
    private lateinit var queue: PendingQueue
    private lateinit var locationManager: LocationManager

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var sessionId = -1
    @Volatile private var running = false
    @Volatile private var lastMessage = ""
    private val collected = AtomicInteger(0)
    private val accepted = AtomicInteger(0)
    private val duplicates = AtomicInteger(0)
    private val rejected = AtomicInteger(0)
    private var lastReading: CellReading? = null
    private var lastReadingTime = 0L
    private var lastLocation: Location? = null

    private lateinit var tvPair: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var tvVerdictNote: TextView
    private lateinit var tvValues: TextView
    private lateinit var tvCounts: TextView
    private lateinit var tvMsg: TextView
    private lateinit var btnStart: Button
    private lateinit var btnUnpair: Button
    private lateinit var etPaste: EditText

    private val requestCode = 42

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

        val connect = card()
        tvPair = label("", 14f, white, false)
        connect.addView(tvPair)
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
        root.addSpaced(connect, 16)

        val session = card()
        tvSession = label("", 14f, white, false)
        session.addView(tvSession)
        btnStart = button("Start session") { onStartStop() }
        session.addSpaced(btnStart)
        tvCounts = label("", 12f, muted, false)
        session.addSpaced(tvCounts)
        root.addSpaced(session, 12)

        val signal = card()
        tvVerdict = label("No reading yet", 30f, cNa, true)
        tvVerdictNote = label("", 14f, white, false)
        tvValues = label("", 14f, white, false)
        tvValues.typeface = Typeface.MONOSPACE
        signal.addView(tvVerdict)
        signal.addView(tvVerdictNote)
        signal.addSpaced(tvValues, 14)
        root.addSpaced(signal, 12)

        tvMsg = label("", 13f, Color.parseColor("#7DD3FC"), false)
        root.addSpaced(tvMsg, 12)
        root.addSpaced(label(
            "Keep this app open with the screen on while measuring. For the full " +
            "analysis, open Signal Check on the dashboard.", 12f, muted, false), 10)

        setContentView(scroll)
    }

    private fun f(v: Number?, unit: String): String = if (v == null) "Unavailable" else "$v $unit"

    private fun refreshUi() {
        val paired = store.isPaired()
        tvPair.text = if (paired) "Paired with ${store.host}:${store.port}"
        else "Not paired. Open Home on the dashboard, press Create QR code, then scan it."
        tvSession.text = if (running) "Session #$sessionId is running" else "No session running"
        btnStart.text = if (running) "Stop session" else "Start session"
        btnStart.isEnabled = paired
        btnUnpair.isEnabled = paired
        tvCounts.text = "Collected ${collected.get()}   Sent ${accepted.get()}   " +
            "Duplicates ${duplicates.get()}   Rejected ${rejected.get()}   Waiting ${queue.size()}"
        tvMsg.text = lastMessage

        val r = lastReading
        if (r == null) {
            tvVerdict.text = "No reading yet"
            tvVerdict.setTextColor(cNa)
            tvVerdictNote.text = "Start a session to see the signal here."
            tvValues.text = ""
            return
        }
        val v = assess(r)
        tvVerdict.text = v.label
        tvVerdict.setTextColor(v.color)
        val stale = running && System.currentTimeMillis() - lastReadingTime > 15000
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

    // ------------------------------------------------------------ verdict (same rules as the dashboard)

    private fun rateRsrp(v: Int?): Int = when {
        v == null -> -1
        v >= -85 -> 0
        v >= -100 -> 1
        else -> 2
    }

    private fun rateRsrq(v: Int?): Int = when {
        v == null -> -1
        v >= -10 -> 0
        v >= -15 -> 1
        else -> 2
    }

    private fun rateSinr(v: Double?): Int = when {
        v == null -> -1
        v >= 13.0 -> 0
        v >= 0.0 -> 1
        else -> 2
    }

    private fun assess(r: CellReading): Verdict {
        if (r.state == "airplane_mode")
            return Verdict("AIRPLANE MODE", cNa, "Turn airplane mode off to measure the cellular signal.")
        if (r.state == "no_service")
            return Verdict("NO SERVICE", cPoor,
                "The phone reports no cellular service here. Move outdoors or next to a window, " +
                    "or use Wi-Fi calling if available.")

        val cov = rateRsrp(r.rsrp)
        val qual = if (r.sinr != null) rateSinr(r.sinr) else rateRsrq(r.rsrq)
        val overall = maxOf(cov, qual)

        if (overall < 0) {
            val older = r.networkType == "GSM" || r.networkType == "UMTS"
            return Verdict("UNAVAILABLE", cNa,
                if (older) "The phone is on an older network (${r.networkType}), so 4G signal values are not available."
                else "This phone did not report signal values here. Check the Location and Phone permissions.")
        }

        val label = listOf("GOOD", "MODERATE", "POOR")[overall]
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

    // ------------------------------------------------------------ pairing

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
                    say("Paired. You can start a session now.")
                } else {
                    say("Pairing failed: ${errText(r)}. Is the receiver running, and are both devices on the same network?")
                }
            }
        }
    }

    private fun unpair() {
        if (running) stopCollecting()
        store.unpair()
        queue.clear()
        sessionId = -1
        say("Unpaired. Readings waiting on this phone were removed.")
    }

    private fun handleUnauthorized() {
        stopCollecting()
        store.unpair()
        queue.clear()
        say("This phone was unpaired or revoked on the dashboard. Scan a new QR code.")
    }

    // ------------------------------------------------------------ permissions and start

    private fun hasPerms(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun onStartStop() {
        if (running) {
            stopSession()
        } else if (!hasPerms()) {
            requestPermissions(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_PHONE_STATE), requestCode)
        } else {
            confirmStart()
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grantResults)
        if (code == requestCode) {
            if (hasPerms()) confirmStart()
            else say("Precise location permission is needed to read the signal and position. Allow it and try again.")
        }
    }

    private fun confirmStart() {
        AlertDialog.Builder(this)
            .setTitle("Start measuring?")
            .setMessage("This reads your cellular signal and GPS location and sends them to your laptop at " +
                "${store.host}. Nothing is sent anywhere else. You can stop at any time.")
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
        val host = store.host ?: return
        val token = store.deviceToken ?: return
        say("Starting session...")
        executor.execute {
            val name = "Phone " + SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date())
            val body = JSONObject().put("source", "phone").put("session_name", name)
            val r = Api.post(host, store.port, "/api/session/start", body, token)
            val b = r.body
            runOnUiThread {
                if (r.code == 200 && b != null) {
                    sessionId = b.optInt("session_id", -1)
                    val warn = checkClock(b.optString("server_time"))
                    collected.set(0)
                    accepted.set(0)
                    duplicates.set(0)
                    rejected.set(0)
                    running = true
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    beginLocation()
                    handler.post(collectLoop)
                    handler.postDelayed(sendLoop, 4000)
                    handler.postDelayed(uiLoop, 1000)
                    say("Session started." + warn)
                } else if (r.code == 401) {
                    handleUnauthorized()
                } else {
                    say("Could not start: ${errText(r)}. Is the receiver running on the laptop?")
                }
            }
        }
    }

    private fun stopCollecting() {
        running = false
        handler.removeCallbacks(collectLoop)
        handler.removeCallbacks(sendLoop)
        handler.removeCallbacks(uiLoop)
        try { locationManager.removeUpdates(locListener) } catch (e: Exception) { }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stopSession() {
        val sid = sessionId
        stopCollecting()
        val host = store.host
        val token = store.deviceToken
        say("Stopping...")
        executor.execute {
            flush()
            if (host != null && token != null && sid >= 0) {
                Api.post(host, store.port, "/api/session/stop", JSONObject().put("session_id", sid), token)
            }
            runOnUiThread {
                sessionId = -1
                val left = queue.size()
                say(if (left > 0) "Session stopped. $left readings are still waiting and will send when the laptop is reachable."
                else "Session stopped. All readings were sent.")
            }
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
    private fun beginLocation() {
        try {
            var any = false
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.requestLocationUpdates(p, 2000L, 0f, locListener)
                    any = true
                }
            }
            if (!any) lastMessage = "Turn on Location in the phone settings, or readings will have no position."
        } catch (e: Exception) {
            lastMessage = "Location is not available: ${e.message}"
        }
    }

    // ------------------------------------------------------------ collect and send

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
            executor.execute { flush() }
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

    private fun putIf(o: JSONObject, key: String, v: Any?) {
        if (v != null) o.put(key, v)
    }

    private fun onReading(cr: CellReading) {
        if (!running) return
        lastReading = cr
        val now = System.currentTimeMillis()
        lastReadingTime = now
        val o = JSONObject()
        o.put("__sid", sessionId)
        o.put("measured_at", now)
        val loc = lastLocation
        if (loc != null && now - loc.time < 20000) {
            o.put("latitude", loc.latitude)
            o.put("longitude", loc.longitude)
            if (loc.hasAccuracy()) o.put("gps_accuracy_m", loc.accuracy.toDouble())
        }
        putIf(o, "operator", cr.operator)
        putIf(o, "network_type", cr.networkType)
        putIf(o, "rsrp_dbm", cr.rsrp)
        putIf(o, "rsrq_db", cr.rsrq)
        putIf(o, "sinr_db", cr.sinr)
        putIf(o, "rssi_dbm", cr.rssi)
        putIf(o, "signal_level", cr.level)
        putIf(o, "cell_id", cr.cellId)
        putIf(o, "connection_state", cr.state)
        queue.add(o)
        collected.incrementAndGet()
        refreshUi()
    }

    private fun flush() {
        val host = store.host
        val token = store.deviceToken
        if (host == null || token == null) return
        while (true) {
            val items = queue.peek(200)
            if (items.isEmpty()) break
            val sid = items[0].optInt("__sid", -1)
            val group = items.takeWhile { it.optInt("__sid", -1) == sid }.take(50)
            if (sid < 0) {
                queue.drop(group.size)
                continue
            }
            val arr = JSONArray()
            for (it in group) {
                val copy = JSONObject(it.toString())
                copy.remove("__sid")
                arr.put(copy)
            }
            val body = JSONObject().put("session_id", sid).put("measurements", arr)
            val r = Api.post(host, store.port, "/api/measurements", body, token)
            val b = r.body
            if (r.code == 200 && b != null) {
                accepted.addAndGet(b.optInt("accepted"))
                duplicates.addAndGet(b.optInt("duplicates"))
                val rej = b.optJSONArray("rejected")
                if (rej != null && rej.length() > 0) {
                    rejected.addAndGet(rej.length())
                    lastMessage = "Some readings were rejected: " +
                        rej.optJSONObject(0)?.optString("reason", "invalid")
                }
                queue.drop(group.size)
            } else if (r.code == 401) {
                runOnUiThread { handleUnauthorized() }
                return
            } else if (r.code in 400..499 && r.code != 429) {
                lastMessage = "Server refused a batch: ${errText(r)}"
                queue.drop(group.size)
            } else {
                lastMessage = "Laptop not reachable (${errText(r)}). Readings are kept and will retry."
                break
            }
        }
        runOnUiThread { refreshUi() }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        reader = SignalReader(this)
        queue = PendingQueue(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        buildUi()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { locationManager.removeUpdates(locListener) } catch (e: Exception) { }
        super.onDestroy()
    }
}
