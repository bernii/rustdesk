// Removes the pressure gradient from the oils' velocity, leaving the part
// that neither compresses nor expands them, and stops flow through the
// screen's edges: the oils sit in a closed container.

uniform sampler2D uPressure;

out vec4 fragColor;

float pressureAt(ivec2 c) {
  return texelFetch(uPressure, clamp(c, ivec2(0), textureSize(uPressure, 0) - 1), 0).r;
}

void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  vec4 s = flowAt(c);
  vec2 gradient = 0.5 * vec2(pressureAt(c + ivec2(1, 0)) - pressureAt(c - ivec2(1, 0)),
                             pressureAt(c + ivec2(0, 1)) - pressureAt(c - ivec2(0, 1)));
  vec2 velocity = s.gb - gradient;
  ivec2 last = textureSize(uFlow, 0) - 1;
  if (c.x == 0 || c.x == last.x) velocity.x = 0.0;
  if (c.y == 0 || c.y == last.y) velocity.y = 0.0;
  fragColor = vec4(s.r, velocity, s.a);
}
