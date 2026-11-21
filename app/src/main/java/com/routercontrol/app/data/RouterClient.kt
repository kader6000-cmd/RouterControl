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
    /** Main router shell HTML; common JS bundles are often referenced here, not in menuView fragments. */
    private var rootPageHtml: String = ""

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
                    rootPageHtml = get("$host/").second
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

    /**
     * Pull data-script names out of a router page's HTML/JS. The web UI's own
     * pages call their data scripts by name (e.g. ?_type=menuData&_tag=..._lua.lua),
     * so the page HTML is the authoritative list of tags for THIS firmware —
     * better than guessing names. Used for device + parental discovery (v1.2).
     */
    fun extractLuaTags(html: String): List<String> {
        val found = linkedSetOf<String>()
        val tagRe = Regex("_tag=([A-Za-z0-9_\\-.]+)")
        for (m in tagRe.findAll(html)) {
            val v = m.groupValues[1]
            if (v.endsWith("_lua.lua")) found.add(v)
        }
        val quotedRe = Regex("[\"']([A-Za-z0-9_\\-./]+_lua\\.lua)[\"']")
        for (m in quotedRe.findAll(html)) {
            found.add(m.groupValues[1].substringAfterLast("/"))
        }
        return found.toList()
    }

    /**
     * Page token (_sessionTmpToken) embedded in a router view's HTML,
     * with \xNN escapes decoded. The parental toggle uses this token;
     * device reads may need it instead of (or as well as) the login token.
     */
    fun extractPageToken(html: String): String? {
        val m = Regex("_sessionTmpToken\\s*=\\s*[\"']([^\"']+)[\"']").find(html) ?: return null
        val raw = m.groupValues[1]
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '\\' && i + 3 < raw.length && raw[i + 1] == 'x') {
                val hex = raw.substring(i + 2, i + 4)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 4
                    continue
                }
            }
            sb.append(raw[i])
            i++
        }
        return sb.toString()
    }

    /**
     * Try one device data script in every request shape that could matter,
     * in order, and return the first body that is NOT a SessionTimeout
     * (or the last body tried). Shapes: (a) GET, millis guid, no token —
     * exactly the parental read that works; (b) GET + page token from the
     * view HTML; (c) GET + login token; (d) POST + page/login token.
     * Earlier versions tried only one shape per release (v1.7 sweep).
     */
    private fun fetchDeviceScript(host: String, script: String, pageToken: String?): String {
        val guid = System.currentTimeMillis()
        val base = "$host/?_type=menuData&_tag=$script&_=$guid"
        val variants = mutableListOf<Pair<String, Map<String, String>?>>()
        variants.add(base to null)
        pageToken?.let { variants.add("$base&_sessionTOKEN=$it" to null) }
        sessToken?.let { variants.add("$base&_sessionTOKEN=$it" to null) }
        val postToken = pageToken ?: sessToken
        postToken?.let { variants.add(base to mapOf("_sessionTOKEN" to it)) }
        var last = ""
        for ((url, form) in variants) {
            val (_, body) = try {
                if (form == null) get(url) else postForm(url, form)
            } catch (e: Exception) {
                0 to ""
            }
            last = body
            if (!body.contains("SessionTimeout") && body.isNotBlank()) return body
        }
        return last
    }

    /**
     * Pull full URL fragments mentioning a data script out of a router page's
     * HTML/JS — including any extra query params the UI sends with them
     * (e.g. ?_type=menuData&_tag=accessdev_ssiddev_lua.lua&SSID=1).
     * The tag name alone may not be the whole request (v1.6).
     */
    fun extractLuaUrls(html: String): List<String> {
        val found = linkedSetOf<String>()
        val re = Regex("[\"']([^\"'\\s]*_lua\\.lua[^\"'\\s]*)[\"']")
        for (m in re.findAll(html)) {
            val v = m.groupValues[1].trim()
            if (v.isNotBlank()) found.add(v)
        }
        return found.toList()
    }

    /** Return the source surrounding the router UI's device-query initializer. */
    fun extractDeviceInitializer(html: String): String {
        val marker = "initial_QueryAccessDeviceLink"
        val at = html.indexOf(marker)
        if (at < 0) return ""
        val scriptStart = html.lastIndexOf("<script", at, ignoreCase = true)
        val start = if (scriptStart >= 0) scriptStart else (at - 1200).coerceAtLeast(0)
        val scriptEnd = html.indexOf("</script>", at, ignoreCase = true)
        val end = if (scriptEnd >= 0) minOf(scriptEnd + 9, start + 9000) else minOf(html.length, at + 7000)
        return html.substring(start, end).replace(Regex("\\s+"), " ").take(7000)
    }

    /** Fetch same-origin external JS files: the page's inline code only invokes
     * initial_QueryAccessDeviceLink(); its implementation is often in a .js file. */
    private fun extractScriptSources(html: String): List<String> {
        val found = linkedSetOf<String>()
        val re = Regex("(?is)<script\\b[^>]*?src\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']")
        for (m in re.findAll(html)) found.add(m.groupValues[1].replace("&amp;", "&"))
        return found.toList()
    }

    private fun functionBody(source: String, functionName: String): String {
        val name = Regex.escape(functionName)
        val patterns = listOf(
            Regex("function\\s+$name\\s*\\([^)]*\\)\\s*\\{"),
            Regex("(?:var\\s+|let\\s+|const\\s+)?$name\\s*=\\s*function\\s*\\([^)]*\\)\\s*\\{"),
            Regex("$name\\s*:\\s*function\\s*\\([^)]*\\)\\s*\\{")
        )
        for (pattern in patterns) {
            val match = pattern.find(source) ?: continue
            val open = source.indexOf('{', match.range.first)
            var depth = 0
            var quote: Char? = null
            var escaped = false
            for (i in open until source.length) {
                val c = source[i]
                if (quote != null) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = null
                    continue
                }
                if (c == '\'' || c == '"' || c == '`') { quote = c; continue }
                if (c == '{') depth++
                if (c == '}') {
                    depth--
                    if (depth == 0) return source.substring(match.range.first, i + 1)
                }
            }
            return source.substring(match.range.first, minOf(source.length, open + 12000))
        }
        return ""
    }

    private fun fetchExternalDeviceInitializer(host: String, pageHtml: String): String {
        val expected = host.toHttpUrlOrNull() ?: return ""
        val results = mutableListOf<String>()
        val sources = extractScriptSources(pageHtml).distinct()
        if (sources.isEmpty()) return "لم يتم العثور على وسوم script src في HTML الخاص بالصفحات/الإطار الرئيسي."
        for (src in sources) {
            val url = expected.resolve(src) ?: continue
            if (url.host != expected.host) continue // never send router cookies off-device
            val (code, body) = try { get(url.toString()) } catch (e: Exception) { 0 to "ERROR: ${e.message}" }
            val definition = functionBody(body, "initial_QueryAccessDeviceLink")
            if (definition.isNotBlank()) {
                results.add("SOURCE: ${url.encodedPath} — HTTP $code, ${body.length} chars\n$definition")
            } else {
                val marker = body.indexOf("initial_QueryAccessDeviceLink")
                if (marker >= 0) {
                    val from = (marker - 2500).coerceAtLeast(0)
                    val to = (marker + 9000).coerceAtMost(body.length)
                    results.add("SOURCE (context): ${url.encodedPath} — HTTP $code, ${body.length} chars\n${body.substring(from, to)}")
                } else {
                    results.add("JS checked: ${url.encodedPath} — HTTP $code, ${body.length} chars, no function marker")
                }
            }
        }
        return results.joinToString("\n\n").take(12000)
    }

    /**
     * Try a URL fragment copied from the router page itself. Some ZTE pages
     * build data requests with extra query parameters (SSID, InstanceID, etc.);
     * reducing those requests to only `_tag=<script>` can change the server
     * context and produce SessionTimeout on otherwise valid sessions.
     */
    private fun fetchDeviceUrl(host: String, fragment: String, pageToken: String?): String {
        val raw = fragment.trim().replace("\\/", "/")
        val pathAndQuery = when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> {
                val parsed = raw.toHttpUrlOrNull() ?: return ""
                val expected = host.toHttpUrlOrNull() ?: return ""
                if (parsed.host != expected.host) return "" // never send router cookies off-device
                parsed.encodedPath + if (parsed.encodedQuery != null) "?${parsed.encodedQuery}" else ""
            }
            raw.startsWith("?") -> "/$raw"
            raw.startsWith("/") -> raw
            raw.contains("?") -> "/$raw"
            else -> "/?_type=menuData&_tag=${raw.substringAfterLast('/') }"
        }
        val withGuid = if (Regex("(?:^|[?&])_=").containsMatchIn(pathAndQuery)) pathAndQuery
            else pathAndQuery + (if (pathAndQuery.contains('?')) "&" else "?") + "_=${nextGuid()}"
        val variants = mutableListOf(withGuid)
        pageToken?.let { variants.add(withGuid + (if (withGuid.contains('?')) "&" else "?") + "_sessionTOKEN=$it") }
        sessToken?.let { variants.add(withGuid + (if (withGuid.contains('?')) "&" else "?") + "_sessionTOKEN=$it") }
        var last = ""
        for (path in variants.distinct()) {
            val body = try { get(host.trimEnd('/') + path).second } catch (_: Exception) { "" }
            last = body
            if (body.isNotBlank() && !body.contains("SessionTimeout")) return body
        }
        return last
    }

    /**
     * H288A device-list endpoints are also exposed through the router's anonymous
     * SID_HTTPS_=ANON_COOKIE session (as used by zte_tracker). Keep this cookie jar
     * separate from the authenticated parental-control session: logging in replaces
     * the anonymous SID on some firmware and can make device reads return SessionTimeout.
     */
    private val anonymousCookies = mutableListOf<Cookie>()
    private val anonymousCookieJar = object : CookieJar {
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
            synchronized(anonymousCookies) {
                for (cookie in cookies) {
                    anonymousCookies.removeAll { it.name == cookie.name }
                    anonymousCookies.add(cookie)
                }
            }
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> =
            synchronized(anonymousCookies) { anonymousCookies.toList() }
    }
    private val anonymousHttp = OkHttpClient.Builder()
        .cookieJar(anonymousCookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun anonymousGet(url: String, host: String): Pair<Int, String> {
        val builder = Request.Builder().url(url)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
            .header("Referer", "$host/")
            // H288A firmware accepts the public device-data session only when the
            // anonymous SID is sent explicitly; it does not always Set-Cookie it.
            .header("Cookie", "SID_HTTPS_=ANON_COOKIE")
        anonymousHttp.newCall(builder.get().build()).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
        }
    }

    private fun fetchAnonymousDevices(host: String): DeviceFetch? {
        return try {
            // Establish the anonymous router session before querying menu data.
            anonymousGet("$host/", host)
            val (viewCode, viewBody) = anonymousGet(
                "$host/?_type=menuView&_tag=localNetStatus&_=${nextGuid()}", host
            )
            val tags = extractLuaTags(viewBody)
            val urls = extractLuaUrls(viewBody)
            val found = linkedMapOf<String, RouterDevice>()
            val snippets = mutableListOf<String>()
            for (script in listOf("accessdev_landevs_lua.lua", "accessdev_ssiddev_lua.lua")) {
                val (_, body) = anonymousGet(
                    "$host/?_type=menuData&_tag=$script&_=${nextGuid()}", host
                )
                snippets.add("$script: ${body.take(500)}")
                if (body.contains("SessionTimeout")) continue
                val type = if (script.contains("lande")) "LAN" else "WLAN"
                // H288A returns different XML container names for LAN and WLAN.
                val parsed = if (type == "LAN") {
                    parseAccessDevices(body, "OBJ_ACCESSDEV_ID", type) +
                        parseAccessDevices(body, "OBJ_LAN_DEVICE_ID", type)
                } else {
                    parseAccessDevices(body, "OBJ_WLAN_AD_ID", type) +
                        parseAccessDevices(body, "OBJ_ACCESSDEV_ID", type)
                }
                for (device in parsed) found.putIfAbsent(device.mac, device)
            }
            if (found.isEmpty()) null else DeviceFetch(
                devices = found.values.sortedBy { it.name.lowercase(Locale.US) },
                debugTags = tags,
                debugSnippet = snippets.joinToString("\n").take(1200),
                debugViewStatus = "anonymous localNetStatus: HTTP $viewCode — HTML",
                debugDeviceUrls = urls,
                debugDeviceInitializer = "تم جلب الأجهزة عبر جلسة الراوتر المجهولة (ANON_COOKIE) بدون تسجيل دخول جديد."
            )
        } catch (_: Exception) {
            null // Fall back to the authenticated flow and preserve its diagnostics.
        }
    }

    /** Live WLAN + LAN devices. Try the router's anonymous tracker session first, then authenticated fallback. */
    suspend fun getDevices(settings: RouterSettings): DeviceFetch = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val host = base(settings)
                fetchAnonymousDevices(host)?.let { return@withContext it }
                if (!loggedIn) {
                    val login = loginUnLocked(settings)
                    if (login is RouterResult.Failure) return@withContext DeviceFetch(emptyList(), login.message)
                }
                refererBase = host
                var attempt = 0
                var result: DeviceFetch? = null
                while (result == null) {
                    // Context setup for the local-network view (zte_tracker: menuView localNetStatus).
                    // The view HTML itself names the data scripts this firmware
                    // uses — read them out instead of only guessing names (v1.2).
                    // The parental page also initializes a device query
                    // (initial_QueryAccessDeviceLink), so its HTML may name the
                    // device script too — read both pages (v1.4).
                    // v1.6: localNetStatus may not exist on GR5.1T16 — if it
                    // answers SessionTimeout, try other view tags until one
                    // returns real HTML; the view must load before its data
                    // scripts answer (same pattern as the parental page).
                    var viewTags: List<String> = emptyList()
                    var deviceUrls: List<String> = emptyList()
                    var lastWlanBody = ""
                    var viewStatus = ""
                    var deviceViewBody = ""
                    var pcViewBody = ""
                    var deviceInitializer = ""
                    val viewCandidates = listOf("localNetStatus", "status", "home", "wlanStatus", "lanStatus")
                    for (viewTag in viewCandidates) {
                        try {
                            val (viewCode, viewBody) = get("$host/?_type=menuView&_tag=$viewTag&_=${System.currentTimeMillis()}")
                            if (viewBody.contains("SessionTimeout")) {
                                viewStatus = "$viewTag: SessionTimeout"
                                continue
                            }
                            deviceViewBody = viewBody
                            viewTags = extractLuaTags(viewBody)
                            deviceUrls = extractLuaUrls(viewBody)
                            deviceInitializer = extractDeviceInitializer(viewBody)
                            viewStatus = "$viewTag: HTTP $viewCode — HTML، لقينا ${viewTags.size} عنوان"
                            break
                        } catch (e: Exception) {
                            viewStatus = "$viewTag: مفيش رد (${e.message ?: "خطأ غير معروف"})"
                        }
                    }
                    try {
                        val (_, pcBody) = get("$host/?_type=menuView&_tag=parentCtrl&Menu3Location=0&_=${System.currentTimeMillis()}")
                        pcViewBody = pcBody
                        viewTags = (viewTags + extractLuaTags(pcBody)).distinct()
                        deviceUrls = (deviceUrls + extractLuaUrls(pcBody)).distinct()
                        if (deviceInitializer.isBlank()) deviceInitializer = extractDeviceInitializer(pcBody)
                    } catch (e: Exception) {
                        // Non-fatal.
                    }
                    // The inline HTML contains only the call. Fetch referenced same-origin JS
                    // and append the actual function definition for the diagnostic report.
                    if (deviceInitializer.isNotBlank() && !deviceInitializer.contains("function initial_QueryAccessDeviceLink")) {
                        val externalDefinition = fetchExternalDeviceInitializer(host, rootPageHtml + deviceViewBody + pcViewBody)
                        if (externalDefinition.isNotBlank()) deviceInitializer += "\n\n--- تعريف الدالة من ملفات JavaScript الخارجية ---\n" + externalDefinition
                    }
                    // Page token from the device view (or the parental view):
                    // device reads may need it instead of the login token (v1.7).
                    val pageToken = extractPageToken(deviceViewBody) ?: extractPageToken(pcViewBody)
                    viewStatus += " — توكن الصفحة: " + if (pageToken.isNullOrBlank()) "مش موجود" else "موجود"
                    val found = linkedMapOf<String, RouterDevice>()
                    var anyTimeout = false
                    // First replay the complete URL fragments found in the page's
                    // own JavaScript, preserving every firmware-specific parameter.
                    // This is intentionally before guessed script-only requests.
                    for (urlFragment in deviceUrls) {
                        val body = fetchDeviceUrl(host, urlFragment, pageToken)
                        if (body.contains("SessionTimeout")) anyTimeout = true
                        if (body.isBlank() || body.contains("SessionTimeout")) continue
                        val parsed = parseAccessDevices(body, "OBJ_ACCESSDEV_ID", "WLAN") +
                            parseAccessDevices(body, "OBJ_WLAN_AD_ID", "WLAN")
                        for (d in parsed) found.putIfAbsent(d.mac, d)
                    }
                    // WLAN — H288A family script first, then any script the view named.
                    // v1.7: sweep every request shape per script (no token / page
                    // token / login token / POST) instead of one shape per release.
                    val wlanCandidates = (listOf("accessdev_ssiddev_lua.lua") + viewTags).distinct()
                    for (script in wlanCandidates) {
                        val body = fetchDeviceScript(host, script, pageToken)
                        lastWlanBody = body
                        if (body.contains("SessionTimeout")) {
                            anyTimeout = true
                            continue
                        }
                        val parsed = parseAccessDevices(body, "OBJ_ACCESSDEV_ID", "WLAN")
                        if (parsed.isNotEmpty()) {
                            for (d in parsed) found.putIfAbsent(d.mac, d)
                            break
                        }
                    }
                    // LAN — same element id on the H288A family.
                    val lanCandidates = (listOf("accessdev_landevs_lua.lua") + viewTags).distinct()
                    for (script in lanCandidates) {
                        val body = fetchDeviceScript(host, script, pageToken)
                        if (body.contains("SessionTimeout")) {
                            anyTimeout = true
                            continue
                        }
                        val parsed = parseAccessDevices(body, "OBJ_ACCESSDEV_ID", "LAN")
                        if (parsed.isNotEmpty()) {
                            for (d in parsed) found.putIfAbsent(d.mac, d)
                            break
                        }
                    }
                    // Fallback: F6640-family WLAN script, only if nothing came back.
                    if (found.isEmpty()) {
                        val fbBody = fetchDeviceScript(host, "wlan_client_stat_lua.lua", pageToken)
                        lastWlanBody = fbBody
                        if (fbBody.contains("SessionTimeout")) anyTimeout = true
                        for (d in parseAccessDevices(fbBody, "OBJ_WLAN_AD_ID", "WLAN")) {
                            found.putIfAbsent(d.mac, d)
                        }
                    }
                    if (found.isNotEmpty()) {
                        result = DeviceFetch(
                            found.values.sortedBy { it.name.lowercase(Locale.US) },
                            null,
                            viewTags,
                            lastWlanBody.take(300),
                            viewStatus,
                            deviceUrls,
                            deviceInitializer
                        )
                        break
                    }
                    // Empty result: was the session evicted? An evicted session
                    // fails as a SessionTimeout router error, not an HTTP error
                    // (zte_tracker #75) — re-login once and retry the fetch.
                    val timedOut = anyTimeout
                    if (timedOut && attempt == 0) {
                        attempt++
                        val relogin = loginUnLocked(settings)
                        if (relogin is RouterResult.Failure) {
                            result = DeviceFetch(emptyList(), relogin.message)
                            break
                        }
                        continue
                    }
                    if (timedOut) {
                        result = DeviceFetch(
                            emptyList(),
                            "الروتر رفض الجلسة (SessionTimeout) — لو صفحة الروتر مفتوحة في المتصفح، اقفلها وحاول تاني من التطبيق",
                            viewTags,
                            lastWlanBody.take(300),
                            viewStatus,
                            deviceUrls,
                            deviceInitializer
                        )
                        break
                    }
                    result = DeviceFetch(
                        emptyList(),
                        "الروتر رد بس من غير أجهزة — ممكن مفيش جهاز متصل دلوقتي، أو سوفت الروتر رد بشكل مختلف عن المتوقع (شوف صفحة التشخيص)",
                        viewTags,
                        lastWlanBody.take(300),
                        viewStatus,
                        deviceUrls,
                        deviceInitializer
                    )
                }
                result ?: DeviceFetch(emptyList(), "فشل جلب الأجهزة: نتيجة غير متوقعة")
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
            rootPageHtml = try { get("$host/").second } catch (_: Exception) { "" }
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
