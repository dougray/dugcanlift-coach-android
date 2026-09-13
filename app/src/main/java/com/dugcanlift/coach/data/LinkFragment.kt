package com.dugcanlift.coach.data

/**
 * The paste sheet accepts either a full `https://www.dugcanlift.com/coach/#<fragment>` URL or a
 * bare fragment copied on its own. If the text contains `#`, only what follows the LAST `#` is
 * kept (a share URL never legitimately contains a second `#`, but a bare fragment might have been
 * pasted twice back-to-back); the result is trimmed of surrounding whitespace either way.
 */
fun fragmentFrom(text: String): String {
    val lastHash = text.lastIndexOf('#')
    val raw = if (lastHash >= 0) text.substring(lastHash + 1) else text
    return raw.trim()
}
