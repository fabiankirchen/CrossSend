import org.gradle.api.GradleException
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.crosssend.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.crosssend.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("KEYSTORE_FILE")
                ?: System.getenv("SIGNING_KEYSTORE_PATH")
                ?: keystoreProperties.getProperty("storeFile")
            if (!storeFilePath.isNullOrBlank()) {
                val resolvedFile = if (file(storeFilePath).exists()) {
                    file(storeFilePath)
                } else if (rootProject.file(storeFilePath).exists()) {
                    rootProject.file(storeFilePath)
                } else null

                if (resolvedFile != null) {
                    storeFile = resolvedFile
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                        ?: System.getenv("SIGNING_KEYSTORE_PASSWORD")
                        ?: keystoreProperties.getProperty("storePassword")
                    keyAlias = System.getenv("KEY_ALIAS")
                        ?: System.getenv("SIGNING_KEY_ALIAS")
                        ?: keystoreProperties.getProperty("keyAlias")
                    keyPassword = System.getenv("KEY_PASSWORD")
                        ?: System.getenv("SIGNING_KEY_PASSWORD")
                        ?: keystoreProperties.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            val allowDebugReleaseSigning =
                project.findProperty("allowDebugReleaseSigning")?.toString() == "true"
            val isReleasePackagingTask = gradle.startParameter.taskNames.any { taskName ->
                val shortName = taskName.substringAfterLast(':')
                shortName.equals("assembleRelease", ignoreCase = true) ||
                    shortName.equals("bundleRelease", ignoreCase = true) ||
                    shortName.equals("packageRelease", ignoreCase = true)
            }
            signingConfig = when {
                releaseSigning.storeFile != null -> releaseSigning
                allowDebugReleaseSigning -> signingConfigs.getByName("debug")
                isReleasePackagingTask -> throw GradleException(
                    "Release signing keystore missing. Configure keystore.properties / env vars or pass -PallowDebugReleaseSigning=true for local unsigned verification."
                )
                else -> null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
    lint {
        checkReleaseBuilds = true
        abortOnError = true
        disable += "NullSafeMutableLiveData"
        disable += "ObsoleteSdkInt"
    }
}

dependencies {
    // AndroidX & Core
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Jetpack Compose & Material 3 Expressive (Latest Stable BOM + M3 1.5.0 Expressive)
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // Room Database
    val roomVersion = "2.8.5"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Networking & WebDAV (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Web Extraction & HTML Parsing (Jsoup)
    implementation("org.jsoup:jsoup:1.18.1")

    // Coroutines & WorkManager (Background RSS Sync)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // PDF Text Extraction & Reflow
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk15to18")
    }


    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
