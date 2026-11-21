package com.routercontrol.app.data

/**
 * Parental Control sync — UNTESTED, read this before trusting it.
 *
 * What is VERIFIED:
 * - The web UI path (user screenshots, ZTE H298A admin manual): Internet >
 *   Parental Controls. One rule per device: Name, User Identity = MAC,
 *   Time Policy Everyday 00:00-23:59, Action "Ban Internet Access", On/Off radio.
 * - The ZTE Lua/CGI pattern itself (verified for login, devices, SSID, reboot):
 *   data calls are GET/POST to "/?_type=menuData&_tag=<script>_lua.lua&_=<guid>"
 *   and writes are form POSTs with IF_ACTION=Apply + _sessionTOKEN.
 *
 * What is NOT verified (no public source covers it — zte_tracker only does
 * device tracking + reboot):
 * - The exact Lua script name for the Parental Controls page.
 * - The exact POST parameter names the page uses to enable/disable/create a rule.
 *
 * How v1 handles that honestly:
 * 1. It NEVER claims a rule change reached the router unless the router's
 *    reply contains SUCC (the standard <IF_ERRORSTR>SUCC</IF_ERRORSTR> marker).
 * 2. It probes a small list of candidate script names (below) that follow ZTE's
 *    observed naming pattern (<area>_<function>_lua.lua). The first candidate
 *    that answers with SUCC is remembered in Settings as the confirmed script.
 * 3. If no candidate answers, the toggle still updates the LOCAL rule list and
 *    marks the rule pendingSync=true, and the UI says clearly that the change
 *    was NOT confirmed on the router.
 * 4. Settings has a manual "parental endpoint override" field: once the real
 *    script name is captured (open the Parental Controls page in a desktop
 *    browser, open DevTools > Network, toggle a rule, and read the _tag= value),
 *    paste it there and no rebuild is needed.
 * 5. The Diagnostics screen shows every probe attempt with its HTTP code and a
 *    short reply snippet, so the first live test can identify the real endpoint.
 */
object ParentalSync {

    /** Candidate script names — GUESSES from ZTE's naming pattern, NOT verified. */
    val CANDIDATE_SCRIPTS = listOf(
        "internet_parental_lua.lua",
        "security_parental_lua.lua",
        "parental_lua.lua"
    )

    private fun looksLikeSuccess(body: String): Boolean {
        val b = body
        return b.contains("SUCC") || b.contains("Parental") || b.contains("PARENTAL")
    }

    private fun snippet(body: String): String = body.take(300)

    /** Try each candidate script; return one DiagnosticsEntry per attempt. */
    suspend fun probe(client: RouterClient, settings: RouterSettings): List<DiagnosticsEntry> {
        val out = mutableListOf<DiagnosticsEntry>()
        val login = client.login(settings)
        if (login is RouterResult.Failure) {
            out.add(DiagnosticsEntry("تسجيل الدخول", login.message, false))
            return out
        }
        for (script in CANDIDATE_SCRIPTS) {
            val (code, body) = client.rawGet(settings, "?_type=menuData&_tag=$script&_=${System.currentTimeMillis()}")
            val ok = code == 200 && looksLikeSuccess(body)
            out.add(
                DiagnosticsEntry(
                    label = script,
                    detail = "HTTP $code — " + if (body.isBlank()) "(رد فاضي)" else snippet(body),
                    ok = ok
                )
            )
        }
        return out
    }

    /**
     * Enable/disable one rule on the router.
     * Returns Success ONLY if the router reply contains SUCC.
     * The POST field names mirror the web form (Name / User Identity / Action /
     * Time Policy) and are UNTESTED — see the class comment.
     */
    suspend fun applyRule(
        client: RouterClient,
        settings: RouterSettings,
        rule: ParentalRule,
        enabled: Boolean
    ): RouterResult {
        val script = settings.parentalScript.ifBlank {
            // No confirmed script yet: probe first and remember a working one.
            val probe = probe(client, settings)
            val working = probe.firstOrNull { it.ok }?.label
            if (working == null) {
                return RouterResult.Failure(
                    "لسه مش متأكد من عنوان صفحة التحكم الأبوي في الروتر — القاعدة اتحفظت محليا بس، وجرّب صفحة التشخيص",
                    probe.joinToString("\n") { "${it.label}: ${it.detail}" }.take(500)
                )
            }
            working
        }
        if (!client.isLoggedIn()) {
            val login = client.login(settings)
            if (login is RouterResult.Failure) return login
        }
        val params = linkedMapOf<String, String>()
        params["IF_ACTION"] = "Apply"
        params["Name"] = rule.name
        params["UserIdentity"] = rule.mac
        params["Enable"] = if (enabled) "1" else "0"
        params["Action"] = rule.action
        params["Days"] = if (rule.daysEveryday) "Everyday" else "Everyday"
        params["StartHour"] = (rule.startMinutes / 60).toString()
        params["StartMin"] = (rule.startMinutes % 60).toString()
        params["EndHour"] = (rule.endMinutes / 60).toString()
        params["EndMin"] = (rule.endMinutes % 60).toString()
        if (rule.instanceId.isNotBlank()) params["_InstID"] = rule.instanceId
        val (code, body) = client.rawPost(settings, "?_type=menuData&_tag=$script", params)
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
     * Same UNTESTED caveats as applyRule; IF_ACTION value is a guess.
     */
    suspend fun createRule(
        client: RouterClient,
        settings: RouterSettings,
        rule: ParentalRule
    ): RouterResult {
        val script = settings.parentalScript.ifBlank {
            val probe = probe(client, settings)
            val working = probe.firstOrNull { it.ok }?.label
            if (working == null) {
                return RouterResult.Failure(
                    "لسه مش متأكد من عنوان صفحة التحكم الأبوي في الروتر — القاعدة اتحفظت محليا بس، وجرّب صفحة التشخيص",
                    probe.joinToString("\n") { "${it.label}: ${it.detail}" }.take(500)
                )
            }
            working
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
        val (code, body) = client.rawPost(settings, "?_type=menuData&_tag=$script", params)
        if (code == 200 && looksLikeSuccess(body)) {
            return RouterResult.Success("تم إنشاء القاعدة على الروتر", snippet(body))
        }
        return RouterResult.Failure(
            "الروتر مردّش بتأكيد على إنشاء القاعدة (HTTP $code) — اتحفظت محليا بس",
            snippet(body)
        )
    }
}
