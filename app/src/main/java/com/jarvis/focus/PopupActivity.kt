package com.jarvis.focus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.focus.ui.theme.FocusTheme
import kotlinx.coroutines.launch

enum class PopupScreen {
    CHOICE, STATS, SELECTOR
}

class PopupActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.85f)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)

        val mode = intent.getStringExtra("mode")
        val pkg = intent.getStringExtra("package_name")
        val message = intent.getStringExtra("message") ?: ""

        setContent {
            FocusTheme {
                var currentScreen by remember { 
                    mutableStateOf(if (mode == "popup") PopupScreen.CHOICE else PopupScreen.STATS) 
                }

                // Smooth appearance: Animate the entry of the card
                val cardAlpha = remember { Animatable(0f) }
                val cardScale = remember { Animatable(0.8f) }
                
                LaunchedEffect(Unit) {
                    launch { cardAlpha.animateTo(1f, tween(500, easing = LinearOutSlowInEasing)) }
                    launch { cardScale.animateTo(1f, tween(500, easing = LinearOutSlowInEasing)) }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AnimatedContent(
                            targetState = currentScreen,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(400)) + scaleIn(initialScale = 0.92f) togetherWith
                                fadeOut(animationSpec = tween(300))
                            },
                            label = "screen_transition",
                            modifier = Modifier.graphicsLayer {
                                alpha = cardAlpha.value
                                scaleX = cardScale.value
                                scaleY = cardScale.value
                            }
                        ) { screen ->
                            when (screen) {
                                PopupScreen.CHOICE -> {
                                    PopupChoiceScreen(
                                        pkg = pkg ?: "",
                                        message = message,
                                        onNavigateToStats = { currentScreen = PopupScreen.STATS },
                                        onNavigateToSelector = { currentScreen = PopupScreen.SELECTOR }
                                    )
                                }
                                PopupScreen.STATS -> {
                                    StatsWrapper(onBack = { 
                                        if (mode == "popup") currentScreen = PopupScreen.CHOICE else finish() 
                                    })
                                }
                                PopupScreen.SELECTOR -> {
                                    AppSelectorScreen(onBack = { 
                                        if (mode == "popup") currentScreen = PopupScreen.CHOICE else finish() 
                                    })
                                }
                            }
                        }
                    }
                }

                BackHandler {
                    if (mode == "popup" && currentScreen != PopupScreen.CHOICE) {
                        currentScreen = PopupScreen.CHOICE
                    } else {
                        finish()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        UsageMonitorService.isPopupShowing = false
    }
}

@Composable
fun StatsWrapper(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("usage_prefs", Context.MODE_PRIVATE)
    val appPrefs = context.getSharedPreferences("monitored_apps_prefs", Context.MODE_PRIVATE)
    val monitoredApps = appPrefs.getStringSet("selected_packages", emptySet()) ?: emptySet()

    val usageMap = monitoredApps.associateWith { prefs.getLong(it, 0L) }.toMutableMap()
    UsageMonitorService.foregroundStart.forEach { (fgPkg, start) ->
        if (monitoredApps.contains(fgPkg)) {
            usageMap[fgPkg] = (usageMap[fgPkg] ?: 0L) + (System.currentTimeMillis() - start)
        }
    }
    
    UsageStatsScreen(stats = usageMap, onBack = onBack)
}

@Composable
fun PopupChoiceScreen(
    pkg: String, 
    message: String, 
    onNavigateToStats: () -> Unit,
    onNavigateToSelector: () -> Unit
) {
    val ctx = LocalContext.current
    val appName = remember { getDynamicAppName(ctx, pkg) }

    Card(
        modifier = Modifier
            .padding(32.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Focus Shield",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "You've reached your limit for $appName.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    val home = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    ctx.startActivity(home)
                    (ctx as? ComponentActivity)?.finish()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text("Exit $appName", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onNavigateToStats,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.BarChart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Stats", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onNavigateToSelector,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Apps", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Need a moment? Quick extension:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TimeOptionButton(modifier = Modifier.weight(1f), "5m") {
                    UsageMonitorService.tempAllowedApps[pkg] = System.currentTimeMillis() + 5 * 60_000
                    (ctx as? ComponentActivity)?.finish()
                }
                TimeOptionButton(modifier = Modifier.weight(1f), "15m") {
                    UsageMonitorService.tempAllowedApps[pkg] = System.currentTimeMillis() + 15 * 60_000
                    (ctx as? ComponentActivity)?.finish()
                }
                TimeOptionButton(modifier = Modifier.weight(1f), "30m") {
                    UsageMonitorService.tempAllowedApps[pkg] = System.currentTimeMillis() + 30 * 60_000
                    (ctx as? ComponentActivity)?.finish()
                }
            }
        }
    }
}

@Composable
fun TimeOptionButton(modifier: Modifier = Modifier, label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageStatsScreen(stats: Map<String, Long>, onBack: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Daily Usage", fontWeight = FontWeight.Black) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                    }
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    UsagePieChart(stats)
                }
                items(stats.entries.filter { it.value > 0L }.sortedByDescending { it.value }) { entry ->
                    AppUsageItem(entry.key, entry.value)
                }
            }
        }
    }
}

@Composable
fun AppUsageItem(pkg: String, usageMs: Long) {
    val mins = (usageMs / 60000).toInt()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(getDynamicAppName(LocalContext.current, pkg), fontWeight = FontWeight.Bold)
            Text("${mins}m", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsagePieChart(stats: Map<String, Long>) {
    val total = stats.values.sum().toFloat()
    if (total == 0f) return
    
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary, Color(0xFF69C9D0))
    val entries = stats.entries.filter { it.value > 0L }.take(4)
    val angles = entries.map { (it.value / total) * 360f }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(160.dp)) {
                var startAngle = -90f
                angles.forEachIndexed { i, angle ->
                    drawArc(color = colors[i % colors.size], startAngle = startAngle, sweepAngle = angle, useCenter = true)
                    startAngle += angle
                }
            }
            Surface(modifier = Modifier.size(100.dp), shape = CircleShape, color = MaterialTheme.colorScheme.background) {
                Box(contentAlignment = Alignment.Center) {
                    Text("Top Apps", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

fun getDynamicAppName(context: Context, packageName: String): String {
    return try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) { packageName }
}
