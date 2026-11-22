import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("com.android.application")
}

android {
    namespace = "com.jonathancruzate.duopanel"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jonathancruzate.duopanel"
        minSdk = 29
        targetSdk = 36
        versionCode = 12
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Framework-only build per TECH-NOTES.md (no AndroidX dependencies).
    // Pinned to Kotlin 2.2.10 matching AGP 9.1.1 built-in Kotlin in local Gradle cache.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
}
