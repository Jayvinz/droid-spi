package com.example.droidreel.account.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.droidreel.base.api.AccountService
import com.example.droidreel.base.ui.DroidReelTheme
import com.example.droidreel.base.ui.ReelBackground
import com.example.droidreel.base.ui.ReelMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.jayvin.android.spi.ServiceLoader

/** Login screen. Returns `RESULT_OK` with a `userId` extra on success. */
class LoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DroidReelTheme {
                LoginScreen(
                    onSuccess = { userId ->
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_USER_ID, userId))
                        finish()
                    },
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_USER_ID = "userId"
    }
}

@Composable
private fun LoginScreen(
    onSuccess: (String) -> Unit,
    onBack: () -> Unit,
) {
    // The account UI never depends on the account implementation: it resolves the contract
    // through the SPI table. A missing entry is surfaced instead of crashing.
    val account = remember { ServiceLoader.load(AccountService::class.java) }
    val scope = rememberCoroutineScope()

    var loginName by remember { mutableStateOf("demo") }
    var password by remember { mutableStateOf("123456") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (busy) return
        val service = account
        if (service == null) {
            error = "Account module is not available"
            return
        }
        if (loginName.isBlank() || password.isBlank()) {
            error = "Enter both the login name and the password"
            return
        }
        error = null
        busy = true
        scope.launch {
            // A short pause makes the state visible; there is no network request.
            delay(300)
            val result = service.login(loginName, password)
            busy = false
            val user = result.user
            if (result.success && user != null) {
                onSuccess(user.id)
            } else {
                error = result.message ?: "Login failed"
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ReelBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = "DroidReel",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Spacer(Modifier.height(40.dp))
                Text(
                    text = "Welcome back",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Sign in to publish works and open the following feed.",
                    color = ReelMuted,
                    fontSize = 14.sp,
                )

                Spacer(Modifier.height(28.dp))
                OutlinedTextField(
                    value = loginName,
                    onValueChange = { loginName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Login name") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Demo account: demo / 123456",
                    color = ReelMuted,
                    fontSize = 13.sp,
                )

                error?.let { message ->
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                }

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { submit() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = !busy && account != null,
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(text = "Sign in", fontSize = 16.sp)
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Demo only: no server is contacted. The fixed test account is the " +
                        "only credential; the session is stored locally without a password.",
                    color = ReelMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
