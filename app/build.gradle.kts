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
        ndkVersion = "27.1.12297006"   // Phase 2: NDK for llama.cpp JNI

        defaultConfig {
            applicationId = "com.vela.app"
            minSdk = 26
            targetSdk = 35
            versionCode = 1
            versionName = "0.1.0"

            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

            // Only build for 64-bit ARM — the only realistic llama.cpp target on modern Android.
            ndk {
                abiFilters += "arm64-v8a"
            }

            // CMake args for llama.cpp
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DCMAKE_BUILD_TYPE=Release",
                    )
                }
            }
        }

        // Phase 2: wire in CMakeLists.txt that builds vela-llama.so from llama_bridge.cpp + llama.cpp
        externalNativeBuild {
            cmake {
                path = file("CMakeLists.txt")
                version = "3.22.1"
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        kotlinOptions {
            jvmTarget = "17"
            // mlkit-genai-prompt:1.0.0-beta2 was compiled against Kotlin 2.2.0 metadata;
            // suppresses the version mismatch error until we upgrade kotlin = "2.2.x" in libs.versions.toml
            freeCompilerArgs += "-Xskip-metadata-version-check"
        }

        buildFeatures {
            compose = true
        }

        room {
            schemaDirectory("$projectDir/schemas")
        }
    }

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

        // Debug Compose tools
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

        // ML Kit GenAI Prompt (Gemma 4 via AICore) — kept as fallback provider
        implementation(libs.mlkit.genai.prompt)

        // OkHttp — shared for web tools + model download
        implementation(libs.okhttp)

        // Unit tests
        testImplementation(libs.junit)
        testImplementation(libs.truth)
        testImplementation(libs.kotlinx.coroutines.test)
        testImplementation("org.json:json:20231013")

        // Instrumented tests
        androidTestImplementation(libs.androidx.test.runner)
        androidTestImplementation(libs.androidx.test.rules)
        androidTestImplementation(libs.androidx.espresso.core)
        androidTestImplementation(libs.androidx.uiautomator)
        androidTestImplementation(libs.truth)
        androidTestImplementation(platform(libs.androidx.compose.bom))
        androidTestImplementation(libs.androidx.compose.ui.test.junit4)
        androidTestImplementation("androidx.test.ext:junit:1.1.5")
    }
    