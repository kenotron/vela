import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.vela.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vela.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-scaffold"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        // Real server config (item 1 of the goal). Populate android/local.properties
        // (gitignored) with:
        //   VELA_SERVER_BASE_URL=http://100.84.25.57:9099
        //   VELA_SERVER_BEARER_TOKEN=<paste AMPLIFIER_AGENT_HTTP_API_KEY from
        //     ~/.amplifier/vela-agent-serve/env on the dev host>
        // Never hardcode the real values here — empty-string defaults only.
        buildConfigField(
            "String",
            "VELA_SERVER_BASE_URL",
            "\"${localProperties.getProperty("VELA_SERVER_BASE_URL", "")}\"",
        )
        buildConfigField(
            "String",
            "VELA_SERVER_BEARER_TOKEN",
            "\"${localProperties.getProperty("VELA_SERVER_BEARER_TOKEN", "")}\"",
        )
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-ui"))
    implementation(project(":voice"))
    implementation(project(":events"))
    implementation(project(":ledger"))
    implementation(project(":host-tools"))

    // Transitive "implementation" deps of :ledger/:host-tools aren't exposed on this
    // module's own compile classpath by default (Gradle/Kotlin visibility rules), so
    // supertypes like RoomDatabase/OkHttpClient referenced directly from app/ code
    // (VelaAppContainer.kt) need to be declared here too. This does not modify
    // ledger/host-tools' own build files -- only adds dependency declarations to
    // app/build.gradle.kts, per this lane's file ownership.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
}
