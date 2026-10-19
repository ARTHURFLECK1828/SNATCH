package com.example.snatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.snatch.AppUsage
import com.example.snatch.data.model.TimeRequest
import com.example.snatch.data.model.UserProfile
import com.example.snatch.data.repository.TimeRequestRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRequestsSection(
    userProfile: UserProfile?,
    installedApps: List<AppUsage>,
    timeRequestRepository: TimeRequestRepository
) {
    val coroutineScope = rememberCoroutineScope()
    val requestsFlow = remember { timeRequestRepository.observeTimeRequests() }
    val timeRequests by requestsFlow.collectAsState(initial = emptyList())

    var expandedAppPicker by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<AppUsage?>(null) }
    var minutesInput by remember { mutableStateOf("15") }
    var reasonInput by remember { mutableStateOf("") }

    var isSubmitting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Extra-Time Requests & Voting",
                style = MaterialTheme.typography.titleLarge
            )

            if (userProfile?.groupId != "main") {
                Text(
                    text = "You must join the shared friend group above before requesting or voting on extra time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "Request Extra Minutes",
                    style = MaterialTheme.typography.titleMedium
                )

                // App selector
                ExposedDropdownMenuBox(
                    expanded = expandedAppPicker,
                    onExpandedChange = { expandedAppPicker = !expandedAppPicker }
                ) {
                    OutlinedTextField(
                        value = selectedApp?.appName ?: "Select Installed App",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAppPicker) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = expandedAppPicker,
                        onDismissRequest = { expandedAppPicker = false }
                    ) {
                        installedApps.forEach { app ->
                            DropdownMenuItem(
                                text = { Text("${app.appName} (${app.packageName})") },
                                onClick = {
                                    selectedApp = app
                                    expandedAppPicker = false
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = minutesInput,
                        onValueChange = { input ->
                            if (input.length <= 3 && input.all { it.isDigit() }) {
                                minutesInput = input
                            }
                        },
                        label = { Text("Extra Minutes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = reasonInput,
                        onValueChange = { reasonInput = it },
                        label = { Text("Reason (optional)") },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                }

                Button(
                    onClick = {
                        val app = selectedApp ?: return@Button
                        val mins = minutesInput.toIntOrNull() ?: return@Button
                        if (mins <= 0) return@Button

                        isSubmitting = true
                        statusMessage = null
                        coroutineScope.launch {
                            val result = timeRequestRepository.createTimeRequest(
                                packageName = app.packageName,
                                appName = app.appName,
                                requestedMinutes = mins,
                                reason = reasonInput.trim()
                            )
                            isSubmitting = false
                            result.fold(
                                onSuccess = {
                                    statusMessage = "Request submitted! Awaiting friend votes."
                                    reasonInput = ""
                                },
                                onFailure = { err ->
                                    statusMessage = "Error: ${err.message}"
                                }
                            )
                        }
                    },
                    enabled = !isSubmitting && selectedApp != null && minutesInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator()
                    } else {
                        Text("Submit Time Request")
                    }
                }

                statusMessage?.let { msg ->
                    Text(
                        text = msg,
                        color = if (msg.startsWith("Request submitted"))
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Group Extra Time Requests:",
                    style = MaterialTheme.typography.titleMedium
                )

                if (timeRequests.isEmpty()) {
                    Text("No active or recent time requests.")
                } else {
                    LazyColumn(
                        modifier = Modifier.height(260.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(timeRequests, key = { it.requestId }) { req ->
                            RequestItem(
                                request = req,
                                currentUid = userProfile?.uid ?: "",
                                timeRequestRepository = timeRequestRepository
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RequestItem(
    request: TimeRequest,
    currentUid: String,
    timeRequestRepository: TimeRequestRepository
) {
    val coroutineScope = rememberCoroutineScope()
    val isRequester = request.requesterUid == currentUid

    val userVoteFlow = remember(request.requestId, currentUid) {
        timeRequestRepository.observeUserVote(request.requestId, currentUid)
    }
    val userVote by userVoteFlow.collectAsState(initial = null)

    var isVoting by remember { mutableStateOf(false) }
    var voteError by remember { mutableStateOf<String?>(null) }

    val statusColor = when (request.status) {
        "approved" -> MaterialTheme.colorScheme.primary
        "rejected" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${request.appName} (+${request.requestedMinutes} min)",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = request.status.uppercase(),
                    color = statusColor,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Text(
                text = "Requested by: ${request.requesterName}" + if (isRequester) " (You)" else "",
                style = MaterialTheme.typography.bodyMedium
            )

            if (request.reason.isNotBlank()) {
                Text(
                    text = "Reason: ${request.reason}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                text = "Votes: ${request.approveVotesCount} Approve, ${request.rejectVotesCount} Reject",
                style = MaterialTheme.typography.labelMedium
            )

            voteError?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (request.status == "pending") {
                if (isRequester) {
                    Text(
                        text = "You requested this extra time. Waiting for group member votes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (userVote != null) {
                    Text(
                        text = "Your vote: ${if (userVote!!.approved) "Approved" else "Rejected"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isVoting = true
                                voteError = null
                                coroutineScope.launch {
                                    val res = timeRequestRepository.voteOnRequest(request.requestId, true)
                                    isVoting = false
                                    res.onFailure { voteError = it.message }
                                }
                            },
                            enabled = !isVoting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Approve")
                        }

                        OutlinedButton(
                            onClick = {
                                isVoting = true
                                voteError = null
                                coroutineScope.launch {
                                    val res = timeRequestRepository.voteOnRequest(request.requestId, false)
                                    isVoting = false
                                    res.onFailure { voteError = it.message }
                                }
                            },
                            enabled = !isVoting,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Reject")
                        }
                    }
                }
            }
        }
    }
}
