package com.example.snatch.fcm

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title
            ?: message.data["title"]
            ?: "Extra-Time Request Received! ⏳"

        val body = message.notification?.body
            ?: message.data["body"]
            ?: message.data["appName"]?.let { app ->
                val requester = message.data["requesterName"] ?: "A friend"
                val mins = message.data["requestedMinutes"] ?: "some"
                "$requester requested +$mins min for $app."
            } ?: "A friend requested extra screen time. Tap to vote!"

        NotificationHelper.showNotification(
            context = applicationContext,
            title = title,
            message = body
        )
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        saveFcmTokenToFirestore(token)
    }

    private fun saveFcmTokenToFirestore(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseFirestore.getInstance()

        db.collection("users").document(uid)
            .update("fcmToken", token)

        db.collection("groups").document("main")
            .collection("members").document(uid)
            .update("fcmToken", token)
    }
}
