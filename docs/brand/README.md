# Bridgeline brand assets

![Bridgeline logo](logo.svg)

## Mark

Two phones (rounded bodies with screens) tilted toward each other and joined by an arc - the
"bridge line" from the phone sharing its cellular data to the device it's sharing it with. Flat, two colours, reads at 48dp and works as an Android
adaptive icon (foreground + background + API 33 monochrome layer) and a status-bar icon.

## Files

| File | Use |
|---|---|
| `icon.svg` | Square app icon (matches `app/src/main/res/drawable/ic_launcher_foreground.xml` + `ic_launcher_background.xml`) |
| `logo.svg` | Icon + "Bridgeline" wordmark, for docs and the top of README.md |

The Android app's own icon/wordmark sources are the vector drawables under
`app/src/main/res/drawable/` (`ic_launcher_foreground.xml`, `ic_launcher_background.xml`,
`ic_launcher_monochrome.xml`, `ic_stat_bridgeline.xml` for the notification icon) - keep those
and these SVGs in sync if the mark changes.

## Colours

| Name | Hex | Resource | Use |
|---|---|---|---|
| Brand primary (teal) | `#0F6E7A` | `@color/brand_primary` (`app/src/main/res/values/colors.xml`) | Icon/logo background, wordmark text |
| On primary (white) | `#FFFFFF` | `@color/brand_on_primary` | Icon/logo mark on top of the brand colour |

## Usage

- Keep clear space around the mark roughly equal to the icon's own corner radius.
- On a dark or busy background, use the monochrome/white-only version (as the status bar and
  Android's themed-icon mode already do) rather than placing the two-colour mark directly over
  something that fights with the teal.
- Don't recolour the mark itself (the phones/link stay white); recolour only the background
  behind it if you need a variant (e.g. transparent for the wordmark lockup in `logo.svg`).
