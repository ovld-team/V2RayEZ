package com.v2rayez.app.data.core

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Best-effort device country (ISO-3166 alpha-2, lowercase). Precedence mirrors Tor's
 * [com.v2rayez.app.data.tor.BridgeProvider] country probe: SIM network country, then SIM
 * registration country, then the process locale. Returns null when nothing usable is found so
 * callers can decide their own fallback (routing must not guess "ir" from noise).
 */
object DeviceCountry {

    fun detect(context: Context): String? {
        runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val net = tm?.networkCountryIso?.trim().orEmpty()
            if (net.length == 2) return net.lowercase(Locale.US)
            val sim = tm?.simCountryIso?.trim().orEmpty()
            if (sim.length == 2) return sim.lowercase(Locale.US)
        }
        val locale = Locale.getDefault().country.trim()
        return if (locale.length == 2) locale.lowercase(Locale.US) else null
    }
}
