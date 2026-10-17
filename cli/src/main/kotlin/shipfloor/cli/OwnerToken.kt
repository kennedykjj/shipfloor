package shipfloor.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Reads the owner token, or null when the file is missing, unreadable or empty. */
fun readOwnerToken(path: Path): String? =
    try {
        Files.readString(path).trim().takeIf { it.isNotEmpty() }
    } catch (e: IOException) {
        null
    }
