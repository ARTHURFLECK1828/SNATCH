package com.example.snatch.data.repository

import com.example.snatch.data.model.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _userProfile = MutableStateFlow<UserProfile?>(null)
    val userProfile: StateFlow<UserProfile?> = _userProfile.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            _currentUser.value = user
            if (user != null) {
                scope.launch { syncUserProfile(user) }
            } else {
                _userProfile.value = null
            }
        }
        auth.currentUser?.let { user ->
            scope.launch { syncUserProfile(user) }
        }
    }

    fun clearError() {
        _authError.value = null
    }

    private suspend fun ensureGroupMembership(uid: String, displayName: String, email: String) {
        try {
            val groupDocRef = firestore.collection("groups").document("main")
            val groupDoc = groupDocRef.get().await()
            if (!groupDoc.exists()) {
                val group = com.example.snatch.data.model.Group(
                    groupId = "main",
                    name = "Snitch Shared Group",
                    inviteCode = "SNITCH2026",
                    createdAt = System.currentTimeMillis()
                )
                groupDocRef.set(group).await()
            }

            val memberRef = groupDocRef.collection("members").document(uid)
            val memberSnap = memberRef.get().await()
            if (!memberSnap.exists()) {
                val member = com.example.snatch.data.model.GroupMember(
                    uid = uid,
                    displayName = displayName,
                    email = email,
                    role = "member",
                    joinedAt = System.currentTimeMillis()
                )
                memberRef.set(member).await()
            }

            firestore.collection("users").document(uid)
                .update("groupId", "main").await()
        } catch (_: Exception) {
            // Background Auto-Join Group
        }
    }

    suspend fun syncUserProfile(user: FirebaseUser): UserProfile? {
        val uid = user.uid // Use authenticated UID
        return try {
            val docRef = firestore.collection("users").document(uid)
            val snapshot = docRef.get().await()
            val existing = snapshot.toObject(UserProfile::class.java)

            val displayName = user.displayName?.takeIf { it.isNotBlank() }
                ?: existing?.displayName?.takeIf { it.isNotBlank() }
                ?: user.email?.substringBefore("@")
                ?: "User_${uid.take(5)}"
            val userEmail = user.email ?: existing?.email ?: ""

            ensureGroupMembership(uid, displayName, userEmail)

            val profile = UserProfile(
                uid = uid,
                displayName = displayName,
                email = userEmail,
                groupId = "main",
                createdAt = existing?.createdAt ?: System.currentTimeMillis()
            )
            docRef.set(profile).await()
            _userProfile.value = profile
            profile
        } catch (e: Exception) {
            _authError.value = "Failed to sync profile: ${e.message}"
            null
        }
    }

    suspend fun signInWithEmail(email: String, pass: String): Boolean {
        _isLoading.value = true
        _authError.value = null
        return try {
            val result = auth.signInWithEmailAndPassword(email, pass).await()
            result.user?.let { syncUserProfile(it) }
            true
        } catch (e: Exception) {
            _authError.value = e.message ?: "Sign in failed"
            false
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun signUpWithEmail(email: String, pass: String, displayName: String): Boolean {
        _isLoading.value = true
        _authError.value = null
        return try {
            val result = auth.createUserWithEmailAndPassword(email, pass).await()
            val user = result.user ?: throw IllegalStateException("User creation failed")
            val profile = UserProfile(
                uid = user.uid,
                displayName = displayName.ifBlank { email.substringBefore("@") },
                email = email,
                groupId = null,
                createdAt = System.currentTimeMillis()
            )
            firestore.collection("users").document(user.uid).set(profile).await()
            _userProfile.value = profile
            true
        } catch (e: Exception) {
            _authError.value = e.message ?: "Sign up failed"
            false
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun signInWithGoogleIdToken(idToken: String): Boolean {
        _isLoading.value = true
        _authError.value = null
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = auth.signInWithCredential(credential).await()
            result.user?.let { syncUserProfile(it) }
            true
        } catch (e: Exception) {
            _authError.value = e.message ?: "Google sign in failed"
            false
        } finally {
            _isLoading.value = false
        }
    }

    fun signOut() {
        auth.signOut()
        _userProfile.value = null
        _currentUser.value = null
    }
}
