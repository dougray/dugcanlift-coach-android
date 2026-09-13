package com.dugcanlift.coach.data

import java.io.File
import org.json.JSONObject

/**
 * The coach's cached copy of the opaque library (recipes/meals/routines/sessions) from the most
 * recent restore that carried one, kept on disk so a later Save Backup can carry it back out
 * untouched even across app runs -- otherwise the round trip [BackupCodec] promises would only
 * hold within a single restore-then-export call, not across app runs.
 *
 * This is the only piece of state spanning two separate user actions -- Restore now, Save later
 * -- which is exactly why it needs direct tests rather than only Compose-level ones.
 */
object PreservedLibraryStore {
    fun load(file: File): JSONObject? =
        file.takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    /**
     * Applies one restore's result to the cache. [RestoreResult.preservedLibrary] is `null` when
     * the restored file carried none of the four library keys (a v1 file, or a v2 file with no
     * library yet) -- coach-ios's BackupCodec.swift documents the rule this must follow: an older
     * backup must never delete newer work sitting on the device, so a restore with no library
     * leaves the cache exactly as it was. A non-null library replaces the cache outright rather
     * than merging by id the way iOS's own restore does for its four typed arrays -- Coach Android
     * has no Cook, Train or Sessions UI, so it never authors library content of its own, and there
     * is therefore no local, Android-made library work a later restore could ever clobber. Every
     * non-null library reaching this method came from a restore, i.e. is by definition the most
     * recent state the coach chose to bring over.
     */
    fun update(file: File, library: JSONObject?) {
        if (library != null) file.writeText(library.toString())
    }
}
