package com.wifiproxy.app.kit

/**
 * Turns a zip entry name into safe path segments relative to the kit's extraction root, or
 * returns null for a zip-slip attempt (design spec §8a: "Reject entries with `..` or absolute
 * paths"). There is no real destination directory to canonicalise against here - extracted files
 * are written straight to `MediaStore.Downloads`, not a filesystem path - so this works purely on
 * the entry name string, which keeps it unit-testable on the JVM with no file I/O at all.
 */
object ZipSlipGuard {

    /**
     * Returns the entry's path as a list of non-empty segments (e.g. `"platform-tools/adb.exe"`
     * -> `["platform-tools", "adb.exe"]`), or null if [entryName]:
     * - is blank, or normalises to no segments at all (e.g. a bare directory separator);
     * - is an absolute Unix-style path (starts with `/`);
     * - is an absolute Windows path (starts with a drive letter like `C:`, or `\\server\share`);
     * - contains a `..` segment, which could otherwise escape the extraction root.
     */
    fun safeRelativeSegments(entryName: String): List<String>? {
        val normalized = entryName.replace('\\', '/')
        if (normalized.isBlank()) return null
        if (normalized.startsWith("/")) return null
        if (normalized.length >= 2 && normalized[1] == ':') return null // "C:/..." drive-letter path

        // "." segments are harmless (same directory) and just get dropped; only ".." can escape
        // the extraction root, so that's the one that disqualifies the whole entry.
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." }) return null
        return segments
    }
}
