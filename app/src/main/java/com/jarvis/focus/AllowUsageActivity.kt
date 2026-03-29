package com.jarvis.focus

import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jarvis.focus.ui.theme.FocusTheme

class AllowUsageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)

        val pkg = intent.getStringExtra("package_name") ?: return finish()
        val message = intent.getStringExtra("message") ?: "Choose an option"

        setContent {
            FocusTheme {
                AllowUsageScreen(pkg, message)
            }
        }
    }
}

@Composable
fun AllowUsageScreen(pkg: String, message: String) {
    val ctx = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(message, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Button(onClick = {
                            val home = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            ctx.startActivity(home)
                        }) {
                            Text("Close App")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            UsageMonitorService.tempAllowedApps[pkg] = System.currentTimeMillis() + 5 * 60_000
                            (ctx as? ComponentActivity)?.finish()
                        }) {
                            Text("Allow 5m")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Button(onClick = {
                            UsageMonitorService.tempAllowedApps[pkg] = System.currentTimeMillis() + 10 * 60_000
                            (ctx as? ComponentActivity)?.finish()
                        }) {
                            Text("Allow 10m")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            UsageMonitorService.tempAllowedApps[pkg] = System.currentTimeMillis() + 15 * 60_000
                            (ctx as? ComponentActivity)?.finish()
                        }) {
                            Text("Allow 15m")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = {
                            UsageMonitorService.tempAllowedApps[pkg] = System.currentTimeMillis() + 30 * 60_000
                            (ctx as? ComponentActivity)?.finish()
                        }) {
                            Text("Allow 30m")
                        }
                    }
                }
            }
        }
    }
}
