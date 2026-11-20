package com.wifiproxy.app.kit

import org.xmlpull.v1.XmlPullParser

/** The stable-channel, Windows `platform-tools` archive parsed out of a Google SDK repository
 *  index (design spec §8a "Laptop kit"). [sha1] is whatever case the index used (compare
 *  case-insensitively). [relativeUrl] is relative to `https://dl.google.com/android/repository/`. */
data class PlatformToolsArchive(
    val relativeUrl: String,
    val sizeBytes: Long,
    val sha1: String,
)

/**
 * Pure [XmlPullParser]-walking logic for Google's SDK repository index
 * (`repository2-3.xml`/`repository2-1.xml` under `https://dl.google.com/android/repository/`),
 * kept free of any Android or network code so it can be unit-tested on the JVM against a fixture
 * with the real document shape (a JVM [XmlPullParser] implementation, e.g. kxml2, stands in for
 * `android.util.Xml.newPullParser()`).
 *
 * The index declares named channels once (`<channel id="channel-0"><name>Stable</name></channel>`)
 * and each `<remotePackage>` references one by id (`<channelRef ref="channel-0"/>`). Only a
 * `platform-tools` package on the channel named "stable" (case-insensitive) is considered; within
 * it, only the `<archive>` whose `<host-os>` is "windows" is read, and its `<complete>` url, size
 * and sha1 checksum are returned. Everything else - other packages, other host-os archives, other
 * channels - is skipped without being materialised.
 */
object RepositoryIndexParser {

    private const val STABLE_CHANNEL_NAME = "stable"
    private const val PLATFORM_TOOLS_PATH = "platform-tools"
    private const val WINDOWS_HOST_OS = "windows"

    /**
     * [parser] must be freshly configured with its input set but not yet advanced past
     * `START_DOCUMENT`. Returns null if no stable-channel Windows `platform-tools` archive is
     * found (including on an empty/garbage document); never throws for a well-formed document
     * that's just missing what we want, though a malformed one can still surface an
     * [org.xmlpull.v1.XmlPullParserException] from the parser itself.
     */
    fun parse(parser: XmlPullParser): PlatformToolsArchive? {
        if (parser.eventType == XmlPullParser.START_DOCUMENT) parser.next()
        if (parser.eventType != XmlPullParser.START_TAG) return null

        val stableChannelIds = mutableSetOf<String>()
        var found: PlatformToolsArchive? = null

        parser.forEachChild { childName ->
            when (childName) {
                "channel" -> {
                    val decl = readChannel(parser)
                    if (decl != null && decl.name.trim().equals(STABLE_CHANNEL_NAME, ignoreCase = true)) {
                        stableChannelIds += decl.id
                    }
                }

                "remotePackage" -> {
                    val path = parser.getAttributeValue(null, "path")
                    val info = readRemotePackage(parser)
                    if (found == null &&
                        path == PLATFORM_TOOLS_PATH &&
                        info.channelRef != null &&
                        info.channelRef in stableChannelIds &&
                        info.windowsArchive != null
                    ) {
                        found = info.windowsArchive
                    }
                }

                else -> parser.skipSubtree()
            }
        }
        return found
    }

    private data class ChannelDecl(val id: String, val name: String)

    // <channel id="channel-0">stable</channel> - the channel's name is its own text content, not
    // a nested element (confirmed against the real repository2-3.xml, not just its XSD).
    private fun readChannel(parser: XmlPullParser): ChannelDecl? {
        val id = parser.getAttributeValue(null, "id")
        val name = parser.nextText()
        return if (id != null) ChannelDecl(id, name) else null
    }

    private data class RemotePackageInfo(val channelRef: String?, val windowsArchive: PlatformToolsArchive?)

    private fun readRemotePackage(parser: XmlPullParser): RemotePackageInfo {
        var channelRef: String? = null
        var archive: PlatformToolsArchive? = null
        parser.forEachChild { childName ->
            when (childName) {
                "channelRef" -> {
                    channelRef = parser.getAttributeValue(null, "ref")
                    parser.skipSubtree()
                }

                // The real schema nests <archive> elements one level down, inside <archives>.
                "archives" -> {
                    parser.forEachChild { archivesChildName ->
                        if (archivesChildName == "archive") {
                            val a = readArchive(parser)
                            if (archive == null) archive = a
                        } else {
                            parser.skipSubtree()
                        }
                    }
                }

                else -> parser.skipSubtree()
            }
        }
        return RemotePackageInfo(channelRef, archive)
    }

    private fun readArchive(parser: XmlPullParser): PlatformToolsArchive? {
        var hostOs: String? = null
        var complete: CompleteInfo? = null
        parser.forEachChild { childName ->
            when (childName) {
                "host-os" -> hostOs = parser.nextText()
                "complete" -> complete = readComplete(parser)
                else -> parser.skipSubtree()
            }
        }
        val c = complete ?: return null
        return if (hostOs == WINDOWS_HOST_OS) PlatformToolsArchive(c.url, c.size, c.sha1) else null
    }

    private data class CompleteInfo(val url: String, val size: Long, val sha1: String)

    private fun readComplete(parser: XmlPullParser): CompleteInfo? {
        var url: String? = null
        var size: Long? = null
        var sha1: String? = null
        parser.forEachChild { childName ->
            when (childName) {
                "url" -> url = parser.nextText()
                "size" -> size = parser.nextText().trim().toLongOrNull()
                "checksum" -> {
                    // The schema's checksum type defaults to sha1 when the attribute is absent.
                    val type = parser.getAttributeValue(null, "type")
                    val value = parser.nextText()
                    if (type == null || type.equals("sha1", ignoreCase = true)) sha1 = value
                }

                else -> parser.skipSubtree()
            }
        }
        val capturedUrl = url
        val capturedSize = size
        val capturedSha1 = sha1
        return if (!capturedUrl.isNullOrBlank() && capturedSize != null && !capturedSha1.isNullOrBlank()) {
            CompleteInfo(capturedUrl, capturedSize, capturedSha1)
        } else {
            null
        }
    }
}

/**
 * Visits each direct child START_TAG of the element [XmlPullParser] currently sits on (which must
 * itself be a START_TAG), calling [onChild] with that child's (namespace-stripped) name for each
 * one. [onChild] must consume its child entirely - leave the parser at that child's matching
 * END_TAG, e.g. via [XmlPullParser.nextText], a nested [forEachChild], or [skipSubtree] - exactly
 * as this function itself does for its own element. Stops at this element's own END_TAG, or
 * defensively at END_DOCUMENT for a malformed/truncated document.
 */
private inline fun XmlPullParser.forEachChild(onChild: (name: String) -> Unit) {
    while (true) {
        when (next()) {
            XmlPullParser.START_TAG -> onChild(name)
            XmlPullParser.END_TAG, XmlPullParser.END_DOCUMENT -> return
        }
    }
}

/** Skips the element [XmlPullParser] currently sits on (a START_TAG), leaving it positioned at
 *  that element's matching END_TAG. */
private fun XmlPullParser.skipSubtree() {
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}
