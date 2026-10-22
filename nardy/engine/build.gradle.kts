plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Pure-Kotlin rules engine: no Android dependencies, so it can be unit-tested
// on a plain JVM and reused later for an iOS / desktop port.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
