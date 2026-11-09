// The moving marble, every frame: white where the flow's phase says the white
// oil is; the black oil toned by the dye it carries; the still marble's fine
// fibres slid along the flow; then contrast, grain and tint as for the still
// one. Prepended with common.glsl, marble.glsl and
// flow.glsl.

uniform sampler2D uFlowDisplay; // the flow, filterable (flow_display.frag)
uniform sampler2D uDrift;      // flow_drift.frag: how far the oil has come since each copy of the fibres started over
uniform sampler2D uSource;     // marble_source.frag: r grey, g grain, b grain strength, a hue field
uniform sampler2D uSourceLow;  // its grey without fine detail
uniform vec2 uRes;             // px
uniform float uTime;           // s
uniform float uDetail;

out vec4 fragColor;

// How long the fibres follow the flow before starting over, s. The renderer
// restarts flow_drift.frag's copies on the same beat.
const float PERIOD = 4.0;

// The still marble's fine detail at a GL px position.
float fibres(vec2 glPx) {
  vec2 uv = glPx / uRes;
  return texture(uSource, uv).r - texture(uSourceLow, uv).r;
}

void main() {
  vec2 glPx = gl_FragCoord.xy;
  vec2 flowUv = glPx / (vec2(textureSize(uFlowDisplay, 0)) * uFlowCell);
  vec4 flow = texture(uFlowDisplay, flowUv);
  vec4 drift = texture(uDrift, flowUv) * uFlowCell;  // px

  // Two copies of the fibres ride with the oil, each starting over every
  // period, half a period apart, and crossfade so neither restart shows.
  // They're read from where the oil here was when its copy started.
  float phaseA = fract(uTime / PERIOD);
  float detail = mix(fibres(glPx - drift.xy), fibres(glPx - drift.zw), abs(2.0 * phaseA - 1.0));

  float blackOil = mix(uGreyBlack, flow.a, 0.6);
  float grey = mix(blackOil, uGreyWhite, 0.5 + 0.5 * flow.r) + uDetail * detail;
  vec4 still = texture(uSource, glPx / uRes);
  fragColor = vec4(marbleShade(grey, still.g, still.b, still.a), 1.0);
}
