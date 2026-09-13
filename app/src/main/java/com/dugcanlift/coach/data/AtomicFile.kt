package com.dugcanlift.coach.data

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes [text] to [target] the only way that survives the device being killed mid-write: into a
 * sibling `<name>.tmp` first, then [moveIntoPlace]. A bare `File.writeText` truncates the live file
 * before it writes a byte, so a kill (low memory, battery, an OS kill during a SAF activity
 * teardown) leaves a truncated file where valid data used to be. Any failure here leaves the
 * existing file exactly as it was and propagates, rather than half-replacing it.
 */
internal fun writeTextAtomically(target: File, text: String) {
    val tmp = File(target.parentFile, "${target.name}.tmp")
    tmp.writeText(text)
    moveIntoPlace(tmp, target)
}

/**
 * Moves [tmp] onto [target] with [Files.move], which throws on failure instead of returning a
 * boolean nobody checks (the old `File.renameTo` bug). Atomic where the filesystem supports it;
 * falls back to a plain replace rather than to the old silent path when it does not.
 */
internal fun moveIntoPlace(tmp: File, target: File) {
    try {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
