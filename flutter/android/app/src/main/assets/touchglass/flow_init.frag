// Starts the flow from the still marble, at rest: the white oil in its
// lightest pools, the black oil everywhere else, carrying the marble's grey as
// dye.
//
// The oils start already separated, with soft boundaries along the marble's
// shapes. Starting from the mid-greys instead puts them in Cahn–Hilliard's
// unstable range, and they split at once into a maze of fine stripes.

uniform sampler2D uSourceLow;  // the marble's grey without fine detail
uniform vec2 uRes;             // px
uniform float uGreySplit;      // the grey where the white oil begins

out vec4 fragColor;

void main() {
  float grey = texture(uSourceLow, gl_FragCoord.xy * uFlowCell / uRes).r;
  float phase = tanh(6.0 * (grey - uGreySplit) / (uGreyWhite - uGreyBlack));
  fragColor = vec4(phase, 0.0, 0.0, grey);
}
