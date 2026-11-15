package io.hyperaudio.bridge

import java.io.IOException
import java.io.InputStream

/** Closing a root subprocess can interrupt read(); it must never escape a logging thread. */
fun drainCaptureLog(input: InputStream, line: (String) -> Unit): IOException? = try {
    input.bufferedReader().useLines { lines -> lines.forEach(line) }
    null
} catch (e: IOException) { e }
