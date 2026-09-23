package com.dugcanlift.coach.ui

import com.dugcanlift.coach.data.AppAppearance
import com.dugcanlift.coach.data.AppearanceStore
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Row
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.saveable.rememberSaveable
import com.dugcanlift.coach.ui.adaptive.AdaptiveLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dugcanlift.coach.data.BackupOutcome
import com.dugcanlift.coach.data.BackupService
import com.dugcanlift.coach.data.CookRepository
import com.dugcanlift.coach.data.RoadPickRepository
import com.dugcanlift.coach.data.SentPlanRepository
import com.dugcanlift.coach.data.TrainRepository
import com.dugcanlift.coach.data.ClientRepository
import java.io.File
import kotlinx.coroutines.launch

private const val PREFS_NAME = "connect"
private const val PREF_COACH_NAME = "coachName"
private const val PREF_COACH_EMAIL = "coachEmail"
private const val BACKUP_FILENAME = "coach-backup.json"

/**
 * Coach iOS's own [inviteText] wording (coach-ios/Sources/App/ConnectView.swift). With no name set
 * it reads "I'm your coach on LIFT" -- substituting "your coach" as the name produced
 * "I'm your coach, your coach on LIFT". An empty email reads as "[enter your email above]".
 */
internal fun inviteText(coachName: String, coachEmail: String): String {
    val intro = if (coachName.isBlank()) "I'm your coach on LIFT" else "I'm ${coachName.trim()}, your coach on LIFT"
    val email = coachEmail.ifEmpty { "[enter your email above]" }
    return "Hi! $intro. To share your training and " +
        "nutrition log with me, open LIFT, go to Settings, and use " +
        "\"Send to Coach\" with this email address: $email"
}

/** Where this device's own copy of the opaque library (recipes/meals/routines/sessions) from the
 * most recent restore is kept, so a later Save Backup can carry it back out untouched -- otherwise
 * the round trip promised by `BackupCodec` would only hold within a single restore-then-export
 * call, not across app runs. See `PreservedLibraryStore` for the load/update rules. */
private fun preservedLibraryFile(context: Context) = File(context.filesDir, "preserved-library.json")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(repo: ClientRepository, onBack: () -> Unit, showBack: Boolean = true) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val appearanceStore = remember { AppearanceStore.get(context) }
    val appearance by appearanceStore.appearance.collectAsState()

    var coachName by remember { mutableStateOf(prefs.getString(PREF_COACH_NAME, "") ?: "") }
    var coachEmail by remember { mutableStateOf(prefs.getString(PREF_COACH_EMAIL, "") ?: "") }
    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var statusIsError by rememberSaveable { mutableStateOf(false) }

    fun updateCoachName(value: String) {
        coachName = value
        prefs.edit().putString(PREF_COACH_NAME, value).apply()
    }
    fun updateCoachEmail(value: String) {
        coachEmail = value
        prefs.edit().putString(PREF_COACH_EMAIL, value).apply()
    }

    // All the file work lives in BackupService, off the main thread -- these callbacks do nothing
    // but launch it and render the outcome. See BackupService for why.
    val scope = rememberCoroutineScope()
    val backups = remember(repo, context) {
        BackupService(repo, preservedLibraryFile(context),
                      CookRepository(context.filesDir), TrainRepository(context.filesDir),
                      RoadPickRepository(context.filesDir), SentPlanRepository(context.filesDir))
    }

    fun show(outcome: BackupOutcome) {
        statusMessage = outcome.message
        statusIsError = outcome.isError
    }

    val createBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { show(backups.export { context.contentResolver.openOutputStream(uri) }) }
    }

    val restoreBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { show(backups.restore { context.contentResolver.openInputStream(uri) }) }
    }

    Scaffold(
        topBar = {
            // Back as the other pushed screens (Cook, Train, a client) have it. Without it the
            // only way off Connect was the system back gesture.
            TopAppBar(
                title = { Text("Connect") },
                navigationIcon = { if (showBack) TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // A short form: on a wide window it stays form-width, centred.
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = AdaptiveLayout.MAX_FORM_DP.dp)
                .padding(16.dp)
        ) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                AppAppearance.entries.forEach { option ->
                    FilterChip(
                        selected = appearance == option,
                        onClick = { appearanceStore.set(option) },
                        label = { Text(option.label) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            Text(
                "System follows your phone's light or dark setting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))

            Text("Your Info", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = coachName,
                onValueChange = ::updateCoachName,
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = coachEmail,
                onValueChange = ::updateCoachEmail,
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Invite a Client", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(inviteText(coachName, coachEmail), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, inviteText(coachName, coachEmail))
                    }
                    context.startActivity(Intent.createChooser(send, "Share Invite"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Share Invite")
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Backup", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { createBackupLauncher.launch(BACKUP_FILENAME) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Backup")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { restoreBackupLauncher.launch(arrayOf("application/json")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Restore from Backup")
            }
            statusMessage?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "Everything stays on this device. There's no account and no server — " +
                    "a backup file is the only way to move your roster, your recipes and " +
                    "your workouts to another device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
