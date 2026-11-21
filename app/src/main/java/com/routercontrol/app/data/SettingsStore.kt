package com.routercontrol.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "router_control")

/**
 * All app settings live ONLY on this phone, in DataStore.
 * The router admin password and the app PIN hash never leave the phone,
 * are never sent anywhere except to the router itself (login), and are
 * never included in the repository.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("router_host")
        val USERNAME = stringPreferencesKey("router_username")
        val PASSWORD = stringPreferencesKey("router_password")
        val USE_HTTPS = booleanPreferencesKey("router_use_https")
        val PARENTAL_SCRIPT = stringPreferencesKey("parental_script")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SET = booleanPreferencesKey("pin_set")
        val RULES_JSON = stringPreferencesKey("rules_json")
        val SCHEDULES_JSON = stringPreferencesKey("schedules_json")
        val DEVICE_NAMES_JSON = stringPreferencesKey("device_names_json")
    }

    suspend fun loadSettings(): RouterSettings {
        val p = context.dataStore.data.first()
        return RouterSettings(
            host = p[Keys.HOST] ?: "192.168.1.1",
            username = p[Keys.USERNAME] ?: "admin",
            password = p[Keys.PASSWORD] ?: "",
            useHttps = p[Keys.USE_HTTPS] ?: false,
            parentalScript = p[Keys.PARENTAL_SCRIPT] ?: ""
        )
    }

    suspend fun saveSettings(s: RouterSettings) {
        context.dataStore.edit { p ->
            p[Keys.HOST] = s.host
            p[Keys.USERNAME] = s.username
            p[Keys.PASSWORD] = s.password
            p[Keys.USE_HTTPS] = s.useHttps
            p[Keys.PARENTAL_SCRIPT] = s.parentalScript
        }
    }

    suspend fun isPinSet(): Boolean = context.dataStore.data.map { it[Keys.PIN_SET] ?: false }.first()

    /** PIN is stored only as a salted SHA-256 hash, never in plain text. */
    suspend fun setPin(pin: String) {
        context.dataStore.edit { p ->
            p[Keys.PIN_HASH] = sha256Hex("routercontrol-pin:$pin")
            p[Keys.PIN_SET] = true
        }
    }

    suspend fun checkPin(pin: String): Boolean {
        val p = context.dataStore.data.first()
        val stored = p[Keys.PIN_HASH] ?: return false
        return stored == sha256Hex("routercontrol-pin:$pin")
    }

    suspend fun clearPin() {
        context.dataStore.edit { p ->
            p.remove(Keys.PIN_HASH)
            p[Keys.PIN_SET] = false
        }
    }

    // ---- Parental rules (local mirror of the router's 6 rules) ----

    fun defaultRules(): List<ParentalRule> = listOf(
        ParentalRule(name = "Ahmed", mac = "", enabled = false),
        ParentalRule(name = "Rasha", mac = "", enabled = false),
        ParentalRule(name = "Desktop", mac = "", enabled = true),
        ParentalRule(name = "My TV", mac = "", enabled = false),
        ParentalRule(name = "Tablet", mac = "", enabled = true),
        ParentalRule(name = "Hyper", mac = "", enabled = true)
    )

    suspend fun loadRules(): List<ParentalRule> {
        val p = context.dataStore.data.first()
        val raw = p[Keys.RULES_JSON] ?: return defaultRules()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<ParentalRule>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    ParentalRule(
                        name = o.optString("name", ""),
                        mac = o.optString("mac", ""),
                        enabled = o.optBoolean("enabled", false),
                        daysEveryday = o.optBoolean("daysEveryday", true),
                        startMinutes = o.optInt("startMinutes", 0),
                        endMinutes = o.optInt("endMinutes", 23 * 60 + 59),
                        action = o.optString("action", "Ban Internet Access"),
                        pendingSync = o.optBoolean("pendingSync", false),
                        instanceId = o.optString("instanceId", "")
                    )
                )
            }
            if (out.isEmpty()) defaultRules() else out
        } catch (e: Exception) {
            defaultRules()
        }
    }

    suspend fun saveRules(rules: List<ParentalRule>) {
        val arr = JSONArray()
        for (r in rules) {
            val o = JSONObject()
            o.put("name", r.name)
            o.put("mac", r.mac)
            o.put("enabled", r.enabled)
            o.put("daysEveryday", r.daysEveryday)
            o.put("startMinutes", r.startMinutes)
            o.put("endMinutes", r.endMinutes)
            o.put("action", r.action)
            o.put("pendingSync", r.pendingSync)
            o.put("instanceId", r.instanceId)
            arr.put(o)
        }
        context.dataStore.edit { p -> p[Keys.RULES_JSON] = arr.toString() }
    }

    // ---- Schedules ----

    suspend fun loadSchedules(): List<RuleSchedule> {
        val p = context.dataStore.data.first()
        val raw = p[Keys.SCHEDULES_JSON] ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<RuleSchedule>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    RuleSchedule(
                        ruleName = o.optString("ruleName", ""),
                        enabled = o.optBoolean("enabled", false),
                        startMinutes = o.optInt("startMinutes", 23 * 60),
                        endMinutes = o.optInt("endMinutes", 7 * 60),
                        daysMask = o.optInt("daysMask", 0x7F)
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveSchedules(list: List<RuleSchedule>) {
        val arr = JSONArray()
        for (s in list) {
            val o = JSONObject()
            o.put("ruleName", s.ruleName)
            o.put("enabled", s.enabled)
            o.put("startMinutes", s.startMinutes)
            o.put("endMinutes", s.endMinutes)
            o.put("daysMask", s.daysMask)
            arr.put(o)
        }
        context.dataStore.edit { p -> p[Keys.SCHEDULES_JSON] = arr.toString() }
    }

    // ---- Friendly device names (MAC -> name) ----

    suspend fun loadDeviceNames(): Map<String, String> {
        val p = context.dataStore.data.first()
        val raw = p[Keys.DEVICE_NAMES_JSON] ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            val out = mutableMapOf<String, String>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.optString(k, "")
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun saveDeviceNames(map: Map<String, String>) {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        context.dataStore.edit { p -> p[Keys.DEVICE_NAMES_JSON] = o.toString() }
    }
}

fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
