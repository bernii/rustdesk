// The moving marble's flow, on a coarse grid in texture space (GL y up).
// Prepended after common.glsl. Per cell:
//   r   phase: +1 is the white oil, −1 the black one, between is their boundary
//   gb  velocity, cells/s
//   a   dye: the marble's broad grey, carried with the oil, which tones the
//       black oil so it stays smoky rather than flat

uniform sampler2D uFlow;
uniform float uFlowCell;   // px per cell
uniform float uGreyWhite;  // marble grey of each oil, before contrast
uniform float uGreyBlack;

vec4 flowAt(ivec2 c) {
  return texelFetch(uFlow, clamp(c, ivec2(0), textureSize(uFlow, 0) - 1), 0);
}

// Bilinear, by hand, since RGBA32F isn't filterable. x is in cells.
vec4 flowSample(vec2 x) {
  x -= 0.5;
  ivec2 i = ivec2(floor(x));
  vec2 f = x - floor(x);
  return mix(mix(flowAt(i), flowAt(i + ivec2(1, 0)), f.x),
             mix(flowAt(i + ivec2(0, 1)), flowAt(i + ivec2(1, 1)), f.x), f.y);
}

vec4 catmullRom(float t) {
  float t2 = t * t;
  float t3 = t2 * t;
  return vec4(-0.5 * t3 + t2 - 0.5 * t, 1.5 * t3 - 2.5 * t2 + 1.0, -1.5 * t3 + 2.0 * t2 + 0.5 * t, 0.5 * t3 - 0.5 * t2);
}

// Catmull–Rom, clamped to the four nearest cells so it never overshoots. For
// carrying the oils: bilinear sampling smears them a little every time, and
// a fast flow (a shake's churn) would blur the white and black into grey.
vec4 flowSampleSharp(vec2 x) {
  x -= 0.5;
  ivec2 i = ivec2(floor(x));
  vec2 f = x - floor(x);
  vec4 wx = catmullRom(f.x);
  vec4 wy = catmullRom(f.y);
  vec4 sum = vec4(0.0);
  vec4 lo = vec4(1e9);
  vec4 hi = vec4(-1e9);
  for (int y = 0; y < 4; y++) {
    vec4 row = vec4(0.0);
    for (int xi = 0; xi < 4; xi++) {
      vec4 v = flowAt(i + ivec2(xi - 1, y - 1));
      row += wx[xi] * v;
      if ((xi == 1 || xi == 2) && (y == 1 || y == 2)) {
        lo = min(lo, v);
        hi = max(hi, v);
      }
    }
    sum += wy[y] * row;
  }
  return clamp(sum, lo, hi);
}

