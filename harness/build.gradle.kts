plugins {
    kotlin("jvm") version "2.1.0"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.vela.harness.MainKt")
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("javax.inject:javax.inject:1")
}

// Pass prompt via system property to avoid Gradle word-splitting --args on spaces.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    val prompt = project.findProperty("prompt") as String?
    val sessionId = project.findProperty("sessionId") as String?
    if (prompt != null) systemProperty("vela.prompt", prompt)
    if (sessionId != null) systemProperty("vela.sessionId", sessionId)
}

// Fat JAR: ./gradlew shadowJar → harness/build/libs/vela-harness.jar
// Then: java -jar harness/build/libs/vela-harness.jar (no Gradle overhead)
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("vela-harness")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest { attributes["Main-Class"] = "com.vela.harness.MainKt" }
}
