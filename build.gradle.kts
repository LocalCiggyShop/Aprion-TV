// Align the plugin classpath too: build tools are part of the supply chain.
buildscript {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "io.netty" && requested.version?.startsWith("4.1.") == true -> useVersion("4.1.137.Final")
                requested.group == "org.bouncycastle" -> useVersion("1.85")
                requested.group == "org.apache.commons" && requested.name == "commons-lang3" -> useVersion("3.18.0")
                requested.group == "org.apache.httpcomponents" && requested.name == "httpclient" -> useVersion("4.5.14")
                (requested.group == "com.fasterxml.jackson" || requested.group.startsWith("com.fasterxml.jackson.")) && requested.name != "jackson-annotations" -> useVersion("2.22.3")
                requested.group == "org.jdom" && requested.name == "jdom2" -> useVersion("2.0.6.1")
                requested.group == "org.bitbucket.b_c" && requested.name == "jose4j" -> useVersion("0.9.6")
            }
        }
    }
}
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.cyclonedx)
}
allprojects {
    tasks.withType<org.cyclonedx.gradle.CyclonedxDirectTask>().configureEach {
        val scope = providers.gradleProperty("sbomScope").orElse("runtime").get()
        if (scope == "runtime") {
            val variant = providers.gradleProperty("sbomVariant").orElse("debug").get()
            includeConfigs.set(listOf("${variant}RuntimeClasspath", "runtimeClasspath"))
            includeBuildEnvironment.set(false)
        } else { includeBuildEnvironment.set(true) }
        includeMetadataResolution.set(true)
    }
    dependencyLocking { lockAllConfigurations() }
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "io.netty" && requested.version?.startsWith("4.1.") == true -> useVersion("4.1.137.Final")
                requested.group == "org.bouncycastle" -> useVersion("1.85")
                requested.group == "org.apache.commons" && requested.name == "commons-lang3" -> useVersion("3.18.0")
                requested.group == "org.apache.httpcomponents" && requested.name == "httpclient" -> useVersion("4.5.14")
                (requested.group == "com.fasterxml.jackson" || requested.group.startsWith("com.fasterxml.jackson.")) && requested.name != "jackson-annotations" -> useVersion("2.22.3")
                requested.group == "org.jdom" && requested.name == "jdom2" -> useVersion("2.0.6.1")
                requested.group == "org.bitbucket.b_c" && requested.name == "jose4j" -> useVersion("0.9.6")
            }
            if (requested.group == "org.jsoup" && requested.name == "jsoup") {
                useVersion("1.23.2")
                because("Correct the pinned extractor's known jsoup vulnerabilities")
            }
            if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
                useVersion("2.3.10")
            }
        }
    }
}
