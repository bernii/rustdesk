// The surface under the oil, when it stands still, in one of three styles.
// Rendered once into a texture whenever its parameters change; prepended
// with common.glsl and marble.glsl.
//
// Organic: domain-warped fbm, black and white by default.
// Liquid marble: see marble.glsl. When it moves, marble_motion.frag draws it
// from the flow instead.
// Image: any picture, scaled to cover the screen and cropped (playground
// only).
//
// All color in Oklab, so saturation never shifts perceived lightness.

uniform vec2 uRes;            // px
uniform int uStyle;           // 0 organic, 1 liquid marble, 2 image

// Organic
uniform float uScale;
uniform float uWarp;
uniform float uContrast;
uniform float uLightness;
uniform float uGrain;

// Image
uniform sampler2D uImage;
uniform vec4 uImageRect;      // uv = px × xy + zw: the picture covering the screen
uniform float uImageSaturation;
uniform float uImageHue;      // rad

out vec4 fragColor;

vec3 picture(vec2 px) {
  vec3 lab = linearSrgbToOklab(srgbToLinear(texture(uImage, px * uImageRect.xy + uImageRect.zw).rgb));
  float c = cos(uImageHue);
  float s = sin(uImageHue);
  lab.yz = mat2(c, s, -s, c) * lab.yz * uImageSaturation;
  return linearToSrgb(oklabToLinearSrgb(lab));
}

vec3 marble(vec2 dp) {
  float hueField;
  float grey = marbleGrey(dp, hueField);
  float grainAlpha;
  float grain = marbleGrain(dp, grainAlpha);
  return marbleShade(grey, grain, grainAlpha, hueField);
}

vec3 organic(vec2 px) {
  // Feature size follows screen width so the look holds at any resolution.
  vec2 p = px / uRes.x * 0.55 / uScale + vec2(float(uSeed % 97u) * 3.7, 0.0);

  float w = 1.6 * uWarp;
  vec2 q = vec2(fbm(p, uSeed), fbm(p + vec2(5.2, 1.3), uSeed));
  vec2 r = vec2(fbm(p + w * q + vec2(1.7, 9.2), uSeed + 11u),
                fbm(p + w * q + vec2(8.3, 2.8), uSeed + 11u));
  float v = fbm(p + w * r, uSeed + 23u);

  float lum = clamp(uLightness + v * uContrast * 0.85, 0.0, 1.0);
  lum = mix(lum, lum * lum * (3.0 - 2.0 * lum), clamp(uContrast - 1.0, 0.0, 1.0));

  float hueField = fbm(p * 0.6 + r * 0.8 + vec2(3.1, 7.7), uSeed + 37u);
  float h = uHue + hueField * uHueRange * 6.2831853;
  float L = mix(0.14, 0.97, lum);
  float C = uSaturation * 0.2 * (0.3 + 0.7 * sin(3.14159265 * lum));
  vec3 col = linearToSrgb(oklabToLinearSrgb(vec3(L, C * cos(h), C * sin(h))));
  return col + (rand2(ivec2(gl_FragCoord.xy), uSeed + 101u).x - 0.5) * uGrain;
}

void main() {
  vec2 px = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y);
  vec3 col = uStyle == 2 ? picture(px) : uStyle == 1 ? marble(px / uDensity) : organic(px);
  fragColor = vec4(col, 1.0);
}
