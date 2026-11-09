// Simulation grid helpers, prepended to the sim passes after oil.glsl.
//
// The grid covers the screen at one cell per few px, in texture space (GL y
// up). The state texture holds per cell:
//   r  η: oil thickness minus its rest thickness, in units of the plateau
//   g  u: velocity through the cell's east face, cells/s
//   b  v: velocity through the cell's north face, cells/s
//   a  a refill: 1 where the oil flows back over ground it left, fading
//      (sim_step.frag; touch-glass-ui), 0 otherwise
// The rest texture holds r rest thickness / plateau, g distance in from the
// contact line in px, b 1 inside the oil.

uniform sampler2D uState;
uniform sampler2D uRest;

const ivec2 EAST = ivec2(1, 0);
const ivec2 NORTH = ivec2(0, 1);

vec4 state(ivec2 c) {
  return texelFetch(uState, clamp(c, ivec2(0), textureSize(uState, 0) - 1), 0);
}

vec4 rest(ivec2 c) {
  return texelFetch(uRest, clamp(c, ivec2(0), textureSize(uRest, 0) - 1), 0);
}
