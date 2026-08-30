// Vendored from termux/termux-app (see ../VENDOR.md). Upstream's build file is
// Groovy, publishes to JitPack and targets SDK 28; this is a rewrite against
// this project's toolchain, not a copy. The Java sources are verbatim.
plugins {
    alias(libs.plugins.android.library)
}

android {
    // Upstream namespace, kept so the R/BuildConfig classes land where the
    // vendored sources expect. The JNI symbol names in src/main/jni/termux.c
    // encode the *package* (com.termux.terminal), which is why neither the
    // package nor this namespace may be renamed without patching the C too.
    namespace = "com.termux.emulator"
    compileSdk {
        version = release(37)
    }
    ndkVersion = providers.gradleProperty("seeker.ndkVersion").get()

    defaultConfig {
        minSdk = 31

        externalNativeBuild {
            ndkBuild {
                cFlags += listOf(
                    "-std=c11", "-Wall", "-Wextra", "-Werror", "-Os",
                    "-fno-stack-protector", "-Wl,--gc-sections"
                )
            }
        }

        // The ABIs the engine is built for (app/build.gradle.kts rustAbis).
        // Shipping libtermux.so for an ABI without libseekercore.so would be
        // a terminal in an app that cannot open a file.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        // The emulator's Logger calls android.util.Log, which is not
        // implemented in the unit-test JAR. Upstream relies on this too.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}
