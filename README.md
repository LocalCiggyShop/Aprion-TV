# QuietScreen

An independent Android TV player for public YouTube videos, with native Media3 playback, remote navigation, search, quality and audio-track controls. No account, analytics or ad SDK is included. This project is not affiliated with YouTube or Google.

Version **0.3.1-beta** (version code 5) is the security patch following the first published 0.3.0-beta. It sanitizes inherited upstream test recordings and adds publication checks. Physical-TV compatibility and audio/sync testing remain pending. Captions are unsupported. The unofficial extraction method can break when YouTube changes; ad-free playback is not guaranteed.

The app checks for and downloads updates while Home or Settings is open. Signed metadata and the APK must match the installed app's package, higher version and certificate. Android may require installation approval. Updates use the configured LocalCiggyShop/QuietScreen GitHub repository.

Search/results stay in memory. Playback and update preferences remain on-device. YouTube/CDN and GitHub receive normal network requests; there is no app-operated usage-data service.

See [BUILDING.md](BUILDING.md), [PUBLIC_SECURITY.md](PUBLIC_SECURITY.md), [PUBLICATION.md](PUBLICATION.md), [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
