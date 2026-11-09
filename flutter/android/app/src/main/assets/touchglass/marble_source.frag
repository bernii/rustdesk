// The still marble's ingredients, rendered once, so the moving marble can be
// rebuilt cheaply every frame:
//   r  grey before contrast   g  grain value   b  grain strength   a  hue field

uniform vec2 uRes;  // px

out vec4 fragColor;

void main() {
  vec2 dp = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y) / uDensity;
  float hueField;
  float grey = marbleGrey(dp, hueField);
  float grainAlpha;
  float grain = marbleGrain(dp, grainAlpha);
  fragColor = vec4(grey, grain, grainAlpha, hueField);
}
