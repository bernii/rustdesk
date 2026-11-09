// Per frame: the surface for the composite, in a filterable texture.
//   r  η (units of plateau thickness)
//   gb its gradient per px, screen axes (y down)
//   a  its Laplacian per px²

uniform float uCell;  // px per cell

out vec4 fragColor;

void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  float eta = state(c).r;
  float east = state(c + EAST).r;
  float west = state(c - EAST).r;
  float north = state(c + NORTH).r;
  float south = state(c - NORTH).r;
  fragColor = vec4(eta,
                   (east - west) / (2.0 * uCell),
                   (south - north) / (2.0 * uCell),
                   (east + west + north + south - 4.0 * eta) / (uCell * uCell));
}
