package com.dugcanlift.coach

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dugcanlift.coach.data.fragmentToImport
import com.dugcanlift.coach.ui.theme.CoachTheme

/**
 * Hosts the app's single [CoachNavHost] (roster / client / connect) over the one [CoachApp]-owned
 * repository, and is the entry point for tap-to-import: a coach tapping a
 * `https://www.dugcanlift.com/coach/#<fragment>` App Link, or sharing text containing one into
 * Coach via the share sheet, lands here through the manifest's VIEW/SEND intent filters.
 *
 * [pendingImportFragment] is only populated from the *launching* intent when [onCreate] runs with
 * a null `savedInstanceState` -- a configuration change (e.g. rotation) recreates the activity with
 * the same [getIntent] but a non-null `savedInstanceState`, so that branch is skipped and the link
 * is not imported a second time. A fresh tap or share while the app is already running (this
 * activity is `singleTop`) arrives via [onNewIntent] instead, which always sets a new pending
 * fragment since that is a genuinely new user action.
 */
class MainActivity : ComponentActivity() {
    private var pendingImportFragment by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            pendingImportFragment = fragmentFrom(intent)
        }
        val repo = (application as CoachApp).repo
        setContent {
            CoachTheme {
                CoachNavHost(
                    repo = repo,
                    modifier = Modifier.fillMaxSize(),
                    pendingImportFragment = pendingImportFragment,
                    onImportHandled = { pendingImportFragment = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingImportFragment = fragmentFrom(intent)
    }

    private fun fragmentFrom(intent: Intent): String? =
        fragmentToImport(
            dataFragment = intent.data?.fragment,
            action = intent.action,
            extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
        )
}
