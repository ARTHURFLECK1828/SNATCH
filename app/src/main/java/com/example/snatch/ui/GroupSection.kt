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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.snatch.data.model.GroupMember
import com.example.snatch.data.model.UserProfile
import com.example.snatch.data.repository.GroupRepository
import java.text.DateFormat
import java.util.Date

@Composable
fun GroupSection(
    userProfile: UserProfile?,
    groupRepository: GroupRepository
) {
    val membersFlow = remember(userProfile?.groupId) {
        groupRepository.observeGroupMembers()
    }
    val members by membersFlow.collectAsState(initial = emptyList())

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Single Shared Friend Group",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = "Group: Snitch Shared Group (main)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Shared Invite Code: SNITCH2026 (All signed-in users automatically join this group)",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Group Members (${members.size}):",
                style = MaterialTheme.typography.titleSmall
            )

            if (members.isEmpty()) {
                Text("Loading group members...")
            } else {
                LazyColumn(
                    modifier = Modifier.height(200.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(members, key = { it.uid }) { member ->
                        MemberItem(member, isCurrentUser = member.uid == userProfile?.uid)
                    }
                }
            }
        }
    }
}

@Composable
fun MemberItem(
    member: GroupMember,
    isCurrentUser: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = member.displayName + if (isCurrentUser) " (You)" else "",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = member.email,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(member.joinedAt)),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
