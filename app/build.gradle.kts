    plugins {
        alias(libs.plugins.android.application)
        id("com.chaquo.python") version "16.0.0"
        alias(libs.plugins.kotlin.android)
        alias(libs.plugins.kotlin.compose)
        alias(libs.plugins.hilt.android)
        alias(libs.plugins.ksp)
        alias(libs.plugins.room)
    }

    android {
        namespace = "com.vela.app"
        compileSdk = 36

        defaultConfig {
            applicationId = "com.vela.app"
            minSdk = 26
            targetSdk = 36
            versionCode = 1
            versionName = "0.2.0"

            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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

        packaging {
            // JSch + Markwon each ship META-INF/versions — keep first, discard rest
            resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            resources.excludes += "META-INF/DEPENDENCIES"
            resources.excludes += "META-INF/LICENSE*"
            resources.excludes += "META-INF/NOTICE*"
        }
    }

    // ─── Chaquopy (embedded CPython) ─────────────────────────────────────────
    // Kotlin DSL: configure outside android {} using the chaquopy {} extension.
    // (Groovy-style python {} inside defaultConfig {} is not valid Kotlin DSL.)
    chaquopy {
        defaultConfig {
            version = "3.13"
        }
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

                // SSH — modern JSch fork, Ed25519 + modern ciphers, pure Java (no Kotlin metadata issues)
            implementation("com.github.mwiede:jsch:0.2.19")

            // Git version control — JGit for vault git sync
            implementation("org.eclipse.jgit:org.eclipse.jgit:6.8.0.202311291450-r")

            // Markdown rendering — Markwon (Java-based, version-agnostic, full GFM)
            // Headers, bold/italic/strikethrough, code blocks, tables, task lists, links
            implementation("io.noties.markwon:core:4.6.2")
            implementation("io.noties.markwon:ext-strikethrough:4.6.2")
            implementation("io.noties.markwon:ext-tables:4.6.2")
            implementation("io.noties.markwon:ext-tasklist:4.6.2")
            implementation("io.noties.markwon:linkify:4.6.2")

        // Local on-device embedding — all-MiniLM-L6-v2 via ONNX Runtime
            implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

            // Unit tests
        testImplementation(libs.junit)
        testImplementation(libs.truth)
        testImplementation(libs.kotlinx.coroutines.test)
        testImplementation("org.json:json:20231013")
        testImplementation("org.mockito:mockito-core:5.4.0")

        androidTestImplementation(libs.androidx.test.runner)
        androidTestImplementation(libs.androidx.test.rules)
        androidTestImplementation(libs.androidx.espresso.core)
        androidTestImplementation(libs.truth)
        androidTestImplementation(platform(libs.androidx.compose.bom))
        androidTestImplementation(libs.androidx.compose.ui.test.junit4)
        androidTestImplementation("androidx.test.ext:junit:1.1.5")
    }
    