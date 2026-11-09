// Rest state on the simulation grid: thickness, distance in from the contact
// line and the inside mask. Rebuilt when the oil's shape changes.

uniform vec2 uRes;    // screen, px
uniform float uCell;  // px per cell

out vec4 fragColor;

void main() {
  vec2 px = vec2(gl_FragCoord.x * uCell, uRes.y - gl_FragCoord.y * uCell);
  vec2 normal;
  float d = oilSdf(px - 0.5 * uRes, normal);
  float inward = max(-d, 0.0);
  float h;
  float slope;
  float bend;
  restProfile(inward, h, slope, bend);
  // The grid's outer ring is never oil, even where a magnet pulls the edge
  // close to the screen's: the edge magnet's blurs need it empty.
  ivec2 c = ivec2(gl_FragCoord.xy);
  bool ring = any(equal(c, ivec2(0))) || any(greaterThanEqual(c, ivec2(ceil(uRes / uCell)) - 1));
  fragColor = vec4(h / uThickness, inward, d < 0.0 && !ring ? 1.0 : 0.0, 0.0);
}
