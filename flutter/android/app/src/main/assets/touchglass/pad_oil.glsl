// touch-glass-exp's oil.glsl for the trackpad, whose oil isn't centered on
// the screen and changes shape: the rest outline sits at uOilCenter. The
// passes that include oil.glsl take this instead; nothing else differs.
//
// The oil puddle at rest: its squircle outline and meniscus profile. Shared
// by the simulation and the composite; prepended after common.glsl.
//
// Coordinates are device px relative to the screen center, y down.
//
// Finger magnets pull the outline: an edge near a finger sticks to it, out to
// a finger beyond the oil or in to one inside, after the liquid glass
// recorder's border magnet. Each lowers the distance by how far it pulls,
// all of it over the touch (its contact ellipse) and fading smoothly outside
// its outline over the magnet's width, so the edge moves with the whole
// touch however it lies, and a finger laid along the border pulls that whole
// stretch of it. For a small touch that's a round Gaussian bump. The screen's
// own edge stops the oil, with a rounded wall. Earlier versions moved each
// point of the edge to the touch's outline along its normal: that left sharp
// slots where a touch overlapped the edge, glitches where the nearest point of
// the edge jumps from side to side near a corner, and no way to drag the edge
// out once a touch overlapped it. The passes that bake the oil at rest set no
// magnets.

uniform vec2 uHalfSize;      // half extents of the oil at rest, px
uniform vec2 uOilCenter;     // its center, px from the screen center
uniform float uRadius;       // corner extent: where the curve leaves the edge, px
uniform float uSmooth;       // squircle exponent
uniform float uThickness;    // plateau thickness, px
uniform float uSlope;        // tan(contact angle)

#define MAX_MAGNETS 4
uniform int uMagnetCount;
uniform vec4 uMagnets[MAX_MAGNETS];     // xy the touch's center (px, as above), z how far it pulls the edge (px, + outward), w how far outside the touch the pull fades (px)
uniform vec4 uMagnetAxes[MAX_MAGNETS];  // xy the touch's major axis (unit), zw its radii along and across it (px)
uniform vec4 uScreenWall;               // xy half the area the oil may reach (px), z its corner radius, w how soft the oil meets it (px)

// Signed distance to the squircle-cornered rectangle (negative inside), with
// the outward unit normal. Corners use an Lp norm; dividing by its gradient
// length keeps distances close to Euclidean near the edge.
float restSdf(vec2 p, out vec2 normal) {
  p -= uOilCenter;
  vec2 s = vec2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
  vec2 q = abs(p) - uHalfSize + uRadius;
  if (q.x > 0.0 && q.y > 0.0) {
    float len = pow(pow(q.x, uSmooth) + pow(q.y, uSmooth), 1.0 / uSmooth);
    vec2 grad = pow(q / max(len, 1e-4), vec2(uSmooth - 1.0));
    float gradLen = length(grad);
    normal = s * grad / gradLen;
    return (len - uRadius) / gradLen;
  }
  normal = q.x > q.y ? vec2(s.x, 0.0) : vec2(0.0, s.y);
  return max(q.x, q.y) - uRadius;
}

// How far outside an ellipse with the given radii q lies, px (0 inside):
// close to the true distance near it.
float ellipseOutside(vec2 q, vec2 radii) {
  float k0 = length(q / radii);
  if (k0 <= 1.0) return 0.0;
  return k0 * (k0 - 1.0) / length(q / (radii * radii));
}

// How far the magnets lower the distance at p, px, with its slope. The slope
// takes the distance outside each ellipse to grow at 1 px per px along its
// outward normal, which it does near the outline.
float magnetLift(vec2 p, out vec2 slope) {
  float lift = 0.0;
  slope = vec2(0.0);
  for (int i = 0; i < MAX_MAGNETS; i++) {
    if (i >= uMagnetCount) break;
    vec4 m = uMagnets[i];
    vec4 axis = uMagnetAxes[i];
    vec2 v = vec2(-axis.y, axis.x);
    vec2 r = p - m.xy;
    vec2 q = vec2(dot(r, axis.xy), dot(r, v));
    float outside = ellipseOutside(q, axis.zw);
    float k = 1.0 / (m.w * m.w);
    float pull = m.z * exp(-outside * outside * k);
    lift += pull;
    if (outside > 0.0) {
      vec2 n = normalize(q / (axis.zw * axis.zw));
      slope -= 2.0 * k * outside * pull * (n.x * axis.xy + n.y * v);
    }
  }
  return lift;
}

// The outline as the magnets pull it, with its outward unit normal. The
// normal tilts with the slope of the lift; dividing by the gradient's length
// keeps distances near the edge about right, so the meniscus keeps its width
// around a bulge. Then the screen's wall: a smooth maximum with it, so the
// oil flattens against it, rounded, rather than passing it.
float oilSdf(vec2 p, out vec2 normal) {
  float d = restSdf(p, normal);
  if (uMagnetCount == 0) return d;
  vec2 liftSlope;
  float lift = magnetLift(p, liftSlope);
  vec2 grad = normal - liftSlope;
  float len = max(length(grad), 0.25);
  float pulled = (d - lift) / len;
  normal = grad / len;

  vec2 q = abs(p) - uScreenWall.xy + uScreenWall.z;
  float wall = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uScreenWall.z;
  vec2 wallNormal = sign(p) * (q.x > 0.0 && q.y > 0.0 ? normalize(q) : q.x > q.y ? vec2(1.0, 0.0) : vec2(0.0, 1.0));
  float k = uScreenWall.w;
  float h = clamp(0.5 + 0.5 * (wall - pulled) / k, 0.0, 1.0);
  normal = normalize(mix(normal, wallNormal, h));
  return mix(pulled, wall, h) + k * h * (1.0 - h);
}

// True where the magnets change how the oil looks near p: where they move
// the edge by a tenth of a px or more, out to its meniscus and shadow.
bool magnetNear(vec2 p) {
  if (uMagnetCount == 0) return false;
  vec2 n;
  float d = restSdf(p, n);
  vec2 slope;
  float lift = abs(magnetLift(p, slope));
  return lift > 0.1 && abs(d) < lift + 2.0 * uThickness;
}

// Oil thickness at a distance `inward` from the contact line, with its first
// and second derivatives along that distance. A tanh meniscus: it rises with
// the slope of the contact angle and levels off at the plateau thickness.
void restProfile(float inward, out float h, out float slope, out float bend) {
  float t = tanh(min(inward * uSlope / uThickness, 10.0));
  h = uThickness * t;
  slope = uSlope * (1.0 - t * t);
  bend = -2.0 * uSlope * uSlope * t * (1.0 - t * t) / uThickness;
}
