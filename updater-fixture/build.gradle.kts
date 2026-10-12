plugins { alias(libs.plugins.android.application) }
android {
    namespace = "org.privateview.quietscreen.fixture"
    compileSdk = 37
    defaultConfig {
        applicationId = "org.privateview.quietscreen.debug"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.3.2-updater-test"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
