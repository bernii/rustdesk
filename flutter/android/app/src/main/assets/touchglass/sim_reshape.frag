// When the magnets move the edge, the oil moves with it: three passes on the
// simulation grid, with two blurs between them, once a frame while the edge
// is pulled or springing back.
//
// The edge itself moves at once: cells the oil now covers get its meniscus,
// and cells it leaves give up their oil. Whatever volume that adds or takes
// is taken from or given back to the oil just behind the edge, spread by the
// blur, so the volume is unchanged and the shallow-water step does the rest:
// pulling the edge out leaves a dip that runs inward as a wake, and pushing
// it in raises a bulge that spreads and settles.
//
// The moving edge also drags extra oil along (Magnet wake): cells it newly
// covers or thickens gain uWake times more than the move gives them, up to
// twice their rest thickness, from the oil behind; and the oil it gives back
// gathers toward it, over about uWakeBand. The bigger dips and bulges are
// what the shallow-water step turns into waves, and the volume stays exact.
// A push on the oil's velocity did this before, but it piled oil past the
// simulation's thickness limit, which clipped it away.
//
// Oil comes only from cells that hold some, in proportion to what they hold,
// and none gives more than TAKE of it. A newly covered cell next to a
// finger's dry spot gets only what the oil around it can give, the rest of
// its meniscus stays dry, and no cell ever goes below dry. Oil goes back to
// every cell of the puddle near the edge, in proportion to its rest
// thickness and a little more, dry or not: given only to cells that held
// oil, what an edge pulled in next to a finger's dry spot gave up piled onto
// the few wet cells there, past the simulation's thickness limit, which
// clipped it away.
//
// uPass 0: per cell, r the volume the move adds (δ, × plateau), and the
//   cell's weight for taking oil from (g) and for giving it to (b). Blurred,
//   g and b are those weights around each cell.
// uPass 1: δ, limited to TAKE of the taking weight around it, divided by
//   that weight (r) where it's oil to take, and divided by the giving weight
//   around it (g) where it's oil to give, so that, blurred again and times
//   each cell's own weights, every cell's volume is spread over the oil near
//   it and sums back to δ.
// uPass 2: the new state: the oil as the move left it, less what the limit
//   held back, less what's taken and plus what's given.

uniform sampler2D uRestOld;  // the rest data before the move
uniform sampler2D uDelta;    // pass 0's output
uniform sampler2D uAround;   // pass 0's output blurred
uniform sampler2D uSpread;   // pass 1's output blurred
uniform int uSpreadScale;    // cells per texel of the two blurs: 2 where they're halved (sim_halve.frag), else 1
uniform int uPass;
uniform float uWake;         // extra oil the edge drags, × what it gains
uniform float uWakeBand;     // px

out vec4 fragColor;

const float TAKE = 0.9;

void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  vec4 s = state(c);
  vec4 now = rest(c);
  vec4 before = texelFetch(uRestOld, c, 0);
  bool wasInside = before.b > 0.5;
  bool inside = now.b > 0.5;
  // η stays with cells that stay inside, new cells start at rest, and none
  // goes below dry: a dry spot where the rest thickness drops stays dry.
  float hBefore = wasInside ? before.r + s.r : 0.0;
  float hAfter = inside ? max(now.r + (wasInside ? s.r : 0.0), 0.0) : 0.0;
  float dragged = uWake * max(hAfter - hBefore, 0.0);
  hAfter = max(hAfter, min(hAfter + dragged, 2.0 * now.r));

  if (uPass == 0) {
    float takeFrom = inside ? min(hBefore, hAfter) : 0.0;
    float giveTo = inside ? (now.r + 0.25) * (1.0 + uWake * exp(-now.g / uWakeBand)) : 0.0;
    fragColor = vec4(hAfter - hBefore, takeFrom, giveTo, 0.0);
    return;
  }
  ivec2 blurred = c / max(uSpreadScale, 1);
  vec4 delta = texelFetch(uDelta, c, 0);
  vec2 around = max(texelFetch(uAround, blurred, 0).gb, 1e-3);
  float taken = delta.r > 0.0 ? min(delta.r, TAKE * around.x) : 0.0;
  float given = min(delta.r, 0.0);
  if (uPass == 1) {
    fragColor = vec4(taken / around.x, given / around.y, 0.0, 0.0);
    return;
  }
  if (!inside) {
    fragColor = vec4(0.0);
    return;
  }
  vec2 spread = texelFetch(uSpread, blurred, 0).rg;
  float h = hAfter - (delta.r - taken - given) - delta.g * spread.x - delta.b * spread.y;

  vec2 velocity = wasInside ? s.gb : vec2(0.0);
  fragColor = vec4(h - now.r, velocity, wasInside ? s.a : 0.0);
}
