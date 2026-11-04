import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.URI
import java.io.File
import java.util.Properties
import java.io.FileInputStream

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
  localProperties.load(FileInputStream(localPropertiesFile))
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.sednium.kryptonide"
  compileSdk = 35
  ndkVersion = "25.2.9519653"

  defaultConfig {
    applicationId = "com.sednium.kryptonide"
    minSdk = 26
    targetSdk = 35
    multiDexEnabled = true
    versionCode = 40
    versionName = "4.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    externalNativeBuild {
      cmake {
        cppFlags += "-std=c++17"
      }
    }
    ndk {
      abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  signingConfigs {
    // Extract the properties once
    val keystoreFile = rootProject.file(localProperties.getProperty("SIGNING_STORE_FILE") ?: "kryptonide.jks")
    val keystorePassword = localProperties.getProperty("SIGNING_STORE_PASSWORD") ?: ""
    val signingKeyAlias = localProperties.getProperty("SIGNING_KEY_ALIAS") ?: "kryptonide"
    val signingKeyPassword = localProperties.getProperty("SIGNING_KEY_PASSWORD") ?: ""

    create("release") {
      storeFile = keystoreFile
      storePassword = keystorePassword
      keyAlias = signingKeyAlias
      keyPassword = signingKeyPassword
    }
    create("debugConfig") {
      storeFile = keystoreFile
      storePassword = keystorePassword
      keyAlias = signingKeyAlias
      keyPassword = signingKeyPassword
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }

  ksp {
    arg("room.verifySchema", "false")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  packaging {
    resources {
      excludes += "META-INF/DEPENDENCIES"
      excludes += "META-INF/LICENSE"
      excludes += "META-INF/LICENSE.txt"
      excludes += "META-INF/NOTICE"
      excludes += "META-INF/NOTICE.txt"
      excludes += "META-INF/*.kotlin_module"
      excludes += "META-INF/versions/**"
      pickFirsts += "kotlin/reflect/reflect.kotlin_builtins"
      pickFirsts += "**/*.kotlin_builtins"
      pickFirsts += "**/*.kotlin_metadata"
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "META-INF/INDEX.LIST"
      excludes += "META-INF/io.netty.versions.properties"
    }
    jniLibs {
      useLegacyPackaging = true
      pickFirsts += "**/libc++_shared.so"
      pickFirsts += "**/libllama*.so"
      pickFirsts += "**/libggml*.so"
      excludes += "**/libllama_jni.so"
    }
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
    }
  }
}

// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  // Sora Editor — native Android code-editing View (real touch selection,
  // hardware-accelerated rendering). See ui/editor/SoraEditorHost.kt.
  //
  // 0.23.6 IS the latest version actually published to Maven Central —
  // confirmed directly against central.sonatype.com's own listing. "0.24.6"
  // exists only as a GitHub release tag with commits targeting Gradle
  // v9/AGP v9, but was never actually pushed to Central, hence the
  // "Could not find" resolution failure when we tried it. So we're back to
  // 0.23.6 without language-textmate (it hits a D8 "Record desugaring"
  // dexing bug specific to our AGP 9.1.1 toolchain — see earlier
  // mergeExtDexDebug failures). Revisit once Rosemoe actually publishes a
  // release past 0.23.6 to Central.
  implementation("io.github.Rosemoe.sora-editor:editor:0.23.6")
  implementation("io.github.Rosemoe.sora-editor:language-java:0.23.6")
  implementation("io.github.Rosemoe.sora-editor:language-textmate:0.23.6")

  implementation("org.eclipse.jgit:org.eclipse.jgit:6.8.0.202311291450-r")
  implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.jsch:6.8.0.202311291450-r")
  implementation(platform(libs.androidx.compose.bom))
  implementation("com.google.apis:google-api-services-drive:v3-rev20240123-2.0.0")
  implementation("com.google.api-client:google-api-client-android:2.2.0")
  implementation("com.google.http-client:google-http-client-gson:1.43.3")
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation("androidx.webkit:webkit:1.11.0")
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.coil.compose)
  implementation(libs.okhttp)
  implementation(libs.okhttp.sse)
  implementation(libs.play.services.auth)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.lucide.icons.android)


  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
