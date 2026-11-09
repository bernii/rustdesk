// The oil when the pad itself moves, on its springs, as it locks, as
// dictation opens the text field and as the keyboard rises. Only the
// container moves: the oil stays where it was, as thick as it was. Cells
// the pad gives up lose their oil, which pad_volume.frag spreads over the
// puddle, so a shrinking pad's oil stands deeper; against the edge that
// moved in, it's thick where the meniscus should be thin, and flows back
// in as a wave. Cells the pad newly covers are open space the oil flows
// out into, with only a thin film on them (PRECURSOR of the plateau), where
// the oil was before: friction grows as 1/h², so over bare surface the
// oil's front would barely creep. Each frame the edge moves, the film just
// behind it is made even again: next to the moving meniscus it flows a
// little each frame, and left a faint stripe behind each step. Only there,
// within FILM_BAND of the edge: made even everywhere, the oil's front
// running out over the film, thin too, was knocked back each frame the edge
// crept on its springs, and a lock stalled for a second before filling.
// The film is thicker in some patches than others, so the oil's front races
// over some and drags on others and comes in uneven; and the newly covered
// cells are marked as refilling (the state's a), so the oil runs back into
// them with less friction, unevenly too (sim_step.frag).
//
// The edge magnet's reshape (sim_reshape.frag) moves oil locally instead,
// from just behind the edge, which suits a finger's short pull. The pad
// moves hundreds of dp in a fraction of a second: oil moved locally piled
// against the shrinking edge past what the simulation holds, which clipped
// it away.

uniform sampler2D uRestOld;  // the rest data before the move

out vec4 fragColor;

const float PRECURSOR = 0.2;
const float FILM_BAND = 30.0;  // px in from the edge
// The film's patches: from 0.4 to 1.6 times PRECURSOR, about this many cells
// across (80 dp at the app's 6 px cells).
const float FILM_PATCH = 35.0;

float film(ivec2 c) {
  return PRECURSOR * (0.4 + 1.2 * clamp(0.5 + gradNoise(vec2(c) / FILM_PATCH, 517u), 0.0, 1.0));
}

void main() {
  ivec2 c = ivec2(gl_FragCoord.xy);
  vec4 now = rest(c);
  if (now.b < 0.5) {
    fragColor = vec4(0.0);
    return;
  }
  vec4 before = texelFetch(uRestOld, c, 0);
  if (before.b < 0.5) {
    fragColor = vec4(film(c) - now.r, 0.0, 0.0, 1.0);
    return;
  }
  vec4 s = state(c);
  float h = max(before.r + s.r, 0.0);
  if (h < 1.5 * film(c) && now.g < FILM_BAND) h = film(c);
  fragColor = vec4(h - now.r, s.gba);
}
