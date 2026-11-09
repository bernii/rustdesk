// A copy of the flow in a filterable half-float texture, once a frame, so the
// full-screen marble reads it with one bilinear fetch instead of four
// (RGBA32F isn't filterable).
//
// The phase is blurred on the way: the simulation keeps the boundary between
// the oils one cell wide, since Cahn–Hilliard's time step shrinks with the
// fourth power of that width, and the softness people see is added here.

uniform float uSoftness;  // σ of the boundary's blur, cells

out vec4 fragColor;

void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  vec4 here = flowAt(c);
  float sigma = max(uSoftness, 0.3);
  float k = -0.5 / (sigma * sigma);
  int radius = min(int(ceil(2.0 * sigma)), 4);
  float phase = 0.0;
  float total = 0.0;
  for (int y = -4; y <= 4; y++) {
    if (abs(y) > radius) continue;
    for (int x = -4; x <= 4; x++) {
      if (abs(x) > radius) continue;
      float w = exp(k * float(x * x + y * y));
      phase += flowAt(c + ivec2(x, y)).r * w;
      total += w;
    }
  }
  fragColor = vec4(phase / total, here.gba);
}
