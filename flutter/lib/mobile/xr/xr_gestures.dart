import 'dart:math' as math;
import 'dart:ui';

Rect xrViewport(Size source, Size output, bool fit, Offset pan) {
  if (source.isEmpty || output.isEmpty) return Rect.zero;
  final scale = fit
      ? math.min(output.width / source.width, output.height / source.height)
      : 1.0;
  final size = Size(source.width * scale, source.height * scale);
  return Rect.fromLTWH(
    size.width <= output.width
        ? (output.width - size.width) / 2
        : -(size.width - output.width) * pan.dx,
    size.height <= output.height
        ? (output.height - size.height) / 2
        : -(size.height - output.height) * pan.dy,
    size.width,
    size.height,
  );
}

Rect xrClearTarget(Rect viewport, Size output, double density) {
  final right =
      math.min(output.width - 64 * density, viewport.right - 8 * density);
  final top = math.max(16 * density, viewport.top + 16 * density);
  return Rect.fromLTWH(right - 116 * density, top, 116 * density, 40 * density);
}
