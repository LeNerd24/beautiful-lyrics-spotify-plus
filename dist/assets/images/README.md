# Sing icons

Editable sources are `microphone.svg` and `sparkle.svg`. They derive from Phosphor Icons' filled `microphone-stage` and `sparkle` icons, under the included MIT license. The sparkle source is cropped to its primary star so three independent instances can animate around the microphone.

- https://github.com/phosphor-icons/core/blob/main/assets/fill/microphone-stage-fill.svg
- https://github.com/phosphor-icons/core/blob/main/assets/fill/sparkle-fill.svg

`scripts/build-native.mjs` converts these SVG paths into cached native Canvas path assets. Animation timing, scale and opacity are defined in TypeScript in `src/Components/sing-control.tsx`; Java draws the received values and preserves fill-aware tinting and slider input. The user's original `mic-sparkle.png` is kept as a design reference.

