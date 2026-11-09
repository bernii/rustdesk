import 'dart:ui';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_hbb/mobile/xr/xr_gestures.dart';

void main() {
  test('fit includes the desktop and 1:1 pan reaches every source edge', () {
    const source = Size(3840, 2160), output = Size(2560, 1080);
    final fitted = xrViewport(source, output, true, Offset.zero);
    expect(fitted.contains(xrClearTarget(fitted, output, 1).center), isTrue);
    expect(xrViewport(source, output, true, Offset.zero),
        const Rect.fromLTWH(320, 0, 1920, 1080));
    expect(xrViewport(source, output, false, Offset.zero),
        const Rect.fromLTWH(0, 0, 3840, 2160));
    expect(xrViewport(source, output, false, const Offset(1, 1)),
        const Rect.fromLTWH(-1280, -1080, 3840, 2160));
  });
}
