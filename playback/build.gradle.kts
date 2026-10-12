plugins { alias(libs.plugins.android.library) }
android {
    namespace = "org.privateview.quietscreen.playback"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":extractor"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.okhttp)
    implementation(libs.coroutines.android)
    coreLibraryDesugaring(libs.desugar)
    testImplementation(libs.junit)
}
