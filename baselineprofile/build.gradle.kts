// Baseline profile generator. This test module cold-starts the app on an
// emulator and records the classes and methods the startup path touches;
// androidx.baselineprofile turns that trace into baseline-prof.txt, which
// release builds compile ahead of time (see :app). Regenerate with:
//
//   ./gradlew :app:generateBaselineProfile
//
// The Gradle-managed device below uses the API 36 google_apis x86_64 image —
// the same one tools/fold-emulator.sh installs — so generation is one command
// with no AVD of its own.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "to.eyed.seeker.code.baselineprofile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    targetProjectPath = ":app"

    // No product flavours here, because :app has none: a test module must
    // mirror the flavours of the module it targets, and the two editions
    // (`full` with the Debian userland, `play` without) collapsed into one.

    testOptions.managedDevices.localDevices {
        create("pixel6Api36") {
            device = "Pixel 6"
            apiLevel = 36
            // google_apis (not playstore): profile capture needs a userdebug
            // build. This image is already used elsewhere in this project.
            systemImageSource = "google"
        }
    }
}

baselineProfile {
    // Generate on the managed device above, never on whatever happens to be
    // plugged in.
    managedDevices += "pixel6Api36"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
