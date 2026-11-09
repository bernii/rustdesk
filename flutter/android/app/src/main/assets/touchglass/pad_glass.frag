// The trackpad's oil over the marble: touch-glass-exp's glass.frag, with the
// gesture legend suspended in the oil and without its baked shortcuts.
//
// Model: a puddle of clear oil sits on the background surface, held inset
// from its edge by surface tension. At rest its thickness rises from zero at
// the contact line with the slope of the contact angle and levels off at the
// plateau (pad_oil.glsl). The simulation adds the moving part of the surface:
// fingers push the oil aside, and waves, bulges and wakes move across it.
// The view ray comes straight down, bends at the oil surface (Snell, per
// color channel for dispersion) and lands on the dot layer and the legend,
// suspended at a fraction of the local depth, and on the background beneath.
// Reflections are Fresnel-weighted; caustics brighten the background under
// bumps.
//
// Coordinates are device px with the origin top-left and y down, like an
// Android view; z points toward the viewer.
//
// The pad changes shape when it locks, when dictation opens the text field
// and when the keyboard rises, so a shortcut baked from its outline, as
// glass.frag's still frame and layers are, would keep going stale. Calm quads
// compose the oil at rest directly instead, which costs about what the
// layers did: around the oil, the bare background under its contact shadow;
// on the plateau, the frosted background, milkiness, dots, legend and the
// flat surface's reflection, with no refraction. The meniscus band and
// anything that moves take the full composite. The light is live in every
// path, so it can follow the phone's turn.

uniform sampler2D uBg;       // background, full size
uniform sampler2D uBgFrost;  // blurred background, quarter size
uniform sampler2D uSurface;  // simulated surface: η, ∇η (per px), ∇²η
uniform sampler2D uDrift;    // dot displacement, cells (texture axes)
uniform sampler2D uDotCells; // per dot: r luminosity, g 1 if drawn (dot_cells.frag)
uniform sampler2D uLegend;   // the gesture legend, premultiplied, top row first
uniform vec2 uRes;           // px
uniform float uCell;         // px per simulation cell
uniform float uFrostOn;
uniform float uFrostFade;    // oil thinner than this frosts less, px; 0 frosts it all alike
uniform float uPlateau;      // px in from the edge past which the oil at rest is flat

uniform float uIor;
uniform float uDispersion;
uniform float uImageDepth;   // oil bottom to background, px
uniform float uMilkiness;
uniform float uVibrance;
uniform float uReflections;
uniform float uRimLine;
uniform float uRimWidth;     // px
uniform vec2 uLightDir;      // toward the light, screen space
uniform vec2 uCausticShift;  // toward the light, px per px of depth: where the light reaching a point entered the oil
uniform float uShadow;
uniform float uCaustics;

uniform float uSpacing;      // px
uniform float uDotSize;      // diameter / spacing
uniform float uDotOpacity;
uniform float uDotDepth;     // 0 surface, 1 bottom
uniform vec3 uDotColor;
uniform int uHex;
uniform ivec2 uDotOrigin;    // texel of the dot at the screen's center

uniform vec4 uLegendBox;     // xy its center (px from the screen center), zw its half size (px), as scrolled and zoomed
uniform vec4 uLegendCalm;    // xy the center of the dots' dimming around it, zw its radii (px)

out vec4 fragColor;

// How far a 2×2 quad's pixels lie from its center, px, with room to spare.
const float QUAD_REACH = 1.5;

vec2 toUv(vec2 px) {
  return vec2(px.x, uRes.y - px.y) / uRes;
}

// The simulation grid starts at the bottom-left corner and may overhang the
// screen by part of a cell.
vec2 simUv(vec2 px) {
  return vec2(px.x, uRes.y - px.y) / (vec2(textureSize(uSurface, 0)) * uCell);
}

// True where the oil is at rest and the dots are home, so the pixel matches
// the oil at rest: a slope that shifts the dots by about a tenth of a px
// (the frosted background hides more); curvature whose caustic changes
// brightness by under 1/255; dots within a tenth of a px. Under a slanted
// light the caustic comes from the surface toward the light, so that's
// checked too. The oil may stand higher or lower than at rest, as it does
// when the pad shrinks or grows: flat, it looks the same as long as it's
// thick enough to frost fully, keep its milkiness and hold its dots.
bool calm(vec2 px) {
  vec2 uv = simUv(px);
  vec4 s = texture(uSurface, uv);
  vec2 drift = texture(uDrift, uv).xy * uCell;
  float lit = texture(uSurface, simUv(px + uCausticShift * (uThickness + uImageDepth))).a;
  return uThickness * (1.0 + s.r) > max(uFrostFade, 0.6 * uThickness) + 0.5
      && uThickness * length(s.gb) < 0.005
      && uThickness * abs(s.a) < 5e-4
      && uThickness * abs(lit) < 5e-4
      && dot(drift, drift) < 0.01;
}

// Lateral travel per px of depth for a straight-down view ray refracted at a
// surface with normal n.
vec2 refractDir(vec3 n, float ior) {
  vec3 t = refract(vec3(0.0, 0.0, -1.0), n, 1.0 / ior);
  return t.xy / max(-t.z, 0.05);
}

// How frosted the background is under oil h px thick, 0 to 1.
float frostAt(float h) {
  return uFrostFade > 0.0 ? uFrostOn * smoothstep(0.0, uFrostFade, h) : uFrostOn;
}

// The background seen through the oil: frosted where the oil is whole,
// clearing to sharp where a finger has thinned it. Only the transition reads
// both. Explicit-level reads, since callers branch per pixel.
vec3 background(vec2 px, float frost) {
  vec2 uv = toUv(px);
  if (frost >= 1.0) return textureLod(uBgFrost, uv, 0.0).rgb;
  if (frost <= 0.0) return textureLod(uBg, uv, 0.0).rgb;
  return mix(textureLod(uBg, uv, 0.0).rgb, textureLod(uBgFrost, uv, 0.0).rgb, frost);
}

// The dot of the grid nearest g (in spacings): its cell, and its center.
void nearestDot(vec2 g, out vec2 cell, out vec2 center) {
  if (uHex == 1) {
    const float ROW = 0.8660254;
    float row = floor(g.y / ROW + 0.5);
    float shift = 0.5 * mod(row, 2.0);
    float col = floor(g.x - shift + 0.5);
    cell = vec2(col, row);
    center = vec2(col + shift, row * ROW);
  } else {
    cell = floor(g + 0.5);
    center = cell;
  }
}

// Coverage of the dot grid at oil-space point q (px, origin at the screen's
// center, already moved back by the flow's drift), antialiased over aa px;
// lum receives that dot's luminosity. No derivatives, so callers can branch.
float dotLayer(vec2 q, float aa, out float lum) {
  float s = uSpacing;
  vec2 g = q / s;
  vec2 cell;
  vec2 center;
  nearestDot(g, cell, center);
  float dist = length(g - center) * s;
  float radius = 0.5 * uDotSize * s;
  float cov = 1.0 - smoothstep(radius - aa, radius + aa, dist);

  // Luminosity and visibility were baked per dot. Cells past the texture's
  // edge clamp to its border, which lies outside the oil and isn't drawn.
  ivec2 texel = clamp(ivec2(cell) + uDotOrigin, ivec2(0), textureSize(uDotCells, 0) - 1);
  vec2 baked = texelFetch(uDotCells, texel, 0).rg;
  lum = baked.r;
  return cov * baked.g;
}

// The design dims the dots behind the legend so it reads: by 82% at its
// center, 70% most of the way out, then fading, over an ellipse that moves
// with it.
float dotsAround(vec2 q) {
  float r = length((q - uLegendCalm.xy) / uLegendCalm.zw);
  float dim = r < 0.62 ? mix(0.82, 0.7, r / 0.62) : mix(0.7, 0.0, min((r - 0.62) / 0.38, 1.0));
  return 1.0 - dim;
}

// The legend at oil-space point q, premultiplied: its glow and its rows of
// icons and words.
vec4 legendAt(vec2 q) {
  vec2 uv = (q - uLegendBox.xy) / (2.0 * uLegendBox.zw) + 0.5;
  if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return vec4(0.0);
  return textureLod(uLegend, uv, 0.0);
}

// The dots and the legend over the oil's body at oil-space point q, fading
// as the oil leaves (kept is how much of its rest thickness it holds).
vec3 suspended(vec3 body, vec3 dots, vec3 lum, vec2 q, float kept) {
  float stay = smoothstep(0.15, 0.45, kept);
  body = mix(body, uDotColor * lum, dots * uDotOpacity * dotsAround(q) * stay);
  vec4 legend = legendAt(q) * stay;
  return body * (1.0 - legend.a) + legend.rgb;
}

// A soft studio: a bright window toward the light, a dimmer bounce opposite.
vec3 environment(vec3 r) {
  vec3 key = normalize(vec3(uLightDir * 0.9, 0.44));
  vec3 bounce = normalize(vec3(-uLightDir * 0.95, 0.3));
  float sky = 0.12 + 0.2 * clamp(r.z, 0.0, 1.0);
  float k = pow(max(dot(r, key), 0.0), 40.0) * 8.0;
  float b = pow(max(dot(r, bounce), 0.0), 16.0) * 1.6;
  return vec3(sky + k + b);
}

// How much light the contact shadow leaves on the surface around the oil at
// p (px from the center), where the oil's signed distance is d: it's cast
// away from the light.
float shadowAt(vec2 p, float d) {
  vec2 unused;
  float soft = 0.8 * uThickness + 1.0;
  float castShadow = 1.0 - smoothstep(-soft, soft, oilSdf(p + uLightDir * 0.5 * uThickness, unused));
  float ao = exp(-max(d, 0.0) / soft);
  return 1.0 - uShadow * clamp(0.45 * ao + 0.6 * castShadow, 0.0, 1.0);
}

// The light off the oil's surface where its normal is n: Fresnel-weighted
// reflections, and inward px from the edge (outward normal normal2) the rim
// line, brighter facing the light.
vec3 surfaceLight(vec3 n, vec2 normal2, float inward) {
  float f0 = pow((uIor - 1.0) / (uIor + 1.0), 2.0);
  float fresnel = f0 + (1.0 - f0) * pow(1.0 - n.z, 5.0);
  float facing = dot(normal2, uLightDir);
  float rim = exp(-inward / uRimWidth) * (0.25 + 0.75 * max(facing, 0.0) + 0.45 * max(-facing, 0.0));
  return fresnel * environment(reflect(vec3(0.0, 0.0, -1.0), n)) * uReflections + uRimLine * rim;
}

vec3 dither() {
  return vec3((rand2(ivec2(gl_FragCoord.xy), 5u).x - 0.5) / 255.0);
}

// The full composite at px.
vec3 composite(vec2 px) {
  vec2 p = px - 0.5 * uRes;
  vec2 normal2;
  float d = oilSdf(p, normal2);
  float coverage = clamp(0.5 - d, 0.0, 1.0);

  // Surface around the oil: a contact shadow cast away from the light.
  vec3 bare = textureLod(uBg, toUv(px), 0.0).rgb;
  vec3 col = bare;
  if (coverage < 1.0) col *= shadowAt(p, d);

  if (coverage > 0.0) {
    float inward = max(-d, 0.0);
    float hRest;
    float slope;
    float bend;
    restProfile(inward, hRest, slope, bend);

    vec4 surface = texture(uSurface, simUv(px));
    float h = max(hRest + uThickness * surface.r, 0.0);
    vec2 grad = -normal2 * slope + uThickness * surface.gb;
    vec3 n = normalize(vec3(-grad, 1.0));
    // How much of its rest thickness the oil keeps here: 0 where a finger has
    // pushed it all away.
    float kept = h / max(hRest, 1.0);
    float wet = smoothstep(0.02, 0.15, kept);
    float whole = smoothstep(0.0, 0.6, kept);

    vec2 dirR = refractDir(n, uIor - 0.5 * uDispersion);
    vec2 dirG = refractDir(n, uIor);
    vec2 dirB = refractDir(n, uIor + 0.5 * uDispersion);
    float bgDepth = h + uImageDepth;
    float dotDepth = h * uDotDepth;
    // Colors only visibly split where red and blue land a quarter px apart:
    // crater walls and steep crests. Elsewhere one sample serves all three.
    bool split = bgDepth * length(dirB - dirR) > 0.25;

    // The dot layer under this pixel, moved back by the flow's drift (looked
    // up once; it varies slowly), and its antialiasing width from the
    // derivative of that position, which stays continuous across cells and
    // widens where the meniscus compresses the grid. Both before branching.
    vec2 qG = p + dirG * dotDepth;
    vec2 drift = texture(uDrift, simUv(qG + 0.5 * uRes)).xy * uCell;
    vec2 back = vec2(drift.x, -drift.y);
    qG -= back;
    float aa = clamp(0.7 * max(fwidth(qG.x), fwidth(qG.y)), 0.5, 6.0);
    // Each dot moves whole, by the drift where it is now, as in
    // touch-glass-exp's glass.frag: read per pixel, a drift that changes
    // across a dot, as where oil slides past an edge that holds it, sheared
    // the dot into a slanted oval. The legend keeps the smooth drift.
    vec2 cell;
    vec2 home;
    nearestDot(qG / uSpacing, cell, home);
    drift = texture(uDrift, simUv(home * uSpacing + back + 0.5 * uRes)).xy * uCell;
    vec2 dotBack = vec2(drift.x, -drift.y);
    vec2 qDot = qG + back - dotBack;

    float frost = frostAt(h) * whole;
    vec3 under;
    vec3 lum;
    vec3 dots;
    if (split) {
      under = vec3(background(px + dirR * bgDepth, frost).r,
                   background(px + dirG * bgDepth, frost).g,
                   background(px + dirB * bgDepth, frost).b);
      dots = vec3(dotLayer(p + dirR * dotDepth - dotBack, aa, lum.r),
                  dotLayer(qDot, aa, lum.g),
                  dotLayer(p + dirB * dotDepth - dotBack, aa, lum.b));
    } else {
      under = background(px + dirG * bgDepth, frost);
      float l;
      dots = vec3(dotLayer(qDot, aa, l));
      lum = vec3(l);
    }
    // Caustics from the moving surface: light converges under bumps and
    // spreads under dips. The small-slope estimate (how much the refracted
    // light's footprint stretches) blows up on steep crater walls, so tanh
    // bounds it to ±50%. A slanted light reaches this point through the
    // surface toward it, so the caustics fall away from the light.
    float curvature = texture(uSurface, simUv(px + uCausticShift * bgDepth)).a;
    float spread = (1.0 - 1.0 / uIor) * bgDepth * uThickness * curvature;
    under *= 1.0 - 0.5 * uCaustics * tanh(spread);

    float luma = dot(under, vec3(0.2126, 0.7152, 0.0722));
    vec3 body = mix(vec3(luma), under, uVibrance);
    body = mix(body, vec3(1.0), uMilkiness * whole);

    // Dots and the legend live in the oil, so they leave with it.
    body = suspended(body, dots, lum, qG, kept);

    body += surfaceLight(n, normal2, inward);

    // Where the oil is gone, the bare background shows through.
    body = mix(bare, body, wet);
    col = mix(col, body, coverage);
  }

  return col + dither();
}

void main() {
  vec2 px = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y);

  // Calm quads take a shortcut. Decide per 2×2 quad, from the quad's center,
  // so a quad never splits between paths and the dots' derivatives stay valid.
  vec2 quadCenter = floor(gl_FragCoord.xy * 0.5) * 2.0 + 1.0;
  vec2 quadPx = vec2(quadCenter.x, uRes.y - quadCenter.y);
  if (calm(quadPx)) {
    vec2 normal2;
    float dq = oilSdf(quadPx - 0.5 * uRes, normal2);
    vec2 p = px - 0.5 * uRes;
    if (dq > 0.5 + QUAD_REACH) {
      // Around the oil: the background under its contact shadow.
      vec2 unused;
      vec3 bare = textureLod(uBg, toUv(px), 0.0).rgb;
      fragColor = vec4(bare * shadowAt(p, oilSdf(p, unused)) + dither(), 1.0);
      return;
    }
    if (-dq > uPlateau + QUAD_REACH) {
      // The plateau at rest: flat, whole and frosted. Its dot layer lies
      // straight below each pixel, one px per px.
      vec3 under = background(px, frostAt(uThickness));
      vec3 body = mix(vec3(dot(under, vec3(0.2126, 0.7152, 0.0722))), under, uVibrance);
      body = mix(body, vec3(1.0), uMilkiness);
      float lum;
      float dots = dotLayer(p, 0.7, lum);
      body = suspended(body, vec3(dots), vec3(lum), p, 1.0);
      body += surfaceLight(vec3(0.0, 0.0, 1.0), normal2, -dq);
      fragColor = vec4(body + dither(), 1.0);
      return;
    }
  }
  fragColor = vec4(composite(px), 1.0);
}
