plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.21"

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