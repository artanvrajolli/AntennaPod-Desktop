# AntennaPod Desktop — icon concepts

Twelve directions for the app icon. Masters are rendered at 256 px, plus a 32 px
export of each (tray size) so you can judge how they hold up small.
Open `contact-sheet.png` for all of them in one view, or the files in `concepts/`.

Regenerate everything after tweaking `generate-icons.ps1`:

```powershell
powershell -ExecutionPolicy Bypass -File icons/generate-icons.ps1
```

## The options

| # | Name | Feel | Reads at 32 px |
|---|------|------|----------------|
| 01 | `antenna-classic` | Warm orange gradient, white antenna mast broadcasting. Closest to the AntennaPod look. | good |
| 02 | `pod-capsule` | Indigo gradient, white capsule mic with a play triangle. Soft, app-store friendly. | good |
| 03 | `waveform-tile` | Charcoal tile with a blue waveform — uses the app's own accent (`#0096C9`). | good |
| 04 | `monogram-a` | Near-black tile, bold white "A" with a signal arc. Typographic, no imagery. | ok |
| 05 | `vinyl-play` | Vinyl record with an orange label and play mark. Podcasts-meet-music. | good |
| 06 | `radio-tower` | Night sky, broadcast tower, red beacon, cyan waves. Storytelling. | good |
| 07 | `cassette` | Retro cassette in teal/purple. Playful, very "podcast". | ok |
| 08 | `synthwave` | **Risky.** Pink→purple sunset, striped sun, neon antenna. Loud and 80s. | good |
| 09 | `glass-orb` | **Risky.** Glossy glowing orb with a waveform. Skeuomorphic, soft. | weak |
| 10 | `neon-outline` | **Risky.** Black tile, glowing cyan antenna outline. Dark and premium. | weak |
| 11 | `gradient-mesh` | **Risky.** Abstract colour mesh + white play button. Modern/abstract. | good |
| 12 | `pixel-antenna` | **Risky.** 8-bit pixel antenna. Fun, but clashes with a serious app. | weak |

## Picking one

Say which number you want (or ask for a mix, e.g. "03 colours with the 01 mast").
Once chosen I will:

1. render it at 16/24/32/48/64/128/256 px,
2. wire it into the window icon and the tray icon,
3. build a multi-resolution `.ico` for the Windows installer and the packaged app,
4. keep the generator script so the icon can be tweaked later without a designer.
