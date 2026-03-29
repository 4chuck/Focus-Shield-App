package com.jarvis.focus

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.jarvis.focus.data.DataRepository
import com.jarvis.focus.ui.theme.FocusTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSelectorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FocusTheme {
                AppSelectorScreen(onBack = { finish() })
            }
        }
    }
}

data class AppItemInfo(
    val packageName: String,
    val name: String,
    val isSelected: Boolean,
    val limitMins: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { DataRepository.getInstance(context) }
    val scope = rememberCoroutineScope()

    var allApps by remember { mutableStateOf(listOf<AppItemInfo>()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    
    val selectedPackages = remember { mutableStateListOf<String>() }
    var appToEditLimit by remember { mutableStateOf<AppItemInfo?>(null) }

    val filteredApps = remember(allApps, searchQuery) {
        allApps.filter { it.name.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val saved = repository.getMonitoredApps()
            val limits = repository.getAllAppLimits()
            
            selectedPackages.addAll(saved)
            
            val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(launcherIntent, 0)
            }

            val appsList = resolveInfos.map { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                AppItemInfo(
                    packageName = pkg,
                    name = resolveInfo.loadLabel(context.packageManager).toString(),
                    isSelected = saved.contains(pkg),
                    limitMins = limits[pkg] ?: 30
                )
            }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.name }
            
            withContext(Dispatchers.Main) {
                allApps = appsList
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 4.dp) {
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                    TopAppBar(
                        title = { Text("App Limits", fontWeight = FontWeight.Black) },
                        navigationIcon = {
                            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                        }
                    )
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search apps...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val isSelected = selectedPackages.contains(app.packageName)
                        AppListItem(
                            app = app,
                            isSelected = isSelected,
                            onToggle = {
                                if (isSelected) selectedPackages.remove(app.packageName)
                                else selectedPackages.add(app.packageName)
                                scope.launch { repository.setMonitoredApps(selectedPackages.toSet()) }
                            },
                            onEditLimit = { appToEditLimit = app }
                        )
                    }
                }
            }
        }
    }

    if (appToEditLimit != null) {
        AppLimitDialog(
            app = appToEditLimit!!,
            onDismiss = { appToEditLimit = null },
            onConfirm = { mins ->
                scope.launch {
                    repository.setAppLimit(appToEditLimit!!.packageName, mins)
                    allApps = allApps.map { if (it.packageName == appToEditLimit!!.packageName) it.copy(limitMins = mins) else it }
                    appToEditLimit = null
                }
            }
        )
    }
}

@Composable
fun AppListItem(app: AppItemInfo, isSelected: Boolean, onToggle: () -> Unit, onEditLimit: () -> Unit) {
    val context = LocalContext.current
    var icon by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(app.packageName) {
        withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(app.packageName)
                icon = drawable.toBitmap(120, 120)
            } catch (e: Exception) {}
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Image(bitmap = icon!!.asImageBitmap(), contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
        } else {
            Box(modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)))
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(app.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${app.limitMins}m limit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (isSelected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Rounded.Timer, contentDescription = null, modifier = Modifier.size(14.dp).clickable { onEditLimit() }, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
    }
}

@Composable
fun AppLimitDialog(app: AppItemInfo, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var mins by remember { mutableStateOf(app.limitMins.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Limit for ${app.name}") },
        text = {
            Column {
                Text("${mins.toInt()} minutes per day", fontWeight = FontWeight.Bold)
                Slider(value = mins, onValueChange = { mins = it }, valueRange = 5f..180f, steps = 35)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(mins.toInt()) }) { Text("Set Limit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
