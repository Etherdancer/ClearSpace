import java.io.FileInputStream
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.aboutlibraries)
  alias(libs.plugins.google.services)
  id("com.github.spotbugs") version "5.0.14"
  id("pmd")
}

android {
    namespace = "app.clearspace.network"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.clearspace.network"
        minSdk = 26
        targetSdk = 36
        versionCode = 38
        versionName = "0.4.4"
    }

    val keystorePropertiesFile = rootProject.file("app/keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String?
            keyPassword = keystoreProperties["keyPassword"] as String?
            storeFile = keystoreProperties["storeFile"]?.let { file(it as String) }
            storePassword = keystoreProperties["storePassword"] as String?
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
      compose = true
      aidl = true
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
    testOptions {
      unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
    }
}

spotbugs {
    toolVersion = "4.8.3"
}

// Configure SpotBugs reports
tasks.withType<com.github.spotbugs.snom.SpotBugsTask> {
    reports {
        create("html") {
            required.set(true)
            outputLocation.set(layout.buildDirectory.file("reports/spotbugs/spotbugs.html"))
        }
        create("xml") {
            required.set(true)
            outputLocation.set(layout.buildDirectory.file("reports/spotbugs/spotbugs.xml"))
        }
    }
}

pmd {
    ruleSets = listOf("java-basic", "java-braces")
    // ignoreFailures removed
}

// Configure PMD reports
tasks.withType<Pmd> {
    reports {
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.file("reports/pmd/pmd.html"))
    }
}

ksp {
  arg("room.generateKotlin", "true")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.fragment.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.biometric)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.iconsCore)
  implementation(libs.androidx.compose.material.iconsExtended)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // ClearSpace Strict Whitelist Dependencies
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  ksp(libs.room.compiler)
  implementation(libs.sqlcipher)
  implementation(libs.bouncycastle)
  implementation(libs.signal.protocol)
  implementation(libs.kotlinx.serialization.json)
  
  // CameraX and ZXing for Trust Verification (Phase 4)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.zxing.core)

  // Firebase (Google Play Hybrid Branch)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.messaging)
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.auth)
  implementation(libs.firebase.appcheck.playintegrity)
  
  // WebRTC Data Channels (Hybrid Media Sync)
  implementation(libs.webrtc.android)
  
  // ML Kit / TFLite (On-Device Filtering)
  implementation(libs.mlkit.custom)
  
  // Media / ExoPlayer (Media3)
  implementation(libs.media3.exoplayer)
  implementation(libs.media3.ui)
  
  // Open Source Licenses UI
  implementation(libs.aboutlibraries.compose)
}







