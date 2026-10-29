package com.dugcanlift.coach.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dugcanlift.coach.data.BookmarkletStore
import androidx.compose.foundation.BorderStroke
import com.dugcanlift.coach.ui.theme.dclOutlinedButtonColors
import com.dugcanlift.coach.ui.theme.dclTextButtonColors

/**
 * The words on the sheet: the wording every build shares for this feature, with
 * {App} = Coach, word for word. Phone steps only -- the computer note and the
 * Safari line are not Android's. Change them in every build or in none.
 */
internal object BookmarkletText {
    const val OFFER = "Send recipes from your browser"
    const val INTRO = "Save this as a bookmark once. On any recipe page, tap the bookmark and the " +
        "recipe opens here for you to check. Coach never visits the page."
    const val LINK_LABEL = "Send to Coach"
    const val COPY = "Copy"
    const val COPIED = "Copied"
    val STEPS = listOf(
        "1. Tap Copy.",
        "2. Bookmark any page.",
        "3. Edit the bookmark. Name it Send to Coach, and paste over its address.",
        "4. On a recipe page, type Send to Coach in the address bar and tap the bookmark."
    )
    const val DONE = "Done"
}

/**
 * "Send recipes from your browser": the kit's `coachApp` bookmarklet, a Copy
 * button, and the steps to save it in Chrome (dugcanlift-kit
 * `bookmarklet/README.md`, "Show the bookmarklet where the person imports").
 * A phone browser cannot drag a link to a bookmarks bar, so it is copy and edit.
 */
@Composable
internal fun BookmarkletDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    var bookmarklet by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        bookmarklet = BookmarkletStore.coachApp(context)
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(BookmarkletText.OFFER) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(BookmarkletText.INTRO, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                val value = bookmarklet
                if (value != null) {
                    Text(BookmarkletText.LINK_LABEL, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SelectionContainer {
                            Text(
                                value,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                modifier = Modifier.padding(8.dp).heightIn(max = 72.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText(BookmarkletText.LINK_LABEL, value))
                            copied = true
                        },
                        colors = dclOutlinedButtonColors()
                    ) { Text(if (copied) BookmarkletText.COPIED else BookmarkletText.COPY) }
                }
                Spacer(Modifier.height(8.dp))
                BookmarkletText.STEPS.forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDone, colors = dclTextButtonColors()) { Text(BookmarkletText.DONE) }
        }
    )
}
