plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nova.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nova.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "3.1"
        ndk { abiFilters += "arm64-v8a" } // every modern phone; keeps the app small
    }

    // A fixed key so each new build installs as an update over the previous one.
    signingConfigs {
        create("nova") {
            storeFile = file("nova.keystore")
            storePassword = "novanova"
            keyAlias = "nova"
            keyPassword = "novanova"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("nova")
        }
        debug {
            signingConfig = signingConfigs.getByName("nova")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
            )
            pickFirsts += setOf("META-INF/*.kotlin_module", "META-INF/io.netty.versions.properties")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // On-device AI model runtime
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
    // Cloud brain (Claude)
    implementation("com.anthropic:anthropic-java:2.73.0")
    // Free brain (Groq) talks HTTP + JSON directly
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    // Offline "Hey Nova" wake word
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}
