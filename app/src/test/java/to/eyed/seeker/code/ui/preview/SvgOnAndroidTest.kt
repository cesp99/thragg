package to.eyed.seeker.code.ui.preview

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * The parser as *Android* hands it over, which is not the one these tests run
 * on.
 *
 * `DocumentBuilderFactory.newInstance()` resolves to Xerces on a desktop JVM
 * and to `org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl` on
 * Android — and Harmony's `setFeature` knows exactly two names and throws
 * `ParserConfigurationException` for every other one, while the base class's
 * `setXIncludeAware` throws `UnsupportedOperationException` unconditionally.
 *
 * The first version of this parser called both inside the `runCatching` that
 * spans the parse, so on a device *every* SVG came back "not an SVG that can
 * be drawn" — with 254 host tests passing. This is the same trap the project
 * already documents for `org.json` in `app/build.gradle.kts`.
 */
class SvgOnAndroidTest {

    @After
    fun restoreTheRealFactory() {
        SvgDocument.documentBuilderFactory = { DocumentBuilderFactory.newInstance() }
    }

    /** Harmony's semantics, over a parser that does work. */
    private class AndroidLikeFactory(
        private val real: DocumentBuilderFactory = DocumentBuilderFactory.newInstance(),
    ) : DocumentBuilderFactory() {
        override fun setFeature(name: String, value: Boolean) {
            if (name != "http://xml.org/sax/features/namespaces" &&
                name != "http://xml.org/sax/features/validation"
            ) {
                throw ParserConfigurationException(name)
            }
        }

        override fun getFeature(name: String): Boolean = false

        override fun setXIncludeAware(state: Boolean) {
            throw UnsupportedOperationException("Android does not implement this")
        }

        override fun newDocumentBuilder(): DocumentBuilder {
            real.isNamespaceAware = isNamespaceAware
            real.isExpandEntityReferences = isExpandEntityReferences
            return real.newDocumentBuilder()
        }

        override fun setAttribute(name: String, value: Any?) = real.setAttribute(name, value)

        override fun getAttribute(name: String): Any = real.getAttribute(name)
    }

    @Test
    fun anOrdinarySvgStillParsesOnAndroidsParser() {
        SvgDocument.documentBuilderFactory = { AndroidLikeFactory() }
        val document = SvgDocument.parse(
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
                 <path d="M0,0 L8,8" stroke="black"/>
               </svg>"""
        )
        assertNotNull("Android's parser must still draw an ordinary SVG", document)
        assertEquals(1, document!!.shapes.size)
    }

    /**
     * And the hardening the feature flags were *for* still holds, because it
     * does not depend on them: the DOCTYPE is refused by this file's own
     * check, before any parser sees the text.
     */
    @Test
    fun aDoctypeIsStillRefusedOnAndroidsParser() {
        SvgDocument.documentBuilderFactory = { AndroidLikeFactory() }
        assertNull(
            SvgDocument.parse(
                """<?xml version="1.0"?>
                   <!DOCTYPE svg [<!ENTITY x "boom">]>
                   <svg viewBox="0 0 16 16"><path d="M0,0" fill="&x;"/></svg>"""
            )
        )
    }
}
