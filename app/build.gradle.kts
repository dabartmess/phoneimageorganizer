import java.util.Properties

val local = Properties().apply {
  val f = rootProject.file("local.properties"); if (f.exists()) f.inputStream().use { load(it) }
}
val dropboxKey: String = local.getProperty("DROPBOX_APP_KEY", "SET_DROPBOX_APP_KEY")
plugins { alias(libs.plugins.android.application) }
android {
  namespace = "com.example.phonefileorganizer"
  compileSdk = 36
  defaultConfig {
    applicationId = "net.dingodaveth.phonefileorganizer"
    minSdk = 29
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"
    manifestPlaceholders["dropboxScheme"] =
      if (dropboxKey == "SET_DROPBOX_APP_KEY") "db-unconfigured" else "db-$dropboxKey"
    buildConfigField("String", "DROPBOX_APP_KEY", "\"$dropboxKey\"")
  }
  buildFeatures { buildConfig = true }
  // ML Kit ships this library without symbols that the Android strip tool can process.
  packaging.jniLibs.keepDebugSymbols += "**/libmlkitcommonpipeline.so"
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}
dependencies {
  implementation(libs.material)
  implementation(libs.androidx.activity.ktx)
  implementation(libs.androidx.documentfile)
  implementation(libs.mlkit.image.labeling)
  implementation(libs.dropbox.core.sdk)
  implementation(libs.dropbox.android.sdk)
  implementation(libs.kotlinx.coroutines.android)

  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
}
