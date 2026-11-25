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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.routercontrol.app.ui.ModernBottomNavBar
import com.routercontrol.app.ui.RouterViewModel
import com.routercontrol.app.ui.Screens
import kotlinx.coroutines.launch

// Neon dark theme (v1.11.0): deep navy background, neon cyan/blue accents,
// light text — replaces the Facebook dark palette. The launcher icon is
// NOT changed.
private val FbBlue = Color(0xFF2979FF)
private val FbBg = Color(0xFF0A0D16)
private val FbCard = Color(0xFF131929)
private val FbText = Color(0xFFF4F7FF)
private val FbMuted = Color(0xFF8E9BB5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: RouterViewModel = viewModel()
            val state by vm.state.collectAsState()
            var unlocked by remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = FbBlue,
                        secondary = Color(0xFF00E5FF),
                        background = FbBg,
                        surface = FbCard,
                        onPrimary = Color.White,
                        onBackground = FbText,
                        onSurface = FbText
                    )
                ) {
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
        Modifier.fillMaxSize().background(FbBg).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (isSetup) "عيّن رقم سري للتطبيق" else "اكتب الرقم السري", color = FbBlue, fontSize = 22.sp)
        Text(
            "4 أرقام — عشان العيال ما يفتحوش التطبيق ويفكّوا الحظر بنفسهم",
            color = FbMuted, fontSize = 14.sp
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
            Text(error ?: "", color = Color(0xFFF02849))
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
            color = FbMuted, fontSize = 12.sp
        )
    }
}

@Composable
private fun AppShell(vm: RouterViewModel) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    // Neon shell (v1.11.0): no top bar — each screen has its own header
    // (hero card on Rules, ScreenHeader elsewhere) — and a neon bottom nav.
    // Tabs: Rules, Who's Online, Tools (diagnostics inside), About
    // (developer + schedule inside), Settings. The launcher icon is unchanged.
    Scaffold(
        containerColor = FbBg,
        bottomBar = {
            ModernBottomNavBar(selectedIndex = tab, onSelect = { tab = it })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            if (state.message != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2238))) {
                    Text(
                        state.message ?: "",
                        color = Color(0xFF00E5FF),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            when (tab) {
                1 -> Screens.OnlinePage(vm)
                2 -> Screens.ToolsPage(vm)
                3 -> Screens.AboutPage(vm)
                4 -> Screens.SettingsPage(vm)
                else -> Screens.RulesPage(vm)
            }
        }
    }
}
