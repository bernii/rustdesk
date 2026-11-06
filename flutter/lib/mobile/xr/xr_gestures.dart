import 'dart:math' as math;
import 'dart:ui';
import 'xr_dictation.dart';

/// Touch-glass acceleration and scroll arbitration, without either drag gesture.
class XrGestures {
  XrGestures(
      {required this.move,
      required this.button,
      required this.scroll,
      required this.zoom,
      required this.dictate,
      required this.isDictating,
      required this.stopDictation,
      required this.finishDictation});
  final void Function(Offset) move, scroll;
  final void Function(bool secondary, bool down) button;
  final void Function(double) zoom;
  final void Function() dictate, stopDictation;
  final bool Function() isDictating;
  final void Function(XrDictationAction) finishDictation;
  int _count = 0, _maxCount = 0, _started = 0, _previous = 0;
  int _secondaryTap = -1, _lastScroll = 0;
  Offset _position = Offset.zero, _start = Offset.zero;
  double _span = 0, _startSpan = 0, _speed = -1;
  bool _moved = false, _pinching = false, _scrolling = false;
  bool _dictationGesture = false, _dictationResolved = false;
  Offset velocity = Offset.zero;

  static double gain(double speed) {
    final r = ((speed - .08) / 1.42).clamp(0.0, 1.0);
    return .25 + 2.75 * r * r * (3 - 2 * r);
  }

  void begin(Offset position, int time) {
    flushSecondaryTap(time);
    _reset();
    _count = _maxCount = 1;
    _position = _start = position;
    _started = _previous = time;
    velocity = Offset.zero;
  }

  void update(int fingers, Offset position, double span, int time) {
    if (fingers != _count) {
      _count = fingers;
      _maxCount = math.max(_maxCount, fingers);
      _position = _start = position;
      _span = _startSpan = span;
      _previous = time;
      if (fingers == 2 &&
          _maxCount == 2 &&
          isDictating() &&
          !_dictationGesture) {
        _dictationGesture = true;
        _secondaryTap = -1;
        stopDictation();
      }
      return;
    }
    if (_dictationGesture) {
      if (!_dictationResolved &&
          fingers == 2 &&
          _maxCount == 2 &&
          (span - _startSpan).abs() > math.max(10, _startSpan * .2)) {
        _dictationResolved = true;
        finishDictation(span < _startSpan
            ? XrDictationAction.enter
            : XrDictationAction.discard);
      }
      return;
    }
    final delta = position - _position;
    final distance = (position - _start).distance;
    if (_count == 1 && _maxCount == 1) {
      if (distance > 5) _moved = true;
      if (_moved) {
        final speed = delta.distance / math.max(1, time - _previous);
        _speed = _speed < 0 ? speed : _speed * .6 + speed * .4;
        move(delta * gain(_speed));
      }
    } else if (_count == 2 && _maxCount == 2) {
      if (!_scrolling && (span - _startSpan).abs() > 10) {
        _pinching = _moved = true;
      }
      if (!_pinching && distance > 5) _scrolling = _moved = true;
      if (_pinching && _span > 0) {
        zoom(span / _span);
      } else if (_scrolling) {
        scroll(delta);
        velocity = delta / (math.max(1, time - _previous) / 1000);
        _lastScroll = time;
      }
    } else if (_count >= 3 && distance > 5) {
      _moved = true;
    }
    _position = position;
    _span = span;
    _previous = time;
  }

  bool end(int time) {
    final fling = _scrolling && time - _lastScroll < 100;
    if (_dictationGesture) {
      if (!_dictationResolved && _maxCount == 2) {
        finishDictation(XrDictationAction.send);
      }
    } else if (!_moved && time - _started < 350) {
      if (_maxCount == 2) {
        if (_secondaryTap >= 0 && time - _secondaryTap < 300) {
          _secondaryTap = -1;
          dictate();
        } else {
          flushSecondaryTap(time);
          _secondaryTap = time;
        }
      } else if (_maxCount == 1) {
        button(false, true);
        button(false, false);
      }
    }
    _reset();
    return fling;
  }

  void flushSecondaryTap(int time) {
    if (_secondaryTap >= 0 && time - _secondaryTap >= 300) {
      _secondaryTap = -1;
      button(true, true);
      button(true, false);
    }
  }

  void cancel() {
    _reset();
    _secondaryTap = -1;
    velocity = Offset.zero;
  }

  void _reset() {
    _count = _maxCount = 0;
    _moved = _pinching = _scrolling = false;
    _dictationGesture = _dictationResolved = false;
    _speed = -1;
  }
}

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
