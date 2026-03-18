plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.heideen.smsbackup"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.heideen.smsbackup"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0.0"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH")
      if (keystorePath != null) {
        storeFile = file(keystorePath)
        storePassword = System.getenv("KEYSTORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS")
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      signingConfig =
          signingConfigs.getByName(
              if (System.getenv("KEYSTORE_PATH") != null) "release" else "debug")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }

  kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }

  buildFeatures { compose = true }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.material.icons.core)
  implementation(libs.androidx.documentfile)
  implementation(libs.kotlinx.coroutines.android)
  debugImplementation(libs.androidx.ui.tooling)
}
