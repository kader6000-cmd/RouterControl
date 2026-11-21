package com.routercontrol.app.data

/** A device currently seen on the home network (WLAN or LAN). */
data class RouterDevice(
    val name: String,
    val mac: String,
    val ip: String,
    val ipv6: String = "",
    val networkType: String, // "WLAN" or "LAN"
    val linkTimeSeconds: Long = 0L,
    val active: Boolean = true
)

/**
 * One Parental Control rule on the ZTE H288A (Internet > Parental Controls).
 * enabled = true means the rule is ON, i.e. "Ban Internet Access" is active
 * for the whole day (Everyday, 00:00-23:59) and the device is BLOCKED.
 * enabled = false means the rule is OFF and the device is ALLOWED.
 * mac may be empty until the rule is linked to a device from "Who's Online".
 */
data class ParentalRule(
    val name: String,
    val mac: String,
    val enabled: Boolean,
    val daysEveryday: Boolean = true,
    val startMinutes: Int = 0, // 00:00
    val endMinutes: Int = 23 * 60 + 59, // 23:59
    val action: String = "Ban Internet Access",
    val pendingSync: Boolean = false,
    val instanceId: String = "" // router-side instance id, if ever discovered; "" = unknown
)

/** A per-rule automatic block window (schedule). daysMask: bit 0 = Sun ... bit 6 = Sat. */
data class RuleSchedule(
    val ruleName: String,
    val enabled: Boolean,
    val startMinutes: Int,
    val endMinutes: Int,
    val daysMask: Int // 0x7F = everyday
)

data class RouterSettings(
    val host: String = "192.168.1.1",
    val username: String = "admin",
    val password: String = "",
    val useHttps: Boolean = false,
    /** Confirmed parental-control Lua script name, or "" if not yet confirmed (UNTESTED). */
    val parentalScript: String = ""
) {
    fun baseUrl(): String = (if (useHttps) "https://" else "http://") + host.trim()
}

data class DiagnosticsEntry(
    val label: String,
    val detail: String,
    val ok: Boolean
)

data class DeviceFetch(
    val devices: List<RouterDevice>,
    val error: String? = null,
    /** Data-script names found in the router's own device pages (Diagnostics). */
    val debugTags: List<String> = emptyList(),
    /** Snippet of the last WLAN data reply (Diagnostics). */
    val debugSnippet: String = "",
    /** Result of loading the local-network view page (Diagnostics). */
    val debugViewStatus: String = "",
    /** Full URL fragments mentioning a data script in the router pages (Diagnostics). */
    val debugDeviceUrls: List<String> = emptyList()
)

/** Result of a router call. `raw` is a short snippet of the router reply, for Diagnostics. */
sealed class RouterResult {
    data class Success(val message: String, val raw: String = "") : RouterResult()
    data class Failure(val message: String, val raw: String = "") : RouterResult()
}
