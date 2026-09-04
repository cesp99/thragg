package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The block of text About copies. It is what a bug report is pasted from, so
 * the shape of it is worth pinning: every line `Label: value`, and every fact
 * an issue needs actually in it.
 */
class SystemSpecsTest {

    private val specs = SystemSpecs(
        appVersion = "0.0.4",
        versionCode = 4,
        hasUserland = true,
        engineVersion = "thragg-engine 0.1.0",
        zedCommit = "bc538def45",
        deviceModel = "Google Pixel 9 Pro",
        androidVersion = "Android 17 (API 37)",
        abi = "arm64-v8a",
        pageSizeBytes = 16384,
    )

    @Test
    fun theReportIsPlainLabelValueLines() {
        assertEquals(
            """
            App: 0.0.4 (4)
            Userland: Linux userland
            Engine: thragg-engine 0.1.0
            Zed: bc538def45
            Device: Google Pixel 9 Pro
            Android: Android 17 (API 37)
            ABI: arm64-v8a
            Page size: 16 KiB
            """.trimIndent(),
            specs.report(),
        )
    }

    @Test
    fun theReportSaysWhetherThereIsAUserland() {
        // Half the reports about git or a language server are really about
        // this one line, so it says it in words rather than naming a build
        // flavour — there was one that meant this, and it is gone.
        assertTrue(specs.report().contains("Userland: Linux userland"))
        assertTrue(
            specs.copy(hasUserland = false).report().contains("Userland: no userland")
        )
    }

    @Test
    fun aFourKilobytePageReadsAsFour() {
        assertEquals("4 KiB", specs.copy(pageSizeBytes = 4096).lines().last().second)
    }

    @Test
    fun theMakeIsNotRepeatedWhenTheModelAlreadyCarriesIt() {
        assertEquals(
            "Google Pixel 9 Pro",
            SystemSpecs.deviceModel(manufacturer = "Google", model = "Pixel 9 Pro"),
        )
        // Samsung's MODEL is "SM-S928B", which says nothing on its own.
        assertEquals(
            "samsung SM-S928B",
            SystemSpecs.deviceModel(manufacturer = "samsung", model = "SM-S928B"),
        )
        // …and where it does already carry the make, saying it twice reads as
        // a typo rather than as data.
        assertEquals(
            "Google Pixel 9",
            SystemSpecs.deviceModel(manufacturer = "google", model = "Google Pixel 9"),
        )
        assertEquals("unknown", SystemSpecs.deviceModel(manufacturer = " ", model = " "))
        assertEquals("Fairphone", SystemSpecs.deviceModel(manufacturer = "Fairphone", model = ""))
    }

    @Test
    fun theLinesAndTheReportSayTheSameThing() {
        assertEquals(
            specs.lines().map { (label, value) -> "$label: $value" },
            specs.report().lines(),
        )
    }
}
