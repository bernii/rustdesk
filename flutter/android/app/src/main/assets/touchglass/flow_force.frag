// The oils' velocity, once a frame. It's carried along by itself (momentum),
// pushed by the currents and by the device's motion, and dragged by friction.
// The pressure solve that follows (flow_divergence, flow_pressure,
// flow_project) removes whatever would compress the oil, so a push that has
// nowhere to go just raises the pressure.
//
// Currents: a slowly changing incompressible flow. Part of it carries both
// oils; the counterflow part pushes them opposite ways and shears them along
// their boundary.
//
// Device motion reaches the oils as a body force: gravity's pull along the
// tilted screen minus the device's own acceleration. Both oils feel it, but
// they differ in density, and in a closed, full container only that
// difference moves them. The heavier oil runs along the pull, the lighter one
// gives way, and their boundary sloshes, fingers and breaks up.
//
// A shake swings that pull back and forth too fast to carry the oils far: at a
// few swings a second they'd only jiggle in place. What a shake really leaves
// is churn, the eddies it stirs up, which fold the big blobs over and tear
// them into shreds that the tension then pulls back into drops. The churn is
// a second set of currents, smaller and quicker, as strong as the shake was
// and dying away after it.
//
// Attraction: each oil drifts toward more of its own kind nearby, as if
// buoyant in a field that points to where its kind is gathered (the phase
// blurred over the attraction's range). Shreds and drops rejoin the blobs
// near them and protrusions draw back in. Farther out, at twice the
// range, each oil is crowded by its own kind instead, as magnetized
// ferrofluid is: a blob grown too big for its place is pushed apart from
// within, so the oils keep to a few blobs of about one size instead of all
// merging into one.

uniform float uDt;           // s
uniform float uTime;         // s
uniform float uSpeed;        // cells/s at a friction of 1/s
uniform float uCounterflow;  // 0: the oils move together, 1: against each other
uniform float uSwirl;        // size of the currents, cells
uniform float uChange;       // how fast the currents change, 1/s
uniform float uFriction;     // 1/s
uniform vec2 uBuoyancy;      // the white oil's push per unit phase, cells/s²: body force × density difference / 2
uniform float uChurn;        // cells/s
uniform float uChurnSize;    // cells
uniform float uChurnTurns;   // how far the churn's eddies have shifted, turns
uniform sampler2D uNear;     // flow_gather.frag's blocks, blurred over the attraction's range: r is the phase nearby
uniform sampler2D uFar;      // the same over twice the range
uniform float uAttraction;   // cells/s at a friction of 1/s
uniform float uAttractionRange;  // cells
uniform float uCrowding;     // the far field's weight against the near one
uniform uint uSeed;

out vec4 fragColor;

// A changing stream function, roughly in [−1, 1], at cell position x: eddies
// of the given size, shifted by `turns`.
float stream(vec2 x, float size, float turns, uint seed) {
  vec2 p = x / size;
  float a = turns * 6.2831853;
  return cos(a) * gradNoise(p, seed) + sin(a) * gradNoise(p + vec2(17.3, 5.1), seed + 7u)
       + 0.5 * gradNoise(2.0 * p + vec2(0.0, turns), seed + 13u);
}

// The velocity a stream function makes, (∂ψ/∂y, −∂ψ/∂x), with ψ itself in
// value. Velocities from stream functions never compress or expand the oil.
vec2 curl(vec2 x, float size, float turns, uint seed, out float value) {
  value = stream(x, size, turns, seed);
  float e = stream(x + vec2(1.0, 0.0), size, turns, seed);
  float w = stream(x - vec2(1.0, 0.0), size, turns, seed);
  float n = stream(x + vec2(0.0, 1.0), size, turns, seed);
  float s = stream(x - vec2(0.0, 1.0), size, turns, seed);
  return 0.5 * vec2(n - s, w - e);
}

// Currents of the given size: part carry both oils, part (`counterflow`) is
// the curl of phase × a second stream function, which pushes white and black
// opposite ways and along their boundary. Noise slopes are about 1.5 per
// eddy, hence the scaling to `speed`.
vec2 currents(vec2 x, float phase, vec2 gradPhase, float speed, float size, float turns, float counterflow,
              uint seed) {
  float psi;
  vec2 together = curl(x, size, turns, seed, psi);
  vec2 counter = curl(x, size, turns, seed + 1u, psi);
  vec2 against = phase * counter + psi * vec2(gradPhase.y, -gradPhase.x);
  return speed * size / 1.5 * mix(together, against, counterflow);
}

void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  vec4 s = flowAt(c);
  vec2 x = vec2(c) + 0.5;

  // Momentum: the velocity upstream carries on here.
  vec2 carried = flowSample(x - s.gb * uDt).gb;

  vec2 gradPhase = 0.5 * vec2(flowAt(c + ivec2(1, 0)).r - flowAt(c - ivec2(1, 0)).r,
                              flowAt(c + ivec2(0, 1)).r - flowAt(c - ivec2(0, 1)).r);
  vec2 current = currents(x, s.r, gradPhase, uSpeed, uSwirl, uTime * uChange, uCounterflow, uSeed * 3u + 1u);
  // The churn after a shake: half stirs both oils, half shears them apart.
  if (uChurn > 0.0) {
    current += currents(x, s.r, gradPhase, uChurn, uChurnSize, uChurnTurns, 0.5, uSeed * 3u + 101u);
  }

  // Buoyancy: each oil's share of the density difference times the body force.
  vec2 buoyancy = s.r * uBuoyancy;

  // Attraction: the slope of the phase nearby less the crowding far off,
  // across half a block (blocks are flow_gather.frag's 4 cells). At the edge
  // of a big blob the near slope is about 1.6 / range, hence the scaling.
  if (uAttraction > 0.0) {
    vec2 size = 4.0 * vec2(textureSize(uNear, 0));
    const float h = 2.0;
    vec2 e = (x + vec2(h, 0.0)) / size;
    vec2 w = (x - vec2(h, 0.0)) / size;
    vec2 n = (x + vec2(0.0, h)) / size;
    vec2 so = (x - vec2(0.0, h)) / size;
    vec2 near = vec2(texture(uNear, e).r - texture(uNear, w).r, texture(uNear, n).r - texture(uNear, so).r);
    vec2 far = vec2(texture(uFar, e).r - texture(uFar, w).r, texture(uFar, n).r - texture(uFar, so).r);
    vec2 slope = (near - uCrowding * far) / (2.0 * h);
    buoyancy += s.r * uAttraction * 0.6 * uAttractionRange * slope;
  }

  // du/dt = (current + buoyancy) − friction × u, implicit so any friction is
  // stable. With no buoyancy the oils settle at current / friction.
  vec2 velocity = (carried + uDt * (current + buoyancy)) / (1.0 + uDt * uFriction);
  fragColor = vec4(s.r, velocity, s.a);
}
