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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.snatch.data.repository.AuthRepository
import com.example.snatch.data.repository.GroupRepository
import com.example.snatch.data.repository.TimeRequestRepository
import com.example.snatch.ui.AuthScreen
import com.example.snatch.ui.GroupSection
import com.example.snatch.ui.TimeRequestsSection
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

data class AppUsage(
    val packageName: String,
    val appName: String,
    val foregroundMillis: Long
)

class MainActivity : ComponentActivity() {
    private val authRepository by lazy { AuthRepository() }
    private val groupRepository by lazy { GroupRepository() }
    private val timeRequestRepository by lazy { TimeRequestRepository() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.snatch.fcm.NotificationHelper.createNotificationChannel(this)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val currentUser by authRepository.currentUser.collectAsState()
                    val userProfile by authRepository.userProfile.collectAsState()

                    if (currentUser == null) {
                        AuthScreen(authRepository = authRepository)
                    } else {
                        MainAppScreen(
                            authRepository = authRepository,
                            groupRepository = groupRepository,
                            timeRequestRepository = timeRequestRepository
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MainAppScreen(
    authRepository: AuthRepository,
    groupRepository: GroupRepository,
    timeRequestRepository: TimeRequestRepository
) {
    val context = LocalContext.current
    val currentUser by authRepository.currentUser.collectAsState()
    val userProfile by authRepository.userProfile.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Usage & Limits", "Friend Group", "Time Requests")

    // Sync grants and notifications in realtime whenever signed in
    LaunchedEffect(currentUser?.uid) {
        currentUser?.uid?.let { uid ->
            // Subscribe to group FCM topic and save token
            try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance()
                    .subscribeToTopic("snitch_group_main").await()
                val token = com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                db.collection("users").document(uid).update("fcmToken", token)
                db.collection("groups").document("main").collection("members").document(uid).update("fcmToken", token)
            } catch (_: Exception) {}

            coroutineScope {
                launch {
                    timeRequestRepository.observeUserGrantsAndSync(context, uid).collect {}
                }

                launch {
                    timeRequestRepository.observeTimeRequestsWithNotifications(context, uid).collect {}
                }
            }
        }
    }

    var usageList by remember { mutableStateOf(emptyList<AppUsage>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Header
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = userProfile?.displayName?.ifBlank { null }
                            ?: currentUser?.email
                            ?: "User",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = currentUser?.email ?: "",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OutlinedButton(onClick = { authRepository.signOut() }) {
                    Text("Sign Out")
                }
            }
        }

        ScrollableTabRow(selectedTabIndex = selectedTabIndex) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTabIndex) {
            0 -> UsageAndLimitsTab(onUsageUpdated = { usageList = it })
            1 -> GroupSection(userProfile = userProfile, groupRepository = groupRepository)
            2 -> TimeRequestsSection(
                userProfile = userProfile,
                installedApps = usageList,
                timeRequestRepository = timeRequestRepository
            )
        }
    }
}

@Composable
fun UsageAndLimitsTab(
    onUsageUpdated: (List<AppUsage>) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var hasAccess by remember { mutableStateOf(hasUsageAccess(context)) }
    var usage by remember { mutableStateOf(emptyList<AppUsage>()) }
    var baseLimits by remember { mutableStateOf(LimitStore.allLimits(context)) }
    var extraMinutesMap by remember { mutableStateOf(TimeExtensionStore.allExtraMinutes(context)) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        hasAccess = hasUsageAccess(context)
        baseLimits = LimitStore.allLimits(context)
        extraMinutesMap = TimeExtensionStore.allExtraMinutes(context)
        if (hasAccess) {
            try {
                usage = readTodayUsage(context)
                onUsageUpdated(usage)
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

    val exceededCount = usage.count { app ->
        val effectiveLimit = TimeExtensionStore.getEffectiveLimitMinutes(context, app.packageName)
        effectiveLimit != null && app.foregroundMillis >= effectiveLimit * 60_000L
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Today: ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())}")
        Text("Limits exceeded: $exceededCount")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { refresh() }) { Text("Refresh") }
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }) { Text("Usage Access") }
        }

        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) {
            Text("Blocking Service Settings")
        }

        error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

        if (!hasAccess) {
            Text("Enable Usage Access to calculate daily limits.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(usage, key = { it.packageName }) { app ->
                    val baseLimit = baseLimits[app.packageName]
                    val extraMins = TimeExtensionStore.getExtraMinutes(context, app.packageName)
                    val effectiveLimit = if (baseLimit != null) baseLimit + extraMins else null

                    val isExceeded = effectiveLimit != null &&
                            app.foregroundMillis >= effectiveLimit * 60_000L

                    var input by remember(app.packageName, baseLimit) {
                        mutableStateOf(baseLimit?.toString() ?: "")
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

                            if (baseLimit != null) {
                                val limitText = if (extraMins > 0) {
                                    "Base limit: ${baseLimit}m + Extra: ${extraMins}m = Effective: ${effectiveLimit}m"
                                } else {
                                    "Daily Limit: ${baseLimit} min"
                                }

                                Text(
                                    if (isExceeded) "LIMIT EXCEEDED ($limitText)"
                                    else limitText
                                )
                                LinearProgressIndicator(
                                    progress = {
                                        val totalLimit = effectiveLimit ?: baseLimit
                                        (app.foregroundMillis.toFloat() /
                                                (totalLimit * 60_000f)).coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Text("No base limit set")
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { value ->
                                        if (value.length <= 4 && value.all { it.isDigit() }) {
                                            input = value
                                        }
                                    },
                                    label = { Text("Base Minutes/day") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(onClick = {
                                    val minutes = input.toIntOrNull()
                                    if (minutes != null && minutes in 1..1440) {
                                        LimitStore.setLimitMinutes(context, app.packageName, minutes)
                                        refresh()
                                    }
                                }) { Text("Save") }
                            }

                            if (baseLimit != null) {
                                Button(onClick = {
                                    LimitStore.removeLimit(context, app.packageName)
                                    refresh()
                                }) { Text("Remove Limit") }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    ) == AppOpsManager.MODE_ALLOWED
}

fun readTodayUsage(context: Context): List<AppUsage> {
    val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
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
        .associate {
            it.activityInfo.packageName to it.loadLabel(context.packageManager).toString()
        }

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
