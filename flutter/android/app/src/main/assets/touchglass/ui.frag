// The trackpad's controls over the oil and the marble, after the Trackpad
// design. Its buttons, panels and text field are droplets of liquid
// glass in one goo: rounded boxes merged by a smooth minimum, so droplets
// that pass near each other flow together, as the lock spills the buttons
// out and drinks them back. Each is the marble frosted behind it, lifted
// toward white, with a lens at its rim that bends in what lies just outside,
// a hairline, a glint toward the light and a soft drop shadow. The chip is a
// droplet of its own. Icons and labels come from an atlas of coverage
// masks; the lock's hold ring and the text field's mic button are drawn
// here too.
//
// Drawn after the oil's composite into the same framebuffer, scissored to
// the boxes around the controls, blended premultiplied. Coordinates are
// device px, origin top-left, y down.

uniform vec2 uRes;
uniform float uDp;                    // px per dp
uniform sampler2D uBgFrost;           // the marble frosted, quarter size
uniform sampler2D uAtlas;             // icons in white and labels in color, premultiplied
uniform vec2 uLightDir;               // toward the light, screen space

#define MAX_DROPS 9
uniform int uDropCount;
uniform vec4 uDrops[MAX_DROPS];       // center xy, half size zw, px
uniform float uDropRadii[MAX_DROPS];  // corner radii, px: where the corner's curve leaves the edge
uniform float uDropPowers[MAX_DROPS]; // corner exponents: 2 a circular arc, more a squircle's
uniform float uDropErode[MAX_DROPS];  // how far a droplet too thin to hold together has shrunk, px
uniform float uDropGoo[MAX_DROPS];    // how close it comes to the others before they flow together, px

uniform vec4 uChip;                   // center xy, half size zw, px
uniform float uChipAlpha;
uniform vec4 uChipLabel;              // top-left xy, size zw, px
uniform vec4 uChipLabelUv;            // atlas uv: top-left xy, bottom-right zw

#define MAX_ICONS 11
uniform int uIconCount;
uniform vec4 uIcons[MAX_ICONS];       // top-left xy, size zw, px
uniform vec4 uIconUvs[MAX_ICONS];     // atlas uv: top-left xy, bottom-right zw
uniform vec4 uIconColors[MAX_ICONS];  // rgb, and a its opacity
uniform int uClipFrom;                // icons from this one on, the text field's, and its mic button, are cropped to the glass

uniform vec4 uFill;                   // the dark pill under the open speed button: center, half size, px
uniform float uFillAlpha;
uniform vec4 uGearFill;               // the same under the settings droplet while its panel is open
uniform float uGearFillAlpha;
uniform vec4 uDisc;                  // the text field's mic button: center xy, radius px, opacity
uniform vec4 uFieldMap;               // the text field's glass: center xy, and how stretched it is from rest zw
uniform int uGear;                    // the settings gear's icon, or -1: stretched with its droplet, and cropped to it
uniform vec4 uGearMap;                // the settings droplet: center xy, and how stretched it is from rest zw
uniform vec4 uRing;                   // the lock's hold ring: center xy, radius px, progress 0 to 1
uniform float uRingAlpha;

out vec4 fragColor;

const vec3 INK = vec3(0.043, 0.043, 0.047);  // #0B0B0C

float box(vec2 p, vec4 b, float r) {
  vec2 q = abs(p - b.xy) - b.zw + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

// A box with squircle corners, as the pad's outline (pad_oil.glsl's restSdf):
// each corner an Lp-norm curve of exponent n, its distance divided by its
// gradient's length so it stays close to Euclidean near the edge.
float squircle(vec2 p, vec4 b, float r, float n) {
  if (n == 2.0) return box(p, b, r);
  vec2 q = abs(p - b.xy) - b.zw + r;
  if (q.x > 0.0 && q.y > 0.0) {
    float len = pow(pow(q.x, n) + pow(q.y, n), 1.0 / n);
    vec2 grad = pow(q / max(len, 1e-4), vec2(n - 1.0));
    return (len - r) / length(grad);
  }
  return max(q.x, q.y) - r;
}

// A smooth minimum: two shapes within k of each other bulge toward each
// other and, closer still, join with a neck.
float smin(float a, float b, float k) {
  float h = max(k - abs(a - b), 0.0) / max(k, 1e-3);
  return min(a, b) - h * h * k * 0.25;
}

float goo(vec2 p) {
  float d = 1e5;
  for (int i = 0; i < MAX_DROPS; i++) {
    if (i >= uDropCount) break;
    d = smin(d, squircle(p, uDrops[i], uDropRadii[i], uDropPowers[i]) + uDropErode[i], uDropGoo[i]);
  }
  return d;
}

// A blurred shape's coverage, from its signed distance: close to the
// Gaussian's, σ in px.
float blurred(float d, float sigma) {
  return 1.0 - smoothstep(-1.9 * sigma, 1.9 * sigma, d);
}

vec4 over(vec4 top, vec4 under) {
  return top + under * (1.0 - top.a);
}

// Glass at px, where its signed distance is d and its outward normal n:
// premultiplied, times opacity.
vec4 glass(vec2 px, float d, vec2 n, float opacity) {
  float coverage = clamp(0.5 - d, 0.0, 1.0) * opacity;
  if (coverage <= 0.0) return vec4(0.0);
  float inward = max(-d, 0.0);
  // The rim is curved, so it shows what lies a little outside the droplet.
  vec2 seen = px + n * (5.0 * uDp) * exp(-inward / (5.0 * uDp));
  vec3 col = textureLod(uBgFrost, vec2(seen.x, uRes.y - seen.y) / uRes, 0.0).rgb;
  col = mix(col, vec3(0.98), 0.62);
  float hairline = 1.0 - smoothstep(1.2 * uDp - 0.5, 1.2 * uDp + 0.5, inward);
  float glint = exp(-inward / (2.5 * uDp)) * max(dot(n, uLightDir), 0.0);
  col = mix(col, vec3(1.0), clamp(0.9 * hairline + 0.35 * glint, 0.0, 1.0));
  return vec4(col * coverage, coverage);
}

// The outward normal of a distance field, from its screen-space slope.
vec2 normalOf(float d) {
  vec2 g = vec2(dFdx(d), -dFdy(d));
  return g / max(length(g), 1e-4);
}

// An icon or label from the atlas, its color multiplied by tint.
vec4 sprite(vec2 px, vec4 rect, vec4 uv, vec3 tint, float opacity) {
  vec2 t = (px - rect.xy) / rect.zw;
  if (any(lessThan(t, vec2(0.0))) || any(greaterThan(t, vec2(1.0)))) return vec4(0.0);
  vec4 s = textureLod(uAtlas, mix(uv.xy, uv.zw, t), 0.0) * opacity;
  return vec4(s.rgb * tint, s.a);
}

void main() {
  vec2 px = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y);
  // Every derivative before any branch.
  float d = goo(px);
  vec2 n = normalOf(d);
  float chip = box(px, uChip, uChip.w);
  vec2 chipNormal = normalOf(chip);

  // The shadow, which the glass hides wherever it's whole.
  vec4 col = vec4(0.0);
  if (d > -1.0) col.a = 0.2 * blurred(goo(px - vec2(0.0, 9.0 * uDp)), 11.0 * uDp);
  col = over(glass(px, d, n, 1.0), col);

  if (uFillAlpha > 0.0) {
    float a = clamp(0.5 - box(px, uFill, uFill.w), 0.0, 1.0) * uFillAlpha;
    col = over(vec4(INK * a, a), col);
  }
  if (uGearFillAlpha > 0.0) {
    float a = clamp(0.5 - box(px, uGearFill, uGearFill.w), 0.0, 1.0) * uGearFillAlpha;
    col = over(vec4(INK * a, a), col);
  }
  // The text field's controls stay whole as its glass bounces, cropped to it,
  // fading over the last 5 dp inside its edge so the crop isn't a hard cut.
  // At rest they sit 6 dp clear of it.
  // And they stretch with it: they're laid out in the glass at rest, so each
  // px is looked up where the stretch takes it from.
  float inGlass = 1.0 - smoothstep(-5.0 * uDp, 0.5, d);
  vec2 stretch = max(uFieldMap.zw, vec2(0.05));
  vec2 inField = uFieldMap.xy + (px - uFieldMap.xy) / stretch;
  vec2 inGear = uGearMap.xy + (px - uGearMap.xy) / max(uGearMap.zw, vec2(0.05));
  if (uDisc.w > 0.0) {
    float edge = (uDisc.z - length(inField - uDisc.xy)) * min(stretch.x, stretch.y);
    float a = clamp(edge + 0.5, 0.0, 1.0) * uDisc.w * inGlass;
    col = over(vec4(INK * a, a), col);
  }
  for (int i = 0; i < MAX_ICONS; i++) {
    if (i >= uIconCount) break;
    bool field = i >= uClipFrom;
    bool gear = i == uGear;
    vec2 at = field ? inField : gear ? inGear : px;
    float crop = field || gear ? inGlass : 1.0;
    col = over(sprite(at, uIcons[i], uIconUvs[i], uIconColors[i].rgb, uIconColors[i].a * crop), col);
  }

  if (uRingAlpha > 0.0) {
    // From twelve o'clock, clockwise, with round ends.
    vec2 q = px - uRing.xy;
    float turn = atan(q.x, -q.y) / 6.2831853;
    turn = turn < 0.0 ? turn + 1.0 : turn;
    float along = abs(length(q) - uRing.z);
    float sweep = uRing.w * 6.2831853;
    vec2 start = vec2(0.0, -uRing.z);
    vec2 end = uRing.z * vec2(sin(sweep), -cos(sweep));
    float dist = turn <= uRing.w ? along : min(length(q - start), length(q - end));
    float a = (1.0 - smoothstep(1.25 * uDp - 0.6, 1.25 * uDp + 0.6, dist)) * uRingAlpha * step(0.002, uRing.w);
    col = over(vec4(INK * a, a), col);
  }

  if (uChipAlpha > 0.0) {
    float shade = 0.16 * uChipAlpha * blurred(box(px - vec2(0.0, 10.0 * uDp), uChip, uChip.w), 14.0 * uDp);
    col = over(vec4(0.0, 0.0, 0.0, shade), col);
    col = over(glass(px, chip, chipNormal, uChipAlpha), col);
    col = over(sprite(px, uChipLabel, uChipLabelUv, vec3(1.0), uChipAlpha), col);
  }

  fragColor = col;
}
