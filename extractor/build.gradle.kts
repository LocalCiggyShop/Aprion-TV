plugins { alias(libs.plugins.android.library) }
android {
    namespace = "org.privateview.quietscreen.extractor"
    compileSdk = 37
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
tasks.withType<Test>().configureEach { systemProperty("quietscreen.live", providers.gradleProperty("liveTests").orElse("false").get()) }
dependencies {
    implementation(project(":core"))
    implementation(libs.newpipe)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    coreLibraryDesugaring(libs.desugar)
    testImplementation(libs.junit)
}
