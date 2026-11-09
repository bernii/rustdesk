// One substep of the two oils, white and black, that won't mix. The velocity
// is set once a frame (flow_force.frag, then the pressure solve); here the
// oils move along it and pull apart. They're carried the whole frame's way in
// the first substep, in one sharp sample: every resample smears them a
// little, and many small ones would blur a fast flow into grey.
//
// The phase is carried by the flow, and Cahn–Hilliard dynamics sort it back
// apart: a phase-separating mixture lowers its energy by sorting into the two
// pure oils, and surface tension keeps their boundary short. Stretched
// filaments thicken or pinch into drops, and blobs round off. The currents
// and the device's motion keep folding them together and the tension keeps
// refusing, so the oils stay in slow, constant motion without ever mixing.
//
// Cahn–Hilliard alone sorts slowly at the scale of blobs: it only moves oil
// by diffusion. Stirred for long enough, the white oil was torn into shreds
// that dissolved into the black faster than they could gather, and the
// screen settled to a grey mix. So the tension also pulls every cell toward
// the nearer pure oil, which turns mixed patches back into pure ones.
// Whatever the white oil loses that way is handed back along every boundary,
// so it keeps its share of the screen and its blobs grow back. The pull is
// local on purpose: with Allen–Cahn's curvature term as well, every blob
// shrank by its curvature and, over half an hour, all but one dissolved.
//
// The black oil's dye drifts the same way: where white oil turns black it
// brings its light dye along, and stirring evens the smoke out, until the
// black oil is a flat light grey. So the dye is pulled back to the mean and
// spread the black oil started with.

uniform float uDt;        // s
uniform float uCarry;     // s of flow this substep carries the oils: the frame's in the first, none after
uniform float uMobility;  // Cahn–Hilliard mobility, cells²/s
uniform float uWidth2;    // boundary width², cells² (one cell; flow_display.frag softens it)
uniform sampler2D uMeasure;   // flow_measure.frag, now: r mean phase, g mean boundary weight, b and a the black oil's dye
uniform sampler2D uBaseline;  // the same, as the oils started
uniform float uSharpen;   // how fast a cell turns pure, 1/s
uniform float uRestore;   // how fast the white oil's share and the black oil's tone come back, 1/s

out vec4 fragColor;

void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  const ivec2 E = ivec2(1, 0);
  const ivec2 N = ivec2(0, 1);
  vec4 s = flowAt(c);
  float e = flowAt(c + E).r;
  float w = flowAt(c - E).r;
  float n = flowAt(c + N).r;
  float so = flowAt(c - N).r;
  float ne = flowAt(c + E + N).r;
  float nw = flowAt(c - E + N).r;
  float se = flowAt(c + E - N).r;
  float sw = flowAt(c - E - N).r;
  float ee = flowAt(c + 2 * E).r;
  float ww = flowAt(c - 2 * E).r;
  float nn = flowAt(c + 2 * N).r;
  float ss = flowAt(c - 2 * N).r;

  // The flow carries the oil, and its dye: this cell's come from upstream.
  vec4 carried = uCarry > 0.0 ? flowSampleSharp(vec2(c) + 0.5 - s.gb * uCarry) : s;

  // Cahn–Hilliard: chemical potential μ = c³ − c − W²∇²c, and ∂c/∂t = M∇²μ.
  float lap = e + w + n + so - 4.0 * s.r;
  float lapE = ee + s.r + ne + se - 4.0 * e;
  float lapW = s.r + ww + nw + sw - 4.0 * w;
  float lapN = ne + nw + nn + s.r - 4.0 * n;
  float lapS = se + sw + s.r + ss - 4.0 * so;
  float mu = s.r * s.r * s.r - s.r - uWidth2 * lap;
  float muE = e * e * e - e - uWidth2 * lapE;
  float muW = w * w * w - w - uWidth2 * lapW;
  float muN = n * n * n - n - uWidth2 * lapN;
  float muS = so * so * so - so - uWidth2 * lapS;
  float phase = carried.r + uDt * uMobility * (muE + muW + muN + muS - 4.0 * mu);

  // Toward a pure oil, and the white oil's lost share back along boundaries.
  phase -= uDt * uSharpen * (s.r * s.r * s.r - s.r);
  vec4 now = texelFetch(uMeasure, ivec2(0), 0);
  vec4 start = texelFetch(uBaseline, ivec2(0), 0);
  phase += uDt * uRestore * (start.r - now.r) / max(now.g, 0.01) * max(0.0, 1.0 - s.r * s.r);

  // The black oil's tone: its dye's mean and spread back toward the start.
  float toned = start.b + (carried.a - now.b) * start.a / max(now.a, 1e-3);
  float dye = mix(carried.a, toned, min(uDt * uRestore, 1.0));

  fragColor = vec4(clamp(phase, -1.5, 1.5), s.gb, dye);
}
