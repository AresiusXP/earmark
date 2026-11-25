# Audiobooks app: design

Binding for all UI work (decided 2026-11-25). Code: `app/src/main/kotlin/xyz/aresius/audiobooks/core/design`.
Mood references (not pixel specs): `docs/design/` (`design-home*.jpg` = Discover, light and dark variants A/B/C, `design-player.jpg` = the player of Phase 3).

## Identity: "reading room"
Warm paper, ink, one hot accent. Editorial, tactile, calm. Not the generic look: no default Material blue/green palette, no
Roboto/Inter, wallpaper dynamic color is OFF (opt-in switch in Settings > Appearance, Android 12+, default off).
**No blue, no green, no purple gradient anywhere.** Status "ok" is ink + a check icon + the word (never green); "cached" is mustard;
errors are a deeper crimson that is always accompanied by an icon and words.

## Color (`Color.kt`, own `ColorScheme`, follows the system light/dark setting)
| | light (paper) | dark (ink) |
|---|---|---|
| background / surface / surfaceVariant | `#F6F0E6` / `#FBF7EF` / `#EADFCF` | `#14110F` / `#1C1815` / `#2A2420` |
| text / muted text | `#1E1A17` / `#6B6258` | `#F1EADF` / `#B8AD9E` |
| primary (persimmon), onPrimary | `#D9482B`, paper | `#FF7A55`, deep ink |
| accent as small text (`extras.accentText`) | `#B1361C` | `#FF7A55` |
| secondary (mustard), tertiary (plum) | `#D9A441`, `#5B2A4B` | `#E8B558`, `#D9A3C8` |
| error | `#9E1B32` | `#FF8D9B` |

Persimmon on paper is 3.7:1: filled persimmon is only paired with large labels (buttons are 20 sp bold) or icons, and small accent
text uses `accentText`. `ContrastTest` checks WCAG AA (4.5:1 normal text, 3:1 large text and graphics) for every pair of both
palettes, that no cold hue sneaks in, and that cover-derived colors keep AA for any cover.

## Cover-derived color (`CoverColors.kt`)
The book detail (and later the player) takes background and tonal accents from the cover with `androidx.palette` (dominant swatch,
vibrant for the accent), desaturated and moved toward the warm ink (dark) or the paper (light) until text keeps AA, cross-faded in
700 ms. Without a cover color the base scheme shows. A soft glow of the cover's swatch (alpha <= 0.34, never neon) sits behind big
covers in the dark theme; the light theme gets a soft warm shadow instead (`coverShadow`, the one shadow style of the app).

## Typography (`Type.kt`, bundled variable fonts, OFL; licenses in `src/main/assets/licenses/`)
* **Fraunces** (opsz, wght, SOFT 50, WONK off, opsz tied to the size class) for display, headlines and titles: 34-48 sp headlines with
  tight tracking, serif book titles.
* **Hanken Grotesk** for UI and body; labels 11-14 sp medium with a little letter spacing. Time and progress use tabular figures
  (`TabularFigures`). Latin + Latin Extended (Dutch).

## Shape and motion (`Shape.kt`, `Motion.kt`)
Large radii: covers 24 dp, cards 28 dp, pill buttons, `SquircleShape` for the player's skip buttons. Generous whitespace, one strong hero per
screen. Spring motion (`Motion.spatial/fastSpatial/effects`), shared-element transition of the cover from a list to the Book detail
(`SharedElements.kt`), no glassmorphism. material3 1.4 has no public expressive theme/motion scheme, so the expressive spirit is built
from our own springs and components: the signature **`WavyProgressBar`** (the played part is a sine wave that runs while playing and
relaxes flat when paused; optional ticks and seek by tap/drag).

## Layout
* **Discover** is editorial: a serif greeting ("Good evening, <name>", localized), the **Continue listening hero** when there is
  progress, then shelves of large squarish covers with serif titles under them.
* **Continue listening is variant B, in light and dark: no card container.** Cover (110 dp, 24 dp radius), serif title, author line, resting
  wavy progress, time left (tabular) and the round persimmon play button sit directly on the screen background with generous spacing; a
  thin hairline divides it from the shelves below. Dark: a soft glow of the cover's palette swatch behind the cover; light: a soft warm
  shadow. The same "no heavy container" idea applies to the player: cover-derived background, no boxed panels (see `design-player.jpg`:
  big cover, serif title, small-caps author, chapter line, wavy seek bar with ticks, round play button between squircle skip buttons,
  outlined pills for speed, sleep timer and bookmark).
* Navigation bar with text labels and a pill indicator in the accent tint.
* Empty and error states are typographic: a short, witty serif line, one quiet sentence, no illustration.
* App icon: persimmon rounded square with an open-book/waveform mark (adaptive, with a monochrome layer); the splash shows the same mark
  on paper/ink.

## Buttons, chips and time (polish)
* **Buttons**: a card has at most one loud action, the filled persimmon `PrimaryButton` (20 sp bold, 56 dp). Everything else in a card is a `CompactButton`:
  hairline outline, ink label, 14 sp medium, one line, 48 dp. Secondary full-size buttons (`SecondaryButton`) shrink their label to one line
  instead of wrapping. Rare actions ("Show files") live in an overflow menu. The state of a card slot never changes its size: the chosen release shows an
  outlined pill of the same height as the button it replaces.
* **Chip rows** scroll; the chip at the edge is cut off on purpose, with a short fade into the background at the end so it reads as "more", and a picked
  chip scrolls into view.
* **Time**: elapsed and remaining are always labelled ("Book 1:23:45", "-16:09:28 left", "Chapter 4:12 of 18:30 · -14:18 left"). An approximate length
  gets a "~" plus a small info button that explains it; an exact one is just clean.
* **Waiting**: "Getting your book ready…" with the persimmon play button turning into a spinner and, in the mini player, a thin indeterminate line in
  place of the wavy bar. No blocking dialogs.
