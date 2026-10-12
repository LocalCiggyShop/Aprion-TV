import com.google.protobuf.gradle.*
plugins { `java-library`; `maven-publish`; id("com.google.protobuf") }
group = "net.newpipe"
version = "0.26.5-private.1"
val upstream = file("../../sources/NewPipeExtractor-f9e6bb808f82bf3e4dc1f2a29a16fd376931c8ef")
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }; withSourcesJar() }
tasks.withType<JavaCompile>().configureEach { options.release.set(11); options.encoding = "UTF-8" }
sourceSets {
    main {
        java.srcDirs("$upstream/extractor/src/main/java", "$upstream/timeago-parser/src/main/java")
        resources.setSrcDirs(listOf("$upstream/extractor/src/main/resources"))
        proto { srcDir("$upstream/extractor/src/main/proto") }
    }
    test {
        java.setSrcDirs(listOf("$upstream/extractor/src/test/java"))
        resources.setSrcDirs(listOf("$upstream/extractor/src/test/resources"))
    }
}
dependencies {
    implementation(project(":nanojson"))
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.google.protobuf:protobuf-javalite:4.35.1")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.mozilla:rhino-engine:1.8.1")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.squareup.okhttp3:okhttp:5.4.0")
    testImplementation("com.google.code.gson:gson:2.14.0")
}
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.35.1" }
    generateProtoTasks { all().configureEach { builtins { named("java") { option("lite") } } } }
}
tasks.jar { exclude("**/*.proto") }
tasks.test {
    useJUnitPlatform()
    systemProperty("downloader", "MOCK")
    workingDir = file("$upstream/extractor")
    // Never run upstream network tests implicitly. These selected tests use recorded fixtures.
    filter {
        includeTestsMatching("*YoutubeSearchQHTest*")
        includeTestsMatching("*YoutubeSearchExtractorTest*")
        includeTestsMatching("*JsonUtilsTest*")
    }
}
publishing {
    publications { create<MavenPublication>("local") { from(components["java"]); artifactId = "extractor"; pom { licenses { license { name = "GPL-3.0-or-later"; url = "https://www.gnu.org/licenses/gpl-3.0.html" } } } } }
    repositories { maven { name = "reviewed"; url = uri("../../maven") } }
}
