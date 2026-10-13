plugins { alias(libs.plugins.android.application) }
android {
    namespace = "org.privateview.quietscreen.fixture"
    compileSdk = 37
    defaultConfig {
        applicationId = "org.privateview.apriontv"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("fixtureVersionCode").orElse("8").get().toInt().also { require(it in 1..2_100_000_000) }
        versionName = providers.gradleProperty("fixtureVersionName").orElse("0.3.4-updater-test").get()
    }
    buildTypes { debug { applicationIdSuffix = ".debug" } }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("test-payload").get().asFile)
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
