plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("kotlin-parcelize") }
import java.util.Properties
val ks = Properties().apply { file("../keystore/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
val samsungAar = file("libs").listFiles()?.firstOrNull { it.name.startsWith("samsung-health-data-api") && it.name.endsWith(".aar") }
android {
  sourceSets { getByName("main") { java.srcDir(if (samsungAar != null) "src/samsung/java" else "src/nosamsung/java") } }
  val hasKs = ks.getProperty("storeFile") != null
  if (hasKs) signingConfigs { create("stable") { storeFile = file(ks.getProperty("storeFile")); storePassword = ks.getProperty("storePassword"); keyAlias = ks.getProperty("keyAlias"); keyPassword = ks.getProperty("keyPassword") } }
  buildTypes {
    getByName("debug") { if (hasKs) signingConfig = signingConfigs.getByName("stable") }
    getByName("release") { if (hasKs) signingConfig = signingConfigs.getByName("stable"); isMinifyEnabled = false }
  }
  namespace = "com.wei.healthsync"
  compileSdk = 36
  defaultConfig { applicationId = "com.wei.healthsync"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "1.0" }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}
dependencies {
  if (samsungAar != null) { implementation(files(samsungAar)); implementation("com.google.code.gson:gson:2.11.0") }
  implementation("com.google.android.material:material:1.12.0")
  implementation("com.google.android.gms:play-services-auth:21.3.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
  implementation("androidx.health.connect:connect-client:1.1.0")
  implementation("androidx.work:work-runtime-ktx:2.10.0")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.activity:activity-ktx:1.9.3")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
