// Bakes each dot's luminosity and whether it is drawn, one texel per dot, so
// the composite fetches them instead of evaluating noise and the outline per
// pixel. Rebuilt when the dots, the background seed or the shape change.
//   r  luminosity
//   g  1 if the dot is drawn: whole, and clear of the contact line

uniform vec2 uRes;           // px
uniform ivec2 uDotOrigin;    // texel of the dot at the oil's center
uniform float uSpacing;      // px
uniform float uDotSize;      // diameter / spacing
uniform float uDotLum;
uniform float uDotVariance;
uniform float uDotGrain;
uniform float uDotInset;     // px
uniform int uHex;
uniform uint uSeed;

out vec4 fragColor;

void main() {
  vec2 cell = vec2(ivec2(gl_FragCoord.xy) - uDotOrigin);
  vec2 center = uHex == 1 ? vec2(cell.x + 0.5 * mod(cell.y, 2.0), cell.y * 0.8660254) : cell;
  vec2 centerPx = center * uSpacing;
  float radius = 0.5 * uDotSize * uSpacing;
  vec2 unused;
  float drawn = step(oilSdf(centerPx, unused), -(uDotInset + radius));

  float random = rand2(ivec2(cell), uSeed + 211u).x;
  float field = clamp(0.5 + 0.55 * gradNoise(centerPx / uRes.x * 3.0, uSeed + 223u), 0.0, 1.0);
  float lum = uDotLum * mix(1.0, mix(field, random, uDotGrain), uDotVariance);
  fragColor = vec4(lum, drawn, 0.0, 1.0);
}
