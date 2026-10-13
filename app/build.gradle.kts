import java.util.Properties

plugins { alias(libs.plugins.android.application); alias(libs.plugins.compose.compiler) }
val updateConfig = Properties().apply {
    val file = rootProject.file("release/public-updates.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val updateOwner = updateConfig.getProperty("owner", "")
val updateRepo = updateConfig.getProperty("repo", "")
val updateBranch = updateConfig.getProperty("branch", "main")
require(updateOwner.matches(Regex("[A-Za-z0-9-]{0,39}")) && updateRepo.matches(Regex("[A-Za-z0-9._-]{0,100}")) &&
    updateBranch.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid public update configuration" }
android {
    namespace = "org.privateview.quietscreen"
    compileSdk = 37
    defaultConfig {
        applicationId = "org.privateview.quietscreen"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.3.5-beta"
        buildConfigField("String", "UPDATE_OWNER", "\"$updateOwner\"")
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "UPDATE_BRANCH", "\"$updateBranch\"")
        manifestPlaceholders["appLabel"] = "Aprion TV"
        testInstrumentationRunner = if (providers.gradleProperty("nativeTrialProbe").orElse("false").get().toBoolean())
            "org.privateview.quietscreen.TrialNativeInstrumentation" else "androidx.test.runner.AndroidJUnitRunner"
        testProguardFiles("test-proguard-rules.pro")
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug"; manifestPlaceholders["appLabel"] = "Aprion TV Dev" }
        release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
        create("trial") {
            initWith(getByName("release"))
            applicationIdSuffix = ".trial"
            versionNameSuffix = "-trial"
            manifestPlaceholders["appLabel"] = "Aprion TV Trial"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += "release"
        }
    }
    testBuildType = providers.gradleProperty("testVariant").orElse("debug").get()
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("update-test-assets").get().asFile)
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    // javax.script is not an Android API and both extractor JS entry points are
    // denied. Do not package an unused desktop ScriptEngine service descriptor.
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/services/javax.script.ScriptEngineFactory") }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        require(updateOwner.isNotEmpty() && updateRepo.isNotEmpty()) {
            "Production release requires the actual GitHub repository in release/public-updates.properties"
        }
    }
}
dependencies {
    implementation(project(":core")); implementation(project(":extractor")); implementation(project(":playback"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui); implementation(libs.compose.foundation); implementation(libs.tv.material)
    implementation(libs.activity.compose); implementation(libs.lifecycle.runtime.compose); implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android); implementation(libs.media3.ui); implementation(libs.media3.exoplayer); implementation(libs.okhttp)
    // Already present in the runtime graph; expose it to lint to verify the receiver removal.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    coreLibraryDesugaring(libs.desugar)
    testImplementation(libs.junit)
    androidTestImplementation(libs.test.runner); androidTestImplementation(libs.test.junit)
}
