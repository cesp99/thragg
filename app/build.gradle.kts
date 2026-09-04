import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

// The Rust engine (core/) is compiled to libthraggcore.so by cargo-ndk and
// packaged from src/main/jniLibs, which is generated and gitignored.
// `-Pthragg.abis=x86_64` narrows a development build to the emulator's ABI:
// the engine is a fat-LTO release build per ABI, so skipping arm64 halves the
// edit-build-test loop. Release builds never pass it.
val rustAbis = (project.findProperty("thragg.abis") as String?)
    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    ?: listOf("arm64-v8a", "x86_64")
val rustJniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

// Which Zed the vendored crates were copied from. `core/vendor/VENDOR.md` is
// the record of that, and it is the *only* record: a bug report has to name
// the upstream commit to be reproducible, and a second copy of the hash in
// Kotlin would be one nobody remembered to move. Read here so About shows
// what VENDOR.md says, and fails loudly at configuration time if the line
// this depends on is ever reworded.
val vendorNotes = rootProject.layout.projectDirectory.file("core/vendor/VENDOR.md").asFile
val zedCommit: String = Regex("""^- Commit: `([0-9a-f]+)`""", RegexOption.MULTILINE)
    .find(vendorNotes.readText())
    ?.groupValues?.get(1)
    ?: error("core/vendor/VENDOR.md has no \"- Commit: `…`\" line for About to read")

// The one public repository URL, read from the one place that already holds
// it. docs/LICENSING.md §3 is emphatic about this: "The offer, the notices
// screen, README.md, THIRD_PARTY.md and the release archive must all name the
// SAME public repository URL, byte for byte" — a URL that moves after the
// phones ship is not a broken link, it is a compliance failure. So the
// licences screen does not carry a string constant; it reads BuildConfig,
// which reads core/Cargo.toml, exactly as About reads the Zed commit above.
//
// NOTE that the tree still disagrees with itself — .github/ISSUE_TEMPLATE
// says github.com/cesp99/thragg — and settling that is on the release
// checklist. Whichever wins, `repository` in core/Cargo.toml is where the app
// will read it from.
val sourceUrl: String = Regex("""^repository\s*=\s*"([^"]+)""", RegexOption.MULTILINE)
    .find(rootProject.layout.projectDirectory.file("core/Cargo.toml").asFile.readText())
    ?.groupValues?.get(1)
    ?: error("core/Cargo.toml has no `repository = \"…\"` line for the licences screen to read")

android {
    namespace = "to.eyed.thragg"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "to.eyed.thragg"
        minSdk = 31
        // The old SELinux domain still permits executing files from app
        // storage, which is what a Debian userland under proot needs — and
        // therefore what apt, git, language servers, cargo and every agent
        // runtime need. Measured on Android 17; see
        // agent-docs/archive/research/android-exec-policy.md.
        //
        // This used to be the `full` product flavour's, with a Play-compatible
        // `play` flavour beside it that targeted a modern SDK and had no
        // userland at all. That edition is gone: a Solana IDE whose terminal
        // cannot run a downloaded program is not this app, and every feature
        // below the UI — clone, apt, the agent, the build runner — was written
        // twice for it. The constraint is now unconditional, so it lives here.
        targetSdk = 28
        versionCode = 15
        versionName = "0.0.15"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "ZED_COMMIT", "\"$zedCommit\"")
        buildConfigField("String", "SOURCE_URL", "\"$sourceUrl\"")
        // Always true now that there is one edition. Kept as a field rather
        // than deleted so a diagnostic report still records the fact (see
        // core/SystemSpecs.kt) and the no-userland branches that remain —
        // solana/build/BuildRunner.kt's NO_USERLAND path, reached before
        // Debian is installed — keep saying what they mean.
        buildConfigField("boolean", "USERLAND", "true")
    }

    buildTypes {
        release {
            // R8: shrink and obfuscate the DEX, and drop unreferenced
            // resources. Worth far more here than it looks — an unminified
            // build carries ~29 MB of Compose/AndroidX classes we barely
            // touch. Keep rules live in src/main/keepRules; the JNI surface
            // must survive renaming (see rules.keep).
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }

    // One APK per ABI instead of one fat APK carrying every ABI. The Rust
    // engine dominates this app's size — tens of MB per architecture — so a
    // universal APK makes every user download an engine they cannot run.
    // The per-ABI APKs are what F-Droid and direct installs want.
    splits {
        abi {
            isEnable = true
            reset()
            include(*rustAbis.toTypedArray())
            // Still emit the every-ABI APK: it is what `adb install` on an
            // unknown device and a plain "download the APK" link need.
            isUniversalApk = true
        }
    }
    // Extract native libraries to nativeLibraryDir on install instead of
    // mapping them straight out of the APK. Measured, not assumed: with the
    // modern default, nativeLibraryDir is an *empty* directory, so nothing
    // there can be executed. Everything on-device that is a process rather
    // than a library — the shell, git, language servers, agent runtimes —
    // depends on this flag.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Baseline profile consumer side: `:baselineprofile` records which methods a
// cold start runs (on a Gradle-managed emulator), and release builds compile
// that list ahead of time so first launch skips the interpreter. Regenerate
// with `./gradlew :app:generateBaselineProfile`; the result is committed.
baselineProfile {
    // One profile in src/main/generated/baselineProfiles rather than one per
    // variant. (This outlived the two editions it was first written for:
    // same code, same startup path.)
    mergeIntoMain = true
    // Committed profile, applied on every release build — no emulator needed
    // at build time.
    saveInSrc = true
    automaticGenerationDuringBuild = false
}

// Pinned in gradle.properties so the app and the vendored terminal modules
// (which build libtermux.so with ndk-build) cannot drift apart.
val ndkVersion = providers.gradleProperty("thragg.ndkVersion").get()
val sdkDir: String = run {
    val localProps = Properties()
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { stream -> localProps.load(stream) }
    }
    localProps.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: "${System.getProperty("user.home")}/Android/Sdk"
}

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "build"
    description = "Builds the Rust core (libthraggcore.so) for Android ABIs"
    workingDir = rootProject.file("core")
    inputs.dir(rootProject.file("core/crates"))
    inputs.file(rootProject.file("core/Cargo.toml"))
    outputs.dir(rustJniLibsDir)
    environment("ANDROID_NDK_HOME", "$sdkDir/ndk/$ndkVersion")
    environment(
        "PATH",
        "${System.getProperty("user.home")}/.cargo/bin:${System.getenv("PATH")}"
    )
    commandLine(
        "cargo", "ndk",
        *rustAbis.flatMap { listOf("-t", it) }.toTypedArray(),
        "-o", rustJniLibsDir.asFile.absolutePath,
        "build", "--release", "-p", "jni-bridge"
    )
}

tasks.named("preBuild") {
    dependsOn(cargoNdkBuild)
}

dependencies {
    implementation(project(":terminal-view"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Installs the baseline profile when the store (or adb) doesn't compile
    // it at install time.
    implementation(libs.androidx.profileinstaller)
    // clientlib-ktx declares androidx.test.ext:junit-ktx as a *runtime*
    // dependency, which would drag junit, hamcrest and androidx.test into the
    // release APK. None of it is used at runtime.
    implementation(libs.mwa.clientlib.ktx) { exclude(group = "androidx.test.ext") }
    implementation(libs.eddsa)
    baselineProfile(project(":baselineprofile"))
    testImplementation(libs.junit)
    // The real org.json, for host tests only. Android ships it, but the
    // android.jar the unit tests compile against holds stubs that throw at
    // runtime — and the language configs arrive from the engine as JSON.
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
// ── The notices bundle ───────────────────────────────────────────────────────
//
// docs/LICENSING.md §4. The APK statically links 471 Rust crates and ships two
// GPL binaries; MIT, BSD-2/3, ISC and Zlib all require their notice to
// accompany a BINARY distribution, Apache-2.0 s4(a) requires the licence text,
// and GPLv3 s4 requires a copy of the GPL with the Program. That inventory is
// app/src/main/assets/licenses/components.json, it is GENERATED, and these are
// the three tasks around it.
//
// None of them is wired into `preBuild` or `check`, and that is deliberate:
// generating requires python3 and an offline `cargo tree`, and a developer
// compiling Kotlin should not need either. `verifyLicenceAssets` is the CI
// gate — run it on every pull request, which is the §4 requirement that a
// dependency added without a notice cannot merge.
//
//   ./gradlew :app:dumpMavenLicences      # refresh the Maven half
//   ./gradlew :app:generateLicenceAssets  # rewrite components.json
//   ./gradlew :app:verifyLicenceAssets    # fail if the committed copy is stale

/**
 * Writes the release runtime classpath to tools/licenses/maven-runtime.json.
 *
 * The coordinates arrive as a plain list of strings rather than as a live
 * `Configuration`, because a task that reaches for `project` at execution time
 * is a task the configuration cache has to refuse. Resolution happens through
 * `resolvedArtifacts`, which is the provider API built for exactly this.
 */
abstract class DumpMavenLicences : DefaultTask() {
    /** "group:name:version", every module on the classpath. */
    @get:Input
    abstract val coordinates: ListProperty<String>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun dump() {
        val modules = coordinates.get().distinct().sorted()
        val body = modules.joinToString(",\n") { """    { "id": "$it" }""" }
        output.get().asFile.apply { parentFile.mkdirs() }.writeText(
            """
            {
              "note": [
                "GENERATED by ./gradlew :app:dumpMavenLicences. Do not edit by hand.",
                "Every Maven module on the RELEASE runtime classpath —",
                "that is, everything that ends up in the APK, and nothing that is only",
                "build tooling. Committed so tools/gen-licenses.py runs without Gradle.",
                "Licences are attached by group in tools/licenses/manifest.jsonc."
              ],
              "moduleCount": ${modules.size},
              "modules": [
            $body
              ]
            }
            """.trimIndent() + "\n"
        )
        logger.lifecycle("wrote ${output.get().asFile.path}: ${modules.size} modules")
    }
}

/**
 * Every Maven module on one configuration, as "group:name:version".
 *
 * Read off the *resolution result* — the dependency graph — and not off
 * `incoming.artifacts`, which additionally has to pick an artifact variant per
 * module and fails as ambiguous on an Android library that publishes a dozen
 * of them (jar, aar, supported-locale-list, …). Nothing here needs a file: the
 * question is which modules are in the graph, and the graph knows.
 */
fun mavenCoordinates(configuration: String): Provider<List<String>> =
    configurations.named(configuration).flatMap { conf ->
        conf.incoming.resolutionResult.rootComponent.map { root ->
            val found = linkedSetOf<String>()
            val seen = mutableSetOf<ResolvedComponentResult>()
            fun walk(component: ResolvedComponentResult) {
                if (!seen.add(component)) return
                // A BOM is in the graph and contributes no bytes to the APK.
                // Detected by the category attribute rather than by the "-bom"
                // in its name, because a naming convention is not a fact and
                // this list is a compliance document. Gradle's own constant is
                // Category.REGULAR_PLATFORM / ENFORCED_PLATFORM.
                val isPlatform = component.variants.any { variant ->
                    variant.attributes.keySet()
                        .firstOrNull { it.name == Category.CATEGORY_ATTRIBUTE.name }
                        ?.let { variant.attributes.getAttribute(it)?.toString() }
                        ?.endsWith("platform") == true
                }
                if (!isPlatform) {
                    (component.id as? ModuleComponentIdentifier)?.let {
                        found += "${it.group}:${it.module}:${it.version}"
                    }
                }
                for (dependency in component.dependencies) {
                    (dependency as? ResolvedDependencyResult)?.let { walk(it.selected) }
                }
            }
            walk(root)
            found.toList()
        }
    }

tasks.register<DumpMavenLicences>("dumpMavenLicences") {
    group = "verification"
    description = "Records the release runtime classpath for the notices bundle"
    // One classpath, still. This used to zip `full` with `play`: the two
    // differed in what they *could execute*, not in what they depended on, so
    // a module reaching only one of them would have been missing from the
    // other's notices. The release runtime classpath is the whole answer.
    coordinates.set(mavenCoordinates("releaseRuntimeClasspath"))
    output.set(rootProject.layout.projectDirectory.file("tools/licenses/maven-runtime.json"))
}

tasks.register<Exec>("generateLicenceAssets") {
    group = "verification"
    description = "Regenerates app/src/main/assets/licenses/components.json"
    workingDir = rootProject.projectDir
    commandLine("python3", "tools/gen-licenses.py")
}

tasks.register<Exec>("verifyLicenceAssets") {
    group = "verification"
    description = "Fails if components.json is not what tools/gen-licenses.py produces"
    workingDir = rootProject.projectDir
    commandLine("python3", "tools/gen-licenses.py", "--check")
}
