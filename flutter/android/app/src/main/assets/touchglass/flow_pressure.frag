// One Jacobi iteration of the pressure that keeps the oils incompressible:
// ∇²p = ∇·u. The pressure persists between frames, so a few iterations a
// frame keep up with a flow that changes slowly.

uniform sampler2D uPressure;
uniform sampler2D uDivergence;

out vec4 fragColor;

float pressureAt(ivec2 c) {
  // Clamping at the edges makes the walls' pressure gradient zero.
  return texelFetch(uPressure, clamp(c, ivec2(0), textureSize(uPressure, 0) - 1), 0).r;
}

void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  float neighbors = pressureAt(c + ivec2(1, 0)) + pressureAt(c - ivec2(1, 0))
                  + pressureAt(c + ivec2(0, 1)) + pressureAt(c - ivec2(0, 1));
  fragColor = vec4(0.25 * (neighbors - texelFetch(uDivergence, c, 0).r), 0.0, 0.0, 0.0);
}
