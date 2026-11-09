// One substep of the shallow-water model, in a single pass.
//
// Momentum: each face velocity accelerates down the pressure gradient,
// diffuses (viscosity), then loses speed to friction with the surface below.
// Pressure, in units of the plateau thickness, is the surface height (gravity)
// minus capillary length² × its Laplacian (surface tension) plus the push of
// the fingers. Continuity: oil moves through the faces with the new velocities,
// carrying the upwind cell's thickness so a dry cell can't give oil away.
//
// A cell stores the velocities of its east and north faces, but continuity
// also needs its west and south faces, so it computes all four from the old
// state. Neighbors compute the shared faces identically. That repeats a
// little arithmetic and saves a whole render pass per substep.
//
// G-force: the phone's motion reaches the oil as a body force along the
// screen, gravity less the phone's acceleration, which varies across the
// screen as the phone turns; and as the oil's weight relative to rest,
// gravity into the screen less the acceleration. The weight scales gravity's
// part of the pressure, so waves run slower where the oil is lighter, and
// where it varies it pushes the oil from the heavier side toward the lighter,
// by −(h/2)∇weight per unit mass. With all of them zero, the step is as it
// was.
//
// A refill (touch-glass-ui): where the oil flows back over ground it left, as
// when the trackpad grows after the keyboard closes, the state's a is 1 as
// the ground opens and fades over a second or two. The oil there slides with
// less friction, by more in some places than others, from a slowly drifting
// noise, so it runs in faster and its front comes in uneven, some parts ahead
// of others. Nothing stirs it, so the oil that has filled in lies still.
// touch-glass-exp sets none of it, and nothing changes.

#define MAX_FINGERS 5

uniform float uDt;              // s
uniform float uWaveSpeed2;      // (cells/s)²
uniform float uCapillary2;      // capillary length², cells²
uniform float uViscosity;       // cells²/s
uniform float uSettling;        // 1/s at plateau thickness
uniform float uEdgeAbsorption;  // 1/s at the contact line
uniform float uEdgeBand;        // px
uniform float uCell;            // px per cell
uniform float uMeniscus;
uniform float uMaxThickness;    // the time step is sized for oil up to this, × plateau
uniform int uFingerCount;
uniform vec4 uFingers[MAX_FINGERS];       // xy cell position, z push
uniform vec4 uFingerShapes[MAX_FINGERS];  // xy unit major axis, z major radius, w minor radius (cells)
uniform vec2 uCenter;           // the screen's center, cells
uniform vec2 uPush;             // G-force at the center, cells/s²
uniform vec4 uSpin;             // its change per cell, 1/s²: ∂x/∂x, ∂x/∂y, ∂y/∂x, ∂y/∂y
uniform vec3 uWeight;           // the oil's weight relative to rest, less 1: at the center, and its change per cell
uniform float uTime;            // s, for the refill's unevenness
uniform float uRefillFade;      // 1/s: how fast a refill fades
uniform float uRefillEase;      // how much of the friction a fresh refill takes away, at most, 0 to 1
uniform float uRefillVary;      // how unevenly, 0 to 1: 1 eases some places not at all
uniform float uRefillSize;      // cells: how big its patches are

out vec4 fragColor;

// glass.frag's, which draws the edge from the weight.
const float WEIGHT_MIN = 0.1;
const float WEIGHT_MAX = 2.5;

vec2 bodyForce(vec2 x) {
  vec2 r = x - uCenter;
  return uPush + vec2(dot(uSpin.xy, r), dot(uSpin.zw, r));
}

float weight(vec2 x) {
  return clamp(1.0 + uWeight.x + dot(uWeight.yz, x - uCenter), WEIGHT_MIN, WEIGHT_MAX);
}

// How much of the friction a refill a takes away at x: uRefillEase, less in
// patches of a slowly drifting noise.
float refillEase(float a, vec2 x) {
  if (a <= 0.0) return 0.0;
  float spot = clamp(0.5 + gradNoise(x / uRefillSize + vec2(0.07, -0.05) * uTime, 911u), 0.0, 1.0);
  return uRefillEase * a * (1.0 - uRefillVary * spot);
}

// The G-force f on the face between cells a and b (positive toward b), with
// their thicknesses. It eases off as the cell it pushes oil into nears
// uMaxThickness, where the step would clip the oil away, so a pile it raises
// spreads back from the edge instead of growing past that. It starts well
// short, as the oil it has set moving runs on into the pile.
float onFace(float f, float hA, float hB) {
  return f * (1.0 - smoothstep(0.4 * uMaxThickness, 0.65 * uMaxThickness, f > 0.0 ? hB : hA));
}

// Finger pressure at cell position x. Each finger is an ellipse, the contact
// patch the touchscreen reports. A push of 1 holds the oil right down to the
// surface below; the meniscus collar pulls oil up around the finger.
float fingerPressure(vec2 x) {
  float p = 0.0;
  for (int i = 0; i < MAX_FINGERS; i++) {
    if (i >= uFingerCount) break;
    vec4 f = uFingers[i];
    vec4 shape = uFingerShapes[i];
    vec2 d = x - f.xy;
    float r = length(vec2(dot(d, shape.xy) / shape.z, dot(d, vec2(-shape.y, shape.x)) / shape.w));
    float core = 1.0 - smoothstep(0.35, 1.0, r);
    float collar = smoothstep(0.7, 1.15, r) * (1.0 - smoothstep(1.15, 1.9, r));
    p += f.z * core - uMeniscus * collar;
  }
  return p;
}

// Pressure at cell c from all but gravity: surface tension, from its η and
// its four neighbors' η, and the fingers, by its rest thickness. Gravity's
// part, η times the oil's weight, is taken at each face.
float otherPressure(ivec2 c, float eta, float neighbors, float restThickness) {
  return -uCapillary2 * (neighbors - 4.0 * eta) + restThickness * fingerPressure(vec2(c) + 0.5);
}

// The momentum step for one face, from its old velocity u0 and the sum of its
// four neighbors'. `a` and `b` are the rest data of the cells on either side;
// h is the face's thickness, `force` the G-force along it, and `ease` how
// much of the friction a refill takes away.
float faceVelocity(float u0, float neighbors, float dPressure, float force, float h, float ease, vec4 a, vec4 b) {
  float u = u0 + uDt * (force - uWaveSpeed2 * dPressure) + uDt * uViscosity * (neighbors - 4.0 * u0);
  // Friction grows as 1/h², like viscous flow in a thin film, so oil thinned
  // by a finger or near the contact line barely moves. Implicit, so stable.
  // The edge's absorption damps waves running into it, so only flow across
  // the edge: a face across it, where the distance in from the edge changes
  // by a whole cell, takes all of it, and one along it none. Damping flow
  // along the edge too held the oil there back as the rest slid past, under
  // a tilt, and the dots there sheared away from the rows further in.
  float across = uCell > 0.0 ? min(abs(b.g - a.g) / uCell, 1.0) : 1.0;
  float absorb = uEdgeAbsorption * across * across * (1.0 - smoothstep(0.0, uEdgeBand, min(a.g, b.g)));
  h = max(h, 0.05);
  u /= 1.0 + uDt * (uSettling * (1.0 - ease) / (h * h) + absorb);
  // Keep each face under a quarter cell per step, so the four faces together
  // can never drain more than a cell holds. Without this a fast finger
  // over-drains cells, the clamp at zero puts the oil back, and the puddle
  // gains volume until it runs away.
  float maxSpeed = 0.25 / uDt;
  u = clamp(u, -maxSpeed, maxSpeed);
  // Surface tension pins the contact line: nothing flows out of the puddle.
  return a.b < 0.5 || b.b < 0.5 ? 0.0 : u;
}

void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  // The 13 cells the four faces need: this one, the four neighbors, the four
  // diagonals and the cells two steps away along each axis.
  vec4 s = state(c);
  vec4 e = state(c + EAST);
  vec4 w = state(c - EAST);
  vec4 n = state(c + NORTH);
  vec4 so = state(c - NORTH);
  vec4 ne = state(c + EAST + NORTH);
  vec4 nw = state(c - EAST + NORTH);
  vec4 se = state(c + EAST - NORTH);
  vec4 sw = state(c - EAST - NORTH);
  vec4 ee = state(c + 2 * EAST);
  vec4 ww = state(c - 2 * EAST);
  vec4 nn = state(c + 2 * NORTH);
  vec4 ss = state(c - 2 * NORTH);
  vec4 rc = rest(c);
  vec4 re = rest(c + EAST);
  vec4 rw = rest(c - EAST);
  vec4 rn = rest(c + NORTH);
  vec4 rs = rest(c - NORTH);

  float p = otherPressure(c, s.r, e.r + w.r + n.r + so.r, rc.r);
  float pE = otherPressure(c + EAST, e.r, ee.r + s.r + ne.r + se.r, re.r);
  float pW = otherPressure(c - EAST, w.r, s.r + ww.r + nw.r + sw.r, rw.r);
  float pN = otherPressure(c + NORTH, n.r, ne.r + nw.r + nn.r + s.r, rn.r);
  float pS = otherPressure(c - NORTH, so.r, se.r + sw.r + s.r + ss.r, rs.r);

  float h = rc.r + s.r;
  float hE = re.r + e.r;
  float hW = rw.r + w.r;
  float hN = rn.r + n.r;
  float hS = rs.r + so.r;

  // The weight at the five cells, and the G-force at the four faces: the
  // body force less the push from heavier to lighter.
  vec2 x = vec2(c) + 0.5;
  float g = weight(x);
  float gE = weight(x + vec2(EAST));
  float gW = weight(x - vec2(EAST));
  float gN = weight(x + vec2(NORTH));
  float gS = weight(x - vec2(NORTH));
  float hE2 = 0.5 * (h + hE);
  float hW2 = 0.5 * (hW + h);
  float hN2 = 0.5 * (h + hN);
  float hS2 = 0.5 * (hS + h);
  float fE = onFace(bodyForce(x + vec2(0.5, 0.0)).x - uWaveSpeed2 * 0.5 * hE2 * (gE - g), h, hE);
  float fW = onFace(bodyForce(x - vec2(0.5, 0.0)).x - uWaveSpeed2 * 0.5 * hW2 * (g - gW), hW, h);
  float fN = onFace(bodyForce(x + vec2(0.0, 0.5)).y - uWaveSpeed2 * 0.5 * hN2 * (gN - g), h, hN);
  float fS = onFace(bodyForce(x - vec2(0.0, 0.5)).y - uWaveSpeed2 * 0.5 * hS2 * (g - gS), hS, h);

  // The refill at each face, the stronger of its two cells'.
  float eE = refillEase(max(s.a, e.a), x + vec2(0.5, 0.0));
  float eW = refillEase(max(w.a, s.a), x - vec2(0.5, 0.0));
  float eN = refillEase(max(s.a, n.a), x + vec2(0.0, 0.5));
  float eS = refillEase(max(so.a, s.a), x - vec2(0.0, 0.5));

  float uE = faceVelocity(s.g, e.g + w.g + n.g + so.g, 0.5 * (g + gE) * (e.r - s.r) + pE - p, fE, hE2, eE, rc, re);
  float uW = faceVelocity(w.g, s.g + ww.g + nw.g + sw.g, 0.5 * (gW + g) * (s.r - w.r) + p - pW, fW, hW2, eW, rw, rc);
  float vN = faceVelocity(s.b, e.b + w.b + n.b + so.b, 0.5 * (g + gN) * (n.r - s.r) + pN - p, fN, hN2, eN, rc, rn);
  float vS = faceVelocity(so.b, se.b + sw.b + s.b + ss.b, 0.5 * (gS + g) * (s.r - so.r) + p - pS, fS, hS2, eS, rs, rc);

  if (rc.b < 0.5) {
    fragColor = vec4(0.0, uE, vN, 0.0);
    return;
  }
  float refill = s.a / (1.0 + uDt * uRefillFade);
  float fluxE = uE * (uE > 0.0 ? h : hE);
  float fluxW = uW * (uW > 0.0 ? hW : h);
  float fluxN = vN * (vN > 0.0 ? h : hN);
  float fluxS = vS * (vS > 0.0 ? hS : h);
  float eta = s.r - uDt * (fluxE - fluxW + fluxN - fluxS);
  // The lower clamp only catches rounding (the momentum step limits outflow).
  // The upper one is a safety net: beyond it waves would outrun the time step.
  fragColor = vec4(clamp(eta, -rc.r, uMaxThickness - rc.r), uE, vN, refill);
}
