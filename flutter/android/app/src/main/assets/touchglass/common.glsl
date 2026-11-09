// Shared helpers, prepended to every fragment shader after the GLSL ES 3.00
// header (see main.js). Integer hashing keeps results identical across GPUs,
// which matters once the same seeds run on Android.

uvec2 pcg2d(uvec2 v) {
  v = v * 1664525u + 1013904223u;
  v.x += v.y * 1664525u;
  v.y += v.x * 1664525u;
  v ^= v >> 16u;
  v.x += v.y * 1664525u;
  v.y += v.x * 1664525u;
  v ^= v >> 16u;
  return v;
}

// Two uniform randoms in [0, 1) for an integer lattice point.
vec2 rand2(ivec2 c, uint seed) {
  uvec2 h = pcg2d(uvec2(c + 1048576) + uvec2(seed, seed * 7919u));
  return vec2(h) * (1.0 / 4294967296.0);
}

vec2 latticeGradient(ivec2 c, uint seed) {
  float a = rand2(c, seed).x * 6.2831853;
  return vec2(cos(a), sin(a));
}

// Gradient noise, roughly in [-1, 1].
float gradNoise(vec2 p, uint seed) {
  vec2 i = floor(p);
  vec2 f = p - i;
  ivec2 c = ivec2(i);
  vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  float a = dot(latticeGradient(c, seed), f);
  float b = dot(latticeGradient(c + ivec2(1, 0), seed), f - vec2(1.0, 0.0));
  float d = dot(latticeGradient(c + ivec2(0, 1), seed), f - vec2(0.0, 1.0));
  float e = dot(latticeGradient(c + ivec2(1, 1), seed), f - vec2(1.0, 1.0));
  return 1.4 * mix(mix(a, b, u.x), mix(d, e, u.x), u.y);
}

// Low-octave fbm: soft, large shapes rather than fine detail.
float fbm(vec2 p, uint seed) {
  const mat2 rot = mat2(0.8, 0.6, -0.6, 0.8);
  float sum = 0.0;
  float amp = 0.62;
  for (int o = 0; o < 4; o++) {
    sum += amp * gradNoise(p, seed + uint(o) * 101u);
    p = rot * p * 2.03 + 17.1;
    amp *= 0.32;
  }
  return sum;
}

vec3 oklabToLinearSrgb(vec3 c) {
  float l = c.x + 0.3963377774 * c.y + 0.2158037573 * c.z;
  float m = c.x - 0.1055613458 * c.y - 0.0638541728 * c.z;
  float s = c.x - 0.0894841775 * c.y - 1.2914855480 * c.z;
  l = l * l * l;
  m = m * m * m;
  s = s * s * s;
  return vec3(
     4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
}

vec3 linearSrgbToOklab(vec3 c) {
  float l = 0.4122214708 * c.r + 0.5363325363 * c.g + 0.0514459929 * c.b;
  float m = 0.2119034982 * c.r + 0.6806995451 * c.g + 0.1073969566 * c.b;
  float s = 0.0883024619 * c.r + 0.2817188376 * c.g + 0.6299787005 * c.b;
  vec3 lms = pow(max(vec3(l, m, s), 0.0), vec3(1.0 / 3.0));
  return vec3(
    0.2104542553 * lms.x + 0.7936177850 * lms.y - 0.0040720468 * lms.z,
    1.9779984951 * lms.x - 2.4285922050 * lms.y + 0.4505937099 * lms.z,
    0.0259040371 * lms.x + 0.7827717662 * lms.y - 0.8086757660 * lms.z);
}

vec3 srgbToLinear(vec3 c) {
  return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
}

vec3 linearToSrgb(vec3 c) {
  c = clamp(c, 0.0, 1.0);
  return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}
