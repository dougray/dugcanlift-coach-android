package com.dugcanlift.coach.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The "Send to Coach" bookmarklet Cook offers, read from `bookmarklets.json`.
 *
 * The file is the kit's (`dugcanlift-kit/bookmarklet/bookmarklets.json`, built
 * from `send-recipe.js`), copied here byte for byte with `bookmarklets.sha256`
 * beside it. Copy both together and never edit either here: `BookmarkletsTest`
 * hashes the bundled bytes, as `RecipeCatalogTest` does for `recipes.json`.
 *
 * Coach's target is [COACH_APP_KEY], `dugcanliftcoach://recipe#r1...` -- the
 * scheme Coach iPhone owns, so one bookmark opens Coach on either phone.
 */
const val BOOKMARKLETS_ASSET = "bookmarklets.json"
const val COACH_APP_KEY = "coachApp"

/** The `javascript:` string for [key], exactly as the file holds it; null when it holds none. */
fun bookmarkletFrom(json: String, key: String = COACH_APP_KEY): String? =
    JSONObject(json).optString(key).takeIf { it.startsWith("javascript:") }

object BookmarkletStore {
    suspend fun coachApp(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            context.applicationContext.assets.open(BOOKMARKLETS_ASSET)
                .bufferedReader().use { bookmarkletFrom(it.readText()) }
        } catch (e: Exception) {
            null
        }
    }
}
