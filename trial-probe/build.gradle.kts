plugins { alias(libs.plugins.android.application) }

// AGP's built-in Kotlin support adds stdlib even to a Java-only app. Exclude
// that implicit runtime so the probe cannot shadow optimized app classes.
configurations.configureEach { exclude(group = "org.jetbrains.kotlin") }

// A separate framework-only probe avoids loading test-library/desugar classes
// into the optimized app. It is never part of the household-TV APK.
android {
    namespace = "org.privateview.quietscreen.trial.probe"
    compileSdk = 37
    defaultConfig {
        applicationId = "org.privateview.apriontv.trial.probe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
        manifestPlaceholders["probeTarget"] = providers.gradleProperty("probeTarget").orElse("org.privateview.apriontv.trial").get().also {
            require(it in setOf("org.privateview.apriontv.trial", "org.privateview.apriontv", "org.privateview.apriontv.debug"))
        }
    }
    sourceSets.getByName("main").java.srcDir("../app/src/androidTest/java")
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("update-assets").get().asFile)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = false
    }
}
