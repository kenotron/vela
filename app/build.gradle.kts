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
            // MSAL pulls in Bouncy Castle — these collide without exclusion
            resources.excludes += "META-INF/BCKEY.DSA"
            resources.excludes += "META-INF/BCKEY.SF"
        }

        testOptions {
            // Allow Android framework calls (e.g. Log.d) in JVM unit tests without crashing.
            unitTests.isReturnDefaultValues = true
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
        implementation("androidx.compose.material3:material3-window-size-class")
        implementation("androidx.compose.material:material-icons-extended")

        debugImplementation(libs.androidx.compose.ui.tooling)
        debugImplementation(libs.androidx.compose.ui.test.manifest)

        // Hilt
        implementation(libs.hilt.android)
        ksp(libs.hilt.android.compiler)
        implementation(libs.hilt.navigation.compose)

        // WorkManager + Hilt integration for ProfileWorker
        implementation("androidx.work:work-runtime-ktx:2.9.1")
        implementation("androidx.hilt:hilt-work:1.2.0")
        ksp("androidx.hilt:hilt-compiler:1.2.0")

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
            //
            // Dependency conflict background:
            //   Markwon 4.6.2 was built against com.atlassian.commonmark (old groupId, 0.13.x).
            //   We also need org.commonmark:commonmark:0.22.0 for the Ktor mini-app server.
            //   Both artifact groups publish classes in the org.commonmark.* Java package, so
            //   having both on the classpath causes duplicate-class build errors.
            //
            // Strategy:
            //   • Exclude com.atlassian.commonmark:commonmark (core) from all Markwon deps —
            //     org.commonmark:commonmark:0.22.0 fills that role, and Markwon core is
            //     binary-compatible with the 0.22.0 API at every call site it uses.
            //   • ext-strikethrough: allow com.atlassian.commonmark:commonmark-ext-gfm-strikethrough
            //     back in (inline-only extension, no block-parser API calls — compatible with 0.22.0).
            //   • ext-tables: exclude ALL com.atlassian.commonmark (the 0.13.0 tables extension
            //     calls ParserState.getLine():CharSequence which was removed in 0.22.0 → crash).
            //     Replaced below with org.commonmark:commonmark-ext-gfm-tables:0.22.0 which is
            //     compiled against the same core API.
            //   • ext-tasklist / linkify: narrow exclusion is safe — no ext artifact is pulled in.
            implementation("io.noties.markwon:core:4.6.2") {
                exclude(group = "com.atlassian.commonmark", module = "commonmark")
            }
            implementation("io.noties.markwon:ext-strikethrough:4.6.2") {
                exclude(group = "com.atlassian.commonmark", module = "commonmark")
            }
            implementation("io.noties.markwon:ext-tables:4.6.2") {
                // Broad exclusion: prevents com.atlassian.commonmark:commonmark-ext-gfm-tables:0.13.0
                // (incompatible with org.commonmark:commonmark:0.22.0 — ParserState.getLine() removed).
                // org.commonmark:commonmark-ext-gfm-tables:0.22.0 added explicitly below.
                exclude(group = "com.atlassian.commonmark")
            }
            implementation("io.noties.markwon:ext-tasklist:4.6.2") {
                exclude(group = "com.atlassian.commonmark", module = "commonmark")
            }
            implementation("io.noties.markwon:linkify:4.6.2") {
                exclude(group = "com.atlassian.commonmark", module = "commonmark")
            }
            // Tables extension compatible with org.commonmark:commonmark:0.22.0.
            // Replaces the excluded com.atlassian.commonmark:commonmark-ext-gfm-tables:0.13.0.
            // Markwon's ext-tables module uses only stable public API (TablesExtension.create(),
            // TableBlock/TableRow/TableCell AST nodes) — all present and unchanged in 0.22.0.
            implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")

        // Ktor embedded server for mini app backend
        implementation("io.ktor:ktor-server-core:2.3.13")
        implementation("io.ktor:ktor-server-cio:2.3.13")

        // CommonMark for server-side markdown → JSON transform
        implementation("org.commonmark:commonmark:0.22.0")

        // Local on-device embedding — all-MiniLM-L6-v2 via ONNX Runtime
            implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

        // Microsoft Authentication (MSAL) — Entra ID + personal Microsoft accounts
        implementation(libs.msal) {
            // display-mask is a Surface Duo foldable SDK — not available on standard
            // Maven repos and not needed for Vela. Exclude to unblock the build.
            exclude(group = "com.microsoft.device.display", module = "display-mask")
        }

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
    