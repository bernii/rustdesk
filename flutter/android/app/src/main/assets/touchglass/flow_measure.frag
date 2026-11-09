// The whole screen, into one texel, from flow_gather.frag's blocks: mean
// phase (r), mean boundary weight (g), and the black oil's dye, mean (b) and
// spread (a). Measured once as the oils start and once a frame after, so
// flow_step.frag can hand back whatever the white oil has lost and keep the
// black oil's tone.

uniform sampler2D uGathered;
uniform ivec2 uCells;  // the flow grid, cells

out vec4 fragColor;

const int GATHER = 4;

void main() {
  ivec2 size = textureSize(uGathered, 0);
  vec4 sum = vec4(0.0);
  float black = 0.0;
  float total = 0.0;
  for (int y = 0; y < size.y; y++) {
    for (int x = 0; x < size.x; x++) {
      // Blocks on the right and top edges hang off the grid.
      ivec2 cover = min(ivec2(GATHER), uCells - ivec2(x, y) * GATHER);
      float n = float(cover.x * cover.y);
      vec4 block = texelFetch(uGathered, ivec2(x, y), 0);
      sum += block * n;
      black += (0.5 - 0.5 * block.r) * n;
      total += n;
    }
  }
  float dye = sum.b / max(black, 1e-3);
  float spread = sqrt(max(sum.a / max(black, 1e-3) - dye * dye, 0.0));
  fragColor = vec4(sum.rg / total, dye, spread);
}
