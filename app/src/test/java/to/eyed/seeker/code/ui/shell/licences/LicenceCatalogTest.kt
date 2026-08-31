package to.eyed.seeker.code.ui.shell.licences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure halves of the licences screen: reading `components.json` and
 * filtering it.
 *
 * Worth pinning because the failure mode is silent. A parser that quietly
 * drops a field produces a screen that still looks right and no longer carries
 * the notice it was built to carry — MIT's one condition is that the copyright
 * notice be included, and a row that prints "null" where the holder should be
 * satisfies nothing. Every assertion below is one of those quiet ways to be
 * wrong.
 */
class LicenceCatalogTest {

    private val sample = """
        {
          "schema": 1,
          "groups": [
            {
              "id": "app",
              "title": "This application",
              "note": "Eyed's own work.",
              "components": [
                {
                  "id": "app/seeker-ide",
                  "name": "Seeker IDE",
                  "version": "",
                  "spdx": "GPL-3.0-or-later; as distributed, GPL-3.0",
                  "copyright": "Copyright (C) 2026 Eyed",
                  "url": "",
                  "licenseFiles": ["licenses/GPL-3.0.txt"],
                  "note": "A fork of Conquest Code."
                }
              ]
            },
            {
              "id": "rust",
              "title": "Rust crates",
              "note": "The link closure.",
              "components": [
                {
                  "id": "rust/serde@1.0.229",
                  "name": "serde",
                  "version": "1.0.229",
                  "spdx": "MIT OR Apache-2.0",
                  "copyright": null,
                  "url": "https://github.com/serde-rs/serde",
                  "licenseFiles": ["licenses/MIT.txt", "licenses/Apache-2.0.txt"],
                  "authors": ["Erick Tryzelaar", "David Tolnay"]
                },
                {
                  "id": "rust/nucleo@0.5.0",
                  "name": "nucleo",
                  "version": "0.5.0",
                  "spdx": "MPL-2.0",
                  "copyright": "Copyright (c) 2023 Pascal Kuthe",
                  "url": "https://github.com/helix-editor/nucleo",
                  "licenseFiles": ["licenses/MPL-2.0.txt"],
                  "origin": "Reached through core/vendor/fuzzy_nucleo."
                }
              ]
            },
            {
              "id": "empty",
              "title": "A group with nothing in it",
              "components": []
            }
          ]
        }
    """.trimIndent()

    private val catalog = LicenceCatalog.parse(sample)

    @Test
    fun `every row and every field survives the parse`() {
        assertEquals(listOf("app", "rust"), catalog.groups.map { it.id })
        assertEquals(3, catalog.components.size)

        val app = catalog["app/seeker-ide"]!!
        assertEquals("Seeker IDE", app.name)
        assertEquals("Copyright (C) 2026 Eyed", app.copyright)
        assertEquals(listOf("licenses/GPL-3.0.txt"), app.licenceFiles)
        assertEquals("A fork of Conquest Code.", app.note)
        assertEquals("This application", catalog.groups.first().title)
        assertEquals("Eyed's own work.", catalog.groups.first().note)
    }

    @Test
    fun `an empty group is dropped rather than drawn as a bare heading`() {
        assertTrue(catalog.groups.none { it.id == "empty" })
    }

    /**
     * `JSONObject.optString` turns a JSON null into the *string* "null", and a
     * compliance screen that prints a copyright holder called null is worse
     * than one that prints none. 119 of the 471 crate rows are null here.
     */
    @Test
    fun `a null copyright stays null and does not become the word null`() {
        val serde = catalog["rust/serde@1.0.229"]!!
        assertNull(serde.copyright)
        assertEquals(listOf("Erick Tryzelaar", "David Tolnay"), serde.authors)
    }

    /** An absent field is absent, not an empty string that renders as a gap. */
    @Test
    fun `missing optional fields come back null and empty`() {
        val serde = catalog["rust/serde@1.0.229"]!!
        assertNull(serde.note)
        assertNull(serde.origin)
        val app = catalog["app/seeker-ide"]!!
        assertNull(app.url)
        assertEquals("", app.version)
        assertTrue(app.authors.isEmpty())
    }

    /**
     * Both texts of a dual-licensed crate, in the order the expression names
     * them. Dropping the second is the defect this asserts against: a reader
     * offered "MIT OR Apache-2.0" and shown one of them cannot exercise the
     * choice the licence gives them.
     */
    @Test
    fun `a dual licensed row keeps every licence text`() {
        assertEquals(
            listOf("licenses/MIT.txt", "licenses/Apache-2.0.txt"),
            catalog["rust/serde@1.0.229"]!!.licenceFiles,
        )
    }

    @Test
    fun `an unknown id is null rather than an exception`() {
        assertNull(catalog["rust/does-not-exist@0.0.0"])
    }

    @Test
    fun `a malformed file parses to an empty catalogue rather than throwing`() {
        assertTrue(LicenceCatalog.parse("{}").isEmpty)
        assertTrue(LicenceCatalog.parse("""{"groups": []}""").isEmpty)
    }

    // ---- filtering ---------------------------------------------------------

    @Test
    fun `an empty query is the whole catalogue`() {
        assertEquals(catalog.components.size, catalog.filter("").components.size)
        assertEquals(catalog.components.size, catalog.filter("   ").components.size)
    }

    @Test
    fun `a name matches case insensitively`() {
        val found = catalog.filter("SERDE")
        assertEquals(listOf("rust/serde@1.0.229"), found.components.map { it.id })
    }

    @Test
    fun `an SPDX identifier is a search term`() {
        assertEquals(
            listOf("rust/nucleo@0.5.0"),
            catalog.filter("mpl").components.map { it.id },
        )
    }

    /** Every token has to match, so two words narrow rather than widen. */
    @Test
    fun `tokens are ANDed`() {
        assertEquals(1, catalog.filter("rust serde").components.size)
        assertTrue(catalog.filter("serde mpl").isEmpty)
    }

    /**
     * The group's own title is part of the haystack, which is what makes
     * "rust" find the crates: not one of them has the word in its name.
     */
    @Test
    fun `a group title matches its components`() {
        assertEquals(2, catalog.filter("crates").components.size)
    }

    @Test
    fun `a copyright holder and an author are both searchable`() {
        assertEquals(
            listOf("rust/nucleo@0.5.0"),
            catalog.filter("kuthe").components.map { it.id },
        )
        assertEquals(
            listOf("rust/serde@1.0.229"),
            catalog.filter("tolnay").components.map { it.id },
        )
    }

    @Test
    fun `a group with no surviving row disappears`() {
        val found = catalog.filter("serde")
        assertEquals(listOf("rust"), found.groups.map { it.id })
        assertFalse(found.isEmpty)
    }

    @Test
    fun `no match is an empty catalogue and not an exception`() {
        assertTrue(catalog.filter("there is no such component").isEmpty)
    }
}
