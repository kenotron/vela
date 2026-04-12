# Vela Phase 1 — Android Shell Implementation Plan

> **For execution:** Use subagent-driven-development recipe with plan_path pointing to this file.

**Goal:** Bootstrap a working Android app with voice input capture, a conversation thread UI, and a Gemma 4 stub — fully testable via accessibility snapshots without needing visual rendering.

**Architecture:** Single-module Kotlin + Compose MVVM. VoiceCapture handles audio. FakeGemmaEngine stubs AI responses. ConversationViewModel wires them. Room persists messages. All instrumented tests use AccessibilitySnapshot to verify UI state from the accessibility tree — no screenshots needed.

**Tech Stack:** Kotlin 2.0, Jetpack Compose, Hilt, Room, UI Automator, Truth, Gradle 8.7 + AGP 8.3

**Self-Testing Pattern:** `UiDevice.dumpWindowHierarchy(file)` → parse XML for `content-desc` and `text` attributes. Every UI assertion goes through `AccessibilitySnapshot.kt`.

---

## Task 1: Bootstrap Project Structure

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/libs.versions.toml`

**Step 1: Create `gradle/wrapper/gradle-wrapper.properties`**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

**Step 2: Create `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.3.2"
kotlin = "2.0.0"
compose-bom = "2024.06.00"
hilt = "2.51"
room = "2.6.1"
uiautomator = "2.3.0"
junit = "4.13.2"
espresso = "3.5.1"
truth = "1.4.2"
coroutines = "1.8.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version = "1.13.1" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version = "2.8.1" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version = "1.9.0" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version = "1.2.0" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
uiautomator = { group = "androidx.test.uiautomator", name = "uiautomator", version.ref = "uiautomator" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }
truth = { group = "com.google.truth", name = "truth", version.ref = "truth" }
androidx-test-runner = { group = "androidx.test", name = "runner", version = "1.5.2" }
androidx-test-rules = { group = "androidx.test", name = "rules", version = "1.5.0" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version = "2.0.0-1.0.21" }
room = { id = "androidx.room", version.ref = "room" }
```

**Step 3: Create `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Vela"
include(":app")
```

**Step 4: Create root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
```

**Step 5: Create `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

**Step 6: Generate the Gradle wrapper**

Run:
```bash
cd /Users/ken/workspace/vela && gradle wrapper --gradle-version 8.7
```

If the `gradle` command is not available on the system, download the wrapper JAR and script manually:
```bash
cd /Users/ken/workspace/vela
mkdir -p gradle/wrapper
curl -fsSL "https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar" -o gradle/wrapper/gradle-wrapper.jar
```

Then create the `gradlew` shell script at `/Users/ken/workspace/vela/gradlew`:

```bash
#!/bin/sh
# Gradle wrapper start-up script — auto-generated
APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Resolve location
PRG="$0"
while [ -h "$PRG" ]; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=$(dirname "$PRG")"/$link"
    fi
done
SAVED="$(pwd)"
cd "$(dirname "$PRG")" >/dev/null || exit
APP_HOME="$(pwd -P)"
cd "$SAVED" >/dev/null || exit

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine the Java command
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
```

Then make it executable:
```bash
chmod +x /Users/ken/workspace/vela/gradlew
```

**Step 7: Verify the Gradle project resolves**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew help
```
Expected: Gradle downloads, resolves, and prints help text. May warn about missing `:app` module — that is fine, it will be created in Task 2.

**Step 8: Commit**

```bash
cd /Users/ken/workspace/vela && git add settings.gradle.kts build.gradle.kts gradle.properties gradle/ gradlew gradlew.bat && git commit -m "build: bootstrap Gradle 8.7 project with version catalog"
```

---

## Task 2: Create App Module Build File and Minimal Sources

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/vela/app/VelaApplication.kt`

**Step 1: Create `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
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
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Unit testing
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented testing
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(libs.truth)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
```

**Step 2: Create `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <application
        android:name=".VelaApplication"
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.Material3.DayNight.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

**Step 3: Create `app/src/main/res/values/strings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Vela</string>
</resources>
```

**Step 4: Create `app/src/main/kotlin/com/vela/app/VelaApplication.kt`**

```kotlin
package com.vela.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VelaApplication : Application()
```

**Step 5: Verify the app module compiles**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. There will be a warning about `MainActivity` being referenced in the manifest but not existing yet — that is expected and does not block compilation. If it does fail on the missing activity, that is also acceptable; the important thing is the build system resolves all dependencies.

**Step 6: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/ && git commit -m "build: add app module with Compose, Hilt, Room dependencies"
```

---

## Task 3: Create AccessibilitySnapshot Helper and First Failing Instrumented Test

**Files:**
- Create: `app/src/androidTest/kotlin/com/vela/app/util/AccessibilitySnapshot.kt`
- Create: `app/src/androidTest/kotlin/com/vela/app/ConversationScreenTest.kt`

**Step 1: Create `app/src/androidTest/kotlin/com/vela/app/util/AccessibilitySnapshot.kt`**

```kotlin
package com.vela.app.util

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertWithMessage
import java.io.File

object AccessibilitySnapshot {
    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    fun dump(): String {
        device.waitForIdle(3000)
        val file = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "snapshot_${System.currentTimeMillis()}.xml"
        )
        device.dumpWindowHierarchy(file)
        return file.readText().also { file.delete() }
    }

    fun assertHasContentDesc(contentDesc: String) {
        val snapshot = dump()
        assertWithMessage("Expected element with content-desc=\"$contentDesc\" in UI hierarchy")
            .that(snapshot)
            .contains("content-desc=\"$contentDesc\"")
    }

    fun assertHasText(text: String) {
        val snapshot = dump()
        assertWithMessage("Expected element with text=\"$text\" in UI hierarchy")
            .that(snapshot)
            .contains("text=\"$text\"")
    }

    fun assertNotHasContentDesc(contentDesc: String) {
        val snapshot = dump()
        assertWithMessage("Expected NO element with content-desc=\"$contentDesc\" in UI hierarchy")
            .that(snapshot)
            .doesNotContain("content-desc=\"$contentDesc\"")
    }
}
```

**Step 2: Create `app/src/androidTest/kotlin/com/vela/app/ConversationScreenTest.kt`**

```kotlin
package com.vela.app

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vela.app.util.AccessibilitySnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationScreenTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun conversationScreenShowsVoiceButton() {
        AccessibilitySnapshot.assertHasContentDesc("Start voice input")
    }
}
```

Note: This test file uses `ActivityScenarioRule` which requires `androidx.test.ext:junit`. Add this dependency to `app/build.gradle.kts` in the `dependencies` block:

```kotlin
androidTestImplementation("androidx.test.ext:junit:1.1.5")
```

**Step 3: Verify the test fails**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebugAndroidTest
```
Expected: Compilation fails with an error like `Unresolved reference: MainActivity`. This confirms the test is written correctly but the implementation does not exist yet.

**Step 4: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/androidTest/ && git commit -m "test: add AccessibilitySnapshot helper and first failing conversation screen test"
```

---

## Task 4: Create VelaApplication, MainActivity, and Empty ConversationScreen

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/MainActivity.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/theme/Color.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/theme/Type.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/theme/Theme.kt`
- Create: `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt`

**Step 1: Create `app/src/main/kotlin/com/vela/app/ui/theme/Color.kt`**

```kotlin
package com.vela.app.ui.theme

import androidx.compose.ui.graphics.Color

val VelaPrimary = Color(0xFF1B5E20)
val VelaSecondary = Color(0xFF388E3C)
val VelaTertiary = Color(0xFF81C784)
```

**Step 2: Create `app/src/main/kotlin/com/vela/app/ui/theme/Type.kt`**

```kotlin
package com.vela.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val VelaTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)
```

**Step 3: Create `app/src/main/kotlin/com/vela/app/ui/theme/Theme.kt`**

```kotlin
package com.vela.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = VelaPrimary,
    secondary = VelaSecondary,
    tertiary = VelaTertiary
)

private val LightColorScheme = lightColorScheme(
    primary = VelaPrimary,
    secondary = VelaSecondary,
    tertiary = VelaTertiary
)

@Composable
fun VelaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VelaTypography,
        content = content
    )
}
```

**Step 4: Create `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt`**

This is an empty shell — it does not contain the voice button yet. The test from Task 3 should still fail.

```kotlin
package com.vela.app.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun ConversationScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("Vela")
    }
}
```

**Step 5: Create `app/src/main/kotlin/com/vela/app/MainActivity.kt`**

```kotlin
package com.vela.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vela.app.ui.conversation.ConversationScreen
import com.vela.app.ui.theme.VelaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VelaTheme {
                ConversationScreen()
            }
        }
    }
}
```

**Step 6: Verify the app compiles but the test still fails**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

Then run the instrumented test:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:connectedDebugAndroidTest --tests "com.vela.app.ConversationScreenTest.conversationScreenShowsVoiceButton"
```
Expected: FAIL — the test cannot find `content-desc="Start voice input"` because the `ConversationScreen` only shows a `Text("Vela")`. The failure message will be: `Expected element with content-desc="Start voice input" in UI hierarchy`.

**Step 7: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/main/ && git commit -m "feat: add MainActivity, empty ConversationScreen, and Compose theme"
```

---

## Task 5: Write Failing Test for VoiceButton State Toggle

**Files:**
- Create: `app/src/androidTest/kotlin/com/vela/app/VoiceButtonTest.kt`

**Step 1: Create `app/src/androidTest/kotlin/com/vela/app/VoiceButtonTest.kt`**

```kotlin
package com.vela.app

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.vela.app.util.AccessibilitySnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceButtonTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun voiceButtonShowsStartStateInitially() {
        AccessibilitySnapshot.assertHasContentDesc("Start voice input")
    }

    @Test
    fun voiceButtonTogglesAfterTap() {
        // Tap the voice button
        val startButton = device.findObject(By.desc("Start voice input"))
        startButton.click()
        device.waitForIdle(2000)

        // Should now show stop state
        AccessibilitySnapshot.assertHasContentDesc("Stop voice input")
        AccessibilitySnapshot.assertNotHasContentDesc("Start voice input")
    }

    @Test
    fun voiceButtonTogglesBackAfterSecondTap() {
        // Tap to start
        val startButton = device.findObject(By.desc("Start voice input"))
        startButton.click()
        device.waitForIdle(2000)

        // Tap to stop
        val stopButton = device.findObject(By.desc("Stop voice input"))
        stopButton.click()
        device.waitForIdle(2000)

        // Should be back to start state
        AccessibilitySnapshot.assertHasContentDesc("Start voice input")
        AccessibilitySnapshot.assertNotHasContentDesc("Stop voice input")
    }
}
```

**Step 2: Verify the tests fail**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:connectedDebugAndroidTest --tests "com.vela.app.VoiceButtonTest"
```
Expected: All 3 tests FAIL. `voiceButtonShowsStartStateInitially` fails because no element with `content-desc="Start voice input"` exists in the hierarchy. The other two fail for the same root cause.

**Step 3: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/androidTest/kotlin/com/vela/app/VoiceButtonTest.kt && git commit -m "test: add failing VoiceButton accessibility and toggle tests"
```

---

## Task 6: Implement VoiceButton Composable

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/components/VoiceButton.kt`
- Modify: `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt`

**Step 1: Create `app/src/main/kotlin/com/vela/app/ui/components/VoiceButton.kt`**

```kotlin
package com.vela.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun VoiceButton(
    isListening: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = if (isListening) "Stop voice input" else "Start voice input"
    val icon = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic
    val iconContentDesc = if (isListening) "Stop" else "Microphone"

    FloatingActionButton(
        onClick = onToggle,
        modifier = modifier.semantics { contentDescription = description },
        containerColor = if (isListening) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = iconContentDesc
        )
    }
}
```

**Step 2: Replace `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt`**

Replace the entire file content with:

```kotlin
package com.vela.app.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vela.app.ui.components.VoiceButton

@Composable
fun ConversationScreen() {
    var isListening by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            VoiceButton(
                isListening = isListening,
                onToggle = { isListening = !isListening }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Text("Vela")
        }
    }
}
```

**Step 3: Verify the VoiceButton tests pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:connectedDebugAndroidTest --tests "com.vela.app.VoiceButtonTest"
```
Expected: All 3 tests PASS.

**Step 4: Verify the ConversationScreen test also passes**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:connectedDebugAndroidTest --tests "com.vela.app.ConversationScreenTest.conversationScreenShowsVoiceButton"
```
Expected: PASS.

**Step 5: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/main/kotlin/com/vela/app/ui/ && git commit -m "feat: implement VoiceButton composable with accessibility toggle"
```

---

## Task 7: Write Failing Unit Test for VoiceCapture

**Files:**
- Create: `app/src/test/kotlin/com/vela/app/voice/VoiceCaptureTest.kt`

**Step 1: Create `app/src/test/kotlin/com/vela/app/voice/VoiceCaptureTest.kt`**

This is a JVM unit test (runs in `test/`, not `androidTest/`). It tests the state machine of `VoiceCapture` without using a real `AudioRecord`. The `VoiceCapture` class will accept an `AudioRecordFactory` function so we can inject a fake in tests.

```kotlin
package com.vela.app.voice

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCaptureTest {

    @Test
    fun initialStateIsIdle() = runTest {
        val capture = VoiceCapture(
            outputDir = createTempDir(),
            audioRecordFactory = { FakeAudioRecord() }
        )
        assertThat(capture.isRecording.value).isFalse()
    }

    @Test
    fun startCaptureTransitionsToRecording() = runTest {
        val capture = VoiceCapture(
            outputDir = createTempDir(),
            audioRecordFactory = { FakeAudioRecord() }
        )
        capture.startCapture()
        assertThat(capture.isRecording.value).isTrue()
    }

    @Test
    fun stopCaptureTransitionsToIdleAndReturnsFilePath() = runTest {
        val tempDir = createTempDir()
        val capture = VoiceCapture(
            outputDir = tempDir,
            audioRecordFactory = { FakeAudioRecord() }
        )
        capture.startCapture()
        val filePath = capture.stopCapture()

        assertThat(capture.isRecording.value).isFalse()
        assertThat(filePath).isNotNull()
        assertThat(File(filePath!!).exists()).isTrue()
        assertThat(File(filePath).extension).isEqualTo("wav")
    }

    @Test
    fun stopCaptureWithoutStartReturnsNull() = runTest {
        val capture = VoiceCapture(
            outputDir = createTempDir(),
            audioRecordFactory = { FakeAudioRecord() }
        )
        val filePath = capture.stopCapture()
        assertThat(filePath).isNull()
    }

    private fun createTempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "vela_test_${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }
}

/**
 * Fake AudioRecord that mimics the interface used by VoiceCapture
 * without requiring Android framework classes.
 */
class FakeAudioRecord : AudioRecordWrapper {
    private var recording = false

    override fun startRecording() {
        recording = true
    }

    override fun stop() {
        recording = false
    }

    override fun read(buffer: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
        // Fill buffer with silence (zeros) to simulate audio data
        buffer.fill(0, offsetInBytes, offsetInBytes + sizeInBytes)
        return sizeInBytes
    }

    override fun release() {
        recording = false
    }
}
```

**Step 2: Verify the test fails**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.voice.VoiceCaptureTest"
```
Expected: Compilation fails with `Unresolved reference: VoiceCapture` and `Unresolved reference: AudioRecordWrapper`.

**Step 3: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/test/kotlin/com/vela/app/voice/ && git commit -m "test: add failing VoiceCapture unit tests with FakeAudioRecord"
```

---

## Task 8: Implement VoiceCapture

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/voice/VoiceCapture.kt`

**Step 1: Create `app/src/main/kotlin/com/vela/app/voice/VoiceCapture.kt`**

```kotlin
package com.vela.app.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Abstraction over Android's AudioRecord so we can test without the framework.
 */
interface AudioRecordWrapper {
    fun startRecording()
    fun stop()
    fun read(buffer: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int
    fun release()
}

/**
 * Captures audio from the microphone and saves it to a WAV file.
 *
 * @param outputDir Directory where WAV files will be saved.
 * @param audioRecordFactory Factory that creates an AudioRecordWrapper.
 *        In production, this wraps a real AudioRecord. In tests, a FakeAudioRecord.
 */
class VoiceCapture(
    private val outputDir: File,
    private val audioRecordFactory: () -> AudioRecordWrapper,
    private val sampleRate: Int = 16000,
    private val channelCount: Int = 1,
    private val bitsPerSample: Int = 16
) {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var recorder: AudioRecordWrapper? = null
    private var outputFile: File? = null
    private var recordingThread: Thread? = null

    fun startCapture() {
        if (_isRecording.value) return

        outputDir.mkdirs()
        outputFile = File(outputDir, "recording_${System.currentTimeMillis()}.wav")

        val record = audioRecordFactory()
        recorder = record
        _isRecording.value = true

        recordingThread = Thread {
            val bufferSize = 1024
            val buffer = ByteArray(bufferSize)
            val fos = FileOutputStream(outputFile!!)

            // Write a placeholder WAV header — will be updated on stop
            val header = ByteArray(44)
            fos.write(header)

            record.startRecording()

            var totalBytesWritten = 0
            while (_isRecording.value) {
                val bytesRead = record.read(buffer, 0, bufferSize)
                if (bytesRead > 0) {
                    fos.write(buffer, 0, bytesRead)
                    totalBytesWritten += bytesRead
                }
                // Yield to avoid busy-spinning in tests
                if (!_isRecording.value) break
                Thread.sleep(10)
            }

            fos.flush()
            fos.close()

            // Finalize WAV header
            writeWavHeader(outputFile!!, totalBytesWritten)
        }
        recordingThread?.start()
    }

    fun stopCapture(): String? {
        if (!_isRecording.value) return null

        _isRecording.value = false
        recordingThread?.join(2000)

        recorder?.stop()
        recorder?.release()
        recorder = null
        recordingThread = null

        return outputFile?.absolutePath
    }

    private fun writeWavHeader(file: File, dataSize: Int) {
        val raf = RandomAccessFile(file, "rw")
        val byteRate = sampleRate * channelCount * bitsPerSample / 8
        val blockAlign = channelCount * bitsPerSample / 8

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            // RIFF header
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())

            // fmt sub-chunk
            put("fmt ".toByteArray())
            putInt(16)                    // Sub-chunk size
            putShort(1)                   // Audio format: PCM
            putShort(channelCount.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())

            // data sub-chunk
            put("data".toByteArray())
            putInt(dataSize)
        }

        raf.seek(0)
        raf.write(header.array())
        raf.close()
    }
}
```

**Step 2: Verify the VoiceCapture unit tests pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.voice.VoiceCaptureTest"
```
Expected: All 4 tests PASS.

**Step 3: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/main/kotlin/com/vela/app/voice/ && git commit -m "feat: implement VoiceCapture with AudioRecordWrapper abstraction"
```

---

## Task 9: Write Failing Unit Test for FakeGemmaEngine

**Files:**
- Create: `app/src/test/kotlin/com/vela/app/ai/FakeGemmaEngineTest.kt`

**Step 1: Create `app/src/test/kotlin/com/vela/app/ai/FakeGemmaEngineTest.kt`**

```kotlin
package com.vela.app.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeGemmaEngineTest {

    @Test
    fun processTextReturnsNonEmptyResponse() = runTest {
        val engine: GemmaEngine = FakeGemmaEngine()
        val response = engine.processText("hello")

        assertThat(response).isNotNull()
        assertThat(response).isNotEmpty()
    }

    @Test
    fun processTextReturnsCannedGreeting() = runTest {
        val engine: GemmaEngine = FakeGemmaEngine()
        val response = engine.processText("hello")

        assertThat(response).isEqualTo("Hello! I'm Vela, your on-device AI assistant. How can I help?")
    }

    @Test
    fun processTextHandlesDifferentInputs() = runTest {
        val engine: GemmaEngine = FakeGemmaEngine()

        val response1 = engine.processText("What's the weather?")
        assertThat(response1).isNotEmpty()

        val response2 = engine.processText("")
        assertThat(response2).isNotEmpty()
    }

    @Test
    fun engineImplementsGemmaEngineInterface() {
        val engine = FakeGemmaEngine()
        assertThat(engine).isInstanceOf(GemmaEngine::class.java)
    }
}
```

**Step 2: Verify the tests fail**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.ai.FakeGemmaEngineTest"
```
Expected: Compilation fails with `Unresolved reference: GemmaEngine` and `Unresolved reference: FakeGemmaEngine`.

**Step 3: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/test/kotlin/com/vela/app/ai/ && git commit -m "test: add failing FakeGemmaEngine unit tests"
```

---

## Task 10: Implement GemmaEngine Interface and FakeGemmaEngine

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ai/GemmaEngine.kt`
- Create: `app/src/main/kotlin/com/vela/app/ai/FakeGemmaEngine.kt`

**Step 1: Create `app/src/main/kotlin/com/vela/app/ai/GemmaEngine.kt`**

```kotlin
package com.vela.app.ai

/**
 * Interface for the on-device AI engine.
 * Phase 1 uses FakeGemmaEngine; Phase 2 will swap in real Gemma 4 inference.
 */
interface GemmaEngine {
    /**
     * Process text input and return the AI response.
     * In production, this runs Gemma 4 inference on-device.
     */
    suspend fun processText(input: String): String
}
```

**Step 2: Create `app/src/main/kotlin/com/vela/app/ai/FakeGemmaEngine.kt`**

```kotlin
package com.vela.app.ai

import kotlinx.coroutines.delay

/**
 * Stub GemmaEngine that returns canned responses.
 * Simulates a small processing delay to mimic real inference latency.
 */
class FakeGemmaEngine : GemmaEngine {

    override suspend fun processText(input: String): String {
        // Simulate inference latency
        delay(100)
        return "Hello! I'm Vela, your on-device AI assistant. How can I help?"
    }
}
```

**Step 3: Verify the FakeGemmaEngine unit tests pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.ai.FakeGemmaEngineTest"
```
Expected: All 4 tests PASS.

**Step 4: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/main/kotlin/com/vela/app/ai/ && git commit -m "feat: implement GemmaEngine interface and FakeGemmaEngine stub"
```

---

## Task 11: Write Failing Unit Test for ConversationViewModel

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/domain/model/Message.kt` (needed by the test to compile, but the ViewModel itself doesn't exist yet)
- Create: `app/src/test/kotlin/com/vela/app/ui/conversation/ConversationViewModelTest.kt`

**Step 1: Create the domain model first (the test depends on it)**

Create `app/src/main/kotlin/com/vela/app/domain/model/Message.kt`:

```kotlin
package com.vela.app.domain.model

import java.util.UUID

enum class MessageRole {
    USER,
    ASSISTANT
}

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
```

**Step 2: Create `app/src/test/kotlin/com/vela/app/ui/conversation/ConversationViewModelTest.kt`**

```kotlin
package com.vela.app.ui.conversation

import com.google.common.truth.Truth.assertThat
import com.vela.app.ai.FakeGemmaEngine
import com.vela.app.domain.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialMessagesListIsEmpty() = runTest {
        val viewModel = ConversationViewModel(
            gemmaEngine = FakeGemmaEngine()
        )
        assertThat(viewModel.messages.value).isEmpty()
    }

    @Test
    fun onVoiceInputAddsUserMessage() = runTest {
        val viewModel = ConversationViewModel(
            gemmaEngine = FakeGemmaEngine()
        )

        viewModel.onVoiceInput("/tmp/test_audio.wav")
        advanceUntilIdle()

        val messages = viewModel.messages.value
        assertThat(messages).isNotEmpty()
        assertThat(messages.first().role).isEqualTo(MessageRole.USER)
    }

    @Test
    fun onVoiceInputTriggersAssistantResponse() = runTest {
        val viewModel = ConversationViewModel(
            gemmaEngine = FakeGemmaEngine()
        )

        viewModel.onVoiceInput("/tmp/test_audio.wav")
        advanceUntilIdle()

        val messages = viewModel.messages.value
        assertThat(messages).hasSize(2)
        assertThat(messages[0].role).isEqualTo(MessageRole.USER)
        assertThat(messages[1].role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(messages[1].content).isEqualTo(
            "Hello! I'm Vela, your on-device AI assistant. How can I help?"
        )
    }

    @Test
    fun isProcessingIsTrueWhileGemmaRuns() = runTest {
        val viewModel = ConversationViewModel(
            gemmaEngine = FakeGemmaEngine()
        )

        assertThat(viewModel.isProcessing.value).isFalse()

        viewModel.onVoiceInput("/tmp/test_audio.wav")
        // Don't advance — processing should be in flight
        testDispatcher.scheduler.advanceTimeBy(50)
        assertThat(viewModel.isProcessing.value).isTrue()

        advanceUntilIdle()
        assertThat(viewModel.isProcessing.value).isFalse()
    }
}
```

**Step 3: Verify the tests fail**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.ui.conversation.ConversationViewModelTest"
```
Expected: Compilation fails with `Unresolved reference: ConversationViewModel`.

**Step 4: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/main/kotlin/com/vela/app/domain/ app/src/test/kotlin/com/vela/app/ui/ && git commit -m "test: add failing ConversationViewModel unit tests and Message domain model"
```

---

## Task 12: Implement ConversationViewModel

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationViewModel.kt`

**Step 1: Create `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationViewModel.kt`**

```kotlin
package com.vela.app.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ai.GemmaEngine
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the conversation screen.
 * Manages the message list, voice state, and AI processing.
 *
 * Note: In Phase 1, this ViewModel is constructed directly in tests.
 * Hilt injection and Room persistence will be wired in Task 14 and beyond.
 */
class ConversationViewModel(
    private val gemmaEngine: GemmaEngine
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    /**
     * Called when voice capture completes. Adds a user message for the audio input,
     * then sends the transcription placeholder to GemmaEngine and appends the response.
     *
     * @param audioPath Path to the captured WAV file.
     */
    fun onVoiceInput(audioPath: String) {
        viewModelScope.launch {
            // Add user message (Phase 1: audio path as placeholder; Phase 2: real transcription)
            val userMessage = Message(
                role = MessageRole.USER,
                content = "[Voice input: $audioPath]"
            )
            _messages.value = _messages.value + userMessage

            // Process through Gemma
            _isProcessing.value = true
            val response = gemmaEngine.processText(userMessage.content)
            _isProcessing.value = false

            // Add assistant response
            val assistantMessage = Message(
                role = MessageRole.ASSISTANT,
                content = response
            )
            _messages.value = _messages.value + assistantMessage
        }
    }
}
```

**Step 2: Verify the ConversationViewModel unit tests pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest --tests "com.vela.app.ui.conversation.ConversationViewModelTest"
```
Expected: All 4 tests PASS.

**Step 3: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/main/kotlin/com/vela/app/ui/conversation/ConversationViewModel.kt && git commit -m "feat: implement ConversationViewModel with GemmaEngine integration"
```

---

## Task 13: Write Failing Instrumented Test for Full Conversation Flow

> **Quality Review Warning (auto-generated):** Quality review loop exhausted after 3
> iterations. Final verdict was APPROVED with no critical or important issues, but three
> cosmetic suggestions remain unresolved:
> 1. Magic numbers `2000` / `5000` should be companion-object constants (`IDLE_TIMEOUT_MS`, `RESPONSE_TIMEOUT_MS`)
> 2. `device` property getter is duplicated across `ConversationScreenTest` and `VoiceButtonTest` — extract to shared base class if a third test class appears
> 3. `device.wait()` return value silently dropped on line 50–53 — add a comment explaining the null return is intentional (ViewModel not yet wired)
>
> **Human reviewer:** Please assess whether these suggestions warrant a follow-up cleanup task.

**Files:**
- Modify: `app/src/androidTest/kotlin/com/vela/app/ConversationScreenTest.kt`

**Step 1: Replace `app/src/androidTest/kotlin/com/vela/app/ConversationScreenTest.kt`**

Replace the entire file content with:

```kotlin
package com.vela.app

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.vela.app.util.AccessibilitySnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationScreenTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun conversationScreenShowsVoiceButton() {
        AccessibilitySnapshot.assertHasContentDesc("Start voice input")
    }

    @Test
    fun tappingVoiceButtonTogglesState() {
        val startButton = device.findObject(By.desc("Start voice input"))
        startButton.click()
        device.waitForIdle(2000)

        AccessibilitySnapshot.assertHasContentDesc("Stop voice input")
    }

    @Test
    fun fullConversationFlowShowsAssistantResponse() {
        // 1. Tap voice button to start
        val startButton = device.findObject(By.desc("Start voice input"))
        startButton.click()
        device.waitForIdle(2000)

        // 2. Verify recording state
        AccessibilitySnapshot.assertHasContentDesc("Stop voice input")

        // 3. Tap voice button to stop — this triggers the voice-to-Gemma pipeline
        val stopButton = device.findObject(By.desc("Stop voice input"))
        stopButton.click()

        // 4. Wait for the assistant response to appear (FakeGemmaEngine has 100ms delay)
        device.wait(Until.findObject(By.text("Hello! I'm Vela, your on-device AI assistant. How can I help?")), 5000)

        // 5. Verify the assistant response is in the accessibility tree
        AccessibilitySnapshot.assertHasText(
            "Hello! I'm Vela, your on-device AI assistant. How can I help?"
        )
    }
}
```

**Step 2: Verify the new test fails**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:connectedDebugAndroidTest --tests "com.vela.app.ConversationScreenTest.fullConversationFlowShowsAssistantResponse"
```
Expected: FAIL. The `ConversationScreen` is not yet wired to the `ConversationViewModel`, so stopping voice input does not trigger any AI response. The test will fail on `assertHasText("Hello! I'm Vela...")` because no such text exists in the hierarchy.

**Step 3: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/src/androidTest/kotlin/com/vela/app/ConversationScreenTest.kt && git commit -m "test: add failing full conversation flow instrumented test"
```

---

## Task 14: Wire ConversationScreen to ViewModel and VoiceCapture

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/di/AppModule.kt`
- Create: `app/src/main/kotlin/com/vela/app/data/db/MessageEntity.kt`
- Create: `app/src/main/kotlin/com/vela/app/data/db/MessageDao.kt`
- Create: `app/src/main/kotlin/com/vela/app/data/db/VelaDatabase.kt`
- Create: `app/src/main/kotlin/com/vela/app/data/repository/ConversationRepository.kt`
- Modify: `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationViewModel.kt`
- Modify: `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt`

This is the largest task — it wires everything together. Follow each step in order.

**Step 1: Create `app/src/main/kotlin/com/vela/app/data/db/MessageEntity.kt`**

```kotlin
package com.vela.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long
)
```

**Step 2: Create `app/src/main/kotlin/com/vela/app/data/db/MessageDao.kt`**

```kotlin
package com.vela.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
```

**Step 3: Create `app/src/main/kotlin/com/vela/app/data/db/VelaDatabase.kt`**

```kotlin
package com.vela.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MessageEntity::class],
    version = 1,
    exportSchema = true
)
abstract class VelaDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
}
```

**Step 4: Create `app/src/main/kotlin/com/vela/app/data/repository/ConversationRepository.kt`**

```kotlin
package com.vela.app.data.repository

import com.vela.app.data.db.MessageDao
import com.vela.app.data.db.MessageEntity
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val messageDao: MessageDao
) {
    fun getMessages(): Flow<List<Message>> {
        return messageDao.getAllMessages().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun saveMessage(message: Message) {
        messageDao.insertMessage(message.toEntity())
    }

    private fun MessageEntity.toDomain(): Message = Message(
        id = id,
        role = MessageRole.valueOf(role),
        content = content,
        timestamp = timestamp
    )

    private fun Message.toEntity(): MessageEntity = MessageEntity(
        id = id,
        role = role.name,
        content = content,
        timestamp = timestamp
    )
}
```

**Step 5: Create `app/src/main/kotlin/com/vela/app/di/AppModule.kt`**

```kotlin
package com.vela.app.di

import android.content.Context
import androidx.room.Room
import com.vela.app.ai.FakeGemmaEngine
import com.vela.app.ai.GemmaEngine
import com.vela.app.data.db.MessageDao
import com.vela.app.data.db.VelaDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideVelaDatabase(@ApplicationContext context: Context): VelaDatabase {
        return Room.databaseBuilder(
            context,
            VelaDatabase::class.java,
            "vela_database"
        ).build()
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: VelaDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    @Singleton
    fun provideGemmaEngine(): GemmaEngine {
        return FakeGemmaEngine()
    }
}
```

**Step 6: Rewrite `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationViewModel.kt`**

Replace the entire file content with:

```kotlin
package com.vela.app.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ai.GemmaEngine
import com.vela.app.data.repository.ConversationRepository
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val gemmaEngine: GemmaEngine,
    private val repository: ConversationRepository
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getMessages().collect { dbMessages ->
                _messages.value = dbMessages
            }
        }
    }

    /**
     * Called when voice capture completes. Adds a user message,
     * runs it through GemmaEngine, and saves both messages.
     */
    fun onVoiceInput(audioPath: String) {
        viewModelScope.launch {
            val userMessage = Message(
                role = MessageRole.USER,
                content = "[Voice input: $audioPath]"
            )
            repository.saveMessage(userMessage)

            _isProcessing.value = true
            val response = gemmaEngine.processText(userMessage.content)
            _isProcessing.value = false

            val assistantMessage = Message(
                role = MessageRole.ASSISTANT,
                content = response
            )
            repository.saveMessage(assistantMessage)
        }
    }
}
```

**Step 7: Update the unit test to use the new constructor**

The `ConversationViewModel` now requires a `ConversationRepository`. Update `app/src/test/kotlin/com/vela/app/ui/conversation/ConversationViewModelTest.kt` — replace the entire file with:

```kotlin
package com.vela.app.ui.conversation

import com.google.common.truth.Truth.assertThat
import com.vela.app.ai.FakeGemmaEngine
import com.vela.app.data.repository.ConversationRepository
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeConversationRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeConversationRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialMessagesListIsEmpty() = runTest {
        val viewModel = ConversationViewModel(
            gemmaEngine = FakeGemmaEngine(),
            repository = fakeRepository
        )
        advanceUntilIdle()
        assertThat(viewModel.messages.value).isEmpty()
    }

    @Test
    fun onVoiceInputAddsUserMessage() = runTest {
        val viewModel = ConversationViewModel(
            gemmaEngine = FakeGemmaEngine(),
            repository = fakeRepository
        )
        advanceUntilIdle()

        viewModel.onVoiceInput("/tmp/test_audio.wav")
        advanceUntilIdle()

        val messages = viewModel.messages.value
        assertThat(messages).isNotEmpty()
        assertThat(messages.first().role).isEqualTo(MessageRole.USER)
    }

    @Test
    fun onVoiceInputTriggersAssistantResponse() = runTest {
        val viewModel = ConversationViewModel(
            gemmaEngine = FakeGemmaEngine(),
            repository = fakeRepository
        )
        advanceUntilIdle()

        viewModel.onVoiceInput("/tmp/test_audio.wav")
        advanceUntilIdle()

        val messages = viewModel.messages.value
        assertThat(messages).hasSize(2)
        assertThat(messages[0].role).isEqualTo(MessageRole.USER)
        assertThat(messages[1].role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(messages[1].content).isEqualTo(
            "Hello! I'm Vela, your on-device AI assistant. How can I help?"
        )
    }

    @Test
    fun isProcessingIsTrueWhileGemmaRuns() = runTest {
        val viewModel = ConversationViewModel(
            gemmaEngine = FakeGemmaEngine(),
            repository = fakeRepository
        )
        advanceUntilIdle()

        assertThat(viewModel.isProcessing.value).isFalse()

        viewModel.onVoiceInput("/tmp/test_audio.wav")
        testDispatcher.scheduler.advanceTimeBy(50)
        assertThat(viewModel.isProcessing.value).isTrue()

        advanceUntilIdle()
        assertThat(viewModel.isProcessing.value).isFalse()
    }
}

/**
 * In-memory fake of ConversationRepository for unit tests.
 */
class FakeConversationRepository : ConversationRepository(
    messageDao = throw IllegalStateException("FakeConversationRepository should not use DAO")
) {
    private val messagesFlow = MutableStateFlow<List<Message>>(emptyList())

    override fun getMessages(): Flow<List<Message>> = messagesFlow

    override suspend fun saveMessage(message: Message) {
        messagesFlow.value = messagesFlow.value + message
    }
}
```

Note: The `FakeConversationRepository` subclass trick requires making `getMessages()` and `saveMessage()` `open` in `ConversationRepository`. Update `app/src/main/kotlin/com/vela/app/data/repository/ConversationRepository.kt` — change the two method signatures to be `open`:

```kotlin
package com.vela.app.data.repository

import com.vela.app.data.db.MessageDao
import com.vela.app.data.db.MessageEntity
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class ConversationRepository @Inject constructor(
    private val messageDao: MessageDao
) {
    open fun getMessages(): Flow<List<Message>> {
        return messageDao.getAllMessages().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    open suspend fun saveMessage(message: Message) {
        messageDao.insertMessage(message.toEntity())
    }

    private fun MessageEntity.toDomain(): Message = Message(
        id = id,
        role = MessageRole.valueOf(role),
        content = content,
        timestamp = timestamp
    )

    private fun Message.toEntity(): MessageEntity = MessageEntity(
        id = id,
        role = role.name,
        content = content,
        timestamp = timestamp
    )
}
```

**Step 8: Rewrite `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt`**

Replace the entire file content with:

```kotlin
package com.vela.app.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import com.vela.app.ui.components.VoiceButton
import com.vela.app.voice.AudioRecordWrapper
import com.vela.app.voice.VoiceCapture

@Composable
fun ConversationScreen(
    viewModel: ConversationViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    var isListening by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Permission state
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
    }

    // VoiceCapture instance
    val voiceCapture = remember {
        VoiceCapture(
            outputDir = context.cacheDir,
            audioRecordFactory = {
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                object : AudioRecordWrapper {
                    override fun startRecording() = audioRecord.startRecording()
                    override fun stop() = audioRecord.stop()
                    override fun read(buffer: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int =
                        audioRecord.read(buffer, offsetInBytes, sizeInBytes)
                    override fun release() = audioRecord.release()
                }
            }
        )
    }

    // Scroll to bottom when new messages arrive
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        floatingActionButton = {
            VoiceButton(
                isListening = isListening,
                onToggle = {
                    if (isListening) {
                        // Stop recording
                        isListening = false
                        val audioPath = voiceCapture.stopCapture()
                        if (audioPath != null) {
                            viewModel.onVoiceInput(audioPath)
                        }
                    } else {
                        // Start recording
                        if (hasAudioPermission) {
                            isListening = true
                            voiceCapture.startCapture()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (messages.isEmpty() && !isProcessing) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Tap the microphone to start",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }

                    if (isProcessing) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = containerColor),
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
```

**Step 9: Verify the unit tests still pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest
```
Expected: All unit tests PASS (VoiceCaptureTest, FakeGemmaEngineTest, ConversationViewModelTest).

**Step 10: Verify the instrumented tests pass**

Run:
```bash
cd /Users/ken/workspace/vela && ./gradlew :app:connectedDebugAndroidTest
```
Expected: All instrumented tests PASS — `ConversationScreenTest` (3 tests) and `VoiceButtonTest` (3 tests).

Note on `fullConversationFlowShowsAssistantResponse`: This test taps the voice button to start, then taps again to stop. In the emulator, the `RECORD_AUDIO` permission must be granted. If the test fails due to a permission dialog, grant the permission via adb before running tests:
```bash
adb shell pm grant com.vela.app android.permission.RECORD_AUDIO
```

Alternatively, if the `AudioRecord` constructor fails on the emulator (no microphone hardware), the test may need the emulator configured with a virtual microphone. If the `fullConversationFlowShowsAssistantResponse` test fails specifically because audio capture throws on the emulator, that is acceptable for Phase 1 — the voice button toggle tests and unit tests still validate the core flow.

**Step 11: Commit**

```bash
cd /Users/ken/workspace/vela && git add app/ && git commit -m "feat: wire ConversationScreen to ViewModel, VoiceCapture, Room, and Hilt DI"
```

---

## Task 15: Final Verification and Phase 1 Commit

**Files:**
- No new files. This task verifies everything works end-to-end.

**Step 1: Run all unit tests**

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:testDebugUnitTest
```
Expected: All unit tests PASS:
- `com.vela.app.voice.VoiceCaptureTest` — 4 tests
- `com.vela.app.ai.FakeGemmaEngineTest` — 4 tests
- `com.vela.app.ui.conversation.ConversationViewModelTest` — 4 tests

**Step 2: Run all instrumented tests**

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:connectedDebugAndroidTest
```
Expected: All instrumented tests PASS:
- `com.vela.app.ConversationScreenTest` — 3 tests
- `com.vela.app.VoiceButtonTest` — 3 tests

**Step 3: Verify the APK builds successfully**

```bash
cd /Users/ken/workspace/vela && ./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. APK at `app/build/outputs/apk/debug/app-debug.apk`.

**Step 4: Tag the Phase 1 milestone**

```bash
cd /Users/ken/workspace/vela && git tag -a v0.1.0-phase1 -m "Phase 1: Android shell with voice input and Gemma stub"
```

**Step 5: Final commit (if any uncommitted changes remain)**

```bash
cd /Users/ken/workspace/vela && git status
```
If clean: done. If there are uncommitted changes:
```bash
cd /Users/ken/workspace/vela && git add -A && git commit -m "feat: Phase 1 complete - Android shell with voice input and Gemma stub"
```

---

## Summary

| Task | What It Delivers | Tests |
|------|-----------------|-------|
| 1 | Gradle project structure | `./gradlew help` |
| 2 | App module build + manifest | `./gradlew :app:assembleDebug` |
| 3 | AccessibilitySnapshot helper + first failing test | Compilation fails (expected) |
| 4 | MainActivity, theme, empty ConversationScreen | App compiles; test still fails |
| 5 | VoiceButton toggle tests (failing) | 3 tests fail (expected) |
| 6 | VoiceButton composable | 4 instrumented tests pass |
| 7 | VoiceCapture unit tests (failing) | Compilation fails (expected) |
| 8 | VoiceCapture implementation | 4 unit tests pass |
| 9 | FakeGemmaEngine unit tests (failing) | Compilation fails (expected) |
| 10 | GemmaEngine interface + FakeGemmaEngine | 4 unit tests pass |
| 11 | ConversationViewModel unit tests (failing) | Compilation fails (expected) |
| 12 | ConversationViewModel implementation | 4 unit tests pass |
| 13 | Full conversation flow test (failing) | 1 test fails (expected) |
| 14 | Full wiring: Screen ↔ VM ↔ VoiceCapture ↔ Room ↔ Hilt | All 18 tests pass |
| 15 | Final verification + tag | All green |

**Total tests at end of Phase 1:** 12 unit tests + 6 instrumented tests = 18 tests