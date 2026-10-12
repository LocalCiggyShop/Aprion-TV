# Building QuietScreen

Use Windows PowerShell 7, JDK 21 and Android SDK platform 37/build tools. The Gradle wrapper, dependency locks and verification metadata pin reviewed versions. Do not blindly regenerate dependency checksums.

Helper scripts expect a verified JDK under `.tools/jdk21/`, SDK under `.tools/android-sdk/`, and Gradle cache under `.tools/gradle-home/`. Alternatively configure JAVA_HOME, ANDROID_HOME and GRADLE_USER_HOME and run the wrapper directly. Local SDK paths and caches must not be committed.

Build reviewed local extractor dependencies with `scripts/Build-Dependencies.ps1` first. Source inputs, patches and provenance are under `third_party/`; corresponding upstream sources and notices are under `licenses/`. Review inputs before executing a build.

That dependency helper uses the separately verified Gradle 9.6.1 installation under `.tools/gradle/gradle-9.6.1/`; the app wrapper pins Gradle 9.4.1. Install official distributions and verify their published SHA-256 values before using them. Build tools 36.0.0 were used for the initial beta.

```powershell
./scripts/Build-Prototype.ps1 -Offline
```

Remove `-Offline` on a fresh machine to fetch dependencies, still using strict checksum verification. Development/trial variants use a development certificate and separate package IDs; they are not public release artifacts.

Production releases are unsigned during Gradle builds. Configure `release/public-updates.properties`, run `:core:test :app:lintRelease :app:assembleRelease` with `--dependency-verification strict`, then sign offline with `scripts/Sign-Release.ps1`. See PUBLICATION.md. Keep signing material private.

The standalone `trial-probe` is an emulator test APK. Do not include it in an end-user release or install it on a household TV. Emulator results do not establish physical-TV compatibility.
