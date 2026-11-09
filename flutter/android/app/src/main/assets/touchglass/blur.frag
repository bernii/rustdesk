// One axis of a separable Gaussian blur. Run horizontally from the full-size
// background into a half-size target, then vertically, to make the frost.

uniform sampler2D uSrc;
uniform vec2 uDstRes;   // px of the target
uniform vec2 uStep;     // uv offset of one target texel along the blur axis
uniform float uSigma;   // in target texels

out vec4 fragColor;

const int MAX_TAPS = 96;

void main() {
  vec2 uv = gl_FragCoord.xy / uDstRes;
  int radius = min(int(ceil(uSigma * 3.0)), MAX_TAPS);
  float k = -0.5 / max(uSigma * uSigma, 1e-4);
  vec3 acc = texture(uSrc, uv).rgb;
  float total = 1.0;
  for (int i = 1; i <= MAX_TAPS; i++) {
    if (i > radius) break;
    float w = exp(k * float(i * i));
    vec2 o = uStep * float(i);
    acc += (texture(uSrc, uv + o).rgb + texture(uSrc, uv - o).rgb) * w;
    total += 2.0 * w;
  }
  fragColor = vec4(acc / total, 1.0);
}
