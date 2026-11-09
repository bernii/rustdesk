// Keeps the oil's volume. The simulation conserves it, but what piles past
// its thickness limit is clipped away, and the pad's own moves add and take
// oil at its edge. So once a frame the oil is summed on the GPU and whatever
// is missing, or extra, is spread back over the puddle. Volumes are in
// plateau thicknesses times cells, on the simulation grid.
//
// The sums run 4 × 4 blocks at a time, many small passes rather than a few
// long loops, so the GPU sums every block of a level at once.
//
// uPass 0: one texel per BLOCK × BLOCK cells, summing over the oil's cells:
//   r its oil, rest + η; g its weight for taking a correction, min(oil,
//   rest), so dry spots stay dry and piles grow no faster than the plateau;
//   b its oil at rest.
// uPass 1: one texel per BLOCK × BLOCK texels of the level below, summed.
//   With uWithTarget, a is the volume to keep (uTarget's r).
// uPass 2: the state, each cell's η plus its share of what's missing, by its
//   weight, at most half its oil a frame.

uniform int uPass;
uniform sampler2D uBlocks;  // the level below, for pass 1
uniform sampler2D uSum;     // the total, for pass 2
uniform sampler2D uTarget;  // r: the volume to keep, for pass 1
uniform int uWithTarget;

out vec4 fragColor;

const int BLOCK = 4;

void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  if (uPass == 0) {
    ivec2 size = textureSize(uState, 0);
    vec4 sum = vec4(0.0);
    for (int y = 0; y < BLOCK; y++) {
      for (int x = 0; x < BLOCK; x++) {
        ivec2 cell = c * BLOCK + ivec2(x, y);
        if (cell.x >= size.x || cell.y >= size.y) continue;
        vec4 r = texelFetch(uRest, cell, 0);
        if (r.b < 0.5) continue;
        float h = max(r.r + texelFetch(uState, cell, 0).r, 0.0);
        sum += vec4(h, min(h, r.r), r.r, 0.0);
      }
    }
    fragColor = sum;
    return;
  }
  if (uPass == 1) {
    ivec2 size = textureSize(uBlocks, 0);
    vec4 sum = vec4(0.0);
    for (int y = 0; y < BLOCK; y++) {
      for (int x = 0; x < BLOCK; x++) {
        ivec2 texel = c * BLOCK + ivec2(x, y);
        if (texel.x < size.x && texel.y < size.y) sum += texelFetch(uBlocks, texel, 0);
      }
    }
    fragColor = vec4(sum.rgb, uWithTarget == 1 ? texelFetch(uTarget, ivec2(0), 0).r : 0.0);
    return;
  }
  vec4 s = state(c);
  vec4 r = rest(c);
  if (r.b < 0.5) {
    fragColor = s;
    return;
  }
  vec4 total = texelFetch(uSum, ivec2(0), 0);
  float share = clamp((total.a - total.r) / max(total.g, 1.0), -0.5, 0.5);
  float h = max(r.r + s.r, 0.0);
  fragColor = vec4(s.r + share * min(h, r.r), s.gba);
}
