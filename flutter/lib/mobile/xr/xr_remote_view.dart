import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'xr_controller.dart';
import 'xr_gestures.dart';

class XrRemoteView extends StatefulWidget {
  const XrRemoteView(
      {super.key,
      required this.controller,
      required this.toolbar,
      required this.keyboard,
      required this.openKeyboard,
      required this.close});
  final XrController controller;
  final Widget toolbar, keyboard;
  final VoidCallback openKeyboard, close;
  @override
  State<XrRemoteView> createState() => _XrRemoteViewState();
}

class _XrRemoteViewState extends State<XrRemoteView>
    with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  XrController get xr => widget.controller;
  late final AnimationController _unlock;
  late final XrGestures _gestures;
  final Map<int, Offset> _points = {};
  final Set<int> _touches = {};
  Timer? _hold, _secondary, _momentum, _feedback;
  final Stopwatch _clock = Stopwatch()..start();
  int? _lockPointer;
  Offset _lockOrigin = Offset.zero;
  bool _unlockedFeedback = false;

  @override
  void initState() {
    super.initState();
    _unlock = AnimationController(
        vsync: this,
        duration: const Duration(seconds: 2),
        animationBehavior: AnimationBehavior.preserve)
      ..addStatusListener((status) {
        if (status == AnimationStatus.completed &&
            xr.holding &&
            _touches.length == 1) {
          xr.setLocked(false);
          setState(() => _unlockedFeedback = true);
          _feedback?.cancel();
          _feedback = Timer(const Duration(milliseconds: 600), () {
            if (mounted) setState(() => _unlockedFeedback = false);
          });
        }
      });
    _gestures = XrGestures(
        move: xr.move,
        button: xr.button,
        scroll: xr.scroll,
        zoom: (_) {},
        dictate: xr.dictate);
    xr.addListener(_changed);
    WidgetsBinding.instance.addObserver(this);
  }

  void _changed() {
    if (!xr.canInput) _cancel();
    if (mounted) setState(() {});
  }

  void _cancel() {
    _hold?.cancel();
    _secondary?.cancel();
    _momentum?.cancel();
    _gestures.cancel();
    _points.clear();
  }

  void _cancelLock() {
    _lockPointer = null;
    _unlock.reset();
    if (xr.holding) xr.setHolding(false);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) {
      _cancel();
      _cancelLock();
      xr.release();
    }
  }

  @override
  void dispose() {
    xr.removeListener(_changed);
    WidgetsBinding.instance.removeObserver(this);
    _cancel();
    _feedback?.cancel();
    _unlock.dispose();
    xr.release();
    super.dispose();
  }

  void _update(int time) {
    if (_points.isEmpty) return;
    final center =
        _points.values.reduce((a, b) => a + b) / _points.length.toDouble();
    final span = _points.length == 2
        ? (_points.values.first - _points.values.last).distance
        : 0.0;
    _gestures.update(_points.length, center, span, time);
  }

  void _down(PointerDownEvent e) {
    if (_lockPointer != null) return;
    _momentum?.cancel();
    _points[e.pointer] = e.localPosition;
    if (_points.length == 1) {
      _gestures.begin(e.localPosition, _clock.elapsedMilliseconds);
      _hold = Timer(const Duration(milliseconds: 450), _gestures.hold);
    } else {
      _hold?.cancel();
      _update(_clock.elapsedMilliseconds);
    }
  }

  void _move(PointerMoveEvent e) {
    if (!_points.containsKey(e.pointer)) return;
    _points[e.pointer] = e.localPosition;
    _update(_clock.elapsedMilliseconds);
  }

  void _up(PointerUpEvent e) {
    if (_points.remove(e.pointer) == null) return;
    if (_points.isNotEmpty) {
      _update(_clock.elapsedMilliseconds);
      return;
    }
    _hold?.cancel();
    final now = _clock.elapsedMilliseconds;
    if (_gestures.end(now)) {
      var velocity = Offset(_gestures.velocity.dx.clamp(-2500, 2500),
          _gestures.velocity.dy.clamp(-2500, 2500));
      var previous = now;
      _momentum = Timer.periodic(const Duration(milliseconds: 16), (timer) {
        if (!xr.canInput) {
          timer.cancel();
          return;
        }
        final time = _clock.elapsedMilliseconds;
        final seconds = ((time - previous) / 1000).clamp(0.0, .05);
        previous = time;
        xr.scroll(velocity * seconds);
        velocity *= math.exp(-6 * seconds);
        if (velocity.distance < 15) timer.cancel();
      });
    }
    _secondary?.cancel();
    _secondary = Timer(const Duration(milliseconds: 300),
        () => _gestures.flushSecondaryTap(_clock.elapsedMilliseconds));
  }

  Widget _pad({Widget? child}) => Listener(
      behavior: HitTestBehavior.opaque,
      onPointerDown: _down,
      onPointerMove: _move,
      onPointerUp: _up,
      onPointerCancel: (_) => _cancel(),
      child: child ?? const SizedBox.expand());

  Widget _lock() => Listener(
        behavior: HitTestBehavior.opaque,
        onPointerDown: (event) {
          _cancel();
          if (!xr.locked) {
            SystemChannels.textInput.invokeMethod<void>('TextInput.hide');
            xr.setLocked(true);
            return;
          }
          if (_touches.isNotEmpty) return;
          _lockPointer = event.pointer;
          _lockOrigin = event.position;
          xr.setHolding(true);
          _unlock.forward(from: 0);
        },
        onPointerMove: (event) {
          if (_lockPointer == event.pointer &&
              ((event.position - _lockOrigin).distance > 12 ||
                  !const Rect.fromLTWH(0, 0, 56, 56)
                      .contains(event.localPosition))) _cancelLock();
        },
        onPointerUp: (_) => _cancelLock(),
        onPointerCancel: (_) => _cancelLock(),
        child: Semantics(
            label: xr.locked
                ? 'Hold for two seconds to unlock controls'
                : 'Lock controls',
            child: SizedBox(
                width: 56,
                height: 56,
                child: AnimatedBuilder(
                    animation: _unlock,
                    builder: (_, __) => CustomPaint(
                        painter: _LockPainter(
                            xr.locked, xr.holding ? _unlock.value : null))))),
      );

  @override
  Widget build(BuildContext context) {
    const cream = Color(0xffd9541a), brown = Color(0xfffff4e9);
    final keyboardVisible = MediaQuery.of(context).viewInsets.bottom > 0;
    return PopScope<Object?>(
        canPop: false,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop && !xr.locked) widget.close();
        },
        child: Scaffold(
            backgroundColor: cream,
            body: Listener(
              onPointerDown: (e) {
                _touches.add(e.pointer);
                if (_touches.length > 1 && xr.holding) _cancelLock();
              },
              onPointerUp: (e) => _touches.remove(e.pointer),
              onPointerCancel: (e) {
                _touches.remove(e.pointer);
                _cancelLock();
              },
              child: Stack(children: [
                Positioned.fill(
                    child: _pad(
                        child: const Center(
                            child: IgnorePointer(
                                child: Column(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                      Icon(Icons.touch_app_outlined, size: 48, color: brown),
                      SizedBox(height: 12),
                      Text('Touchpad',
                          style: TextStyle(fontSize: 28, color: brown)),
                      SizedBox(height: 8),
                      Text(
                          'One finger to move · hold to drag\nTwo fingers to scroll or right-click\nTwo-finger double-tap to dictate',
                          textAlign: TextAlign.center,
                          style: TextStyle(color: brown, height: 1.6)),
                    ]))))),
                IgnorePointer(
                    ignoring: xr.locked,
                    child: SafeArea(
                        child: Column(children: [
                      Padding(
                          padding: const EdgeInsets.fromLTRB(20, 18, 80, 0),
                          child: Align(
                              alignment: Alignment.centerLeft,
                              child: Text(
                                  'rustdesk-xr\n${xr.source.width.toInt()} × ${xr.source.height.toInt()} source\n'
                                  '${xr.surface.width.toInt()} × ${xr.surface.height.toInt()} buffer → '
                                  '${xr.output.width.toInt()} × ${xr.output.height.toInt()} output',
                                  style: const TextStyle(
                                      color: brown, height: 1.5)))),
                      const Expanded(child: SizedBox()),
                      if (xr.status.isNotEmpty)
                        Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 20),
                            child: Text(xr.status,
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(color: brown))),
                      if (xr.transcript.isNotEmpty)
                        Padding(
                            padding: const EdgeInsets.all(12),
                            child: Text(xr.transcript,
                                maxLines: 4,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(color: brown))),
                      if (!keyboardVisible) ...[
                        Row(children: [
                          const SizedBox(width: 20),
                          const Text('Pointer speed',
                              style: TextStyle(color: brown)),
                          Expanded(
                              child: Slider(
                                  value: xr.sensitivity,
                                  min: .5,
                                  max: 8,
                                  divisions: 30,
                                  label:
                                      '${xr.sensitivity.toStringAsFixed(2)}×',
                                  onChanged: xr.setSensitivity)),
                          Text('${xr.sensitivity.toStringAsFixed(2)}×',
                              style: const TextStyle(color: brown)),
                          const SizedBox(width: 20)
                        ]),
                        if (!xr.fit) ...[
                          _panSlider('Horizontal viewport', xr.pan.dx,
                              (v) => xr.setPan(Offset(v, xr.pan.dy))),
                          _panSlider('Vertical viewport', xr.pan.dy,
                              (v) => xr.setPan(Offset(xr.pan.dx, v))),
                        ],
                        Wrap(
                            alignment: WrapAlignment.center,
                            spacing: 4,
                            children: [
                              TextButton.icon(
                                  onPressed: xr.dictate,
                                  icon: Icon(xr.recognizing
                                      ? Icons.stop
                                      : Icons.mic_none),
                                  label: Text(
                                      xr.recognizing ? 'Stop' : 'Dictate')),
                              TextButton.icon(
                                  onPressed: () {
                                    xr.cancelSpeech();
                                    widget.openKeyboard();
                                  },
                                  icon: const Icon(Icons.keyboard_outlined),
                                  label: const Text('Keys')),
                              TextButton(
                                  onPressed: xr.toggleFit,
                                  child: Text(
                                      xr.fit ? '1:1 / pan' : 'Fit desktop')),
                              TextButton(
                                  onPressed: xr.toggleFullSourceBuffer,
                                  child: Text(xr.fullSourceBuffer && xr.fit
                                      ? 'Full-res buffer: on'
                                      : 'Full-res buffer: off')),
                              TextButton(
                                  onPressed: xr.clearText,
                                  child: const Text('Clear text')),
                              TextButton(
                                  onPressed: xr.reopen,
                                  child: const Text('Wake')),
                              TextButton(
                                  onPressed: xr.toggleEnabled,
                                  child: const Text('Exit XR')),
                            ]),
                      ],
                      Listener(
                          onPointerDown: (_) => xr.cancelSpeech(),
                          child: widget.toolbar),
                    ]))),
                if (xr.locked) Positioned.fill(child: _pad()),
                Positioned(
                    top: MediaQuery.of(context).padding.top + 8,
                    right: 12,
                    child: _lock()),
                if (xr.holding || _unlockedFeedback)
                  Positioned.fill(
                      child: IgnorePointer(
                          child: Center(
                              child: AnimatedBuilder(
                                  animation: _unlock,
                                  builder: (_, __) => Column(
                                          mainAxisSize: MainAxisSize.min,
                                          children: [
                                            SizedBox(
                                                width: 128,
                                                height: 128,
                                                child: CustomPaint(
                                                    painter: _LockPainter(
                                                        xr.locked,
                                                        _unlock.value))),
                                            Text(
                                                xr.holding
                                                    ? 'Hold to unlock'
                                                    : 'Unlocked',
                                                style: const TextStyle(
                                                    color: brown,
                                                    fontSize: 20)),
                                          ]))))),
                widget.keyboard,
              ]),
            )));
  }

  Widget _panSlider(String label, double value, ValueChanged<double> change) =>
      Row(children: [
        const SizedBox(width: 20),
        SizedBox(width: 100, child: Text(label)),
        Expanded(child: Slider(value: value, onChanged: change)),
      ]);
}

class _LockPainter extends CustomPainter {
  _LockPainter(this.locked, this.progress);
  final bool locked;
  final double? progress;
  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.scale(size.width / 44, size.height / 44);
    final paint = Paint()..color = Colors.white;
    canvas.drawCircle(const Offset(22, 22), 20, paint);
    if (progress != null) {
      paint
        ..style = PaintingStyle.stroke
        ..strokeWidth = 3
        ..strokeCap = StrokeCap.round
        ..color = const Color(0xffd9561a);
      canvas.drawArc(const Rect.fromLTWH(2, 2, 40, 40), -math.pi / 2,
          2 * math.pi * progress!, false, paint);
    }
    canvas.translate(12.5, 12.5);
    canvas.scale(19 / 24);
    paint
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.8
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..color = const Color(0xff4a1b05);
    canvas.drawRRect(
        RRect.fromRectAndRadius(
            const Rect.fromLTRB(5, 11, 19, 21), const Radius.circular(2.5)),
        paint);
    final path = Path()
      ..moveTo(8, 11)
      ..lineTo(8, 8);
    if (locked) {
      path.cubicTo(8, 2.67, 16, 2.67, 16, 8);
      path.lineTo(16, 11);
    } else {
      path.cubicTo(8, 3.1, 14.2, 2.3, 15.7, 6.5);
    }
    canvas.drawPath(path, paint);
    canvas.restore();
  }

  @override
  bool shouldRepaint(_LockPainter old) =>
      old.locked != locked || old.progress != progress;
}
