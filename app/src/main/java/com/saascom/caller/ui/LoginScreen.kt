package com.saascom.caller.ui

import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saascom.caller.BuildConfig
import com.saascom.caller.R
import com.saascom.caller.Services
import com.saascom.caller.data.ApiException
import com.saascom.caller.data.SessionUser
import com.saascom.caller.data.SyncWorker
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The server field shows in debug builds, or when the release build has no CRM address.
    val askServer = BuildConfig.DEBUG || BuildConfig.CRM_URL.isBlank()

    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(Services.session.baseUrl) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val networkError = stringResource(R.string.error_network)
    val genericError = stringResource(R.string.error_generic)

    fun submit() {
        if (loading || email.isBlank() || password.isEmpty() || server.isBlank()) return
        loading = true
        error = null
        scope.launch {
            try {
                if (askServer) Services.session.baseUrl = server
                val result = Services.api.login(email.trim(), password, deviceName())
                Services.session.save(result.token, SessionUser(result.user.id, result.user.name, result.user.role))
                SyncWorker.schedule(context)
                launch { runCatching { Services.directory.sync() } }
                onLoggedIn()
            } catch (e: ApiException.Network) {
                error = networkError
            } catch (e: ApiException.Http) {
                error = e.message ?: genericError
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("Login", "unexpected login failure", e)
                error = genericError
            } finally {
                loading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text("SAASCOM Caller", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.login_title), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.login_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.login_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.login_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        if (askServer) {
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text(stringResource(R.string.login_server)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(
            onClick = ::submit,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.login_button), fontSize = 16.sp)
            }
        }
    }
}

/** One token per phone in the CRM («الأجهزة المرتبطة» shows this name). */
private fun deviceName(): String {
    val brand = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    val model = Build.MODEL
    return if (model.startsWith(brand, ignoreCase = true)) model else "$brand $model"
}
