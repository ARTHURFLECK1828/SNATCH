package com.example.snatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.example.snatch.data.repository.AuthRepository
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

@Composable
fun AuthScreen(
    authRepository: AuthRepository
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    val isLoading by authRepository.isLoading.collectAsState()
    val authError by authRepository.authError.collectAsState()

    var isSignUpMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var googleSignInErrorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Snitch",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Screen-Time & Friend Verification",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isSignUpMode) "Create Account" else "Sign In",
                    style = MaterialTheme.typography.headlineSmall
                )

                if (isSignUpMode) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email Address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )

                val displayError = authError ?: googleSignInErrorMessage
                displayError?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    Button(
                        onClick = {
                            authRepository.clearError()
                            googleSignInErrorMessage = null
                            coroutineScope.launch {
                                if (isSignUpMode) {
                                    authRepository.signUpWithEmail(email.trim(), password, displayName.trim())
                                } else {
                                    authRepository.signInWithEmail(email.trim(), password)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = email.isNotBlank() && password.length >= 6
                    ) {
                        Text(text = if (isSignUpMode) "Sign Up" else "Sign In")
                    }

                    OutlinedButton(
                        onClick = {
                            authRepository.clearError()
                            googleSignInErrorMessage = null
                            coroutineScope.launch {
                                try {
                                    val rawNonce = UUID.randomUUID().toString()
                                    val md = MessageDigest.getInstance("SHA-256")
                                    val digest = md.digest(rawNonce.toByteArray())
                                    val hashedNonce = digest.fold("") { str, it -> str + "%02x".format(it) }

                                    val webClientId = try {
                                        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
                                        if (resId != 0) context.getString(resId) else "382139308893-0ui26o0clrlb58s5jcnm3snfllouebsr.apps.googleusercontent.com"
                                    } catch (_: Exception) {
                                        "382139308893-0ui26o0clrlb58s5jcnm3snfllouebsr.apps.googleusercontent.com"
                                    }

                                    val googleIdOption = GetGoogleIdOption.Builder()
                                        .setFilterByAuthorizedAccounts(false)
                                        .setServerClientId(webClientId)
                                        .setNonce(hashedNonce)
                                        .build()

                                    val request = GetCredentialRequest.Builder()
                                        .addCredentialOption(googleIdOption)
                                        .build()

                                    val result = credentialManager.getCredential(context, request)
                                    val credential = result.credential

                                    try {
                                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                                        authRepository.signInWithGoogleIdToken(googleIdTokenCredential.idToken)
                                    } catch (e: Exception) {
                                        googleSignInErrorMessage = "Failed to parse Google ID Token: ${e.message}"
                                    }
                                } catch (e: Exception) {
                                    googleSignInErrorMessage = e.message ?: "Google Sign-In failed"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Sign In with Google")
                    }

                    TextButton(
                        onClick = {
                            isSignUpMode = !isSignUpMode
                            authRepository.clearError()
                            googleSignInErrorMessage = null
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            text = if (isSignUpMode)
                                "Already have an account? Sign In"
                            else
                                "Need an account? Sign Up"
                        )
                    }
                }
            }
        }
    }
}
