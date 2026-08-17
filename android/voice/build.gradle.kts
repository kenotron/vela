plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vela.voice"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core-domain"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // LiveKit Android SDK. NOTE: this artifact could not be resolved from
    // Google/Maven Central in this session's build environment (verified: it
    // is not published to either repository under this coordinate - see
    // README.md "Dependency resolution" section for the exact error and the
    // substitution made). All direct SDK usage is isolated behind the
    // LiveKitRoomClient wrapper interface (internal/LiveKitRoomClient.kt) so
    // the rest of this module - and its unit tests - compile and run
    // correctly regardless of whether this dependency line is enabled. The
    // real LiveKit Android SDK coordinate/repository must be confirmed and
    // this line re-enabled before this module is used in a production build.
    // implementation("io.livekit:livekit-android:2.5.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
