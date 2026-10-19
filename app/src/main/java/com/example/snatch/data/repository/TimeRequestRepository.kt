package com.example.snatch.data.repository

import android.content.Context
import com.example.snatch.TimeExtensionStore
import com.example.snatch.data.model.RequestVote
import com.example.snatch.data.model.TimeGrant
import com.example.snatch.data.model.TimeRequest
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

class TimeRequestRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance()
) {
    suspend fun createTimeRequest(
        packageName: String,
        appName: String,
        requestedMinutes: Int,
        reason: String
    ): Result<String> {
        val currentUser = auth.currentUser
            ?: return Result.failure(IllegalStateException("Must be signed in to request extra time"))

        require(requestedMinutes in 1..240) { "Requested minutes must be between 1 and 240" }

        val uid = currentUser.uid
        val requesterName = currentUser.displayName?.ifBlank { null }
            ?: currentUser.email?.substringBefore("@")
            ?: "User_${uid.take(5)}"

        val requestId = UUID.randomUUID().toString()
        val request = TimeRequest(
            requestId = requestId,
            requesterUid = uid,
            requesterName = requesterName,
            packageName = packageName,
            appName = appName,
            requestedMinutes = requestedMinutes,
            reason = reason,
            status = "pending",
            createdAt = System.currentTimeMillis(),
            approveVotesCount = 0,
            rejectVotesCount = 0
        )

        return try {
            firestore.collection("groups").document("main")
                .collection("timeRequests").document(requestId)
                .set(request).await()
            Result.success(requestId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun voteOnRequest(
        requestId: String,
        approve: Boolean
    ): Result<Unit> {
        val currentUser = auth.currentUser
            ?: return Result.failure(IllegalStateException("Must be signed in to vote"))

        val uid = currentUser.uid
        val voterName = currentUser.displayName?.ifBlank { null }
            ?: currentUser.email?.substringBefore("@")
            ?: "User_${uid.take(5)}"

        return try {
            val reqDocRef = firestore.collection("groups").document("main")
                .collection("timeRequests").document(requestId)

            firestore.runTransaction { transaction ->
                val reqDoc = transaction.get(reqDocRef)
                val timeRequest = reqDoc.toObject(TimeRequest::class.java)
                    ?: throw IllegalStateException("Time request not found")

                if (timeRequest.requesterUid == uid) {
                    throw IllegalArgumentException("You cannot vote on your own request")
                }

                if (timeRequest.status != "pending") {
                    throw IllegalStateException("This request is already ${timeRequest.status}")
                }

                val voteDocRef = reqDocRef.collection("votes").document(uid)
                val vote = RequestVote(
                    uid = uid,
                    voterName = voterName,
                    approved = approve,
                    votedAt = System.currentTimeMillis()
                )
                transaction.set(voteDocRef, vote)

                val newApproveCount = timeRequest.approveVotesCount + (if (approve) 1 else 0)
                val newRejectCount = timeRequest.rejectVotesCount + (if (!approve) 1 else 0)

                val newStatus = when {
                    newApproveCount >= 1 -> "approved"
                    newRejectCount >= 1 -> "rejected"
                    else -> "pending"
                }

                transaction.update(
                    reqDocRef,
                    mapOf(
                        "approveVotesCount" to newApproveCount,
                        "rejectVotesCount" to newRejectCount,
                        "status" to newStatus
                    )
                )

                if (newStatus == "approved") {
                    val grantId = UUID.randomUUID().toString()
                    val grantRef = firestore.collection("groups").document("main")
                        .collection("grants").document(grantId)
                    val grant = TimeGrant(
                        grantId = grantId,
                        requestId = requestId,
                        recipientUid = timeRequest.requesterUid,
                        packageName = timeRequest.packageName,
                        minutes = timeRequest.requestedMinutes,
                        issuedAt = System.currentTimeMillis()
                    )
                    transaction.set(grantRef, grant)
                }
            }.await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeTimeRequests(): Flow<List<TimeRequest>> = callbackFlow {
        val listener = firestore.collection("groups").document("main")
            .collection("timeRequests")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val requests = snapshot?.documents?.mapNotNull {
                    it.toObject(TimeRequest::class.java)
                }?.sortedByDescending { it.createdAt } ?: emptyList()
                trySend(requests)
            }
        awaitClose { listener.remove() }
    }

    fun observeTimeRequestsWithNotifications(context: Context, currentUid: String): Flow<List<TimeRequest>> = callbackFlow {
        val notifiedRequestIds = mutableSetOf<String>()
        val listener = firestore.collection("groups").document("main")
            .collection("timeRequests")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val requests = snapshot?.documents?.mapNotNull {
                    it.toObject(TimeRequest::class.java)
                }?.sortedByDescending { it.createdAt } ?: emptyList()

                // Trigger notification for new requests from other members
                for (req in requests) {
                    if (req.requesterUid.isNotBlank() && req.requesterUid != currentUid && req.status == "pending") {
                        if (!notifiedRequestIds.contains(req.requestId)) {
                            notifiedRequestIds.add(req.requestId)
                            if (req.createdAt > System.currentTimeMillis() - 300_000L) {
                                com.example.snatch.fcm.NotificationHelper.showNotification(
                                    context = context,
                                    title = "Extra-Time Request Received! ⏳",
                                    message = "${req.requesterName} requested +${req.requestedMinutes} min for ${req.appName}. Tap to vote!"
                                )
                            }
                        }
                    }
                }

                trySend(requests)
            }
        awaitClose { listener.remove() }
    }

    fun observeUserVote(requestId: String, uid: String): Flow<RequestVote?> = callbackFlow {
        val listener = firestore.collection("groups").document("main")
            .collection("timeRequests").document(requestId)
            .collection("votes").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                val vote = snapshot?.toObject(RequestVote::class.java)
                trySend(vote)
            }
        awaitClose { listener.remove() }
    }

    fun observeUserGrantsAndSync(context: Context, uid: String): Flow<List<TimeGrant>> = callbackFlow {
        val listener = firestore.collection("groups").document("main")
            .collection("grants")
            .whereEqualTo("recipientUid", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val grants = snapshot?.documents?.mapNotNull {
                    it.toObject(TimeGrant::class.java)
                } ?: emptyList()

                // Sync each grant to local TimeExtensionStore
                for (grant in grants) {
                    if (grant.packageName.isNotBlank() && grant.minutes > 0) {
                        TimeExtensionStore.addExtraMinutes(context, grant.packageName, grant.minutes)
                    }
                }

                trySend(grants)
            }
        awaitClose { listener.remove() }
    }
}
