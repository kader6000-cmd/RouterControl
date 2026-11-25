package com.routercontrol.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Neon dark theme (v1.11.0) — design tokens taken from the ChatGPT neon
// reskin, applied to the REAL screens. The reskin file itself was NOT
// adopted as-is: it dropped the diagnostics list, the schedule save,
// the real saved password (it hardcoded "12345678"), the PIN lock, the
// link-device dialog and the status banner, and its About/Schedule had
// no save button at all. All of that stays working here; only the look
// comes from the reskin.
object RouterColors {
    val Background = Color(0xFF0A0D16)
    val SurfaceCard = Color(0xFF131929)
    val SurfaceCardElevated = Color(0xFF1A2238)
    val BorderSubtle = Color(0xFF26314D)

    val ElectricBlue = Color(0xFF2979FF)
    val NeonCyan = Color(0xFF00E5FF)
    val NeonPurple = Color(0xFF7C4DFF)
    val NeonGreen = Color(0xFF00E676)
    val CrimsonRed = Color(0xFFFF1744)
    val AmberWarning = Color(0xFFFFAB00)

    val TextPrimary = Color(0xFFF4F7FF)
    val TextSecondary = Color(0xFF8E9BB5)

    val HeaderCardGradient = Brush.linearGradient(
        colors = listOf(Color(0xFF1A2980), Color(0xFF4A148C))
    )
    val SleepModeGradient = Brush.horizontalGradient(
        colors = listOf(Color(0xFFD50032), Color(0xFFFF4081))
    )
    val PrimaryButtonGradient = Brush.horizontalGradient(
        colors = listOf(ElectricBlue, NeonPurple)
    )
}

/** Device icon by rule/device name (the real rules carry no device type). */
fun iconForDeviceName(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        n.contains("tv") || n.contains("تلفزيون") -> Icons.Rounded.Tv
        n.contains("desktop") || n.contains("كمبيوتر") || n.contains("pc") -> Icons.Rounded.Computer
        n.contains("laptop") || n.contains("لاب") -> Icons.Rounded.Laptop
        else -> Icons.Rounded.Smartphone
    }
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    actionIcon: ImageVector? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, color = RouterColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = RouterColors.TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        if (actionIcon != null && onActionClick != null) {
            IconButton(
                onClick = onActionClick,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(RouterColors.SurfaceCardElevated)
            ) {
                Icon(actionIcon, contentDescription = null, tint = RouterColors.NeonCyan)
            }
        }
    }
}

@Composable
fun RouterHeroCard(
    routerModel: String,
    routerIp: String,
    blockedCount: Int,
    totalCount: Int,
    onRefreshClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(RouterColors.HeaderCardGradient)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(RouterColors.NeonCyan.copy(alpha = 0.5f), RouterColors.NeonPurple.copy(alpha = 0.2f))
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Router,
                            contentDescription = null,
                            tint = RouterColors.NeonCyan,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Column {
                        Text("التحكم في الروتر", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "$routerModel — $routerIp",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                IconButton(
                    onClick = onRefreshClick,
                    modifier = Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "تحديث", tint = Color.White)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusPill(Modifier.weight(1f), "متصل بالراوتر", RouterColors.NeonGreen)
                StatusPill(
                    Modifier.weight(1f),
                    "محظور: $blockedCount من $totalCount",
                    if (blockedCount > 0) RouterColors.CrimsonRed else RouterColors.NeonCyan
                )
            }
        }
    }
}

@Composable
fun StatusPill(modifier: Modifier = Modifier, label: String, dotColor: Color) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SleepModeBanner(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(RouterColors.SleepModeGradient)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                Column {
                    Text("اقفل الكل (وضع النوم)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("حظر فوري لجميع الأجهزة المسجلة", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                }
            }
            Icon(Icons.Rounded.ChevronLeft, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
fun NeonGradientButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(RouterColors.PrimaryButtonGradient)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ModernTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = RouterColors.NeonCyan,
            unfocusedBorderColor = RouterColors.BorderSubtle,
            focusedLabelColor = RouterColors.NeonCyan,
            unfocusedLabelColor = RouterColors.TextSecondary,
            focusedTextColor = RouterColors.TextPrimary,
            unfocusedTextColor = RouterColors.TextPrimary,
            focusedContainerColor = RouterColors.SurfaceCardElevated,
            unfocusedContainerColor = RouterColors.SurfaceCardElevated
        )
    )
}

@Composable
fun ModernBottomNavBar(selectedIndex: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        Pair("القواعد", Icons.Rounded.Home),
        Pair("مين أونلاين", Icons.Rounded.People),
        Pair("أدوات", Icons.Rounded.Build),
        Pair("حول", Icons.Rounded.Info),
        Pair("الإعدادات", Icons.Rounded.Settings)
    )
    NavigationBar(
        containerColor = RouterColors.SurfaceCard,
        tonalElevation = 0.dp,
        modifier = Modifier.border(
            width = 1.dp,
            color = RouterColors.BorderSubtle,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        )
    ) {
        items.forEachIndexed { index, (title, icon) ->
            val selected = selectedIndex == index
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(index) },
                icon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = if (selected) RouterColors.NeonCyan else RouterColors.TextSecondary
                    )
                },
                label = {
                    Text(
                        text = title,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) RouterColors.NeonCyan else RouterColors.TextSecondary
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = RouterColors.ElectricBlue.copy(alpha = 0.2f)
                )
            )
        }
    }
}
