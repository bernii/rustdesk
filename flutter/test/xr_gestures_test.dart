import 'dart:ui';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_hbb/mobile/xr/xr_gestures.dart';
import 'package:flutter_hbb/mobile/xr/xr_dictation.dart';

void main() {
  test('two-finger double-tap dictates without a secondary click', () {
    final buttons = <String>[];
    var dictations = 0;
    final gestures = XrGestures(
        move: (_) {},
        scroll: (_) {},
        zoom: (_) {},
        button: (secondary, down) => buttons.add('$secondary:$down'),
        dictate: () => dictations++,
        isDictating: () => false,
        stopDictation: () {},
        finishDictation: (_) {});
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

  test('holds and tap-then-move never drag; pointer gain uses smoothed speed',
      () {
    final buttons = <bool>[];
    final moves = <Offset>[];
    final gestures = XrGestures(
        move: moves.add,
        scroll: (_) {},
        zoom: (_) {},
        button: (_, down) => buttons.add(down),
        dictate: () {},
        isDictating: () => false,
        stopDictation: () {},
        finishDictation: (_) {});
    gestures.begin(Offset.zero, 0);
    gestures.update(1, const Offset(8, 0), 0, 450);
    gestures.cancel();
    gestures.cancel();
    expect(buttons, isEmpty);
    expect(moves.single, const Offset(2, 0));
    expect(XrGestures.gain(1.5), 3);
    gestures.begin(Offset.zero, 1000);
    gestures.end(1060);
    gestures.begin(Offset.zero, 1100);
    gestures.update(1, const Offset(8, 0), 0, 1120);
    gestures.end(1150);
    expect(buttons,
        [true, false]); // The first tap clicks; the second touch only moves.
    gestures.begin(Offset.zero, 2000);
    gestures.update(1, const Offset(10, 0), 0, 2100);
    gestures.update(1, const Offset(20, 0), 0, 2120);
    expect(
        moves.last.dx, closeTo(10 * XrGestures.gain(.1 * .6 + .5 * .4), 1e-9));
  });

  test(
      'third two-finger touch stops, then release or pinch chooses exactly once',
      () {
    for (final action in XrDictationAction.values) {
      bool active = false;
      int starts = 0, stops = 0;
      final actions = <XrDictationAction>[];
      final buttons = <bool>[], moves = <Offset>[], scrolls = <Offset>[];
      final gestures = XrGestures(
          move: moves.add,
          scroll: scrolls.add,
          button: (_, down) => buttons.add(down),
          zoom: (_) {},
          dictate: () {
            active = true;
            starts++;
          },
          isDictating: () => active,
          stopDictation: () => stops++,
          finishDictation: actions.add);
      for (final time in [0, 180]) {
        gestures.begin(Offset.zero, time);
        gestures.update(2, Offset.zero, 100, time + 10);
        gestures.end(time + 70);
      }
      expect(starts, 1);
      gestures.begin(Offset.zero, 1000);
      gestures.update(2, Offset.zero, 100, 1010);
      expect(stops, 1);
      expect(actions, isEmpty);
      final span = action == XrDictationAction.enter
          ? 70.0
          : action == XrDictationAction.discard
              ? 130.0
              : 100.0;
      gestures.update(2, const Offset(20, 0), span, 1100);
      gestures.update(2, const Offset(40, 0), span, 1200);
      gestures.update(1, Offset.zero, 0, 1500);
      expect(gestures.end(1510), isFalse);
      gestures.flushSecondaryTap(2000);
      expect(actions, [action]);
      expect(buttons, isEmpty);
      expect(moves, isEmpty);
      expect(scrolls, isEmpty);
    }
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
