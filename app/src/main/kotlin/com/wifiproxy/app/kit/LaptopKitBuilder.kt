package com.wifiproxy.app.kit

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

private const val PREFS_NAME = "wifi_proxy_laptop_kit"
private const val KEY_MEDIASTORE_IDS = "mediastore_ids"

private const val REPO_BASE_URL = "https://dl.google.com/android/repository/"
private const val INDEX_PRIMARY = "repository2-3.xml"
private const val INDEX_FALLBACK = "repository2-1.xml"
private const val KIT_RELATIVE_ROOT = "Download/WiFiProxy-LaptopKit"
private const val ASSETS_DIR = "laptop-kit"

private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 20_000
private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024

private val BUNDLED_ASSET_FILES = listOf("connect-usb.ps1", "connect-usb.cmd", "README.txt")

/**
 * Builds the offline "Laptop kit" (design spec §8a, plan Task 7): fetches Google's SDK repository
 * index, downloads and verifies the stable Windows `platform-tools` archive, extracts it, and
 * writes it plus the bundled laptop scripts to `Download/WiFiProxy-LaptopKit/` via
 * `MediaStore.Downloads` - so a laptop with no internet of its own can get `adb` and the sharing
 * scripts over a USB **File transfer** copy.
 *
 * [prepare] always runs on [Dispatchers.IO] and checks for cancellation between zip entries and
 * inside the download loop, so cancelling the caller's coroutine stops it promptly rather than
 * running a lengthy download/extract to completion first.
 *
 * The three pure, Android-free pieces - index parsing, size/SHA-1 verification, and the zip-slip
 * guard - live in [RepositoryIndexParser], [KitVerification] and [ZipSlipGuard] so they can be
 * unit-tested on the JVM; this class is the (untested-by-JVM-unit-test) Android glue around them.
 */
class LaptopKitBuilder(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Downloads, verifies, extracts and writes the kit, reporting progress through [progress].
     *  Re-running this replaces whatever kit a previous run wrote (tracked via MediaStore row ids
     *  in [SharedPreferences], not file paths, since MediaStore may have renamed a clashing name). */
    suspend fun prepare(progress: (KitProgress) -> Unit): KitResult = withContext(Dispatchers.IO) {
        var pendingUri: Uri? = null
        try {
            progress(KitProgress.Downloading(0))
            val archive = fetchIndex()
                ?: return@withContext fail(progress, "Could not read Google's SDK repository index")

            val zipBytes = downloadZip(archive) { percent -> progress(KitProgress.Downloading(percent)) }
                ?: return@withContext fail(progress, "Download failed")

            progress(KitProgress.Verifying)
            currentCoroutineContext().ensureActive()
            val actualSha1 = KitVerification.sha1Hex(zipBytes.inputStream())
            when (val result = KitVerification.verify(zipBytes.size.toLong(), actualSha1, archive)) {
                is VerificationResult.Mismatch -> return@withContext fail(progress, result.reason)
                VerificationResult.Ok -> Unit
            }

            removePreviousKit()

            val writtenIds = mutableListOf<Long>()
            val platformToolsCount = extractPlatformTools(zipBytes, writtenIds) { uri -> pendingUri = uri }
            val assetCount = writeBundledAssets(writtenIds) { uri -> pendingUri = uri }
            pendingUri = null
            persistIds(writtenIds)

            val message = "Ready in Download/WiFiProxy-LaptopKit - copy it to the laptop over USB File transfer."
            progress(KitProgress.Ready(message))
            KitResult.Success(platformToolsCount + assetCount)
        } catch (e: CancellationException) {
            // Best-effort: don't leave a half-written, still-IS_PENDING row behind. If this fails
            // (e.g. the row's already gone), there's nothing more useful to do before re-throwing.
            pendingUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            throw e
        } catch (e: Exception) {
            fail(progress, e.message ?: e::class.simpleName ?: "Unknown error")
        }
    }

    private fun fail(progress: (KitProgress) -> Unit, reason: String): KitResult {
        progress(KitProgress.Failed(reason))
        return KitResult.Failure(reason)
    }

    // --- Repository index -----------------------------------------------------------------------

    private suspend fun fetchIndex(): PlatformToolsArchive? =
        fetchIndexDocument(INDEX_PRIMARY) ?: fetchIndexDocument(INDEX_FALLBACK)

    private suspend fun fetchIndexDocument(fileName: String): PlatformToolsArchive? {
        currentCoroutineContext().ensureActive()
        val connection = openConnection(REPO_BASE_URL + fileName)
        return try {
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { stream ->
                RepositoryIndexParser.parse(newXmlPullParser(stream))
            }
        } catch (e: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun newXmlPullParser(stream: InputStream): XmlPullParser {
        val parser = android.util.Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)
        return parser
    }

    // --- Download ---------------------------------------------------------------------------------

    private suspend fun downloadZip(archive: PlatformToolsArchive, onProgress: (Int) -> Unit): ByteArray? {
        val connection = openConnection(REPO_BASE_URL + archive.relativeUrl)
        return try {
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val total = archive.sizeBytes.takeIf { it > 0 } ?: connection.contentLengthLong
            val initialCapacity = if (total in 1..Int.MAX_VALUE.toLong()) total.toInt() else DOWNLOAD_BUFFER_SIZE
            val output = ByteArrayOutputStream(initialCapacity)
            connection.inputStream.use { input ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                var readSoFar = 0L
                var lastReportedPercent = -1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    readSoFar += read
                    if (total > 0) {
                        val percent = ((readSoFar * 100) / total).toInt().coerceIn(0, 99)
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            onProgress(100)
            output.toByteArray()
        } catch (e: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }

    // --- Extraction / MediaStore write ------------------------------------------------------------

    /** Extracts every file entry of [zipBytes] (a `platform-tools_*.zip` from the Android SDK
     *  repository) to `Download/WiFiProxy-LaptopKit/<entry path>` via MediaStore, appending each
     *  written row's id to [writtenIds]. Refuses the whole kit (throws) on a zip-slip entry,
     *  rather than silently dropping just that one file. */
    private suspend fun extractPlatformTools(
        zipBytes: ByteArray,
        writtenIds: MutableList<Long>,
        onPending: (Uri?) -> Unit,
    ): Int {
        var count = 0
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val segments = ZipSlipGuard.safeRelativeSegments(entry.name)
                    ?: throw IOException("Refusing unsafe zip entry: ${entry.name}")

                val id = writeToDownloads(segments, zip, onPending)
                writtenIds += id
                count++
            }
        }
        return count
    }

    /** Copies the three laptop scripts bundled into the app's assets (by the `copyLaptopKitAssets`
     *  Gradle task, from `tools/usb-client/` and `app/laptop-kit-static/`) to the kit's root. */
    private suspend fun writeBundledAssets(writtenIds: MutableList<Long>, onPending: (Uri?) -> Unit): Int {
        var count = 0
        for (name in BUNDLED_ASSET_FILES) {
            currentCoroutineContext().ensureActive()
            context.assets.open("$ASSETS_DIR/$name").use { input ->
                val id = writeToDownloads(listOf(name), input, onPending)
                writtenIds += id
            }
            count++
        }
        return count
    }

    /** Writes [input] to `Download/WiFiProxy-LaptopKit/<segments>` via `MediaStore.Downloads`,
     *  using the `IS_PENDING` dance so the file never appears half-written to other apps. Returns
     *  the inserted row's id. */
    private fun writeToDownloads(segments: List<String>, input: InputStream, onPending: (Uri?) -> Unit): Long {
        val displayName = segments.last()
        val subdir = segments.dropLast(1)
        // MediaStore.MediaColumns.RELATIVE_PATH is documented as ending in "/".
        val relativePath = if (subdir.isEmpty()) {
            "$KIT_RELATIVE_ROOT/"
        } else {
            "$KIT_RELATIVE_ROOT/${subdir.joinToString("/")}/"
        }

        val resolver = context.contentResolver
        val insertValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, insertValues)
            ?: throw IOException("MediaStore insert failed for $displayName")
        onPending(uri)
        try {
            resolver.openOutputStream(uri)?.use { out -> input.copyTo(out) }
                ?: throw IOException("Could not open an output stream for $displayName")
            val clearPending = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, clearPending, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        onPending(null)
        return ContentUris.parseId(uri)
    }

    // --- Replace-previous-kit tracking --------------------------------------------------------------

    private fun removePreviousKit() {
        val ids = prefs.getStringSet(KEY_MEDIASTORE_IDS, null).orEmpty()
        if (ids.isEmpty()) return
        val resolver = context.contentResolver
        for (idString in ids) {
            val id = idString.toLongOrNull() ?: continue
            val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            // Already gone (e.g. the user deleted it by hand) is not an error here.
            runCatching { resolver.delete(uri, null, null) }
        }
        prefs.edit { remove(KEY_MEDIASTORE_IDS) }
    }

    private fun persistIds(ids: List<Long>) {
        prefs.edit { putStringSet(KEY_MEDIASTORE_IDS, ids.map { it.toString() }.toSet()) }
    }
}
