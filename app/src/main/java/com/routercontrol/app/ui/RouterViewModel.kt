package com.routercontrol.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.routercontrol.app.data.DeviceFetch
import com.routercontrol.app.data.DiagnosticsEntry
import com.routercontrol.app.data.ParentalRule
import com.routercontrol.app.data.ParentalSync
import com.routercontrol.app.data.RouterClient
import com.routercontrol.app.data.RouterResult
import com.routercontrol.app.data.RouterSettings
import com.routercontrol.app.data.RuleSchedule
import com.routercontrol.app.data.ScheduleWorker
import com.routercontrol.app.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val loading: Boolean = false,
    val connected: Boolean = false,
    val message: String? = null,
    val rules: List<ParentalRule> = emptyList(),
    val devices: List<com.routercontrol.app.data.RouterDevice> = emptyList(),
    val schedules: List<RuleSchedule> = emptyList(),
    val diagnostics: List<DiagnosticsEntry> = emptyList(),
    val settings: RouterSettings = RouterSettings(),
    val pinSet: Boolean = false
)

class RouterViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val client = RouterClient()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = store.loadSettings()
            val rules = store.loadRules()
            val schedules = store.loadSchedules()
            val pinSet = store.isPinSet()
            _state.update { it.copy(settings = settings, rules = rules, schedules = schedules, pinSet = pinSet) }
            ScheduleWorker.enqueuePeriodic(app)
        }
    }

    fun setMessage(msg: String?) {
        _state.update { it.copy(message = msg) }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            val settings = store.loadSettings()
            val login = client.login(settings)
            if (login is RouterResult.Failure) {
                _state.update { it.copy(loading = false, connected = false, message = login.message) }
                return@launch
            }
            val fetch: DeviceFetch = client.getDevices(settings)
            _state.update {
                it.copy(
                    loading = false,
                    connected = true,
                    devices = fetch.devices,
                    message = fetch.error ?: "تم الاتصال بالروتر — ${fetch.devices.size} جهاز متصل"
                )
            }
        }
    }

    /** Toggle one rule. Local state updates immediately; router sync is attempted
     *  and the rule is flagged pendingSync if the router does not confirm (SUCC). */
    fun toggleRule(rule: ParentalRule) {
        viewModelScope.launch {
            val target = !rule.enabled
            val updated = _state.value.rules.map {
                if (it.name == rule.name) it.copy(enabled = target, pendingSync = true) else it
            }
            _state.update { it.copy(rules = updated) }
            store.saveRules(updated)
            val settings = store.loadSettings()
            val res = ParentalSync.applyRule(client, settings, rule, target)
            val final = _state.value.rules.map {
                if (it.name == rule.name) it.copy(pendingSync = res is RouterResult.Failure) else it
            }
            store.saveRules(final)
            _state.update {
                it.copy(
                    rules = final,
                    message = when (res) {
                        is RouterResult.Success -> "تم ${if (target) "حظر" else "فتح"} ${rule.name}"
                        is RouterResult.Failure -> res.message
                    }
                )
            }
        }
    }

    /** Block a device from Who's Online: toggle its rule, or create one if it has none. */
    fun blockDevice(device: com.routercontrol.app.data.RouterDevice) {
        viewModelScope.launch {
            val existing = _state.value.rules.firstOrNull { it.mac.equals(device.mac, ignoreCase = true) }
            val settings = store.loadSettings()
            if (existing != null) {
                toggleRule(existing)
                return@launch
            }
            val baseName = device.name.ifBlank { device.mac }
            var name = baseName
            var n = 2
            while (_state.value.rules.any { it.name == name }) {
                name = "$baseName $n"
                n++
            }
            val rule = ParentalRule(name = name, mac = device.mac.uppercase(), enabled = true)
            val updated = _state.value.rules + rule
            _state.update { it.copy(rules = updated) }
            store.saveRules(updated)
            val res = ParentalSync.createRule(client, settings, rule)
            val final = _state.value.rules.map {
                if (it.name == name) it.copy(pendingSync = res is RouterResult.Failure) else it
            }
            store.saveRules(final)
            _state.update {
                it.copy(
                    rules = final,
                    message = when (res) {
                        is RouterResult.Success -> "تم حظر $name"
                        is RouterResult.Failure -> res.message
                    }
                )
            }
        }
    }

    /** Link a rule to a device MAC (picked from Who's Online). */
    fun linkRuleToMac(ruleName: String, mac: String) {
        viewModelScope.launch {
            val updated = _state.value.rules.map {
                if (it.name == ruleName) it.copy(mac = mac.uppercase()) else it
            }
            store.saveRules(updated)
            _state.update { it.copy(rules = updated, message = "تم ربط $ruleName بالجهاز") }
        }
    }

    /** One-tap bedtime mode: enable (block) every rule that has a MAC linked. */
    fun blockAll() {
        viewModelScope.launch {
            val settings = store.loadSettings()
            var failed = 0
            val updated = _state.value.rules.map { rule ->
                if (!rule.enabled) {
                    val res = ParentalSync.applyRule(client, settings, rule, true)
                    if (res is RouterResult.Failure) failed++
                    rule.copy(enabled = true, pendingSync = res is RouterResult.Failure)
                } else rule
            }
            store.saveRules(updated)
            _state.update {
                it.copy(
                    rules = updated,
                    message = if (failed == 0) "تم حظر كل الأجهزة" else "تم الحظر محليا، بس $failed قاعدة لم يؤكدها الروتر"
                )
            }
        }
    }

    /** Open a blocked device for [minutes], then block it again automatically. */
    fun openTemporarily(rule: ParentalRule, minutes: Long) {
        viewModelScope.launch {
            val updated = _state.value.rules.map {
                if (it.name == rule.name) it.copy(enabled = false, pendingSync = true) else it
            }
            _state.update { it.copy(rules = updated) }
            store.saveRules(updated)
            val settings = store.loadSettings()
            val res = ParentalSync.applyRule(client, settings, rule, false)
            val final = _state.value.rules.map {
                if (it.name == rule.name) it.copy(pendingSync = res is RouterResult.Failure) else it
            }
            store.saveRules(final)
            if (res is RouterResult.Success) {
                ScheduleWorker.enqueueTimer(getApplication(), rule.name, minutes)
                _state.update { it.copy(rules = final, message = "تم فتح ${rule.name} لمدة $minutes دقيقة — هيتقفل لوحده بعدها") }
            } else {
                _state.update { it.copy(rules = final, message = res.message) }
            }
        }
    }

    fun saveSettings(settings: RouterSettings) {
        viewModelScope.launch {
            store.saveSettings(settings)
            _state.update { it.copy(settings = settings, message = "تم حفظ الإعدادات") }
        }
    }

    fun saveSchedule(schedule: RuleSchedule) {
        viewModelScope.launch {
            val list = _state.value.schedules.filter { it.ruleName != schedule.ruleName } + schedule
            store.saveSchedules(list)
            _state.update { it.copy(schedules = list, message = "تم حفظ الجدولة لـ ${schedule.ruleName}") }
            ScheduleWorker.enqueuePeriodic(getApplication())
        }
    }

    fun deleteSchedule(ruleName: String) {
        viewModelScope.launch {
            val list = _state.value.schedules.filter { it.ruleName != ruleName }
            store.saveSchedules(list)
            _state.update { it.copy(schedules = list) }
        }
    }

    fun runDiagnostics() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = "بفحص عنوان صفحة التحكم الأبوي...") }
            val settings = store.loadSettings()
            val entries = ParentalSync.probe(client, settings)
            val working = entries.firstOrNull { it.ok }?.label
            if (working != null) {
                store.saveSettings(settings.copy(parentalScript = working))
                _state.update { it.copy(settings = settings.copy(parentalScript = working)) }
            }
            _state.update {
                it.copy(
                    loading = false,
                    diagnostics = entries,
                    message = if (working != null) "لقينا عنوان شغال: $working" else "مفيش عنوان من المقترحات رد — شوف صفحة التشخيص"
                )
            }
        }
    }

    suspend fun setPin(pin: String) {
        store.setPin(pin)
        _state.update { it.copy(pinSet = true) }
    }

    suspend fun checkPin(pin: String): Boolean = store.checkPin(pin)

    suspend fun isPinSet(): Boolean = store.isPinSet()

    fun logout() {
        viewModelScope.launch {
            client.logout(store.loadSettings())
            _state.update { it.copy(connected = false) }
        }
    }
}
