# 14 - Generators & Noise

Generators are pack-level noise definitions that biomes link for terrain height. Each generator composites one or more noise layers, optionally applies cliffs and cell fracture, and interpolates across biome boundaries. Styles, expressions, and image maps supply the raw noise signal.

Related: `11 - Dimensions.md`, `12 - Regions.md`, `13 - Biomes.md`, `15 - Caves & Carving.md`, `16 - Surfaces, Decorators & Deposits.md`, `05 - Concepts & Pack Layout.md`, `10 - Studio & VSCode Schemas.md`.

## Where files live

| Path | Registrant | Role |
|------|------------|------|
| `generators/<key>.json` | `IrisGenerator` | Height-map composite used by biomes |
| `expressions/<key>.json` | `IrisExpression` | Math expression used as a style source |
| `images/<key>.png` | `IrisImage` | PNG sampled by `IrisImageMap` |

Biome JSON does not embed generators. It references them by key:

```json
{
"generators": [
  { "generator": "plain", "min": 4, "max": 14 }
]
}
```

`IrisBiomeGeneratorLink` loads `generators/<generator>.json`, samples height in 0..1, then lerps to `min`..`max` relative to fluid height. Negative ranges produce ocean floors.

## Authoring workflow

1. Create `generators/<name>.json` with `seed`, `interpolator`, and at least one `composite` entry.
2. Reference that key from every biome that should share the shape (`generators[].generator`).
3. Tune `min`/`max` per biome for local relief; leave the generator file for global shape and frequency.
4. Hotload in studio; regenerate nearby chunks to verify blending across biome edges (`interpolator.horizontalScale`).
5. Optional: replace a style's built-in `NoiseStyle` with `expression` or `imageMap` for custom fields.

## Generator file (`IrisGenerator`)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `zoom` | double ≥ 0.001 | `1` | Frequency; higher sample coords are divided by zoom |
| `opacity` | double ≥ 0 | `1` | Multiplier on composite output |
| `multiplicitive` | boolean | `false` | When true, multiplies composite layers instead of averaging by opacity sum (field spelling is code-authoritative) |
| `seed` | long | `1` | Required base seed |
| `offsetX` / `offsetZ` | double | `0` | Shifts sample coordinates |
| `interpolator` | `IrisInterpolator` | bilinear starcast | Cross-biome height smoothing |
| `composite` | `IrisNoiseGenerator[]` | `[]` | Required layers; empty → height 0 |
| `cliffHeightMin` / `cliffHeightMax` | double 0..8192 | `0` | Both 0 disables cliffs |
| `cliffHeightGenerator` | `IrisNoiseGenerator` | default | Picks cliff step height between min/max |
| `cellFractureZoom` | double ≥ 0.001 | `1` | Cell crack scale |
| `cellFractureShuffle` | double ≥ 0 | `12` | Cell coordinate shuffle |
| `cellFractureHeight` | double | `0` | `0` disables cell cracks; non-zero multiplies height outside cell cores |
| `cellPercentSize` | double 0..1 | `0.75` | Relative cell core size vs veins |

### Interpolator (`IrisInterpolator`)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `function` | `InterpolationMethod` | `BILINEAR_STARCAST_6` | Smoothing kernel |
| `horizontalScale` | double 1..8192 | `7` | Sample radius; smaller = more detail, less smooth |

Common `InterpolationMethod` values: `NONE`, `BILINEAR`, `STARCAST_3/6/9/12`, `BILINEAR_STARCAST_3/6/9/12`, `HERMITE_STARCAST_3/6/9/12`. Overworld generators typically use `BILINEAR_STARCAST_9` with `horizontalScale` 12–52.

### Noise layer (`IrisNoiseGenerator`)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `enabled` | boolean | `true` | Disabled layers return `offsetY` only |
| `zoom` | double ≥ 0.0001 | `1` | Layer frequency |
| `opacity` | double 0..1 | `1` | Layer weight / amplitude |
| `negative` | boolean | `false` | Output becomes `-noise + opacity` |
| `offsetX` / `offsetY` / `offsetZ` | double | `0` | Coordinate / output offsets; avoid `offsetY` for terrain |
| `seed` | long | `0` | Required |
| `style` | `IrisGeneratorStyle` | `IRIS` | Noise source |
| `octaves` | int ≥ 1 | `1` | Multi-octave CNG |
| `exponent` | double | `1` | Power curve on output |
| `parametric` / `bezier` / `sinCentered` | boolean | `false` | Output remaps |
| `fracture` | `IrisNoiseGenerator[]` | `[]` | Child noise warps this layer's input coordinates |

Composite evaluation (add mode): sum each layer's noise, divide by total opacity, multiply generator `opacity`. Multiplicative mode starts at 1 and multiplies each layer.

### Cliff and cell post-process

- Cliffs quantize height: `(round((v*255)/cliffHeight) * cliffHeight) / 255` when `cliffHeightMax > 0`.
- Cell fracture uses a cell distance field; outside the cell core, height is scaled by `cellFractureHeight`.

## Generator style (`IrisGeneratorStyle`)

Used everywhere noise is configured: generator layers, decorators, deposits palettes, cave profiles, dimension biome styles.

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `style` | `NoiseStyle` | `FLAT` | Built-in algorithm when expression/image unset |
| `zoom` | double ≥ 0.00001 | `1` | Style scale (`1/zoom` applied to CNG) |
| `exponent` | double 0.01562..64 | `1` | Power on style output |
| `multiplier` | double ≥ 0.00001 | `1` | Fracture strength when this style is a `fracture` child |
| `fracture` | nested `IrisGeneratorStyle` | null | Distorts parent coordinates |
| `axialFracturing` | boolean | `false` | Different axis order per dimension (slower) |
| `cellularFrequency` | double | `0` | `>0` cellularizes style |
| `cellularZoom` | double | `1` | Cell scale after cellularize |
| `expression` | string key | null | Load `expressions/<key>.json` instead of `style` |
| `imageMap` | `IrisImageMap` | null | Sample PNG instead of `style` |
| `cacheSize` | int 0..8192 | `0` | Disk-backed CNG cache size when >0 |

Priority when building CNG: `expression` if set and loadable, else `imageMap` if set, else `NoiseStyle`.

### Common `NoiseStyle` values

Terrain / large forms: `IRIS`, `IRIS_DOUBLE`, `IRIS_THICK`, `IRIS_HALF`, `SIMPLEX`, `PERLIN`, `PERLIN_IRIS`, `FRACTAL_SMOKE`, `FRACTAL_WATER`, `FRACTAL_FBM_SIMPLEX`, `FRACTAL_BILLOW_PERLIN`, `NOWHERE`, `NOWHERE_CELLULAR`, `GLOB`, `CELLULAR_HEIGHT`.

Scatter / decoration: `STATIC` (white noise), `STATIC_BILINEAR`, `FLAT` (always 0.5).

Cells / veins: `CELLULAR`, `CELLULAR_IRIS_DOUBLE`, `VASCULAR`, `VASCULAR_THIN`, `SIMPLEX_VASCULAR`, `CLOVER` and starcast variants, hex family (`HEXAGON`, `HEX_JAMES`, …).

Full enum is large; Studio schemas list every constant.

## Expressions (`IrisExpression`)

| Field | Type | Notes |
|-------|------|-------|
| `expression` | string | Required. Inherited vars: `x`, `y`, `z` (do not redeclare) |
| `variables` | `IrisExpressionLoad[]` | Named variables bound before evaluate |
| `functions` | `IrisExpressionFunction[]` | Named dynamic functions (style or engine stream) |

### Variable (`IrisExpressionLoad`)

| Field | Notes |
|-------|-------|
| `name` | Variable name (not `x`/`y`/`z`) |
| `staticValue` | Used when no other source |
| `styleValue` | Nested `IrisGeneratorStyle` sampled at coords |
| `engineStreamValue` | Engine procedural stream enum |
| `engineValue` | Engine scalar enum |

### Function (`IrisExpressionFunction`)

| Field | Notes |
|-------|-------|
| `name` | Function name in expression text |
| `styleValue` | Style-backed noise function |
| `engineStreamValue` | Engine stream function |
| `args` | Argument count (≥2); engine streams force 2 |

Parser: Paralithic. Load failures log and return null CNG fallback paths.

## Image maps (`IrisImageMap` + `IrisImage`)

PNG files under `images/` load as `IrisImage`. Styles reference them:

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `image` | string key | `""` | Image registrant key |
| `coordinateScale` | double ≥ 1 | `32` | Blocks per pixel (before style zoom) |
| `interpolationMethod` | `InterpolationMethod` | `BILINEAR_STARCAST_6` | Use `NONE` for nearest |
| `channel` | `IrisImageChannel` | `COMPOSITE_ADD_HSB` | Pixel → 0..1 |
| `inverted` | boolean | `false` | `1 - value` |
| `tiled` | boolean | `false` | Modulo wrap |
| `centered` | boolean | `true` | Origin at image center |

`IrisImageChannel`: `RED`, `GREEN`, `BLUE`, `SATURATION`, `HUE`, `BRIGHTNESS`, `COMPOSITE_ADD_RGB`, `COMPOSITE_MUL_RGB`, `COMPOSITE_MAX_RGB`, `COMPOSITE_ADD_HSB`, `COMPOSITE_MUL_HSB`, `COMPOSITE_MAX_HSB`, `RAW`.

Out-of-bounds pixels (non-tiled) return 0. Missing images log and yield 0.

## Dimension-level noise (related)

Dimensions also use styles and shaped styles for placement, not height generators:

| Dimension field | Role |
|-----------------|------|
| `landBiomeStyle` / `seaBiomeStyle` / `shoreBiomeStyle` / `caveBiomeStyle` / `regionStyle` / `continentalStyle` | Biome/region placement noise |
| `overlayNoise` | `IrisShapedGeneratorStyle[]` height overlays (`generator` style + `min`/`max`) |
| `coordFractureDistance` / `coordFractureZoom` | Global coordinate warp |
| `rockZoom` / `rockPalette` / `fluidPalette` | Default fill materials (see `16 - Surfaces, Decorators & Deposits.md`) |

## Overworld examples

`generators/plain.json` — smooth lowland:

```json
{
  "interpolator": { "function": "BILINEAR_STARCAST_9", "horizontalScale": 12 },
  "seed": 7246661,
  "composite": [{
    "style": {
      "style": "IRIS_DOUBLE",
      "zoom": 0.87,
      "fracture": { "style": "NOWHERE", "zoom": 0.195, "multiplier": 18 }
    },
    "seed": 10056,
    "bezier": true
  }]
}
```

`generators/mountain.json` — large-scale smoke:

```json
{
  "interpolator": { "horizontalScale": 52, "function": "BILINEAR_STARCAST_9" },
  "seed": 53551,
  "composite": [{
    "style": { "style": "FRACTAL_SMOKE", "zoom": 1 },
    "seed": 3356
  }]
}
```

`generators/cracked-cliffs.json` — cliffs + inverted glob:

```json
{
  "interpolator": { "function": "BILINEAR_STARCAST_9", "horizontalScale": 12 },
  "seed": 7246661,
  "composite": [{
    "style": {
      "style": "GLOB",
      "zoom": 0.4,
      "exponent": 0.7,
      "fracture": { "style": "NOWHERE", "zoom": 0.1, "multiplier": 5 }
    },
    "negative": true,
    "seed": 10056
  }],
  "cliffHeightMax": 80,
  "cliffHeightMin": 35,
  "cliffHeightGenerator": {
    "seed": 2348,
    "style": { "style": "CELLULAR_HEIGHT" },
    "zoom": 0.4
  }
}
```

Shipping overworld generators do not use `expression` or `imageMap`. Pack ships `images/prototype-rivers.png` and `images/vascularcliffs.png` for optional author use. Snippet styles under `snippet/style/` (e.g. `bedrock.json` with `"style": "STATIC"`) are reusable style fragments.

## Practical notes

- Prefer sharing one generator across many biomes; vary `min`/`max` per biome for height bands.
- Match interpolator `horizontalScale` between neighboring biomes that should blend smoothly.
- Nested `fracture` multiplies cost; keep fracture chains short on hot terrain paths.
- `STATIC` is for scatter, not terrain relief.
- Field names `multiplicitive` and deposit `varience` are intentional code spellings; JSON must match.
