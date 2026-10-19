
package com.example.snatch

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

data class AppUsage(
    val packageName: String,
    val appName: String,
    val foregroundMillis: Long
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    UsageScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // UsageScreen refreshes when it resumes via lifecycle below.
    }
}

@Composable
fun UsageScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var hasAccess by remember { mutableStateOf(hasUsageAccess(context)) }
    var usage by remember { mutableStateOf(emptyList<AppUsage>()) }
    var limits by remember { mutableStateOf(LimitStore.allLimits(context)) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        hasAccess = hasUsageAccess(context)
        limits = LimitStore.allLimits(context)
        if (hasAccess) {
            try {
                usage = readTodayUsage(context)
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Unable to read usage statistics."
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { refresh() }

    val exceeded = usage.count { app ->
        val limit = limits[app.packageName]
        limit != null && app.foregroundMillis >= limit * 60_000L
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Snitch", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Today: ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())}"
        )
        Text("Limits exceeded: $exceeded")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { refresh() }) { Text("Refresh") }
            Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                )
            }) { Text("Usage access") }
        }

        Button(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
        }) {
            Text("Blocking service settings")
        }

        error?.let { Text("Error: $it") }

        if (!hasAccess) {
            Text("Enable Usage Access to calculate daily limits.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(usage, key = { it.packageName }) { app ->
                    val limit = limits[app.packageName]
                    val usedMinutes = app.foregroundMillis / 60_000L
                    val isExceeded = limit != null &&
                            app.foregroundMillis >= limit * 60_000L
                    var input by remember(app.packageName, limit) {
                        mutableStateOf(limit?.toString() ?: "")
                    }

                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                app.appName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text("Used today: ${formatDuration(app.foregroundMillis)}")
                            Text("Package: ${app.packageName}")

                            if (limit != null) {
                                Text(
                                    if (isExceeded) "LIMIT EXCEEDED"
                                    else "Limit: ${limit} min"
                                )
                                LinearProgressIndicator(
                                    progress = {
                                        (app.foregroundMillis.toFloat() /
                                                (limit * 60_000f)).coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Text("No daily limit set")
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { value ->
                                        if (value.length <= 4 &&
                                            value.all { it.isDigit() }) {
                                            input = value
                                        }
                                    },
                                    label = { Text("Minutes/day") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(onClick = {
                                    val minutes = input.toIntOrNull()
                                    if (minutes != null && minutes in 1..1440) {
                                        LimitStore.setLimitMinutes(
                                            context, app.packageName, minutes
                                        )
                                        limits = LimitStore.allLimits(context)
                                    }
                                }) { Text("Save") }
                            }

                            if (limit != null) {
                                Button(onClick = {
                                    LimitStore.removeLimit(context, app.packageName)
                                    limits = LimitStore.allLimits(context)
                                }) { Text("Remove limit") }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
            as AppOpsManager
    return appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    ) == AppOpsManager.MODE_ALLOWED
}

fun readTodayUsage(context: Context): List<AppUsage> {
    val manager = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as UsageStatsManager
    val now = System.currentTimeMillis()
    val calendar = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    val stats = manager.queryUsageStats(
        UsageStatsManager.INTERVAL_DAILY,
        calendar.timeInMillis,
        now
    )

    val launcherIntent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)

    val labels = context.packageManager
        .queryIntentActivities(launcherIntent, 0)
        .associate { it.activityInfo.packageName to
                it.loadLabel(context.packageManager).toString() }

    return stats.asSequence()
        .filter { it.totalTimeInForeground > 0L }
        .filter { it.packageName != context.packageName }
        .groupBy { it.packageName }
        .mapNotNull { (pkg, entries) ->
            val label = labels[pkg] ?: return@mapNotNull null
            AppUsage(
                pkg,
                label,
                entries.sumOf { it.totalTimeInForeground }
            )
        }
        .sortedByDescending { it.foregroundMillis }
}

fun formatDuration(millis: Long): String {
    val minutes = millis / 60_000L
    return if (minutes >= 60) {
        "${minutes / 60}h ${minutes % 60}m"
    } else {
        "${minutes}m"
    }
}
