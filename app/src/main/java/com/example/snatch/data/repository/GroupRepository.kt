package com.example.snatch.data.repository

import com.example.snatch.data.model.Group
import com.example.snatch.data.model.GroupMember
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class GroupRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance()
) {
    suspend fun joinGroupWithInviteCode(inviteCode: String): Result<Unit> {
        val currentUser = auth.currentUser
            ?: return Result.failure(IllegalStateException("Must be signed in to join a group"))

        return try {
            // Call the trusted Cloud Function backend first
            val data = hashMapOf("inviteCode" to inviteCode.trim())
            functions.getHttpsCallable("joinGroup").call(data).await()
            Result.success(Unit)
        } catch (functionException: Exception) {
            // Fallback for direct Firestore validation (works 100% FREE on Spark plan)
            try {
                val groupDocRef = firestore.collection("groups").document("main")
                var groupDoc = groupDocRef.get().await()
                if (!groupDoc.exists()) {
                    // Seed initial main group metadata if missing
                    val initialGroup = Group(
                        groupId = "main",
                        name = "Snitch Shared Group",
                        inviteCode = "SNITCH2026",
                        createdAt = System.currentTimeMillis()
                    )
                    groupDocRef.set(initialGroup).await()
                    groupDoc = groupDocRef.get().await()
                }

                val expectedInviteCode = groupDoc.getString("inviteCode") ?: "SNITCH2026"
                if (!inviteCode.trim().equals(expectedInviteCode, ignoreCase = true)) {
                    return Result.failure(IllegalArgumentException("Invalid invite code."))
                }

                val uid = currentUser.uid
                val memberDocRef = firestore.collection("groups").document("main")
                    .collection("members").document(uid)

                val existingMember = memberDocRef.get().await()
                if (existingMember.exists()) {
                    return Result.failure(IllegalStateException("You are already a member of this group."))
                }

                val displayName = currentUser.displayName?.ifBlank { null }
                    ?: currentUser.email?.substringBefore("@")
                    ?: "User_${uid.take(5)}"

                val newMember = GroupMember(
                    uid = uid,
                    displayName = displayName,
                    email = currentUser.email ?: "",
                    role = "member",
                    joinedAt = System.currentTimeMillis()
                )

                memberDocRef.set(newMember).await()
                firestore.collection("users").document(uid)
                    .update("groupId", "main").await()

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    fun observeGroupMembers(): Flow<List<GroupMember>> = callbackFlow {
        val listener = firestore.collection("groups").document("main")
            .collection("members")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val members = snapshot?.documents?.mapNotNull {
                    it.toObject(GroupMember::class.java)
                } ?: emptyList()
                trySend(members)
            }
        awaitClose { listener.remove() }
    }

    fun observeGroupInfo(): Flow<Group?> = callbackFlow {
        val listener = firestore.collection("groups").document("main")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val group = snapshot?.toObject(Group::class.java)
                trySend(group)
            }
        awaitClose { listener.remove() }
    }
}
