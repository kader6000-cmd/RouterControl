package com.routercontrol.app.data

import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parental Control sync — v1.1.
 *
 * What the v1.0 live test (2026-11-21) proved:
 * - Login works and the router answers, BUT every data request came back with
 *   <IF_ERRORSTR>SessionTimeout</IF_ERRORSTR> — and v1.0's success check
 *   (body.contains("SUCC")) wrongly "confirmed" a script because the
 *   SessionTimeout reply also carries <IF_ERRORPARAM>SUCC</IF_ERRORPARAM>.
 *   Success is now checked STRICTLY on <IF_ERRORSTR>SUCC</IF_ERRORSTR>.
 * - A SessionTimeout reply does NOT always mean a dead session: zte_tracker's
 *   own notes (H3640) say a missing _tag answers SessionTimeout too, and a
 *   re-login cannot fix a missing tag. So the probe must distinguish
 *   "tag missing" from "session evicted".
 *
 * How v1.1 discovers the real endpoint (pattern verified in zte_tracker's
 * F680 parental-control support — get_parental_controls /
 * set_parental_control_enabled):
 * 1. Open the parental view the way the web UI does:
 *    GET /?_type=menuView&_tag=parentCtrl&Menu3Location=0 — the returned HTML
 *    embeds _sessionTmpToken, the token a write must carry.
 * 2. GET the data script /?_type=menuData&_tag=<script> and parse the
 *    <Instance> elements: _InstID, Name, Enable, UserIdentity (MAC).
 * 3. Toggle by instance: POST IF_ACTION=Apply, Enable=1/0, _InstID=<id>,
 *    _sessionTOKEN=<page token>, with a Check header (SHA256 of the body,
 *    RSA-encrypted with ZTE's 2048-bit public key; plain SHA256 hex and
 *    no-Check are tried as fallbacks).
 *
 * The data script name for H288A is still unconfirmed — candidates are tried
 * in order, starting with firewall_parentctrl_lua.lua (the F680 one).
 * Nothing is reported as "confirmed on the router" unless the router's
 * <IF_ERRORSTR> is SUCC/SUCCESS/OK.
 */
object ParentalSync {

    /** Data script candidates — firewall_parentctrl_lua.lua is the verified F680 one. */
    val CANDIDATE_SCRIPTS = listOf(
        "firewall_parentctrl_lua.lua",
        "internet_parental_lua.lua",
        "security_parental_lua.lua",
        "parental_lua.lua"
    )

    private const val VIEW_TAG = "parentCtrl&Menu3Location=0"

    /** ZTE's 2048-bit RSA public key for the Check header (from zte_tracker). */
    private const val ZTE_PUBLIC_KEY_PEM =
        "-----BEGIN PUBLIC KEY-----\n" +
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAodPTerkUVCYmv28SOfRV\n" +
        "7UKHVujx/HjCUTAWy9l0L5H0JV0LfDudTdMNPEKloZsNam3YrtEnq6jqMLJV4ASb\n" +
        "1d6axmIgJ636wyTUS99gj4BKs6bQSTUSE8h/QkUYv4gEIt3saMS0pZpd90y6+B/9\n" +
        "hZxZE/RKU8e+zgRqp1/762TB7vcjtjOwXRDEL0w71Jk9i8VUQ59MR1Uj5E8X3WIc\n" +
        "fYSK5RWBkMhfaTRM6ozS9Bqhi40xlSOb3GBxCmliCifOJNLoO9kFoWgAIw5hkSIb\n" +
        "GH+4Csop9Uy8VvmmB+B3ubFLN35qIa5OG5+SDXn4L7FeAA5lRiGxRi8tsWrtew8w\n" +
        "nwIDAQAB\n" +
        "-----END PUBLIC KEY-----"

    data class FetchedRules(
        val rules: List<ParentalRule>,
        val tag: String,
        val pageToken: String?,
        val entries: List<DiagnosticsEntry>
    )

    /** Strict success: the router's own error string must be SUCC, and no SessionTimeout. */
    fun looksLikeSuccess(body: String): Boolean {
        if (body.contains("SessionTimeout")) return false
        return body.contains("<IF_ERRORSTR>SUCC</IF_ERRORSTR>") ||
            body.contains("<IF_ERRORSTR>SUCCESS</IF_ERRORSTR>") ||
            body.contains("<IF_ERRORSTR>OK</IF_ERRORSTR>")
    }

    fun hasSessionTimeout(body: String): Boolean = body.contains("SessionTimeout")

    private fun snippet(body: String): String = body.take(300)

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Check header: SHA256 hex of the urlencoded body, RSA-encrypted (ZTE 2048-bit key). */
    private fun checkHeaderRsa(postData: String): String? {
        return try {
            val digestHex = sha256Hex(postData)
            val pem = ZTE_PUBLIC_KEY_PEM
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
            val keyBytes = Base64.decode(pem, Base64.DEFAULT)
            val key = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(keyBytes))
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(digestHex.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractPageToken(viewHtml: String): String? {
        val m = Regex("_sessionTmpToken\\s*=\\s*[\"']([^\"']+)[\"']").find(viewHtml) ?: return null
        val raw = m.groupValues[1]
        // The page embeds the token with \xNN escapes — decode them.
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

    private fun parseInstances(xml: String): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        if (xml.isBlank()) return out
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val doc = factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
            val nodes = doc.getElementsByTagName("Instance")
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val fields = mutableMapOf<String, String>()
                val children = el.childNodes
                // Two shapes exist. F680 style: direct fields (<Name>Ahmed</Name>).
                // H288A GR5.1T16 style (seen live 2026-11-21): name/value pairs
                // (<ParaName>_InstID</ParaName><ParaValue>DEV.PCUser1</ParaValue>).
                var pendingName: String? = null
                for (j in 0 until children.length) {
                    val child = children.item(j) as? Element ?: continue
                    if (child.tagName == "ParaName") {
                        pendingName = child.textContent?.trim()
                    } else if (child.tagName == "ParaValue" && pendingName != null) {
                        fields[pendingName] = child.textContent?.trim() ?: ""
                        pendingName = null
                    } else {
                        fields[child.tagName] = child.textContent?.trim() ?: ""
                    }
                }
                if (fields.isNotEmpty()) out.add(fields)
            }
        } catch (e: Exception) {
            // Malformed XML: return whatever was parsed so far.
        }
        return out
    }

    private fun fieldsToRule(fields: Map<String, String>): ParentalRule? {
        val instId = fields["_InstID"] ?: fields["InstID"] ?: return null
        val enable = fields["Enable"] ?: fields["Enabled"] ?: "0"
        val enabled = enable.trim().lowercase() in setOf("1", "true", "yes", "on")
        val name = (fields["Name"] ?: fields["UserName"] ?: fields["PCName"] ?: fields["Alias"] ?: instId)
            .trim().ifBlank { instId }
        val mac = (fields["UserIdentity"] ?: fields["MACAddress"] ?: fields["Mac"] ?: fields["DeviceMac"] ?: "")
            .trim().uppercase()
        return ParentalRule(
            name = name,
            mac = mac,
            enabled = enabled,
            instanceId = instId.trim()
        )
    }

    /**
     * Discover the parental-control data script the way the web UI does:
     * open the view, then try each data script and parse real <Instance> rules.
     * Returns the rules found (empty if none), the working tag, the page token,
     * and one DiagnosticsEntry per attempt.
     */
    suspend fun fetchRules(client: RouterClient, settings: RouterSettings): FetchedRules {
        val entries = mutableListOf<DiagnosticsEntry>()
        if (!client.isLoggedIn()) {
            val login = client.login(settings)
            if (login is RouterResult.Failure) {
                entries.add(DiagnosticsEntry("تسجيل الدخول", login.message, false))
                return FetchedRules(emptyList(), "", null, entries)
            }
        }
        // Device fetch sanity check — shows whether data requests work at all.
        val devices = client.getDevices(settings)
        entries.add(
            DiagnosticsEntry(
                "جلب الأجهزة (مين أونلاين)",
                if (devices.error == null) "تم — ${devices.devices.size} جهاز" else devices.error,
                devices.error == null
            )
        )
        // What the router's own device pages name, and what the last WLAN
        // data reply actually said — the evidence for the right device tag (v1.4).
        entries.add(
            DiagnosticsEntry(
                "عناوين الأجهزة من صفحة الروتر",
                if (devices.debugTags.isEmpty()) "مفيش أسماء سكربتات ظاهرة في صفحة الأجهزة"
                else devices.debugTags.joinToString("، "),
                devices.debugTags.isNotEmpty()
            )
        )
        entries.add(
            DiagnosticsEntry(
                "رد الأجهزة الخام",
                if (devices.debugSnippet.isBlank()) "(مفيش رد)" else devices.debugSnippet,
                devices.error == null
            )
        )
        entries.add(
            DiagnosticsEntry(
                "صفحة الأجهزة (localNetStatus)",
                if (devices.debugViewStatus.isBlank()) "(مفيش رد)" else devices.debugViewStatus,
                !devices.debugViewStatus.contains("SessionTimeout")
            )
        )
        entries.add(
            DiagnosticsEntry(
                "روابط الأجهزة من صفحة الروتر",
                if (devices.debugDeviceUrls.isEmpty()) "مفيش روابط سكربتات ظاهرة في صفحة الأجهزة"
                else devices.debugDeviceUrls.joinToString("، "),
                devices.debugDeviceUrls.isNotEmpty()
            )
        )
        entries.add(
            DiagnosticsEntry(
                "كود استدعاء الأجهزة من واجهة الراوتر",
                devices.debugDeviceInitializer.ifBlank { "الدالة initial_QueryAccessDeviceLink لم تظهر في HTML المحمّل" },
                devices.debugDeviceInitializer.isNotBlank()
            )
        )
        // Parental reads run on a FRESH admin session, retried once (v1.8.6).
        // The anonymous device handshake (v1.8.4) logs in again with the admin
        // account, and this router keeps a single admin session — so by the time
        // getDevices returns, the session these reads need is already evicted:
        // the parentCtrl view answers 404 and every data script SessionTimeout.
        // A fresh login right before the parental reads (and one retry) makes
        // the parental side self-healing. Only the last attempt's entries are
        // kept in the report, so the Diagnostics list does not duplicate.
        var foundRules: List<ParentalRule> = emptyList()
        var foundTag = ""
        var foundToken: String? = null
        var parentalEntries: List<DiagnosticsEntry> = emptyList()
        for (attempt in 0..1) {
            val refresh = client.login(settings)
            if (refresh is RouterResult.Failure) {
                parentalEntries = listOf(DiagnosticsEntry("تسجيل الدخول", refresh.message, false))
                break
            }
            val attemptEntries = mutableListOf<DiagnosticsEntry>()
            // 1. Open the parental view; the HTML embeds _sessionTmpToken.
            val (viewCode, viewHtml) = client.rawGet(
                settings,
                "?_type=menuView&_tag=$VIEW_TAG&_=${System.currentTimeMillis()}"
            )
            val attemptToken = extractPageToken(viewHtml)
            attemptEntries.add(
                DiagnosticsEntry(
                    "صفحة التحكم الأبوي (parentCtrl)",
                    "HTTP $viewCode — " + if (viewHtml.isBlank()) "(رد فاضي)" else
                        "توكن الصفحة: " + (if (attemptToken.isNullOrBlank()) "مش موجود" else "موجود") +
                        " — " + snippet(viewHtml),
                    viewCode == 200 && !hasSessionTimeout(viewHtml)
                )
            )
            // The parental view's own HTML names the data scripts this firmware
            // uses — read them out and try them alongside the public guesses (v1.2).
            val viewTags = client.extractLuaTags(viewHtml)
            val candidates = (CANDIDATE_SCRIPTS + viewTags).distinct()
            attemptEntries.add(
                DiagnosticsEntry(
                    "عناوين لقيناها في صفحة الروتر",
                    if (viewTags.isEmpty()) "مفيش أسماء سكربتات ظاهرة في الصفحة — هنجرّب العناوين المعروفة بس"
                    else viewTags.joinToString("، "),
                    viewTags.isNotEmpty()
                )
            )
            // 2. Try each data script.
            var attemptOk = false
            for (script in candidates) {
                val (code, body) = client.rawGet(
                    settings,
                    "?_type=menuData&_tag=$script&_=${System.currentTimeMillis()}"
                )
                val timeout = hasSessionTimeout(body)
                val ok = code == 200 && looksLikeSuccess(body)
                val instances = if (ok) parseInstances(body) else emptyList()
                val rules = instances.mapNotNull { fieldsToRule(it) }
                attemptEntries.add(
                    DiagnosticsEntry(
                        label = script,
                        detail = "HTTP $code — " +
                            (if (timeout) "SessionTimeout (العنوان مش موجود في السوفت، أو الجلسة اتقطعت) — " else "") +
                            (if (rules.isNotEmpty()) "القواعد: " + rules.joinToString("، ") {
                                it.name + if (it.enabled) " (شغالة)" else " (مقفولة)"
                            } + " — " else "") +
                            (if (body.isBlank()) "(رد فاضي)" else snippet(body)),
                        ok = ok && rules.isNotEmpty()
                    )
                )
                if (ok && rules.isNotEmpty()) {
                    foundRules = rules
                    foundTag = script
                    foundToken = attemptToken
                    attemptOk = true
                    break
                }
            }
            parentalEntries = attemptEntries
            if (attemptOk) break
        }
        entries.addAll(parentalEntries)
        return FetchedRules(foundRules, foundTag, foundToken, entries)
    }

    /** Backwards-compatible probe: discovery entries for the Diagnostics screen. */
    suspend fun probe(client: RouterClient, settings: RouterSettings): List<DiagnosticsEntry> {
        return fetchRules(client, settings).entries
    }

    private suspend fun postToggle(
        client: RouterClient,
        settings: RouterSettings,
        tag: String,
        params: LinkedHashMap<String, String>,
        pageToken: String?
    ): Pair<Int, String> {
        // Build the urlencoded body once — the Check header is computed over this exact string.
        val encoded = params.entries.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        val rsaCheck = checkHeaderRsa(encoded)
        val attempts = mutableListOf<Pair<String, String?>>()
        if (rsaCheck != null) attempts.add("rsa" to rsaCheck)
        attempts.add("plain" to sha256Hex(encoded))
        attempts.add("none" to null)
        var last: Pair<Int, String> = 0 to ""
        for ((mode, check) in attempts) {
            val result = client.rawPostChecked(
                settings,
                "?_type=menuData&_tag=$tag&_=${System.currentTimeMillis()}",
                encoded,
                check
            )
            last = result
            if (result.first == 200 && looksLikeSuccess(result.second)) {
                return result
            }
            // A SessionTimeout on a write means the session/token is wrong — retrying
            // other Check modes will not help; stop and report it.
            if (hasSessionTimeout(result.second)) break
            // Keep the last attempt's result for the error message; the loop tries the next mode.
            @Suppress("UNUSED_VARIABLE")
            val unusedMode = mode
        }
        return last
    }

    /**
     * Enable/disable one rule on the router.
     * Prefers the instance-based toggle (verified pattern): IF_ACTION=Apply,
     * Enable, _InstID, _sessionTOKEN from the parental page, Check header.
     * Falls back to the old name-based form only when the rule has no instance id.
     * Returns Success ONLY on a strict SUCC reply.
     */
    suspend fun applyRule(
        client: RouterClient,
        settings: RouterSettings,
        rule: ParentalRule,
        enabled: Boolean
    ): RouterResult {
        // Fresh admin session for the write (v1.8.6): the Who's Online anonymous
        // handshake may have evicted the session this write needs, so a toggle
        // posted on the stale session comes back HTTP 200 without SUCC.
        val fresh = client.login(settings)
        if (fresh is RouterResult.Failure) return fresh
        val tag: String
        var pageToken: String? = null
        if (settings.parentalScript.isNotBlank()) {
            tag = settings.parentalScript
            // Fresh page token for the write.
            val (_, viewHtml) = client.rawGet(
                settings,
                "?_type=menuView&_tag=$VIEW_TAG&_=${System.currentTimeMillis()}"
            )
            pageToken = extractPageToken(viewHtml)
        } else {
            val fetched = fetchRules(client, settings)
            if (fetched.tag.isBlank()) {
                return RouterResult.Failure(
                    "لسه مش متأكد من عنوان صفحة التحكم الأبوي في الروتر — القاعدة اتحفظت محليا بس، وجرّب صفحة التشخيص",
                    fetched.entries.joinToString("\n") { "${it.label}: ${it.detail}" }.take(500)
                )
            }
            tag = fetched.tag
            pageToken = fetched.pageToken
        }
        if (!client.isLoggedIn()) {
            val login = client.login(settings)
            if (login is RouterResult.Failure) return login
        }
        if (rule.instanceId.isNotBlank()) {
            val params = linkedMapOf(
                "IF_ACTION" to "Apply",
                "Enable" to if (enabled) "1" else "0",
                "_InstID" to rule.instanceId,
                "_sessionTOKEN" to (pageToken ?: client.currentSessionToken() ?: "")
            )
            val (code, body) = postToggle(client, settings, tag, params, pageToken)
            if (code == 200 && looksLikeSuccess(body)) {
                return RouterResult.Success("تم تطبيق القاعدة على الروتر", snippet(body))
            }
            return RouterResult.Failure(
                "الروتر مردّش بتأكيد على تغيير القاعدة (HTTP $code) — اتحفظت محليا بس",
                snippet(body)
            )
        }
        // No instance id (rule was never synced from the router): old name-based form.
        val params = linkedMapOf<String, String>()
        params["IF_ACTION"] = "Apply"
        params["Name"] = rule.name
        params["UserIdentity"] = rule.mac
        params["Enable"] = if (enabled) "1" else "0"
        params["Action"] = rule.action
        params["Days"] = "Everyday"
        params["StartHour"] = (rule.startMinutes / 60).toString()
        params["StartMin"] = (rule.startMinutes % 60).toString()
        params["EndHour"] = (rule.endMinutes / 60).toString()
        params["EndMin"] = (rule.endMinutes % 60).toString()
        val (code, body) = client.rawPost(settings, "?_type=menuData&_tag=$tag", params)
        if (code == 200 && looksLikeSuccess(body)) {
            return RouterResult.Success("تم تطبيق القاعدة على الروتر", snippet(body))
        }
        return RouterResult.Failure(
            "الروتر مردّش بتأكيد على تغيير القاعدة (HTTP $code) — اتحفظت محليا بس",
            snippet(body)
        )
    }

    /**
     * Create a new rule for a device that has none yet (Who's Online > Block).
     * Uses the discovered data tag; the Add form is still the web form's shape.
     */
    suspend fun createRule(
        client: RouterClient,
        settings: RouterSettings,
        rule: ParentalRule
    ): RouterResult {
        // Fresh admin session for the write (v1.8.6, same reason as applyRule).
        val fresh = client.login(settings)
        if (fresh is RouterResult.Failure) return fresh
        val tag = if (settings.parentalScript.isNotBlank()) {
            settings.parentalScript
        } else {
            val fetched = fetchRules(client, settings)
            if (fetched.tag.isBlank()) {
                return RouterResult.Failure(
                    "لسه مش متأكد من عنوان صفحة التحكم الأبوي في الروتر — القاعدة اتحفظت محليا بس، وجرّب صفحة التشخيص",
                    fetched.entries.joinToString("\n") { "${it.label}: ${it.detail}" }.take(500)
                )
            }
            fetched.tag
        }
        if (!client.isLoggedIn()) {
            val login = client.login(settings)
            if (login is RouterResult.Failure) return login
        }
        val params = linkedMapOf<String, String>()
        params["IF_ACTION"] = "Add"
        params["Name"] = rule.name
        params["UserIdentity"] = rule.mac
        params["Enable"] = "1"
        params["Action"] = rule.action
        params["Days"] = "Everyday"
        params["StartHour"] = "0"
        params["StartMin"] = "0"
        params["EndHour"] = "23"
        params["EndMin"] = "59"
        val (code, body) = client.rawPost(settings, "?_type=menuData&_tag=$tag", params)
        if (code == 200 && looksLikeSuccess(body)) {
            return RouterResult.Success("تم إنشاء القاعدة على الروتر", snippet(body))
        }
        return RouterResult.Failure(
            "الروتر مردّش بتأكيد على إنشاء القاعدة (HTTP $code) — اتحفظت محليا بس",
            snippet(body)
        )
    }
}
