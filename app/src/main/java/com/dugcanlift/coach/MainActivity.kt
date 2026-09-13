package com.dugcanlift.coach

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.dugcanlift.coach.ui.theme.CoachTheme

/** Hosts the app's single [CoachNavHost] (roster / client / connect) over the one [CoachApp]-owned repository. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as CoachApp).repo
        setContent {
            CoachTheme {
                CoachNavHost(repo = repo, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
