# Telegram Desktop default notification recording

Copyright (c) 2014–2026 The Telegram Desktop Authors.

The unmodified `msg_incoming.mp3`, resource index `sounds.qrc`, `LEGAL` and full
`LICENSE` were retrieved from the official `telegramdesktop/tdesktop` repository
at commit `a7f453612f4896ac04d0007a36ab0d377e5bcbcf` on 2026-10-26. They retain the
upstream GPL-3.0-or-later terms described in `LEGAL`. No separate permissive
license or Telegram endorsement is claimed.

Original files:

- [msg_incoming.mp3](https://github.com/telegramdesktop/tdesktop/blob/a7f453612f4896ac04d0007a36ab0d377e5bcbcf/Telegram/Resources/sounds/msg_incoming.mp3)
- [sounds.qrc](https://github.com/telegramdesktop/tdesktop/blob/a7f453612f4896ac04d0007a36ab0d377e5bcbcf/Telegram/Resources/qrc/telegram/sounds.qrc)
- [LEGAL](https://github.com/telegramdesktop/tdesktop/blob/a7f453612f4896ac04d0007a36ab0d377e5bcbcf/LEGAL)
- [LICENSE](https://github.com/telegramdesktop/tdesktop/blob/a7f453612f4896ac04d0007a36ab0d377e5bcbcf/LICENSE)

The original MP3 is 34,480 bytes, 44.1 kHz stereo, about 1.366712 seconds. Its Git
blob is `97a035320c074b41b125e99457ec68a8401432db`, verified against the pinned
repository tree. This is the embedded default message sound, separate from
server-selected custom ringtone documents and user sound overrides.

Official selection and playback are established by:

- `Resources/qrc/telegram/sounds.qrc:3`: registers `/sounds/msg_incoming.mp3`.
- `SourceFiles/core/core_settings.cpp:1380`, `Settings::getSoundPath`: defaults
  to `:/sounds/<key>.mp3` when there is no user override.
- `SourceFiles/window/notifications_manager.cpp:945/986/1026`,
  `System::lookupSoundBytes`, `ensureSoundCreated`, `playSound`: resolve the
  default recording and play it once; custom document IDs use their own bytes.
- `notifications_manager.cpp:725–741`, `System::showNext`: checks sound settings
  and uses the current ringtone volume before playing.

Packaged files live in `shared/src/jvmMain/resources/telegram-sounds/`. The MP3,
LEGAL and LICENSE there are byte-for-byte copies. `msg_incoming.s16le` is a
build-time decode of the complete original recording into the existing WinMM
format: signed 16-bit little-endian stereo PCM, 48 kHz, 65,603 frames, 262,412
bytes (about 1.366729 seconds). There is no synthesized oscillator, trimming,
fading, normalization or replacement recording. The only conversion is MP3
decode plus resampling for the existing output format.

Reproduce with the repository's bundled FFmpeg
`N-120010-ge6298e0759-20250623`:

```powershell
.\native\windows-x64\media\ffmpeg.exe -hide_banner -loglevel error -nostdin -i third_party/telegram-sounds/msg_incoming.mp3 -map 0:a:0 -vn -sn -dn -acodec pcm_s16le -ar 48000 -ac 2 -f s16le shared/src/jvmMain/resources/telegram-sounds/msg_incoming.s16le
```

The application does not start FFmpeg or fetch network data for this sound. It
loads and validates the bounded prepared recording on its existing notification
worker, copies/scales the PCM per requested device volume, then uses its existing
WinMM output and cancellation owner. A missing or changed asset reports a sound
failure; it does not silently substitute a generated cue.

SHA-256:

| File | Hash |
| --- | --- |
| msg_incoming.mp3 | `b042883d8264409fe024a8d533d26d1aef5919c1eb6b96093eb07f8c001edd85` |
| msg_incoming.s16le | `b85015e0270d12da67eedac5a8812056bee0b60e34392fa7b9f6985e3c43dfda` |
| LEGAL | `0d37f2717dc27ae914194c404eff323fb7bee92d77d5d50a593be12bef596638` |
| LICENSE | `a728b65d02eeaf6b50c075a33b76107c652c3e5a12ff67f53682c83d47a23e1e` |

Static hash checks and silent decoding establish source identity and PCM format.
They do not establish actual audio-device output or a live-account notification.
