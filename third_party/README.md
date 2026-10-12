# Reviewed source inputs

Extractor: TeamNewPipe/NewPipeExtractor at f9e6bb808f82bf3e4dc1f2a29a16fd376931c8ef (v0.26.5), GPL-3.0-or-later.
JSON parser: TeamNewPipe/nanojson at e9d656ddb49a412a5a0a5d5ef20ca7ef09549996; Java file headers are Apache-2.0.

Archives were obtained from official GitHub codeload endpoints. Their hashes and API identities are recorded in the evidence directory. No prebuilt third-party APK is used.

`build-extractor` is our small reviewed local-only build. It compiles the actual pinned upstream source, uses JDK 21 with `--release 11` for the extractor and `--release 8` for nanojson, generates the upstream protobuf files, pins jsoup 1.23.2 and publishes both JARs to `third_party/maven`. It avoids the upstream publishing credentials, shell/Git-hash invocation, obsolete nanojson Maven plugins, and JitPack.

The only runtime upstream source modification initially replaces both JavaScript compilation/evaluation entry points with immediate `SecurityException`. No source transformation or fetched script is executed. Some videos may consequently be unsupported; this is intentionally tested before expanding the UI.

Use `scripts/Build-Dependencies.ps1` to rebuild, then verify the local artifact hashes against the recorded build provenance. Never regenerate dependency verification blindly when a hash changes.
