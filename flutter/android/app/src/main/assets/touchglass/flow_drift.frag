// How far the oil in each cell has come since the marble's fibres last started
// over, once a frame, so marble_motion.frag slides the fibres exactly as far
// as the oil went. Sliding them by the velocity times the time since the
// restart is only right for a steady flow: a shake swings the velocity back
// and forth, and the fibres would swing hundreds of pixels while the oil
// barely moves.
//
// Two copies, half a period apart, as marble_motion.frag crossfades them; the
// renderer zeroes one when it starts over. Per cell, in cells:
//   rg  copy A's drift
//   ba  copy B's

uniform sampler2D uDrift;  // last frame's, filterable
uniform float uDt;         // s
uniform vec2 uRestart;     // 1 for a copy that starts over this frame

out vec4 fragColor;

void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  vec2 x = vec2(c) + 0.5;
  vec2 moved = flowAt(c).gb * uDt;
  // The oil here came from upstream, and had drifted that far already.
  vec4 drift = texture(uDrift, (x - moved) / vec2(textureSize(uDrift, 0))) + moved.xyxy;
  fragColor = drift * (1.0 - uRestart.xxyy);
}
