import 'dart:ui';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_hbb/mobile/xr/xr_gestures.dart';

void main() {
  test('two-finger double-tap dictates without a secondary click', () {
    final buttons = <String>[];
    var dictations = 0;
    final gestures = XrGestures(
        move: (_) {},
        scroll: (_) {},
        zoom: (_) {},
        button: (secondary, down) => buttons.add('$secondary:$down'),
        dictate: () => dictations++);
    void tap(int start) {
      gestures.begin(Offset.zero, start);
      gestures.update(2, Offset.zero, 30, start + 10);
      gestures.end(start + 70);
    }

    tap(0);
    tap(180);
    gestures.flushSecondaryTap(1000);
    expect(dictations, 1);
    expect(buttons, isEmpty);
    tap(1200);
    gestures.flushSecondaryTap(1570);
    expect(buttons, ['true:true', 'true:false']);
  });

  test('drag cancellation releases once and slow movement stays precise', () {
    final buttons = <bool>[];
    final moves = <Offset>[];
    final gestures = XrGestures(
        move: moves.add,
        scroll: (_) {},
        zoom: (_) {},
        button: (_, down) => buttons.add(down),
        dictate: () {});
    gestures.begin(Offset.zero, 0);
    gestures.hold();
    gestures.update(1, const Offset(8, 0), 0, 450);
    gestures.cancel();
    gestures.cancel();
    expect(buttons, [true, false]);
    expect(moves.single, const Offset(2, 0));
    expect(XrGestures.gain(1.5), 3);
  });

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
