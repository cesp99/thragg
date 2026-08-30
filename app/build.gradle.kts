import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

// The Rust engine (core/) is compiled to libseekercore.so by cargo-ndk and
// packaged from src/main/jniLibs, which is generated and gitignored.
// `-Pseeker.abis=x86_64` narrows a development build to the emulator's ABI:
// the engine is a fat-LTO release build per ABI, so skipping arm64 halves the
// edit-build-test loop. Release builds never pass it.
val rustAbis = (project.findProperty("seeker.abis") as String?)
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

android {
    namespace = "to.eyed.seeker.code"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "to.eyed.seeker.code"
        minSdk = 31
        versionCode = 5
        versionName = "0.0.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "ZED_COMMIT", "\"$zedCommit\"")
    }

    // Two editions of the same app, differing in one thing that changes
    // everything downstream: whether Android will let a downloaded program
    // run. See docs/BUILDING.md and agent-docs/DECISIONS.md.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            // The old SELinux domain still permits executing files from app
            // storage, which is what a Debian userland under proot needs.
            // Measured on Android 17; see agent-docs/archive/research/android-exec-policy.md.
            targetSdk = 28
            versionNameSuffix = "-full"
            buildConfigField("boolean", "USERLAND", "true")
        }
        create("play") {
            dimension = "distribution"
            targetSdk = 37
            versionNameSuffix = "-play"
            buildConfigField("boolean", "USERLAND", "false")
        }
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
    // Play prefers an app bundle, which splits this way on its own; the
    // per-ABI APKs are what F-Droid and direct installs want.
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
    // Both flavors start up through the same code, so keep one merged profile
    // in src/main/generated/baselineProfiles rather than one copy per flavor.
    mergeIntoMain = true
    // Committed profile, applied on every release build — no emulator needed
    // at build time.
    saveInSrc = true
    automaticGenerationDuringBuild = false
}

// Pinned in gradle.properties so the app and the vendored terminal modules
// (which build libtermux.so with ndk-build) cannot drift apart.
val ndkVersion = providers.gradleProperty("seeker.ndkVersion").get()
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
    description = "Builds the Rust core (libseekercore.so) for Android ABIs"
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
    // Audio and video tabs. ExoPlayer rather than the platform MediaPlayer
    // because it decodes Matroska, WebM, Opus and FLAC on every API level
    // this app supports, and reports the video's size before the first frame
    // so the surface can be laid out at the right aspect ratio.
    implementation(libs.androidx.media3.exoplayer)
    // Installs the baseline profile when the store (or adb) doesn't compile
    // it at install time.
    implementation(libs.androidx.profileinstaller)
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