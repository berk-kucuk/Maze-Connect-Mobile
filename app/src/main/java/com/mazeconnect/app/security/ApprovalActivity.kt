package com.mazeconnect.app.security

import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mazeconnect.app.service.Link
import com.mazeconnect.app.ui.components.MazeButton
import com.mazeconnect.app.ui.components.MazeLabel
import com.mazeconnect.app.ui.theme.LocalMazeColors
import com.mazeconnect.app.ui.theme.MazeColors
import com.mazeconnect.app.ui.theme.MazeConnectTheme
import com.mazeconnect.core.ApprovalPrompt
import com.mazeconnect.core.DeviceManager
import com.mazeconnect.core.approval.ApprovalPurpose
import com.mazeconnect.core.approval.ApprovalSigning

/**
 * Where the owner says yes or no to an approval: unlocking a computer this
 * phone asked to unlock, or a sudo/polkit prompt on one.
 *
 * What it shows comes from two places, and the difference matters. The
 * computer's **name** and the **meaning** of the request ("unlock its
 * screen", "administrator access") are this phone's own — from its pairing
 * record and the closed purpose set. The host, user and detail lines are the
 * computer's, bounded and checked for disguising characters, and shown as
 * the computer's words.
 *
 * Saying yes is two steps that cannot be skipped: for administrator access,
 * typing the code the computer's terminal shows (the signature only verifies
 * with that code); then the fingerprint, through BiometricPrompt with the
 * Signature as its CryptoObject, so the authentication is spent on exactly
 * this one signature. The window hides other apps' overlays and ignores
 * touches that arrive through one, so nothing drawn on top can steer the tap.
 */
class ApprovalActivity : ComponentActivity() {

    private val manager: DeviceManager by lazy { Link.manager(applicationContext) }
    private var cancel: CancellationSignal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }
        // Not a secret, but not something for the recents thumbnail either.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val unlockFor = intent.getStringExtra(EXTRA_UNLOCK_DEVICE)
        if (unlockFor != null && savedInstanceState == null) {
            if (!manager.requestUnlock(unlockFor)) {
                finish()
                return
            }
        }

        setContent {
            MazeConnectTheme {
                val prompt by manager.approval.collectAsState()
                val unlock by manager.unlock.collectAsState()
                val paired by manager.pairedDevices.collectAsState()
                ApprovalScreen(
                    prompt = prompt,
                    waitingForUnlock = unlockFor?.let { unlock[it] },
                    unlockName = unlockFor?.let { id ->
                        paired.firstOrNull { it.deviceId == id }?.deviceName ?: id
                    },
                    onApprove = ::approve,
                    onDecline = { p -> manager.answerApproval(p, null, "declined on the phone") },
                    onClose = {
                        manager.dismissApproval()
                        finish()
                    },
                )
            }
        }
        window.decorView.filterTouchesWhenObscured = true
    }

    override fun onDestroy() {
        cancel?.cancel()
        super.onDestroy()
    }

    private fun approve(prompt: ApprovalPrompt, code: String) {
        if (prompt.codeRequired && !ApprovalSigning.isWellFormedCode(code)) return
        val signature = try {
            ApprovalKeys.signature()
        } catch (_: KeyPermanentlyInvalidatedException) {
            // Fingerprints changed since the key was made: it is dead by
            // design. Remove it everywhere and say what to do.
            ApprovalKeys.delete()
            manager.setApprovalKey(null)
            manager.answerApproval(prompt, null,
                "this phone's fingerprints changed; approvals need setting up again")
            return
        } catch (e: Exception) {
            manager.answerApproval(prompt, null, "the approval key is not available")
            return
        }
        val input = prompt.signingInput(if (prompt.codeRequired) code else "")

        val title = when (prompt.purpose) {
            ApprovalPurpose.UNLOCK -> "Unlock ${prompt.computerName}"
            ApprovalPurpose.SUDO -> "Allow sudo on ${prompt.computerName}"
            ApprovalPurpose.POLKIT -> "Allow administrator access on ${prompt.computerName}"
        }
        val builder = BiometricPrompt.Builder(this)
            .setTitle(title)
            .setSubtitle("${prompt.user}@${prompt.host}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(ApprovalKeys.AUTHENTICATORS)
        } else {
            builder.setNegativeButton("Cancel", mainExecutor) { _, _ -> }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setConfirmationRequired(true)
        }
        val signal = CancellationSignal().also { cancel = it }
        builder.build().authenticate(
            BiometricPrompt.CryptoObject(signature),
            signal,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authed = result.cryptoObject?.signature ?: return
                    val signed = runCatching {
                        authed.update(input)
                        authed.sign()
                    }.getOrNull()
                    manager.answerApproval(prompt, signed,
                        if (signed == null) "could not sign" else null)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancelled or locked out: nothing is sent yet, so the
                    // owner can try again or decline from the screen.
                }
            },
        )
    }

    companion object {
        private const val EXTRA_UNLOCK_DEVICE = "unlock_device"

        /** Open for whatever approval is in front of the owner. */
        fun intent(context: Context) = Intent(context, ApprovalActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** Ask [deviceId] to unlock, and wait here for its challenge. */
        fun unlockIntent(context: Context, deviceId: String) =
            Intent(context, ApprovalActivity::class.java)
                .putExtra(EXTRA_UNLOCK_DEVICE, deviceId)
    }
}

@Composable
private fun ApprovalScreen(
    prompt: ApprovalPrompt?,
    waitingForUnlock: com.mazeconnect.core.UnlockState?,
    unlockName: String?,
    onApprove: (ApprovalPrompt, String) -> Unit,
    onDecline: (ApprovalPrompt) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalMazeColors.current
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MazeLabel("Approval")

            // Asked to unlock, challenge not here yet (or refused up front).
            if (prompt == null || (unlockName != null && prompt.purpose != ApprovalPurpose.UNLOCK)) {
                Text(
                    text = if (unlockName != null) "Unlock $unlockName" else "Nothing to approve",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MazeColors.Paper,
                )
                Text(
                    text = when {
                        waitingForUnlock?.asking == true -> "Asking the computer…"
                        waitingForUnlock?.message != null -> waitingForUnlock.message!!
                        else -> "There is no request open."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.dim,
                )
                MazeButton(text = "Close", onClick = onClose, primary = false)
                return@Column
            }

            val headline = when (prompt.purpose) {
                ApprovalPurpose.UNLOCK -> "Unlock ${prompt.computerName}?"
                ApprovalPurpose.SUDO -> "Allow sudo on ${prompt.computerName}?"
                ApprovalPurpose.POLKIT -> "Allow administrator access on ${prompt.computerName}?"
            }
            Text(headline, style = MaterialTheme.typography.headlineSmall, color = MazeColors.Paper)
            Text(
                text = when (prompt.purpose) {
                    ApprovalPurpose.UNLOCK ->
                        "Its screen unlocks as soon as you confirm with your fingerprint."
                    else ->
                        "Someone is asking for administrator rights on that computer. Only " +
                            "approve if it is you, at that computer, right now."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
            )
            // The computer's own words, labelled as such.
            Detail("Account", "${prompt.user}@${prompt.host}")
            if (prompt.detail.isNotEmpty()) Detail("Says", prompt.detail)

            when (prompt.status) {
                ApprovalPrompt.Status.OPEN -> {
                    var code by remember(prompt.requestId) { mutableStateOf("") }
                    if (prompt.codeRequired) {
                        Text(
                            text = "Type the two-digit code shown on the computer.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MazeColors.Paper,
                        )
                        OutlinedTextField(
                            value = code,
                            onValueChange = { typed ->
                                code = typed.filter { it in '0'..'9' }.take(2)
                            },
                            singleLine = true,
                            label = { Text("Code") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                autoCorrectEnabled = false,
                            ),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row {
                        MazeButton(
                            text = "Approve",
                            onClick = { onApprove(prompt, code) },
                            enabled = !prompt.codeRequired || ApprovalSigning.isWellFormedCode(code),
                        )
                        Spacer(Modifier.width(10.dp))
                        MazeButton(text = "Decline", onClick = { onDecline(prompt) }, primary = false)
                    }
                }
                ApprovalPrompt.Status.WAITING -> Text(
                    "Sent — waiting for the computer…",
                    style = MaterialTheme.typography.bodyMedium, color = colors.dim,
                )
                else -> {
                    Text(
                        text = when (prompt.status) {
                            ApprovalPrompt.Status.APPROVED -> when (prompt.purpose) {
                                ApprovalPurpose.UNLOCK -> "Unlocked."
                                else -> "Approved."
                            }
                            ApprovalPrompt.Status.REFUSED ->
                                "Not approved" + (prompt.message?.let { " — $it" } ?: ".")
                            else -> "Closed" + (prompt.message?.let { " — $it" } ?: ".")
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MazeColors.Paper,
                    )
                    MazeButton(text = "Done", onClick = onClose, primary = false)
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    val colors = LocalMazeColors.current
    Row {
        MazeLabel(label, Modifier.width(90.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.dim)
    }
}
