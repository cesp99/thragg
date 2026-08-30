// Vendored from termux/termux-app (see ../VENDOR.md). Java sources verbatim;
// this build file is a rewrite against this project's toolchain.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    // `api`, not `implementation`: TerminalView's public surface is written in
    // terminal-emulator types (TerminalSession, TerminalEmulator), so anyone
    // embedding the view needs them on the compile classpath.
    api(project(":terminal-emulator"))
    testImplementation(libs.junit)
}
