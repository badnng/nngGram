# Telegram Desktop wallpaper artwork

Copyright (c) 2014–2026 The Telegram Desktop Authors.

The original artwork and theme archives in this folder are taken from the official
`telegramdesktop/tdesktop` repository at commit
`83f9f56347cc3421c1e3ef537c7bb6dd06ce7cd0` (retrieved 2026-10-23).
They retain the upstream GPL-3.0-or-later license described in `LEGAL`; the full
license is included as `LICENSE`. This attribution does not grant a separate
permissive license to the artwork or claim any Telegram endorsement.

Original sources:

- [background.tgv](https://github.com/telegramdesktop/tdesktop/blob/83f9f56347cc3421c1e3ef537c7bb6dd06ce7cd0/Telegram/Resources/art/background.tgv)
- [day-custom-base.tdesktop-theme](https://github.com/telegramdesktop/tdesktop/blob/83f9f56347cc3421c1e3ef537c7bb6dd06ce7cd0/Telegram/Resources/day-custom-base.tdesktop-theme)
- [night-custom-base.tdesktop-theme](https://github.com/telegramdesktop/tdesktop/blob/83f9f56347cc3421c1e3ef537c7bb6dd06ce7cd0/Telegram/Resources/night-custom-base.tdesktop-theme)
- [LEGAL](https://github.com/telegramdesktop/tdesktop/blob/83f9f56347cc3421c1e3ef537c7bb6dd06ce7cd0/LEGAL)
- [LICENSE](https://github.com/telegramdesktop/tdesktop/blob/83f9f56347cc3421c1e3ef537c7bb6dd06ce7cd0/LICENSE)

The `.tgv` is a gzip-compressed SVG with a `1440 × 2960` view box. The two theme
files are ZIP archives. Their `background.jpg` entries are the official 512 × 512
light gradient and dark background respectively. The official night background
is a plain dark surface; it does not contain a separate night doodle pattern.

Packaged derivatives in `shared/src/commonMain/composeResources/drawable/`:

- `telegram_wallpaper_day.jpg`: byte-for-byte `background.jpg` from the day archive.
- `telegram_wallpaper_night.jpg`: byte-for-byte `background.jpg` from the night archive.
- `telegram_wallpaper_pattern.png`: the decompressed SVG rasterized unchanged to
  transparent 1440 × 2960 RGBA using Skia SVGDOM 0.150.1, with lossless PNG
  compression. No drawing paths, colors or motif positions were edited.

The SVG and theme archives remain here as the preferred editable source. For
rasterization, decompress `background.tgv` with gzip, render its complete view box
onto a transparent 1440 × 2960 canvas, and save losslessly as PNG. No network
requests are made by the application for these resources.

`ConversationWallpaper` draws the official day gradient to the viewport and the
official pattern at a fixed 720 × 1480 dp size, using Softlight at 50% opacity.
The 50% setting and SoftLight composition follow `WallPaper::ConstructDefault`
and `GenerateBackgroundImage` in the pinned source. The gradient is a static
official theme bitmap; this implementation does not animate Telegram's gradients.
Night mode uses the official night background without adding an invented pattern.

SHA-256:

| File | Hash |
| --- | --- |
| background.tgv | `3c5ef666d033ce9effe703b80348b9d7363323f0076b2856ec041923c78b0ab8` |
| day-custom-base.tdesktop-theme | `3e32484edd81fbebfa1725847737709d7b4b288561aa466566d8069bc288829b` |
| night-custom-base.tdesktop-theme | `ca675f580b40f8ecfb1d581f29ebd777c03eab4c27821251a369ed16f6f5cb5d` |
| telegram_wallpaper_day.jpg | `d17365d57822f7fe290c76ebd27377c4ecc8a27c7d049cb2990b0f3229fbf3d1` |
| telegram_wallpaper_night.jpg | `0843030b71b13017161c88775539692a6b4077d5ebdbd15d19dc1d20e98cbf7d` |
| telegram_wallpaper_pattern.png | `e23891eff91e0bc01adf6cdfc58637d23504c5a35bb183f2b00886c51c80b860` |
