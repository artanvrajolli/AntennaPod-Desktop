# AntennaPod Desktop app icon

The shipped icon is **mesh + waveform** (`mesh-wave`) — the soft orange/pink/blue/violet
mesh behind a white badge holding a waveform. Each bar takes the mesh colours it sits
between: orange to pink along the tops, blue to violet along the bottoms, through
magenta. It replaced **gradient mesh** (the same mesh with a play badge).

Which design ships is `$ShippedDesign` in `icon-designs.ps1`: `generate-icons.ps1`
exports it by default and both contact sheets show it as 00.

![the icon](app-icon-preview.png)

## Files

| Path | Used for |
|------|----------|
| `app.ico` | `jpackage` — the `.exe` icon, Start-menu shortcut and installer |
| `app-icon-preview.png` | the preview above (a copy of the 256 px frame) |
| `../app/src/main/resources/icons/app-icon-{16,24,32,48,64,128,256}.png` | window/taskbar icon (JavaFX picks the right size) and the tray icon |
| `../app/src/main/resources/icons/app-icon.png` | 256 px copy for anything that wants a single file |
| `icon-designs.ps1` | the first designs, as drawing code, plus the shared drawing helpers |
| `icon-designs-redesigns.ps1` | the redesign round, including the shipped `mesh-wave` |
| `candidates/`, `redesigns/` | renders of each round, for choosing a new icon |

## Regenerating

```powershell
powershell -ExecutionPolicy Bypass -File icons/generate-icons.ps1
```

Each design draws on a 512×512 virtual canvas that is scaled down, so editing a
design in `icon-designs.ps1` (colours, shape, badge) updates every size and the
`.ico` in one run. PNG frame sizes are set by `$sizes` in `generate-icons.ps1`.

## Candidates

The first round of alternatives is in `icon-designs.ps1`. Their renders
are in `candidates/`, and `candidates/contact-sheet.png` shows them all side by side,
large and at the real 48/32/24/16 px sizes on a dark and a light taskbar strip:

![icon candidates](candidates/contact-sheet.png)

| # | Key | Idea |
|---|-----|------|
| 00 | `mesh-wave` | the shipped icon |
| 01 | `gradient-mesh` | the icon it replaced: the mesh with a play badge |
| 02 | `signal-tower` | antenna mast with signal arcs on blue: closest to AntennaPod's own mark |
| 03 | `broadcast` | the broadcast symbol on a sunset gradient |
| 04 | `mesh-broadcast` | the mesh and badge, broadcast symbol in place of play |
| 05 | `headphones` | headphones around a play triangle on teal |
| 06 | `microphone` | studio microphone on deep violet with a warm glow |
| 07 | `pea-pod` | a pod whose middle pea is a play button |
| 08 | `waveform` | rounded audio bars in a cyan-to-violet gradient on dark |
| 09 | `monogram-a` | an "A" drawn as an antenna, with signal arcs, on amber |
| 10 | `speech-bubble` | talk bubble holding a waveform |
| 11 | `progress-ring` | play triangle inside a ¾ progress ring, echoing the seek bar |

Re-render them after editing a design:

```powershell
powershell -ExecutionPolicy Bypass -File icons/generate-candidates.ps1
```

## Redesigns

A second round of 26 designs, the shipped one among them, lives in `icon-designs-redesigns.ps1` (loaded by
`icon-designs.ps1`, so the keys work everywhere the first ten do). Their renders
and contact sheet are in `redesigns/`:

![icon redesigns](redesigns/contact-sheet.png)

| # | Key | Idea |
|---|-----|------|
| 01 | `pod-antenna` | a pea pod with an antenna and signal arcs; the middle pea is a play button |
| 02 | `feed-play` | the feed (RSS) arcs, with a play triangle for the dot |
| 03 | `equalizer` | five rounded bars, pink to violet, on deep purple |
| 04 | `vinyl` | a record with grooves, a sheen and a play label |
| 05 | `cassette` | a cassette tape on a warm gradient |
| 06 | `mic-waves` | a studio microphone with sound waves either side |
| 07 | `queue-stack` | stacked episode cards, the front one with play and a progress line |
| 08 | `satellite-dish` | a dish sending signal arcs |
| 09 | `rabbit-ears` | a retro TV with antenna ears and play on the screen |
| 10 | `a-play` | an "A" whose crossbar is a play triangle |
| 11 | `p-ring` | a "P" whose bowl is a ring around play |
| 12 | `orb` | a glossy violet sphere with play |
| 13 | `duotone` | diagonal pink/indigo split, white play badge |
| 14 | `pixel-play` | play triangle in pixel blocks, cyan to pink |
| 15 | `ripple` | ripples spreading from a play badge on mint |
| 16 | `sunrise` | a sun rising over sound waves |
| 17 | `glass-play` | the shipped mesh behind a frosted glass card |
| 18 | `neon` | neon ring and play on black |
| 19 | `paper-waves` | layered paper waves under a play badge |
| 20 | `seek-capsule` | a seek bar with the play knob, echoing the player |
| 21 | `hex-badge` | play on a green hexagon |
| 22 | `orbit` | a planet with a ring, play on the planet |
| 23 | `mesh-antenna` | the mesh and badge, antenna mast in place of the waveform |
| 24 | `mono-line` | a line-drawn pod with antenna on off-white, orange play pea |
| 25 | `bookmark-play` | a bookmark ribbon with play: the saved episode |

`mesh-wave`, now shipped, is 00 on this sheet too.

```powershell
powershell -ExecutionPolicy Bypass -File icons/generate-candidates.ps1 -Set redesigns
```

To adopt one, export it by key; this rewrites `app.ico`, the preview and the
runtime PNGs:

```powershell
powershell -ExecutionPolicy Bypass -File icons/generate-icons.ps1 -Design waveform
```

Then update the description at the top of this file. The installer's wizard
backdrop (`packaging/windows/generate-installer-bitmaps.ps1`) draws `app.ico`
over the mesh colours, with the waveform badge at the centre of its rings; rerun
it after a new icon, and restyle its glows and badge if the new icon differs.
