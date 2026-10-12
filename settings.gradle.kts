pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository { maven { name = "reviewedLocalSources"; url = uri("third_party/maven") } }
            filter { includeGroup("net.newpipe"); includeGroup("org.privateview.thirdparty") }
        }
    }
}
rootProject.name = "QuietScreen"
include(":app", ":core", ":extractor", ":playback", ":trial-probe")
include(":updater-fixture")
