import 'dart:math' as math;
import 'dart:ui';

/// The probe's gesture arbitration, in dp and monotonic event milliseconds.
class XrGestures {
  XrGestures(
      {required this.move,
      required this.button,
      required this.scroll,
      required this.zoom,
      required this.dictate});
  final void Function(Offset) move, scroll;
  final void Function(bool secondary, bool down) button;
  final void Function(double) zoom;
  final void Function() dictate;
  int _count = 0, _maxCount = 0, _started = 0, _previous = 0;
  int _lastTap = -1000, _secondaryTap = -1, _lastScroll = 0;
  Offset _position = Offset.zero, _start = Offset.zero;
  double _span = 0, _startSpan = 0;
  bool _moved = false, _dragging = false, _pinching = false;
  bool _scrolling = false, _secondTap = false;
  Offset velocity = Offset.zero;

  static double gain(double speed) {
    final r = ((speed - .08) / 1.42).clamp(0.0, 1.0);
    return .25 + 2.75 * r * r * (3 - 2 * r);
  }

  void begin(Offset position, int time) {
    flushSecondaryTap(time);
    if (_dragging) button(false, false);
    _reset();
    _count = _maxCount = 1;
    _position = _start = position;
    _started = _previous = time;
    _secondTap = time - _lastTap < 300;
    velocity = Offset.zero;
  }

  void update(int fingers, Offset position, double span, int time) {
    if (fingers != _count) {
      if (_dragging) {
        button(false, false);
        _dragging = false;
      }
      _count = fingers;
      _maxCount = math.max(_maxCount, fingers);
      _position = _start = position;
      _span = _startSpan = span;
      _previous = time;
      return;
    }
    final delta = position - _position;
    final distance = (position - _start).distance;
    if (_count == 1 && _maxCount == 1) {
      if (distance > 5) _moved = true;
      if (_moved) {
        if (_secondTap && !_dragging) {
          _dragging = true;
          button(false, true);
        }
        move(delta * gain(delta.distance / math.max(1, time - _previous)));
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

  void hold() {
    if (_count == 1 && _maxCount == 1 && !_moved && !_dragging) {
      _moved = _dragging = true;
      button(false, true);
    }
  }

  bool end(int time) {
    final fling = _scrolling && time - _lastScroll < 100;
    if (_dragging) {
      button(false, false);
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
        _lastTap = time;
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
    if (_dragging) button(false, false);
    _reset();
    _lastTap = -1000;
    _secondaryTap = -1;
    velocity = Offset.zero;
  }

  void _reset() {
    _count = _maxCount = 0;
    _moved = _dragging = _pinching = _scrolling = _secondTap = false;
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
