# Security and privacy

The app requests Internet access and permission to install its own updates. It has no login, analytics, ad SDK, microphone/storage/location permission or public update-control receiver. Backups and cleartext traffic are disabled. Search/results remain in memory; preferences and update state are private on-device.

Video traffic uses a restricted YouTube/CDN policy. JavaScript evaluation is denied. Updates use a separate HTTPS client, public-IP DNS checks, no cookies/authentication, an exact repository metadata path and bounded GitHub asset redirects. Providers can see normal request metadata and the network IP address.

Update metadata is capped at 8 KiB and signed with the permanent APK key using SHA-256/RSA. Only higher versions for the same package and compatible Android version are accepted. APKs are capped at 64 MiB, checked against the signed byte count and SHA-256, and checked for the same certificate. Android performs final APK signature verification. Invalid updates leave the installed app in place. Key rotation is not implemented.

Updates check on foreground Home/Settings with a six-hour throttle. Downloads stop when leaving these screens or the app. Automatic updates default on and can be disabled. Android may require permission to install from QuietScreen and/or confirmation. Silent installation depends on Android and the device.

Private signing material, local planning, machine paths, recordings, logs and emulator evidence are excluded from public source. Exports use a reviewed source allowlist and hash manifest. GitHub repository names, usernames and commit identity are public; use GitHub's no-reply commit email if desired.

Dependency versions, checksums, licenses and provenance are preserved. A moderate Kotlin Gradle plugin build-cache advisory remains a tooling limitation; output caching and incremental compilation are disabled as mitigation. This is not a claim that all dependencies or devices have passed security qualification.
