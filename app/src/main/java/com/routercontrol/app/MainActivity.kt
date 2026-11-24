package com.routercontrol.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
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
import com.routercontrol.app.ui.RouterViewModel
import com.routercontrol.app.ui.Screens
import kotlinx.coroutines.launch

// Facebook dark-mode theme (v1.9.9): dark gray feed background, darker cards
// and top bar, Facebook dark blue primary, light text — replaces the light
// theme (the user does not like the white app). The launcher icon is NOT changed.
private val FbBlue = Color(0xFF2D88FF)
private val FbLightBlue = Color(0xFF3A3B3C)
private val FbBg = Color(0xFF18191A)
private val FbCard = Color(0xFF242526)
private val FbText = Color(0xFFE4E6EB)
private val FbMuted = Color(0xFFB0B3B8)

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
                        secondary = FbBlue,
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
    var page by remember { mutableStateOf("rules") }
    // Facebook-style shell (v1.9.8): white top bar with the blue title,
    // light gray feed background, and a bottom tab bar with icons.
    // Tabs: Rules, Who's Online, Tools (diagnostics inside), About
    // (developer + schedule inside), Settings. The launcher icon is unchanged.
    val tabs = listOf(
        Triple("rules", "القواعد", Icons.Filled.Home),
        Triple("online", "مين أونلاين", Icons.Filled.People),
        Triple("tools", "أدوات", Icons.Filled.Build),
        Triple("about", "حول", Icons.Filled.Info),
        Triple("settings", "الإعدادات", Icons.Filled.Settings)
    )
    Scaffold(
        containerColor = FbBg,
        topBar = {
            Column(
                Modifier.fillMaxWidth().background(FbCard)
                    .padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 8.dp)
            ) {
                Text("التحكم في الروتر", color = FbBlue, fontSize = 20.sp)
                Text("ZTE H288A — 192.168.1.1", color = FbMuted, fontSize = 12.sp)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = FbCard) {
                tabs.forEach { (key, label, icon) ->
                    NavigationBarItem(
                        selected = page == key,
                        onClick = { page = key },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = FbBlue,
                            selectedTextColor = FbBlue,
                            unselectedIconColor = FbMuted,
                            unselectedTextColor = FbMuted,
                            indicatorColor = FbLightBlue
                        )
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            if (state.message != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2E42))) {
                    Text(
                        state.message ?: "",
                        color = FbBlue,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            when (page) {
                "online" -> Screens.OnlinePage(vm)
                "tools" -> Screens.ToolsPage(vm)
                "about" -> Screens.AboutPage(vm)
                "settings" -> Screens.SettingsPage(vm)
                else -> Screens.RulesPage(vm)
            }
        }
    }
}
