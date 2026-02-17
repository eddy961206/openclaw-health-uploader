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
    buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secrets.getProperty("SUPABASE_ANON_KEY", "")}\"")
    // Safe rollout: keep false until backend `health_daily` has the v2 sleep columns.
    buildConfigField("boolean", "SEND_SLEEP_V2_FIELDS", "${secrets.getProperty("SEND_SLEEP_V2_FIELDS", "false")}")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildFeatures {
    buildConfig = true
    viewBinding = true
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      // For easy sideload testing
      signingConfig = signingConfigs.getByName("debug")
    }
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
  implementation("androidx.recyclerview:recyclerview:1.3.2")
  implementation("androidx.activity:activity-ktx:1.8.2")
  implementation("androidx.fragment:fragment-ktx:1.6.2")
  implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

  // Health Connect
  implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

  // HTTP
  implementation("com.squareup.okhttp3:okhttp:4.12.0")

  // Coroutines Main dispatcher (prevents startup crash on Dispatchers.Main)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

  // Background scheduling
  implementation("androidx.work:work-runtime-ktx:2.9.1")

  // Instrumentation tests (smoke)
  androidTestImplementation("androidx.test.ext:junit:1.1.5")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
  androidTestImplementation("androidx.test:runner:1.5.2")
  androidTestImplementation("androidx.test:rules:1.5.0")
}
