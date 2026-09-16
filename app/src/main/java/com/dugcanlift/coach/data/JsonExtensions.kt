package com.dugcanlift.coach.data

import org.json.JSONObject

/** Reads [name] from [this], or null if absent or JSON null. Shared by [Models.kt]'s and
 * [BackupCodec]'s decoders so the two copies (previously identical, but drifting duplicates
 * waiting to happen) don't diverge. */
internal fun JSONObject.optStringOrNull(name: String): String? = if (has(name) && !isNull(name)) getString(name) else null

/** [optStringOrNull] for a whole number. */
internal fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) getLong(name) else null
