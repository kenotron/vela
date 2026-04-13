    plugins {
        alias(libs.plugins.android.application)
        alias(libs.plugins.kotlin.android)
        alias(libs.plugins.kotlin.compose)
        alias(libs.plugins.hilt.android)
        alias(libs.plugins.ksp)
        alias(libs.plugins.room)
    }

    android {
        namespace = "com.vela.app"
        compileSdk = 35

        defaultConfig {
            applicationId = "com.vela.app"
            minSdk = 26
            targetSdk = 35
            versionCode = 1
            versionName = "0.2.0"

            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        kotlinOptions { jvmTarget = "17" }

        buildFeatures { compose = true }

        room { schemaDirectory("$projectDir/schemas") }

        // Pre-built Rust .so lands in jniLibs — Android Studio auto-packages it
        sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
    }

    // ─── Rust build (cargo-ndk) ──────────────────────────────────────────────────
    // Requires: cargo install cargo-ndk  +  rustup target add aarch64-linux-android
    // Run once to set up: bash scripts/setup-rust-android.sh
    tasks.register<Exec>("buildRustRelease") {
        group = "build"
        description = "Compile amplifier-android Rust crate for arm64-v8a via cargo-ndk"
        workingDir("src/main/rust/amplifier-android")

        // Find the NDK — respect explicit env var, then fall back to sdk.dir/ndk/*
        val ndkHome = System.getenv("ANDROID_NDK_HOME")
            ?: (properties["sdk.dir"]?.toString()
                ?: System.getenv("ANDROID_HOME")
                ?: "${System.getProperty("user.home")}/Library/Android/sdk")
                .let { sdkDir -> file("$sdkDir/ndk").listFiles()?.maxByOrNull { it.name }?.absolutePath }

        if (ndkHome != null) environment("ANDROID_NDK_HOME", ndkHome)

        commandLine(
            "cargo", "ndk",
            "--target", "arm64-v8a",
            "--platform", "26",
            "--output-dir", "../../jniLibs",
            "build", "--release"
        )
    }

    tasks.named("preBuild") { dependsOn("buildRustRelease") }

    dependencies {
        // Core Android
        implementation(libs.androidx.core.ktx)
        implementation(libs.androidx.lifecycle.runtime.ktx)
        implementation(libs.androidx.activity.compose)

        // Compose BOM
        implementation(platform(libs.androidx.compose.bom))
        implementation(libs.androidx.compose.ui)
        implementation(libs.androidx.compose.ui.graphics)
        implementation(libs.androidx.compose.ui.tooling.preview)
        implementation(libs.androidx.compose.material3)
        implementation("androidx.compose.material:material-icons-extended")

        debugImplementation(libs.androidx.compose.ui.tooling)
        debugImplementation(libs.androidx.compose.ui.test.manifest)

        // Hilt
        implementation(libs.hilt.android)
        ksp(libs.hilt.android.compiler)
        implementation(libs.hilt.navigation.compose)

        // Room
        implementation(libs.androidx.room.runtime)
        implementation(libs.androidx.room.ktx)
        ksp(libs.androidx.room.compiler)

        // Coroutines
        implementation(libs.kotlinx.coroutines.android)

        // OkHttp — web tools
        implementation(libs.okhttp)

        // Unit tests
        testImplementation(libs.junit)
        testImplementation(libs.truth)
        testImplementation(libs.kotlinx.coroutines.test)
        testImplementation("org.json:json:20231013")

        androidTestImplementation(libs.androidx.test.runner)
        androidTestImplementation(libs.androidx.test.rules)
        androidTestImplementation(libs.androidx.espresso.core)
        androidTestImplementation(libs.truth)
        androidTestImplementation(platform(libs.androidx.compose.bom))
        androidTestImplementation(libs.androidx.compose.ui.test.junit4)
        androidTestImplementation("androidx.test.ext:junit:1.1.5")
    }
    