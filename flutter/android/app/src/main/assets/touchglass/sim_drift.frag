// Per frame: displacement of the dot layer, in cells (texture axes). The flow
// carries the dots and a spring pulls them home over the return time.

uniform sampler2D uDrift;
uniform float uDt;          // s
uniform float uGain;
uniform float uReturnRate;  // 1/s
uniform float uMaxDrift;    // cells

out vec4 fragColor;

void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  vec4 s = state(c);
  vec2 velocity = 0.5 * vec2(s.g + state(c - EAST).g, s.b + state(c - NORTH).b);
  vec2 d = texelFetch(uDrift, c, 0).xy;
  d = (d + uDt * uGain * velocity) / (1.0 + uDt * uReturnRate);
  float len = length(d);
  if (len > uMaxDrift) d *= uMaxDrift / len;
  fragColor = vec4(d, 0.0, 0.0);
}
