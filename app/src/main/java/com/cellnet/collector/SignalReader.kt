package com.cellnet.collector

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.ServiceState
import android.telephony.TelephonyManager

class CellReading(
    val operator: String?,
    val networkType: String?,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Double?,
    val rssi: Int?,
    val level: Int?,
    val cellId: String?,
    val rawRssnr: Int?,
    val state: String?
)

class SignalReader(private val context: Context) {

    private val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val unavailable = Int.MAX_VALUE

    private fun inRange(v: Int, lo: Int, hi: Int): Int? =
        if (v == unavailable || v < lo || v > hi) null else v

    // Android reports LTE SINR (rssnr) in dB on some phones and in tenths of
    // a dB on others. Values in -20..30 are used as dB, larger ones are
    // divided by 10. The raw value is shown on screen so it can be checked.
    private fun sinrFrom(raw: Int): Double? {
        if (raw == unavailable) return null
        return when {
            raw in -20..30 -> raw.toDouble()
            raw in -200..300 -> raw / 10.0
            else -> null
        }
    }

    private fun operatorName(): String? {
        val a = tm.networkOperatorName
        val name = if (!a.isNullOrBlank()) a else tm.simOperatorName
        val s = (name ?: "").trim().take(40)
        return if (s.isEmpty()) null else s
    }

    private fun airplane(): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    @SuppressLint("MissingPermission")
    private fun connectionState(hasCell: Boolean): String? {
        if (airplane()) return "airplane_mode"
        try {
            val st = tm.serviceState
            if (st != null) {
                return when (st.state) {
                    ServiceState.STATE_IN_SERVICE -> "in_service"
                    ServiceState.STATE_OUT_OF_SERVICE, ServiceState.STATE_EMERGENCY_ONLY -> "no_service"
                    ServiceState.STATE_POWER_OFF -> "airplane_mode"
                    else -> null
                }
            }
        } catch (e: Exception) { }
        return if (hasCell) "in_service" else "no_cell_info"
    }

    private fun fromLte(c: CellInfoLte, op: String?, state: String?): CellReading {
        val s = c.cellSignalStrength
        val rssiVal = if (Build.VERSION.SDK_INT >= 29) inRange(s.rssi, -130, -30) else null
        val ci = c.cellIdentity.ci
        val raw = s.rssnr
        return CellReading(
            op, "LTE",
            inRange(s.rsrp, -160, -30),
            inRange(s.rsrq, -45, 0),
            sinrFrom(raw),
            rssiVal,
            s.level,
            if (ci == unavailable) null else ci.toString(),
            if (raw == unavailable) null else raw,
            state
        )
    }

    @TargetApi(29)
    private fun fromNr(c: CellInfoNr, op: String?, state: String?): CellReading {
        val s = c.cellSignalStrength as CellSignalStrengthNr
        return CellReading(
            op, "NR",
            inRange(s.ssRsrp, -160, -30),
            inRange(s.ssRsrq, -45, 0),
            inRange(s.ssSinr, -30, 50)?.toDouble(),
            null, s.level, null, null, state
        )
    }

    private fun build(list: List<CellInfo>): CellReading {
        val op = operatorName()
        val registered = list.filter { it.isRegistered }
        val state = connectionState(registered.isNotEmpty())

        val lte = registered.filterIsInstance<CellInfoLte>().firstOrNull()
        if (lte != null) return fromLte(lte, op, state)

        if (Build.VERSION.SDK_INT >= 29) {
            val nr = registered.filterIsInstance<CellInfoNr>().firstOrNull()
            if (nr != null) return fromNr(nr, op, state)
        }

        val w = registered.filterIsInstance<CellInfoWcdma>().firstOrNull()
        if (w != null) {
            val s = w.cellSignalStrength
            return CellReading(op, "UMTS", null, null, null, inRange(s.dbm, -130, -30),
                s.level, null, null, state)
        }

        val g = registered.filterIsInstance<CellInfoGsm>().firstOrNull()
        if (g != null) {
            val s = g.cellSignalStrength
            return CellReading(op, "GSM", null, null, null, inRange(s.dbm, -130, -30),
                s.level, null, null, state)
        }

        return CellReading(op, null, null, null, null, null, null, null, null, state)
    }

    @SuppressLint("MissingPermission")
    private fun safeAllCellInfo(): List<CellInfo> {
        return try {
            tm.allCellInfo ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun read(callback: (CellReading) -> Unit) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                tm.requestCellInfoUpdate(context.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        callback(build(cellInfo))
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        callback(build(safeAllCellInfo()))
                    }
                })
            } else {
                callback(build(safeAllCellInfo()))
            }
        } catch (e: Exception) {
            callback(build(emptyList()))
        }
    }
}
