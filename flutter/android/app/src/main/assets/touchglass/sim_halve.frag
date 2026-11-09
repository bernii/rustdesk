// A simulation-grid texture at half resolution, each texel the mean of a 2×2
// block. The Android app blurs the edge magnet's spread at half resolution;
// read back a block at a time (sim_reshape.frag's uSpreadScale), halving,
// blurring and doubling stays symmetric, so the spread still sums back
// exactly.

uniform sampler2D uSrc;

out vec4 fragColor;

void main() {
  ivec2 c = 2 * ivec2(gl_FragCoord.xy);
  ivec2 last = textureSize(uSrc, 0) - 1;
  fragColor = 0.25 * (texelFetch(uSrc, min(c, last), 0) + texelFetch(uSrc, min(c + ivec2(1, 0), last), 0)
                      + texelFetch(uSrc, min(c + ivec2(0, 1), last), 0) + texelFetch(uSrc, min(c + ivec2(1, 1), last), 0));
}
