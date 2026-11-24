package com.routercontrol.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.routercontrol.app.BuildConfig
import com.routercontrol.app.data.RouterDevice
import com.routercontrol.app.data.RuleSchedule
import kotlinx.coroutines.launch

// Facebook-style palette (v1.9.8): light gray feed background, white cards,
// Facebook blue primary, dark text — replaces the dark navy/cyan theme.
private val FbBlue = Color(0xFF1877F2)
private val FbText = Color(0xFF050505)
private val FbMuted = Color(0xFF65676B)
private val FbWarn = Color(0xFF8A6100)
private val FbRed = Color(0xFFF02849)
private val FbGreen = Color(0xFF31A24C)

private fun fmtMinutes(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

private fun parseHHMM(s: String): Int? {
    val parts = s.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

object Screens {

    @Composable
    fun RulesPage(vm: RouterViewModel) {
        val state by vm.state.collectAsState()
        Column(Modifier.fillMaxSize()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.refreshAll() }) { Text("تحديث") }
                Button(
                    onClick = { vm.blockAll() },
                    colors = ButtonDefaults.buttonColors(containerColor = FbRed)
                ) { Text("اقفل الكل (وضع النوم)") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "On = القاعدة شغالة = الجهاز محظور طول اليوم. Off = الجهاز مفتوح.",
                color = FbMuted, fontSize = 13.sp
            )
            Text(
                "لو ظهرت علامة (لم يؤكد الروتر) جنب قاعدة، يبقى التغيير اتحفظ محليا بس — شوف صفحة التشخيص.",
                color = FbWarn, fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            if (state.loading) {
                CircularProgressIndicator()
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.rules, key = { it.name }) { rule ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FbText)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(rule.name, color = FbText, fontSize = 17.sp)
                                    Text(
                                        if (rule.mac.isBlank()) "مش مربوط بجهاز — اربطه من صفحة مين أونلاين"
                                        else "MAC: ${rule.mac}",
                                        color = FbMuted, fontSize = 12.sp
                                    )
                                    if (rule.pendingSync) {
                                        Text("لم يؤكد الروتر بعد", color = FbWarn, fontSize = 12.sp)
                                    }
                                }
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { vm.toggleRule(rule) }
                                )
                            }
                            if (!rule.enabled) {
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("افتح مؤقتا:", color = FbMuted, fontSize = 13.sp)
                                    Button(onClick = { vm.openTemporarily(rule, 30) }) { Text("30 دقيقة") }
                                    Button(onClick = { vm.openTemporarily(rule, 60) }) { Text("ساعة") }
                                }
                                Text("هيتقفل لوحده بعد ما الوقت يخلص.", color = FbMuted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun OnlinePage(vm: RouterViewModel) {
        val state by vm.state.collectAsState()
        var linkDevice by remember { mutableStateOf<RouterDevice?>(null) }
        Column(Modifier.fillMaxSize()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.refreshAll() }) { Text("تحديث القايمة") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "الأجهزة المتصلة دلوقتي بالروتر (واي فاي + سلك). دوس حظر على أي جهاز، ولو ليه قاعدة هيتقلب زرارها، ولو جديد هيتعمل له قاعدة حظر جديدة.",
                color = FbMuted, fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            if (state.loading) CircularProgressIndicator()
            if (state.devices.isEmpty() && !state.loading) {
                Text("مفيش أجهزة ظاهرة — دوس تحديث، واتأكد إنك متصل بواي فاي البيت.", color = FbMuted)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.devices, key = { it.mac }) { device ->
                    val linked = state.rules.firstOrNull { it.mac.equals(device.mac, ignoreCase = true) }
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FbText)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(device.name, color = FbText, fontSize = 16.sp)
                                    Text(
                                        "${device.networkType} — ${device.ip.ifBlank { "من غير IP" }}",
                                        color = FbMuted, fontSize = 12.sp
                                    )
                                    Text(device.mac, color = FbMuted, fontSize = 12.sp)
                                    if (linked != null) {
                                        // A rule the router never confirmed (pendingSync) is
                                        // only blocked locally — say so, and let the user
                                        // retry Block instead of showing a red "blocked".
                                        val stateText = when {
                                            linked.enabled && linked.pendingSync -> "محظور محليا بس — الروتر مأكدش، دوس حظر تاني"
                                            linked.enabled -> "محظور"
                                            else -> "مفتوح"
                                        }
                                        val stateColor = when {
                                            linked.enabled && linked.pendingSync -> Color(0xFFFF9500)
                                            linked.enabled -> FbRed
                                            else -> FbGreen
                                        }
                                        Text(
                                            "مربوط بقاعدة: ${linked.name} — $stateText",
                                            color = stateColor,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Button(
                                        onClick = { vm.blockDevice(device) },
                                        // Keep Block enabled while the rule is only
                                        // blocked locally (pendingSync): the router
                                        // never confirmed it, so the user must be
                                        // able to retry from this page.
                                        enabled = linked == null || !linked.enabled || linked.pendingSync,
                                        colors = ButtonDefaults.buttonColors(containerColor = FbRed)
                                    ) { Text("حظر") }
                                    Spacer(Modifier.height(6.dp))
                                    Button(
                                        onClick = { vm.unblockDevice(device) },
                                        enabled = linked?.enabled == true,
                                        colors = ButtonDefaults.buttonColors(containerColor = FbGreen)
                                    ) { Text("سماح") }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            TextButton(onClick = { linkDevice = device }) {
                                Text("ربط الجهاز ده بقاعدة موجودة", color = FbBlue, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        val target = linkDevice
        if (target != null) {
            AlertDialog(
                onDismissRequest = { linkDevice = null },
                title = { Text("اربط ${target.name} بقاعدة") },
                text = {
                    Column {
                        state.rules.forEach { rule ->
                            TextButton(
                                onClick = {
                                    vm.linkRuleToMac(rule.name, target.mac)
                                    linkDevice = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(rule.name, modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { linkDevice = null }) { Text("إلغاء") }
                }
            )
        }
    }

    @Composable
    fun SchedulePage(vm: RouterViewModel) {
        val state by vm.state.collectAsState()
        var selectedRule by remember { mutableStateOf(state.rules.firstOrNull()?.name ?: "") }
        var startText by remember { mutableStateOf("23:00") }
        var endText by remember { mutableStateOf("07:00") }
        var daysPreset by remember { mutableStateOf(0x7F) }
        var enabled by remember { mutableStateOf(true) }
        Column(Modifier.fillMaxSize()) {
            Text("جدولة الحظر التلقائي", color = FbText, fontSize = 18.sp)
            Text(
                "مثال: اقفل جهاز أحمد كل يوم من 11 بالليل لـ 7 الصبح. الجدولة بتتنفذ من التطبيق كل ربع ساعة وانت على واي فاي البيت.",
                color = FbMuted, fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            Text("القاعدة:", color = FbText)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.rules.forEach { rule ->
                    FilterChip(
                        selected = selectedRule == rule.name,
                        onClick = { selectedRule = rule.name },
                        label = { Text(rule.name, fontSize = 12.sp) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text("من (23:00)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    label = { Text("إلى (07:00)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("الأيام:", color = FbText)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = daysPreset == 0x7F, onClick = { daysPreset = 0x7F }, label = { Text("كل يوم") })
                FilterChip(selected = daysPreset == 0x1F, onClick = { daysPreset = 0x1F }, label = { Text("الأحد - الخميس") })
                FilterChip(selected = daysPreset == 0x60, onClick = { daysPreset = 0x60 }, label = { Text("جمعة + سبت") })
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = { enabled = it })
                Spacer(Modifier.width(8.dp))
                Text("الجدولة شغالة", color = FbText)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val start = parseHHMM(startText)
                    val end = parseHHMM(endText)
                    if (selectedRule.isBlank()) {
                        vm.setMessage("اختار قاعدة الأول")
                        return@Button
                    }
                    if (start == null || end == null) {
                        vm.setMessage("اكتب الوقت بالشكل ده: 23:00")
                        return@Button
                    }
                    vm.saveSchedule(RuleSchedule(selectedRule, enabled, start, end, daysPreset))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("حفظ الجدولة") }
            Spacer(Modifier.height(12.dp))
            Text("الجداول المحفوظة:", color = FbText, fontSize = 16.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.schedules, key = { it.ruleName }) { s ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FbText)) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${s.ruleName}: ${fmtMinutes(s.startMinutes)} - ${fmtMinutes(s.endMinutes)} " +
                                    if (s.enabled) "(شغالة)" else "(واقفة)",
                                color = FbText
                            )
                            TextButton(onClick = { vm.deleteSchedule(s.ruleName) }) {
                                Text("مسح", color = FbRed)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun SettingsPage(vm: RouterViewModel) {
        val state by vm.state.collectAsState()
        var host by remember { mutableStateOf(state.settings.host) }
        var username by remember { mutableStateOf(state.settings.username) }
        var password by remember { mutableStateOf(state.settings.password) }
        var useHttps by remember { mutableStateOf(state.settings.useHttps) }
        var parentalScript by remember { mutableStateOf(state.settings.parentalScript) }
        var pin by remember { mutableStateOf("") }
        val pinScope = rememberCoroutineScope()
        Column(Modifier.fillMaxSize()) {
            Text("إعدادات الروتر", color = FbText, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("عنوان الروتر (مثال: 192.168.1.1)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("اسم المستخدم") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("باسورد الأدمن") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = useHttps, onCheckedChange = { useHttps = it })
                Spacer(Modifier.width(8.dp))
                Text("استخدم https بدل http (الافتراضي http للروتر ده)", color = FbText, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = parentalScript, onValueChange = { parentalScript = it },
                label = { Text("عنوان صفحة التحكم الأبوي (متقدم — سيبه فاضي لو مش عارفه)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Text(
                "لو عرفت اسم السكربت الحقيقي من صفحة التشخيص أو من متصفح الكمبيوتر، اكتبه هنا ومش هتحتاج نسخة جديدة.",
                color = FbMuted, fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    vm.saveSettings(
                        com.routercontrol.app.data.RouterSettings(
                            host = host.trim(), username = username.trim(),
                            password = password, useHttps = useHttps,
                            parentalScript = parentalScript.trim()
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("حفظ الإعدادات") }
            Spacer(Modifier.height(16.dp))
            Text("الرقم السري للتطبيق", color = FbText, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin, onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pin = it },
                    label = { Text("PIN جديد (4 أرقام)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true, modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    if (pin.length == 4) {
                        pinScope.launch {
                            vm.setPin(pin)
                            pin = ""
                            vm.setMessage("تم تغيير الرقم السري")
                        }
                    } else {
                        vm.setMessage("الـ PIN لازم يكون 4 أرقام")
                    }
                }) { Text("تغيير") }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "تنبيه مهم: الموبايلات اللي مفعّلة الـ MAC العشوائي (Private MAC) ممكن تهرب من الحظر، لأن الروتر بيحظر بالماك. ثبّت الماك من إعدادات الواي فاي على جهاز العيال (Use device MAC).",
                color = FbWarn, fontSize = 13.sp
            )
            Text(
                "التطبيق بيشتغل على واي فاي البيت بس، والباسورد محفوظ على موبايلك بس ومبيتبعتش لأي حد.",
                color = FbMuted, fontSize = 13.sp
            )
        }
    }

    @Composable
    fun DiagnosticsPage(vm: RouterViewModel) {
        val state by vm.state.collectAsState()
        val clipboard = LocalClipboardManager.current
        var copied by remember { mutableStateOf(false) }
        val diagnosticReport = remember(state.diagnostics, state.settings.host, state.settings.parentalScript, state.message) {
            buildString {
                appendLine("RouterControl - تقرير التشخيص")
                appendLine("الإصدار: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("الراوتر: ${state.settings.host}")
                appendLine("عنوان التحكم الأبوي: ${state.settings.parentalScript.ifBlank { "غير مؤكد" }}")
                state.message?.let { appendLine("الحالة: $it") }
                appendLine("--------------------------------")
                state.diagnostics.forEachIndexed { index, entry ->
                    appendLine("${index + 1}. ${entry.label}")
                    appendLine("النتيجة: ${if (entry.ok) "نجاح" else "فشل/غير مؤكد"}")
                    appendLine(entry.detail)
                    appendLine("--------------------------------")
                }
                if (state.diagnostics.isEmpty()) appendLine("لا توجد نتائج تشخيص بعد. اضغط فحص عنوان التحكم الأبوي أولاً.")
            }
        }
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("التشخيص", color = FbText, fontSize = 18.sp)
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(diagnosticReport))
                    copied = true
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "نسخ التشخيص")
                    Spacer(Modifier.width(6.dp))
                    Text(if (copied) "تم النسخ" else "نسخ التشخيص")
                }
            }
            Text(
                "التشخيص يفحص صفحة الأجهزة والتحكم الأبوي، ويعرض كود initial_QueryAccessDeviceLink الذي تستخدمه واجهة الراوتر لجلب الأجهزة، حتى نحدد طلب API الحقيقي بدل تخمينه.",
                color = FbMuted, fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.runDiagnostics() }, modifier = Modifier.fillMaxWidth()) {
                Text("افحص عنوان التحكم الأبوي")
            }
            Spacer(Modifier.height(8.dp))
            if (state.loading) CircularProgressIndicator()
            Text(
                "العنوان المؤكد حاليا: ${state.settings.parentalScript.ifBlank { "لسه مش مؤكد" }}",
                color = FbText, fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.diagnostics, key = { it.label }) { d ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (d.ok) Color(0xFFE6F4EA) else Color(0xFFFDECEA)
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(d.label, color = FbText, fontSize = 15.sp)
                            Text(d.detail, color = FbMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    /** Tools tab: the diagnostics screen lives here (v1.9.8). */
    @Composable
    fun ToolsPage(vm: RouterViewModel) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "أدوات مساعدة — التشخيص بيفحص الاتصال بالروتر وصفحة التحكم الأبوي، ولو حصلت مشكلة في الحظر انسخ التقرير من هنا.",
                color = FbMuted, fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            DiagnosticsPage(vm)
        }
    }

    /** About tab (v1.9.8): developer + version, with the schedule section inside. */
    @Composable
    fun AboutPage(vm: RouterViewModel) {
        Column(Modifier.fillMaxSize()) {
            Text("حول التطبيق", color = FbText, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("التحكم في الروتر", color = FbText, fontSize = 18.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("تطوير: Abdelkader Ahmed", color = FbBlue, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "الإصدار: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        color = FbMuted, fontSize = 13.sp
                    )
                    Text("الروتر: ZTE ZXHN H288A — 192.168.1.1", color = FbMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "التطبيق بيتحكم في التحكم الأبوي للروتر من موبايلك: حظر وسماح للأجهزة، وجدولة تلقائية، ومزامنة مع صفحة الروتر.",
                        color = FbMuted, fontSize = 13.sp
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            SchedulePage(vm)
        }
    }
}
