plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// Offline tool that trains the AI's neural nets by self-play; not part of the app.
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
    implementation(project(":engine"))
}

application {
    mainClass.set("com.bizoneceo.nardy.trainer.TrainerKt")
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}
