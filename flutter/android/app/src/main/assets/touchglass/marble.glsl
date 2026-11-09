// Liquid marble: the Trackpad design's SVG filter (tg-bg), stage by stage. Two
// fractal noises, one displacing the other, averaged to grey, pushed to high
// contrast, with film grain overlaid. Lengths are in dp, like the filter's
// user units on a 412-wide artboard. Shared by the still background and the
// moving one; prepended after common.glsl.

uniform float uDensity;        // px per dp
uniform uint uSeed;
uniform float uSaturation;
uniform float uHue;            // radians
uniform float uHueRange;
uniform float uWarpScale;      // dp
uniform float uWarpFrequency;  // cycles per dp
uniform int uWarpOctaves;
uniform float uBaseFrequency;  // cycles per dp
uniform int uBaseOctaves;
uniform float uSlope;
uniform float uOffset;
uniform float uGrainOpacity;
uniform float uGrainFrequency; // cycles per dp

// Grey in sRGB, tinted along the hue field without changing its lightness.
vec3 tint(float grey, float hueField) {
  if (uSaturation <= 0.0) return vec3(grey);
  float lin = grey <= 0.04045 ? grey / 12.92 : pow((grey + 0.055) / 1.055, 2.4);
  float h = uHue + hueField * uHueRange * 6.2831853;
  // Chroma fades toward black and white so extremes stay clean.
  float C = uSaturation * 0.2 * (0.3 + 0.7 * sin(3.14159265 * grey));
  return linearToSrgb(oklabToLinearSrgb(vec3(pow(lin, 1.0 / 3.0), C * cos(h), C * sin(h))));
}

// Perlin noise as feTurbulence computes it: unit lattice gradients blended
// with the cubic curve 3t² − 2t³. Unlike gradNoise's quintic curve, the cubic
// leaves faint creases along the lattice lines; warped, they become the
// marble's fine fibres.
float perlinNoise(vec2 p, uint seed) {
  vec2 i = floor(p);
  vec2 f = p - i;
  ivec2 c = ivec2(i);
  vec2 u = f * f * (3.0 - 2.0 * f);
  float a = dot(latticeGradient(c, seed), f);
  float b = dot(latticeGradient(c + ivec2(1, 0), seed), f - vec2(1.0, 0.0));
  float d = dot(latticeGradient(c + ivec2(0, 1), seed), f - vec2(0.0, 1.0));
  float e = dot(latticeGradient(c + ivec2(1, 1), seed), f - vec2(1.0, 1.0));
  // Scaled to the spread of Chrome's feTurbulence, measured on the same
  // filter; unscaled, ours varies about 20% more and clips more to white.
  return 0.83 * mix(mix(a, b, u.x), mix(d, e, u.x), u.y);
}

// feTurbulence type="fractalNoise", one channel: octaves of Perlin noise at
// doubling frequency and halving amplitude, mapped to [0, 1]. Each octave has
// its own lattice, which gives softer, smokier folds than the browser's
// shared one.
float fractalNoise(vec2 p, int octaves, uint seed) {
  float sum = 0.0;
  float amp = 1.0;
  for (int o = 0; o < 8; o++) {
    if (o >= octaves) break;
    sum += amp * perlinNoise(p, seed + uint(o) * 131u);
    p *= 2.0;
    amp *= 0.5;
  }
  return clamp(0.5 * (sum + 1.0), 0.0, 1.0);
}

// The filter's intermediate images are 8-bit, so the warp moves the base in
// steps of scale / 255.
vec3 bits8(vec3 v) {
  return floor(v * 255.0 + 0.5) / 255.0;
}

// The marble's grey before contrast, at dp. hueField receives the warp's red
// channel, which tints along the folds.
float marbleGrey(vec2 dp, out float hueField) {
  uint s = uSeed * 16u;
  // feDisplacementMap: the base noise is read from where the warp's red and
  // green channels push it, by up to half the scale either way.
  vec2 wp = dp * uWarpFrequency;
  vec2 warp = bits8(vec3(fractalNoise(wp, uWarpOctaves, s + 1u), fractalNoise(wp, uWarpOctaves, s + 2u), 0.0)).xy;
  vec2 bp = (dp + uWarpScale * (warp - 0.5)) * uBaseFrequency;
  // feColorMatrix: the base's red, green and blue channels averaged to grey.
  vec3 base = bits8(vec3(fractalNoise(bp, uBaseOctaves, s + 3u),
                         fractalNoise(bp, uBaseOctaves, s + 4u),
                         fractalNoise(bp, uBaseOctaves, s + 5u)));
  hueField = warp.x;
  return (base.r + base.g + base.b) / 3.0;
}

// The film grain at dp: its desaturated value, and in alpha the strength the
// SVG rect composites it at (grain opacity times the noise's own alpha).
float marbleGrain(vec2 dp, out float alpha) {
  uint s = uSeed * 16u;
  vec2 gp = dp * uGrainFrequency;
  alpha = uGrainOpacity * fractalNoise(gp, 1, s + 9u);
  return dot(vec3(fractalNoise(gp, 1, s + 6u), fractalNoise(gp, 1, s + 7u), fractalNoise(gp, 1, s + 8u)),
             vec3(0.2126, 0.7152, 0.0722));
}

// From grey to color: feComponentTransfer's steep contrast clips highlights
// to white and sinks the folds; then the grain is overlay-blended and the
// result tinted.
vec3 marbleShade(float grey, float grain, float grainAlpha, float hueField) {
  float v = clamp(uSlope * grey + uOffset, 0.0, 1.0);
  float overlay = v <= 0.5 ? 2.0 * v * grain : 1.0 - 2.0 * (1.0 - v) * (1.0 - grain);
  return tint(mix(v, overlay, grainAlpha), hueField - 0.5);
}
