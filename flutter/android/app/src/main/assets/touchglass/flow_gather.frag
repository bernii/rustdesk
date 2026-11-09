// The oils averaged over blocks of GATHER × GATHER cells, once a frame. Per
// block, means over its cells on the screen: how white it is (r, phase), how
// much boundary it holds (g, 1 − phase², which is 0 in either pure oil), and
// the black oil's dye (b, w × dye, and a, w × dye², with w = (1 − phase) / 2
// how black the cell is). Blurred, r is what each oil's attraction to its own
// kind follows (flow_force.frag); summed, the blocks keep the white oil's
// share of the screen and the black oil's tone (flow_measure.frag).

const int GATHER = 4;

out vec4 fragColor;

void main() {
  ivec2 first = ivec2(gl_FragCoord.xy) * GATHER;
  ivec2 size = textureSize(uFlow, 0);
  vec4 sum = vec4(0.0);
  float count = 0.0;
  for (int y = 0; y < GATHER; y++) {
    for (int x = 0; x < GATHER; x++) {
      ivec2 c = first + ivec2(x, y);
      if (c.x >= size.x || c.y >= size.y) continue;
      vec4 s = texelFetch(uFlow, c, 0);
      float black = clamp(0.5 - 0.5 * s.r, 0.0, 1.0);
      sum += vec4(s.r, max(0.0, 1.0 - s.r * s.r), black * s.a, black * s.a * s.a);
      count += 1.0;
    }
  }
  fragColor = sum / count;
}
