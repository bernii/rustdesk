// How much the oils' velocity compresses or expands them, per cell: the
// right-hand side of the pressure solve.

out vec4 fragColor;

void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  float divergence = 0.5 * (flowAt(c + ivec2(1, 0)).g - flowAt(c - ivec2(1, 0)).g
                          + flowAt(c + ivec2(0, 1)).b - flowAt(c - ivec2(0, 1)).b);
  fragColor = vec4(divergence, 0.0, 0.0, 0.0);
}
