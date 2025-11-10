plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.21"
    // Add application plugin so `./gradlew run` is available
    application

}

group = "org.example"
version = "0.1"

repositories {
    mavenCentral()

}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.google.ortools:ortools-java:9.12.4544")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Configure the application plugin: the Kotlin top-level `main` in `Main.kt` becomes class `MainKt`
application {
    // If your `main` is in a package, use the fully-qualified name, e.g. "com.example.MainKt"
    mainClass.set("MainKt")
}