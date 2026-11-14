# LrcView

The Android lyric component is vendored from
[wangchenyan/LrcView](https://github.com/wangchenyan/LrcView) at commit
`3df3232531d9bf9d9340b88fb1805b7096cd03a7` under Apache License 2.0.

LrcEntry, LrcUtils and the view resources remain unchanged. Since 0.9.0, LrcView
adds an opt-in seek-on-release mode: a completed drag seeks once to the centered
line, a cancelled drag restores the playing line, and inertia and the separate play
marker are disabled in that mode. The default mode retains upstream behavior.
The local module uses the app's Android toolchain; its manifest omits permissions
for optional file and network loading because the app supplies cached lyric strings.

PlayerSheet uses the component's scrolling, inline timeline, release and tap
callbacks. The app normalizes its original and translated lyric streams before
loading them. License and attribution are bundled in both APKs.
