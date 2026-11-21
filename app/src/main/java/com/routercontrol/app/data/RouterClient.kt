package com.routercontrol.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * HTTP client for the ZTE ZXHN H288A web interface (firmware H288A V1.0_GR5.1T16).
 *
 * VERIFIED endpoints (juacas/zte_tracker — Home Assistant integration, H288A family
 * verified; its own reverse-engineering notes at
 * github.com/juacas/zte_tracker/tree/master/custom_components/zte_tracker/zteclient):
 *
 *  LOGIN — 3-step challenge-response:
 *   1. GET  /?_type=loginData&_tag=login_token&_=<guid>
 *      -> XML body, the text inside <ajax_response_xml_root> is the numeric challenge token.
 *   2. GET  /?_type=loginData&_tag=login_entry
 *      -> JSON {"lockingTime":0,"loginErrMsg":"","sess_token":"..."}.
 *   3. POST /?_type=loginData&_tag=login_entry  (form, x-www-form-urlencoded)
 *      action=login & Username=<admin> & Password=SHA256(password + token) & _sessionTOKEN=<sess_token>
 *      -> JSON with a new sess_token; login_need_refresh=1 means re-login is required.
 *   Session is kept with cookies (the router sets an SID cookie); every request
 *   carries a guid = current time in millis, incremented per request, to defeat caching.
 *   Headers: X-Requested-With: XMLHttpRequest, Chrome-like User-Agent.
 *   LOGOUT: POST /?_type=loginData&_tag=logout_entry with IF_LogOff=1.
 *
 *  DEVICES — H288A family (zte_tracker _MODELS["H288A"]):
 *   - Context setup: GET /?_type=menuView&_tag=localNetStatus&_=<guid>
 *   - WLAN: GET /?_type=menuData&_tag=accessdev_ssiddev_lua.lua&_=<guid>
 *   - LAN:  GET /?_type=menuData&_tag=accessdev_landevs_lua.lua&_=<guid>
 *   Both return XML; devices live in <OBJ_ACCESSDEV_ID> instances with
 *   HostName / MACAddress / IPAddress / IPV6Address / LinkTime fields.
 *   Fallback (F6640 family script, only if the H288A scripts return nothing):
 *   GET /?_type=menuData&_tag=wlan_client_stat_lua.lua — devices in <OBJ_WLAN_AD_ID>.
 *   All responses carry <IF_ERRORSTR>SUCC</IF_ERRORSTR> on success.
 *
 * PARENTAL CONTROL — NOT covered by zte_tracker and NO public Lua endpoint was
 * found for it (searched 2026-11-21). See ParentalSync.kt: the app tries a small
 * list of candidate script names following ZTE's naming pattern and records which
 * one (if any) answers, and exposes a Diagnostics screen + a manual endpoint
 * override in Settings. NOTHING about parental control is presented as verified.
 * The web UI path itself is verified from the user's screenshots + the ZTE H298A
 * admin manual: Internet > Parental Controls, one rule per device (Name, User
 * Identity = MAC, Time Policy Everyday 00:00-23:59, Action "Ban Internet Access").
 */
class RouterClient {

    private val mutex = Mutex()
    private var guid = System.currentTimeMillis()
    private var sessToken: String? = null
    private var loggedIn = false
    /** Base URL of the router, for the Referer header the web UI always sends. */
    private var refererBase: String? = null

    private fun nextGuid(): Long {
        guid += 1
        return guid
    }

    private val cookieStore = mutableListOf<Cookie>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
            synchronized(cookieStore) {
                for (c in cookies) {
                    cookieStore.removeAll { it.name == c.name }
                    cookieStore.add(c)
                }
            }
        }

        override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> {
            synchronized(cookieStore) { return cookieStore.toList() }
        }
    }

    private val http = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun isLoggedIn(): Boolean = loggedIn

    private fun base(settings: RouterSettings): String = settings.baseUrl()

    private fun get(url: String): Pair<Int, String> {
        val builder = Request.Builder()
            .url(url)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
        // The browser always sends the page as Referer; some ZTE firmwares
        // answer SessionTimeout to XHR calls without it.
        refererBase?.let { builder.header("Referer", "$it/") }
        val req = builder.get().build()
        http.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    private fun postForm(url: String, params: Map<String, String>): Pair<Int, String> {
        val form = FormBody.Builder()
        for ((k, v) in params) form.add(k, v)
        val builder = Request.Builder()
            .url(url)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
        refererBase?.let { builder.header("Referer", "$it/") }
        val req = builder.post(form.build()).build()
        http.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    /**
     * Login, 3 steps as documented above. Returns Success on a valid session,
     * Failure with an Arabic message otherwise (wrong password, router locked,
     * unreachable, ...). Never throws out of this function.
     */
    suspend fun login(settings: RouterSettings): RouterResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val host = base(settings)
                if (settings.password.isBlank()) {
                    return@withContext RouterResult.Failure("اكتب باسورد الأدمن في الإعدادات الأول")
                }
                if (host.toHttpUrlOrNull() == null) {
                    return@withContext RouterResult.Failure("عنوان الروتر مش صحيح — مثال: 192.168.1.1")
                }
                refererBase = host
                // Browser-style page load first: sets the router's cookies
                // (_TESTCOOKIESUPPORT / SID) the way a real page load does.
                // zte_tracker notes that without this, some endpoints answer
                // SessionTimeout. Best effort — login decides success.
                try {
                    get("$host/")
                } catch (e: Exception) {
                    // Ignore; the login steps below will fail loudly if unreachable.
                }
                // Step 1: challenge token (XML text).
                val (_, tokenBody) = get("$host/?_type=loginData&_tag=login_token&_=${nextGuid()}")
                val token = tokenBody.replace(Regex("<[^>]+>"), "").trim()
                if (token.isBlank()) {
                    return@withContext RouterResult.Failure(
                        "الروتر مردّش على طلب الدخول — اتأكد إنك على نفس الواي فاي وإن العنوان صح",
                        tokenBody.take(200)
                    )
                }
                // Step 2: session entry (JSON with sess_token + lockingTime).
                val (_, entryBody) = get("$host/?_type=loginData&_tag=login_entry")
                val entry = org.json.JSONObject(entryBody)
                val locking = entry.optInt("lockingTime", 0)
                if (locking == -1) {
                    return@withContext RouterResult.Failure("الروتر مقفول نهائيا بسبب محاولات دخول كتير — استنى أو ريستّر الروتر")
                }
                if (locking > 0) {
                    return@withContext RouterResult.Failure("الروتر مقفول مؤقتا $locking ثانية بسبب محاولات دخول — استنى وحاول تاني")
                }
                val sess = entry.optString("sess_token", "")
                if (sess.isBlank()) {
                    return@withContext RouterResult.Failure("الروتر مردّش بجلسة دخول — جرّب تاني", entryBody.take(200))
                }
                sessToken = sess
                // Step 3: submit SHA256(password + token).
                val hashed = sha256Hex(settings.password + token)
                val (code, loginBody) = postForm(
                    "$host/?_type=loginData&_tag=login_entry",
                    mapOf(
                        "action" to "login",
                        "Username" to settings.username,
                        "Password" to hashed,
                        "_sessionTOKEN" to sess
                    )
                )
                if (code != 200) {
                    loggedIn = false
                    return@withContext RouterResult.Failure("الروتر رد بكود $code على تسجيل الدخول", loginBody.take(200))
                }
                val loginJson = org.json.JSONObject(loginBody)
                val errMsg = loginJson.optString("loginErrMsg", "")
                val newSess = loginJson.optString("sess_token", "")
                if (errMsg.isNotBlank()) {
                    loggedIn = false
                    return@withContext RouterResult.Failure("الدخول فشل: $errMsg")
                }
                if (newSess.isBlank() && loginJson.optInt("login_need_refresh", 0) == 0) {
                    // Some firmwares answer without a fresh token but with no error;
                    // treat a non-empty reply without an error as a login only if the
                    // reply also contains a session token. Otherwise fail safe.
                    loggedIn = false
                    return@withContext RouterResult.Failure("الدخول فشل — اتأكد من اسم المستخدم والباسورد", loginBody.take(200))
                }
                if (newSess.isNotBlank()) sessToken = newSess
                loggedIn = true
                RouterResult.Success("تم تسجيل الدخول للروتر")
            } catch (e: Exception) {
                loggedIn = false
                RouterResult.Failure("مش قادر أوصل للروتر: ${e.message ?: "خطأ غير معروف"}")
            }
        }
    }

    suspend fun logout(settings: RouterSettings) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val token = sessToken ?: ""
                    postForm(
                        "${base(settings)}/?_type=loginData&_tag=logout_entry",
                        mapOf("IF_LogOff" to "1", "_sessionTOKEN" to token)
                    )
                } catch (e: Exception) {
                    // Logout is best-effort; ignore failures.
                } finally {
                    loggedIn = false
                    sessToken = null
                    synchronized(cookieStore) { cookieStore.clear() }
                }
            }
        }
    }

    /** Raw authenticated GET, for the Diagnostics screen and for ParentalSync probes. */
    suspend fun rawGet(settings: RouterSettings, pathAndQuery: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    get("${base(settings)}/$pathAndQuery")
                } catch (e: Exception) {
                    0 to (e.message ?: "خطأ غير معروف")
                }
            }
        }

    /** Raw authenticated POST, for ParentalSync. Returns HTTP code + body. */
    suspend fun rawPost(settings: RouterSettings, pathAndQuery: String, params: Map<String, String>): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val withToken = params.toMutableMap()
                    sessToken?.let { withToken["_sessionTOKEN"] = it }
                    postForm("${base(settings)}/$pathAndQuery", withToken)
                } catch (e: Exception) {
                    0 to (e.message ?: "خطأ غير معروف")
                }
            }
        }

    /**
     * POST with an already-urlencoded body and an optional Check header
     * (ZTE verifies a SHA256/RSA Check on signed form POSTs). The body string
     * is sent exactly as given, so the Check header matches it byte for byte.
     */
    suspend fun rawPostChecked(
        settings: RouterSettings,
        pathAndQuery: String,
        encodedBody: String,
        check: String?
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val body = encodedBody.toRequestBody(
                    "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
                )
                val builder = Request.Builder()
                    .url("${base(settings)}/$pathAndQuery")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                if (check != null) builder.header("Check", check)
                refererBase?.let { builder.header("Referer", "$it/") }
                http.newCall(builder.post(body).build()).execute().use { resp ->
                    resp.code to (resp.body?.string() ?: "")
                }
            } catch (e: Exception) {
                0 to (e.message ?: "خطأ غير معروف")
            }
        }
    }

    fun currentSessionToken(): String? = sessToken

    /** Live WLAN + LAN devices. Requires a logged-in session (logs in first if needed). */
    suspend fun getDevices(settings: RouterSettings): DeviceFetch = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!loggedIn) {
                    val login = loginUnLocked(settings)
                    if (login is RouterResult.Failure) return@withContext DeviceFetch(emptyList(), login.message)
                }
                val host = base(settings)
                refererBase = host
                var attempt = 0
                while (true) {
                    // Context setup for the local-network view (zte_tracker: menuView localNetStatus).
                    try {
                        get("$host/?_type=menuView&_tag=localNetStatus&_=${nextGuid()}")
                    } catch (e: Exception) {
                        // Non-fatal; the data requests below decide success.
                    }
                    val found = linkedMapOf<String, RouterDevice>()
                    // WLAN — H288A family script.
                    val (_, wlanBody) = get("$host/?_type=menuData&_tag=accessdev_ssiddev_lua.lua&_=${nextGuid()}")
                    for (d in parseAccessDevices(wlanBody, "OBJ_ACCESSDEV_ID", "WLAN")) {
                        found.putIfAbsent(d.mac, d)
                    }
                    // LAN — same element id on the H288A family.
                    val (_, lanBody) = get("$host/?_type=menuData&_tag=accessdev_landevs_lua.lua&_=${nextGuid()}")
                    for (d in parseAccessDevices(lanBody, "OBJ_ACCESSDEV_ID", "LAN")) {
                        found.putIfAbsent(d.mac, d)
                    }
                    // Fallback: F6640-family WLAN script, only if nothing came back.
                    var fbBody = ""
                    if (found.isEmpty()) {
                        val (_, body) = get("$host/?_type=menuData&_tag=wlan_client_stat_lua.lua&_=${nextGuid()}")
                        fbBody = body
                        for (d in parseAccessDevices(fbBody, "OBJ_WLAN_AD_ID", "WLAN")) {
                            found.putIfAbsent(d.mac, d)
                        }
                    }
                    if (found.isNotEmpty()) {
                        return@withContext DeviceFetch(found.values.sortedBy { it.name.lowercase(Locale.US) }, null)
                    }
                    // Empty result: was the session evicted? An evicted session
                    // fails as a SessionTimeout router error, not an HTTP error
                    // (zte_tracker #75) — re-login once and retry the fetch.
                    val timedOut = wlanBody.contains("SessionTimeout") ||
                        lanBody.contains("SessionTimeout") ||
                        fbBody.contains("SessionTimeout")
                    if (timedOut && attempt == 0) {
                        attempt++
                        val relogin = loginUnLocked(settings)
                        if (relogin is RouterResult.Failure) {
                            return@withContext DeviceFetch(emptyList(), relogin.message)
                        }
                        continue
                    }
                    if (timedOut) {
                        return@withContext DeviceFetch(
                            emptyList(),
                            "الروتر رفض الجلسة (SessionTimeout) — لو صفحة الروتر مفتوحة في المتصفح، اقفلها وحاول تاني من التطبيق"
                        )
                    }
                    return@withContext DeviceFetch(
                        emptyList(),
                        "الروتر رد بس من غير أجهزة — ممكن مفيش جهاز متصل دلوقتي، أو سوفت الروتر رد بشكل مختلف عن المتوقع (شوف صفحة التشخيص)"
                    )
                }
            } catch (e: Exception) {
                DeviceFetch(emptyList(), "فشل جلب الأجهزة: ${e.message ?: "خطأ غير معروف"}")
            }
        }
    }

    /** Login without re-entering the outer mutex (getDevices already holds it). */
    private fun loginUnLocked(settings: RouterSettings): RouterResult {
        return try {
            val host = base(settings)
            if (settings.password.isBlank()) return RouterResult.Failure("اكتب باسورد الأدمن في الإعدادات الأول")
            val (_, tokenBody) = get("$host/?_type=loginData&_tag=login_token&_=${nextGuid()}")
            val token = tokenBody.replace(Regex("<[^>]+>"), "").trim()
            if (token.isBlank()) return RouterResult.Failure("الروتر مردّش على طلب الدخول")
            val (_, entryBody) = get("$host/?_type=loginData&_tag=login_entry")
            val entry = org.json.JSONObject(entryBody)
            if (entry.optInt("lockingTime", 0) != 0) {
                return RouterResult.Failure("الروتر مقفول مؤقتا بسبب محاولات دخول — استنى وحاول تاني")
            }
            val sess = entry.optString("sess_token", "")
            if (sess.isBlank()) return RouterResult.Failure("الروتر مردّش بجلسة دخول")
            sessToken = sess
            val hashed = sha256Hex(settings.password + token)
            val (code, loginBody) = postForm(
                "$host/?_type=loginData&_tag=login_entry",
                mapOf("action" to "login", "Username" to settings.username, "Password" to hashed, "_sessionTOKEN" to sess)
            )
            if (code != 200) {
                loggedIn = false
                return RouterResult.Failure("الروتر رد بكود $code على تسجيل الدخول")
            }
            val loginJson = org.json.JSONObject(loginBody)
            val errMsg = loginJson.optString("loginErrMsg", "")
            if (errMsg.isNotBlank()) {
                loggedIn = false
                return RouterResult.Failure("الدخول فشل: $errMsg")
            }
            val newSess = loginJson.optString("sess_token", "")
            if (newSess.isNotBlank()) sessToken = newSess else {
                loggedIn = false
                return RouterResult.Failure("الدخول فشل — اتأكد من اسم المستخدم والباسورد")
            }
            loggedIn = true
            RouterResult.Success("تم تسجيل الدخول للروتر")
        } catch (e: Exception) {
            loggedIn = false
            RouterResult.Failure("مش قادر أوصل للروتر: ${e.message ?: "خطأ غير معروف"}")
        }
    }

    private fun parseAccessDevices(xml: String, elementName: String, networkType: String): List<RouterDevice> {
        val out = mutableListOf<RouterDevice>()
        if (xml.isBlank()) return out
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val doc = factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
            val nodes = doc.getElementsByTagName(elementName)
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                fun tag(name: String): String {
                    val list = el.getElementsByTagName(name)
                    if (list.length == 0) return ""
                    return list.item(0)?.textContent?.trim() ?: ""
                }
                val mac = tag("MACAddress").uppercase(Locale.US)
                if (mac.isBlank()) continue
                val name = tag("HostName").ifBlank { mac }
                out.add(
                    RouterDevice(
                        name = name,
                        mac = mac,
                        ip = tag("IPAddress"),
                        ipv6 = tag("IPV6Address"),
                        networkType = networkType,
                        linkTimeSeconds = tag("LinkTime").toLongOrNull() ?: 0L,
                        active = true
                    )
                )
            }
        } catch (e: Exception) {
            // Malformed XML from the router: return whatever was parsed so far.
        }
        return out
    }
}
