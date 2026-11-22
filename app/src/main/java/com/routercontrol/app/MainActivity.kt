package com.routercontrol.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.routercontrol.app.ui.RouterViewModel
import com.routercontrol.app.ui.Screens
import kotlinx.coroutines.launch

// Bright vivid theme (v1.9.6): deep navy background, vivid cyan primary,
// bright orange secondary, royal-blue panels — replaces the old gold/black.
private val Navy = Color(0xFF0A1B33)
private val Cyan = Color(0xFF00C8FF)
private val Orange = Color(0xFFFF9500)
private val Panel = Color(0xFF17407E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: RouterViewModel = viewModel()
            val state by vm.state.collectAsState()
            var unlocked by remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Orange, tertiary = Color(0xFFB388FF), background = Navy, surface = Panel, onPrimary = Color(0xFF00222E), onSecondary = Color(0xFF2A1500))) {
                    if (state.pinSet && !unlocked) {
                        PinScreen(vm) { unlocked = true }
                    } else {
                        AppShell(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun PinScreen(vm: RouterViewModel, onUnlock: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isSetup by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isSetup = !vm.isPinSet()
    }
    Column(
        Modifier.fillMaxSize().background(Navy).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (isSetup) "عيّن رقم سري للتطبيق" else "اكتب الرقم السري", color = Cyan, fontSize = 22.sp)
        Text(
            "4 أرقام — عشان العيال ما يفتحوش التطبيق ويفكّوا الحظر بنفسهم",
            color = Color(0xFFDCE8FA), fontSize = 14.sp
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pin = it },
            label = { Text("الرقم السري (4 أرقام)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error ?: "", color = Color(0xFFFF6B6B))
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (pin.length != 4) {
                    error = "الرقم السري لازم يكون 4 أرقام"
                    return@Button
                }
                scope.launch {
                    if (isSetup) {
                        vm.setPin(pin)
                        onUnlock()
                    } else if (vm.checkPin(pin)) {
                        onUnlock()
                    } else {
                        error = "الرقم غلط — حاول تاني"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (isSetup) "حفظ وفتح" else "فتح") }
        Spacer(Modifier.height(12.dp))
        Text(
            "لو نسيت الرقم: امسح بيانات التطبيق من إعدادات الموبايل (هيتمسح معاها إعدادات الروتر المحفوظة).",
            color = Color(0xFF9DB8DC), fontSize = 12.sp
        )
    }
}

@Composable
private fun AppShell(vm: RouterViewModel) {
    val state by vm.state.collectAsState()
    var page by remember { mutableStateOf("rules") }
    Column(Modifier.fillMaxSize().background(Navy).padding(16.dp)) {
        Text("التحكم في الروتر", color = Cyan, fontSize = 25.sp)
        Text("ZTE H288A — 192.168.1.1", color = Color.White, fontSize = 14.sp)
        if (state.message != null) {
            Spacer(Modifier.height(8.dp))
            Text(state.message ?: "", color = Color(0xFF90CAF9), fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                "rules" to "القواعد",
                "online" to "مين أونلاين",
                "schedule" to "الجدولة",
                "settings" to "الإعدادات",
                "diag" to "التشخيص"
            ).forEach { (key, label) ->
                TextButton(onClick = { page = key }) {
                    Text(label, color = if (page == key) Cyan else Color(0xFFDCE8FA), fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        when (page) {
            "online" -> Screens.OnlinePage(vm)
            "schedule" -> Screens.SchedulePage(vm)
            "settings" -> Screens.SettingsPage(vm)
            "diag" -> Screens.DiagnosticsPage(vm)
            else -> Screens.RulesPage(vm)
        }
    }
}
