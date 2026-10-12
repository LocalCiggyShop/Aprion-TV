plugins { `java-library`; `maven-publish` }
group = "org.privateview.thirdparty"
version = "nanojson-e9d656d-private.1"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }; withSourcesJar() }
tasks.withType<JavaCompile>().configureEach { options.release.set(8); options.encoding = "UTF-8" }
sourceSets.main { java.setSrcDirs(listOf("../../sources/nanojson-e9d656ddb49a412a5a0a5d5ef20ca7ef09549996/src/main/java")) }
publishing {
    publications { create<MavenPublication>("local") { from(components["java"]); artifactId = "nanojson"; pom { licenses { license { name = "Apache-2.0"; url = "https://www.apache.org/licenses/LICENSE-2.0" } } } } }
    repositories { maven { name = "reviewed"; url = uri("../../maven") } }
}
