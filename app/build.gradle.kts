import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

val secrets = Properties().apply {
  val f = rootProject.file("secrets.properties")
  if (f.exists()) f.inputStream().use { load(it) }
}

android {
  namespace = "com.openclaw.healthuploader"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.openclaw.healthuploader"
    minSdk = 26
    targetSdk = 34
    versionCode = 1
    versionName = "0.1"

    // Inject ingest endpoint + secret at build time (kept in secrets.properties)
    buildConfigField("String", "INGEST_ENDPOINT", "\"${secrets.getProperty("INGEST_ENDPOINT", "")}\"")
    buildConfigField("String", "INGEST_SECRET", "\"${secrets.getProperty("INGEST_SECRET", "")}\"")
  }

  buildFeatures {
    buildConfig = true
    viewBinding = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
}

dependencies {
  implementation("androidx.core:core-ktx:1.12.0")
  implementation("androidx.appcompat:appcompat:1.6.1")
  implementation("com.google.android.material:material:1.11.0")

  // Health Connect
  implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

  // HTTP
  implementation("com.squareup.okhttp3:okhttp:4.12.0")

  // Coroutines Main dispatcher (prevents startup crash on Dispatchers.Main)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
