# Telegram Desktop notification recording

Copyright (c) 2014–2026 The Telegram Desktop Authors.

Original source: Telegram Desktop commit
`a7f453612f4896ac04d0007a36ab0d377e5bcbcf`,
`Telegram/Resources/sounds/msg_incoming.mp3`.

https://github.com/telegramdesktop/tdesktop/blob/a7f453612f4896ac04d0007a36ab0d377e5bcbcf/Telegram/Resources/sounds/msg_incoming.mp3

The original MP3 is included unchanged. Its Git blob is
`97a035320c074b41b125e99457ec68a8401432db` and SHA-256 is
`b042883d8264409fe024a8d533d26d1aef5919c1eb6b96093eb07f8c001edd85`.

`msg_incoming.s16le` is the complete original recording decoded to the app's
existing WinMM output format: 48 kHz stereo, signed 16-bit little-endian PCM,
65,603 frames / 262,412 bytes. SHA-256:
`b85015e0270d12da67eedac5a8812056bee0b60e34392fa7b9f6985e3c43dfda`.

Prepared with FFmpeg `N-120010-ge6298e0759-20250623` using MP3 decode and
`-map 0:a:0 -vn -sn -dn -acodec pcm_s16le -ar 48000 -ac 2 -f s16le`.
No synthesis, trimming, fading or normalization was applied. Only runtime device
volume scales these PCM samples. The application does not fetch this recording
or run an audio decoder process at playback time.

The recording and derivative retain the GPL-3.0-or-later terms of the original
project. See the included unmodified `LEGAL` and full `LICENSE`. Original source
and reproducible preparation details are retained in `third_party/telegram-sounds`.
