# QuietScreen

An independent Android TV player for public YouTube videos, with native Media3 playback, remote navigation, search, quality and audio-track controls. No account, analytics or ad SDK is included. This project is not affiliated with YouTube or Google.

Version **0.3.3-beta** (version code 7) repairs damaged update-cache retries and adds restricted GitHub feed fallback and actionable local update diagnostics, retaining TV browsing, private recommendations, Continue watching and signed-update protections. Physical-TV compatibility and audio/sync testing remain pending. Captions are unsupported. The unofficial extraction method can break when YouTube changes; ad-free playback is not guaranteed.

The app checks for and downloads updates while Home or Settings is open. Signed metadata and the APK must match the installed app's package, higher version and certificate. Android may require installation approval. Updates use the configured LocalCiggyShop/QuietScreen GitHub repository.

The browsing screen provides remote-controlled horizontal genre rows, pagination and focus restoration after playback. Eight diverse genres remain available; an in-house model brings preferred genres first using actual watch time, estimated completion and modest early-exit signals. Diminishing returns prevent one long video from outweighing repeated enjoyable sessions. Search playback does not train the model. Settings can pause personalization or erase it. Continue watching appears second only when there are unfinished videos and resumes them at their saved position.

Search and catalog results stay in memory. Eight bounded genre scores, playback/update settings and up to 24 unfinished video records (ID, title, channel, position, duration and source genre) remain privately on-device, with Android backups disabled. The last 100 completed IDs keep recently finished videos out of Continue watching, including replays. Clear browsing session erases saved viewing records and completion IDs; Reset personalization separately erases genre scores. No database, server, account, telemetry or external recommender is used. Browsing sends fixed genre searches to YouTube; thumbnails/playback contact YouTube/CDNs and updates contact GitHub. Providers see normal request metadata and IP addresses. Saved records and genre scores are not uploaded; resuming requests the selected public video ID as ordinary playback does.

See [BUILDING.md](BUILDING.md), [PUBLIC_SECURITY.md](PUBLIC_SECURITY.md), [PUBLICATION.md](PUBLICATION.md), [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
