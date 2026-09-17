package com.dugcanlift.coach

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dugcanlift.coach.ui.theme.LocalDclDark
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.dugcanlift.coach.ui.adaptive.ProvideWindowLayout
import kotlinx.coroutines.launch
import com.dugcanlift.coach.data.fragmentToImport
import com.dugcanlift.coach.ui.theme.CoachTheme

/**
 * Hosts the app's single [CoachNavHost] (roster / client / connect) over the one [CoachApp]-owned
 * repository, and is the entry point for tap-to-import: a coach tapping a
 * `https://www.dugcanlift.com/coach/#<fragment>` App Link, or sharing text containing one into
 * Coach via the share sheet, lands here through the manifest's VIEW/SEND intent filters.
 *
 * [pendingImportFragment] is only populated from the *launching* intent when [onCreate] runs with
 * a null `savedInstanceState` -- a configuration change that recreates the activity (a theme or
 * density change; rotation, resizing and folding do not, see the manifest's `configChanges`) comes back with
 * the same [getIntent] but a non-null `savedInstanceState`, so that branch is skipped and the link
 * is not imported a second time. A fresh tap or share while the app is already running (this
 * activity is `singleTop`) arrives via [onNewIntent] instead, which always sets a new pending
 * fragment since that is a genuinely new user action.
 */
class MainActivity : ComponentActivity() {
    private var pendingImportFragment by mutableStateOf<String?>(null)
    private var verticalHinge by mutableStateOf<Pair<Int, Int>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            pendingImportFragment = fragmentFrom(intent)
        }
        watchFolds()
        val repo = (application as CoachApp).repo
        setContent {
            CoachTheme {
                // System bar icons follow the appearance actually drawn. The default
                // enableEdgeToEdge() follows the phone, which with Light chosen on a
                // dark phone leaves light icons on parchment.
                val dark = LocalDclDark.current
                LaunchedEffect(dark) {
                    val style = if (dark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                ProvideWindowLayout(hingePx = verticalHinge, modifier = Modifier.fillMaxSize()) {
                    CoachNavHost(
                        repo = repo,
                        modifier = Modifier.fillMaxSize(),
                        pendingImportFragment = pendingImportFragment,
                        onImportHandled = { pendingImportFragment = null }
                    )
                }
            }
        }
    }

    /**
     * A fold that separates the window -- a book-posture foldable, or a dual-screen device's gap --
     * as its left and right edge in window pixels. Only a vertical one: see
     * [com.dugcanlift.coach.ui.adaptive.VerticalHinge]. `androidx.window` is already on the
     * classpath through Material 3, so this costs no new code in the APK.
     */
    private fun watchFolds() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity)
                    .windowLayoutInfo(this@MainActivity)
                    .collect { info ->
                        verticalHinge = info.displayFeatures
                            .filterIsInstance<FoldingFeature>()
                            .firstOrNull { it.isSeparating && it.orientation == FoldingFeature.Orientation.VERTICAL }
                            ?.bounds?.let { it.left to it.right }
                    }
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
