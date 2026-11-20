package com.wifiproxy.app.kit

import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [RepositoryIndexParser] is pure [XmlPullParser]-walking logic with no Android dependency, so
 * these run against kxml2's JVM implementation of [XmlPullParser] (the same interface
 * `android.util.Xml.newPullParser()` returns on-device) rather than a real network fetch.
 */
class RepositoryIndexParserTest {

    private fun parserFor(xml: String): XmlPullParser {
        val parser = KXmlParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        return parser
    }

    private fun parserForFixture(): XmlPullParser {
        val xml = requireNotNull(javaClass.getResourceAsStream("/kit/repository-index-fixture.xml")) {
            "Missing test fixture kit/repository-index-fixture.xml"
        }.bufferedReader().readText()
        return parserFor(xml)
    }

    @Test
    fun `picks the stable channel's Windows platform-tools archive`() {
        val archive = RepositoryIndexParser.parse(parserForFixture())

        assertEquals(
            PlatformToolsArchive(
                relativeUrl = "platform-tools_r37.0.3-win.zip",
                sizeBytes = 12345678L,
                sha1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            ),
            archive,
        )
    }

    @Test
    fun `ignores the non-stable channel's platform-tools package`() {
        val archive = RepositoryIndexParser.parse(parserForFixture())

        // The Canary package's archive (size 99999999, sha1 starting 'd') must never win, even
        // though it also has a Windows archive and appears after the stable package.
        assertEquals(12345678L, archive?.sizeBytes)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", archive?.sha1)
    }

    @Test
    fun `ignores non-Windows archives within the stable package`() {
        val archive = RepositoryIndexParser.parse(parserForFixture())

        assertEquals("platform-tools_r37.0.3-win.zip", archive?.relativeUrl)
        // Sanity: neither the linux nor macosx sha1/url ever surface.
        assertEquals(null, listOf("bbbb", "cccc").find { archive?.sha1?.startsWith(it) == true })
    }

    @Test
    fun `returns null when there is no matching package at all`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sdk-repository>
                <channel id="channel-0">stable</channel>
                <remotePackage path="platforms;android-36">
                    <channelRef ref="channel-0"/>
                    <archives>
                        <archive>
                            <host-os>windows</host-os>
                            <complete>
                                <size>1</size>
                                <checksum type="sha1">ffffffffffffffffffffffffffffffffffffffff</checksum>
                                <url>irrelevant.zip</url>
                            </complete>
                        </archive>
                    </archives>
                </remotePackage>
            </sdk-repository>
        """.trimIndent()

        assertNull(RepositoryIndexParser.parse(parserFor(xml)))
    }

    @Test
    fun `returns null when the only platform-tools archive is not on the stable channel`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sdk-repository>
                <channel id="channel-0">stable</channel>
                <channel id="channel-3">canary</channel>
                <remotePackage path="platform-tools">
                    <channelRef ref="channel-3"/>
                    <archives>
                        <archive>
                            <host-os>windows</host-os>
                            <complete>
                                <size>1</size>
                                <checksum type="sha1">ffffffffffffffffffffffffffffffffffffffff</checksum>
                                <url>irrelevant.zip</url>
                            </complete>
                        </archive>
                    </archives>
                </remotePackage>
            </sdk-repository>
        """.trimIndent()

        assertNull(RepositoryIndexParser.parse(parserFor(xml)))
    }

    @Test
    fun `a checksum with no type attribute defaults to sha1`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sdk-repository>
                <channel id="channel-0">stable</channel>
                <remotePackage path="platform-tools">
                    <channelRef ref="channel-0"/>
                    <archives>
                        <archive>
                            <host-os>windows</host-os>
                            <complete>
                                <size>42</size>
                                <checksum>1234567890123456789012345678901234567890</checksum>
                                <url>platform-tools.zip</url>
                            </complete>
                        </archive>
                    </archives>
                </remotePackage>
            </sdk-repository>
        """.trimIndent()

        val archive = RepositoryIndexParser.parse(parserFor(xml))
        assertEquals("1234567890123456789012345678901234567890", archive?.sha1)
    }

    @Test
    fun `an empty channel element (no text) is simply not named stable`() {
        // Defensive: a self-closing <channel id="x"/> must not crash nextText(), and must not be
        // mistaken for the stable channel.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sdk-repository>
                <channel id="channel-0"/>
                <remotePackage path="platform-tools">
                    <channelRef ref="channel-0"/>
                    <archives>
                        <archive>
                            <host-os>windows</host-os>
                            <complete>
                                <size>1</size>
                                <checksum type="sha1">ffffffffffffffffffffffffffffffffffffffff</checksum>
                                <url>irrelevant.zip</url>
                            </complete>
                        </archive>
                    </archives>
                </remotePackage>
            </sdk-repository>
        """.trimIndent()

        assertNull(RepositoryIndexParser.parse(parserFor(xml)))
    }
}
