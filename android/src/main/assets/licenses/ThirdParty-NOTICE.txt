# Project license

Original 听屿 (Tingyu) code is licensed under the GNU General Public
License version 3 only (`GPL-3.0-only`). See [LICENSE](../LICENSE) and [NOTICE](../NOTICE).
Third-party code, libraries and assets retain their original licenses and copyright
notices; this project license does not relicense those components.

The About page provides offline, selectable license texts. Public builds
bundle the project license as `assets/licenses/GPL-3.0.txt`, the project copyright
notice as `assets/licenses/Project-NOTICE.txt`, and this source/attribution document
as `assets/licenses/ThirdParty-NOTICE.txt`, alongside the existing third-party licenses.
The GPLv3 text is the unmodified GNU publication at
https://www.gnu.org/licenses/gpl-3.0.txt.

# ChunkSeal acknowledgement and permission

The About page acknowledges [ChunkSeal](https://github.com/kity-yolo/chunkseal-protocol),
licensed under `AGPL-3.0-only` with `Copyright (C) 2026 ChunkSeal contributors`.
Its [integration permission](../licenses/ChunkSeal-GPL-Permission.md) permits covered
original material to be integrated into [Tingyu](https://github.com/qiuyuxc/tingyu)
and distributed as part of that program under `GPL-3.0-only`.

The About page includes `assets/licenses/ChunkSeal-NOTICE.txt`,
`assets/licenses/ChunkSeal-GPL-Permission.txt` and the unmodified upstream
license in `assets/licenses/AGPL-3.0.txt`. The notice and permission match
the copies in the repository's `licenses/` directory. The permission identifies both
source repositories. The public edition includes the ChunkSeal V1/V2 reader,
music metadata integration and fixed protocol test vectors under that permission.
The reader also preserves Tingyu's legacy NCMX compatibility.

# AndroidX, Kotlin and Java compatibility

AndroidX, Jetpack Compose, the Kotlin standard library, JetBrains annotations
and kotlinx.coroutines are unmodified runtime dependencies under Apache License 2.0,
as declared by their published Maven license metadata. Their license and source
attributions are bundled as `assets/licenses/Apache-2.0.txt` and
`assets/licenses/AndroidX-Kotlin-NOTICE.txt`.

- AndroidX / Compose: https://github.com/androidx/androidx
- Kotlin: https://github.com/JetBrains/kotlin
- JetBrains annotations: https://github.com/JetBrains/java-annotations
- Coroutines: https://github.com/Kotlin/kotlinx.coroutines

`com.android.tools:desugar_jdk_libs:2.1.5` provides Java standard-library
compatibility for older Android releases. Its published Maven license metadata
specifies GPL version 2 with the Classpath Exception. Both are retained in
`assets/licenses/Desugar-JDK.txt`, with the dependency and source attribution in
`assets/licenses/Desugar-JDK-NOTICE.txt`. This runtime library is unmodified.
Upstream: https://github.com/google/desugar_jdk_libs
License source: https://github.com/google/desugar_jdk_libs/blob/master/LICENSE

# MIUIX

The app uses [MIUIX](https://github.com/compose-miuix-ui/miuix),
under the Apache License 2.0. The published dependencies are pinned to version 0.9.4:

- `top.yukonga.miuix.kmp:miuix-ui:0.9.4`
- `top.yukonga.miuix.kmp:miuix-preference:0.9.4`
- `top.yukonga.miuix.kmp:miuix-blur:0.9.4`

`MiuixComponents.kt` and `MiuixViews.kt` adapt the library theme, buttons,
switches and preference components to the app. `TopBar.kt` uses the library's
small app bar, and `PlayerSheet.kt` uses its slider and transport buttons.
The upstream 0.9.4 source and documentation were used to check the component APIs.

The upstream license is included in public builds as `assets/licenses/Miuix.txt`.

Upstream: https://github.com/compose-miuix-ui/miuix

License: https://www.apache.org/licenses/LICENSE-2.0

# Liquid glass navigation

[KernelSU](https://github.com/tiann/KernelSU) at commit
`10efe6648aa52e0497bbd686f940832ad05848cf` was inspected for its floating navigation
layout and effect parameters. Its `manager/app/src/main/java/me/weishu/kernelsu/ui/component/FloatingBottomBar.kt`
identifies the MIUIX `IosLiquidGlassNavigationBar` example as its Apache-2.0 source.

This app adapts that Apache-2.0 upstream example directly from MIUIX 0.9.4,
rather than copying KernelSU's application-specific implementation. Seven files from
`example/shared/src/commonMain/kotlin/component/{animation,liquid}` are included under
`com.qiuyuxc.tingyu.navigation`. They retain the MIUIX copyright, Apache-2.0 identifiers,
and the original attribution to [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass).
Changes cover package names, app navigation items, theme lookup, font-scaled sizing,
safe-area handling and preserving the app's action when its active tab is tapped.

As of 0.4.0, the full upstream navigation effect is restored: the base and tinted-tab
backdrops, 24dp refraction, gravity-rotated highlights, press spot, moving lens with
chromatic dispersion, inner shadow, rubber-band movement and velocity deformation.
App-specific sizing, accessibility and safe-area handling remain. Hidden sampling
passes do not duplicate focus targets or accessibility actions. In 0.4.1, dragging
updates the indicator and panel offset directly; release uses a spring with the
gesture velocity. There is no new indicator animation job for each pointer move.
The blur branch is guarded by API 33. Android 7–12 use `FloatingNavigation.kt`,
which does not reference the blur implementation.

The Apache-2.0 license with `Copyright 2025 Kyant` is included as
`assets/licenses/AndroidLiquidGlass.txt`; adaptation notices are in
`assets/licenses/LiquidNavigation-NOTICE.txt`. MIUIX's license remains in `Miuix.txt`.

# Lyrics

Version 0.8.0 uses [wangchenyan/LrcView](https://github.com/wangchenyan/LrcView) at commit
`3df3232531d9bf9d9340b88fb1805b7096cd03a7`, under Apache License 2.0.
LrcEntry, LrcUtils and the view resources are unchanged in `vendor/lrcview`.
In 0.9.0, LrcView adds opt-in seek-on-release: completed drags seek to the centered
line, cancelled drags restore playback tracking, and inertia and the separate play
marker are disabled in this mode. The default upstream mode remains available.
The local module uses the app's toolchain and omits optional manifest permissions.
PlayerSheet uses its scrolling, inline timeline, release and tap callbacks.
The app supplies normalized original and translated lyric streams.

The license and attribution are bundled as `assets/licenses/LrcView.txt` and
`assets/licenses/LrcView-NOTICE.txt`. Details are in `vendor/lrcview/README.md`.

# Platform audio

Version 0.10.0 uses `androidx.media3:media3-exoplayer:1.11.1` and its Media3
dependencies under Apache License 2.0. Android 8.1 and newer use its progressive
extractors, software-preferred audio decoders and float PCM output for platform
streams. The sources were checked against the published 1.11.1 source archives.
The libraries are unmodified. Version 0.12.8 also uses the same float-capable
Media3 chain for local files on Android 8.1 and newer. Android 7–8.0 retains
the framework MediaPlayer path.

The upstream license is bundled as `assets/licenses/Media3.txt`.
Upstream: https://github.com/androidx/media/tree/1.11.1

# QR login

QR bitmap generation uses `com.google.zxing:core:3.5.3` (ZXing), Apache License 2.0.
The upstream license is bundled as `assets/licenses/ZXing.txt`.
Upstream: https://github.com/zxing/zxing/tree/zxing-3.5.3

The native QR authorization protocol was checked against these protocol references:

- https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/tree/main/module
- https://github.com/MakcRe/KuGouMusicApi/tree/master/module

`PlatformLoginClient.kt`, `PlatformProtocol.kt`, `PlatformLibraryClient.kt` and `PlatformDiscoveryClient.kt` implement
QR authorization, account profiles, playlist pagination, favorite updates, authorized
stream resolution and lyrics with Java crypto and HTTPS. Kugou uses the concept
edition (appid 3116), its own signature salt and its tracker parameters.
Version 0.5.0 also reads first-party search, ranking, comments, subscription and per-quality
metadata, and adds account verification plus per-song authorization for the concept tracker.
The implementation does not claim membership or playback rights when the platform omits them.
Version 0.7.0 adds daily recommendations, Netease radio and the concept recommendation feed,
using the same protocol references; playback pagination commits only after a queue accepts the result.
Version 0.8.0 preserves Netease's full quality catalog and requested stream level, uses its native
song-like endpoint, and recognizes Kugou default favorites by the platform marker and collection
metadata. Displayed comment counts come from the platform response.
The references were inspected at commits `4a7d557326d16d2f1e5172ee69c9471eb3a50173`
(Netease) and `7ac64e1cdd333ad9bfff644ded82a0ffeef002d1` (Kugou).
Requests go directly to the platform;
no upstream Node server, dependency or source bundle is included. The app does not
collect platform passwords. Platform credentials are kept in app-private storage.

# OriginOS protocol reference

[md3Music](https://github.com/zzyoxml/md3Music) at commit
`94f3fbfc3586f01cf5b8677cacefab4aed9fe70b` was inspected to understand its
vivo music widget / atomic island interoperability. The upstream project is AGPL-3.0.
No upstream implementation or binary was copied into this app; `VivoLyrics.kt`
and the framework `MediaBrowserService` integration implement the observed protocol
using this app's existing player and lyric data.

Relevant reference files:

- `third_party/just_audio/android/src/main/java/androidx/media3/session/legacy/MediaSessionCompat.java`
- `third_party/just_audio/android/src/main/java/androidx/media3/session/LegacyConversions.java`
- `android/app/src/main/kotlin/com/md3music/md3music/AudioPlaybackService.kt`
- `android/app/src/main/kotlin/com/md3music/md3music/MD3MusicMediaSessionService.kt`

Protocol fields (including the original `meida` / `meidia` spelling) are sent via
`MediaSession.setExtras`, rather than a broadcast or notification title replacement.
The extras carry `vivomusicmix.extra.lrc_change`, the matching media ID, and whole LRC.
The framework metadata always includes `vivomusicmix.media.metadata.support_event=31`.
Only the five supported capabilities are declared. The island follows the framework
playback state; a 25-second refresh handles late connections. No third-party partner
authentication token, shadow session, forked media library, or remote artwork server
is included.
