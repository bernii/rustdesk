import 'dart:async';
import 'dart:convert';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import '../../models/platform_model.dart';
import '../../common.dart' show showToast;
import 'xr_session.dart';
import '../../models/model.dart';
import 'xr_gestures.dart';
import 'xr_video.dart';

class XrController extends ChangeNotifier {
  XrController(this.ffi) {
    _channel.setMethodCallHandler(_event);
    XrSession.onEvent = _sessionEvent;
    HardwareKeyboard.instance.addHandler(_localKey);
    ffi.ffiModel.addListener(_sessionChanged);
    ffi.cursorModel.addListener(_cursorChanged);
  }
  final FFI ffi;
  static const _channel = MethodChannel('rustdesk-xr');
  bool active = false, enabled = true, available = false, locked = true;
  bool listening = false, recognizing = false, holding = false, fit = true;
  bool fullSourceBuffer = true;
  bool _disposed = false, _begun = false;
  double sensitivity = 3, density = 1;
  ui.Size output = ui.Size.zero, surface = ui.Size.zero;
  ui.Size viewportSize = ui.Size.zero;
  ui.Offset pan = const ui.Offset(.5, .5), pointer = ui.Offset.zero;
  String status = '', transcript = '', _draft = '';
  final Set<bool> _buttons = {};
  bool _clearPressed = false;
  ui.Image? _cursorImage;
  ui.Rect _source = ui.Rect.zero;
  ui.Rect get source => _source;
  bool get canInput =>
      active &&
      !ffi.closed &&
      ffi.ffiModel.keyboard &&
      !ffi.ffiModel.viewOnly &&
      !ffi.inputModel.isViewCamera;
  bool get canType => canInput && ffi.inputModel.keyboardInputAllowed;
  String get session => ffi.sessionId.toString();

  Future<void> _call(String method,
      [Map<String, dynamic> args = const {}]) async {
    try {
      await _channel.invokeMethod(method, {'session': session, ...args});
    } on PlatformException catch (error) {
      if (!_disposed) {
        status = error.message ?? 'XR platform error';
        notifyListeners();
      }
    } on MissingPluginException {
      if (!_disposed) {
        status = 'XR mode is unavailable in this build';
        notifyListeners();
      }
    }
  }

  bool _localKey(KeyEvent event) {
    if (active && event is KeyDownEvent && recognizing) cancelSpeech();
    return false;
  }

  Future<void> begin() async {
    if (_disposed || _begun) return;
    _begun = true;
    try {
      final state = await _channel
          .invokeMapMethod<String, dynamic>('begin', {'session': session});
      if (!_disposed && state != null) _state(state);
    } on PlatformException catch (e) {
      if (_disposed) return;
      status = e.message ?? 'XR mode could not start';
      notifyListeners();
    } on MissingPluginException {
      if (_disposed) return;
      status = 'XR mode is unavailable in this build';
      notifyListeners();
    }
  }

  Future<void> _event(MethodCall call) async {
    if (_disposed) return;
    final args = Map<String, dynamic>.from(call.arguments as Map);
    if (args['session'] != session) return;
    switch (call.method) {
      case 'state':
        _state(args);
        break;
      case 'speech':
        if (!recognizing || !canType) return;
        final text = args['text'] as String? ?? '';
        transcript = _draft + text;
        if (args['complete'] == true) {
          recognizing = false;
          _draft = transcript;
          if (text.isNotEmpty) {
            await bind.sessionInputString(
                sessionId: ffi.sessionId, value: text);
          }
        }
        if (_disposed) return;
        _overlay();
        notifyListeners();
        break;
      case 'speechStatus':
        status = args['status'] as String? ?? '';
        recognizing = args['active'] == true;
        notifyListeners();
        break;
      case 'listening':
        listening = args['listening'] == true;
        _overlay();
        notifyListeners();
        break;
      case 'clear':
        clearText();
        break;
    }
  }

  void _state(Map<String, dynamic> state) {
    final next = state['active'] == true;
    if (active && !next) {
      release();
      XrVideo.stop();
    }
    if (!active && next) {
      locked = true;
      holding = false;
      pointer = ui.Offset(source.width / 2, source.height / 2);
    }
    active = next;
    enabled = state['enabled'] == true;
    available = state['available'] == true;
    sensitivity =
        (state['sensitivity'] as num? ?? sensitivity).toDouble().clamp(.5, 8);
    density = (state['density'] as num? ?? 1).toDouble();
    output = ui.Size((state['outputWidth'] as num? ?? 0).toDouble(),
        (state['outputHeight'] as num? ?? 0).toDouble());
    surface = ui.Size((state['surfaceWidth'] as num? ?? 0).toDouble(),
        (state['surfaceHeight'] as num? ?? 0).toDouble());
    viewportSize = ui.Size(
        (state['viewportWidth'] as num? ?? surface.width).toDouble(),
        (state['viewportHeight'] as num? ?? surface.height).toDouble());
    final error = state['error'] as String? ?? '';
    if (error.isNotEmpty && error != status) {
      status = error;
      showToast(error);
    }
    _sessionChanged();
    if (active) {
      _overlay();
      _cursorChanged();
    }
    notifyListeners();
  }

  void _sessionEvent(String id, Map<String, dynamic> event) {
    if (id != session || _disposed || !_begun) return;
    final type = event['type'] as String? ?? '';
    if (event['name'] == 'msgbox' &&
        (event['title'] == 'Connection Error' ||
            type.contains('error') ||
            type == 'restarting' ||
            type == 'restarting-show')) {
      release();
      XrVideo.stop();
      active = false;
      _begun = false;
      unawaited(_call('end'));
      notifyListeners();
    }
  }

  void _sessionChanged() {
    if (_disposed) return;
    if (active && !canInput) release();
    final pi = ffi.ffiModel.pi;
    final rect = pi.getDisplayRect(pi.currentDisplay) ?? ui.Rect.zero;
    if (rect != _source) {
      if (active) release();
      _source = rect;
      pointer = ui.Offset(rect.width / 2, rect.height / 2);
    }
    if (active && !rect.isEmpty) {
      try {
        XrVideo.configure(session, pi.currentDisplay, rect.width.toInt(),
            rect.height.toInt());
      } catch (error) {
        status = 'XR video bridge unavailable: $error';
        active = false;
        XrVideo.stop();
        unawaited(_call('enabled', {'value': false}));
      }
      _overlay();
    }
    notifyListeners();
  }

  Future<void> _cursorChanged() async {
    if (!active || _disposed) return;
    final model = ffi.cursorModel;
    final remote = model.offset - _source.topLeft;
    if (ui.Rect.fromLTWH(0, 0, source.width, source.height).contains(remote)) {
      pointer = remote;
    }
    final image = model.image;
    if (!identical(image, _cursorImage)) {
      _cursorImage = image;
      final cache = model.cache;
      final hotx = cache?.hotxOrigin ?? model.hotx;
      final hoty = cache?.hotyOrigin ?? model.hoty;
      final width = cache?.width ?? image?.width ?? 0;
      final height = cache?.height ?? image?.height ?? 0;
      final bytes = await image?.toByteData(format: ui.ImageByteFormat.png);
      if (_disposed || !active || !identical(image, _cursorImage)) return;
      unawaited(_call('overlay', {
        'values': {
          'cursor': bytes?.buffer.asUint8List(),
          'hotx': hotx,
          'hoty': hoty,
          'cursorWidth': width,
          'cursorHeight': height,
        }
      }));
    }
    _overlay();
  }

  void _overlay() {
    if (_disposed || !active) return;
    unawaited(_call('overlay', {
      'values': {
        'width': source.width,
        'height': source.height,
        'x': pointer.dx,
        'y': pointer.dy,
        'fit': fit,
        'fullSourceBuffer': fullSourceBuffer,
        'panX': pan.dx,
        'panY': pan.dy,
        'cursorEmbedded': ffi.canvasModel.cursorEmbedded,
        'locked': locked,
        'holding': holding,
        'listening': listening,
        'transcript': transcript,
      }
    }));
  }

  void move(ui.Offset delta) {
    if (!canInput || source.isEmpty) return;
    pointer = ui.Offset(
        (pointer.dx + delta.dx * sensitivity).clamp(0, source.width - 1),
        (pointer.dy + delta.dy * sensitivity).clamp(0, source.height - 1));
    ffi.inputModel.moveMouse(pointer.dx + source.left, pointer.dy + source.top);
    final canvas = ffi.canvasModel;
    ffi.cursorModel.moveLocal(pointer.dx * canvas.scale + canvas.x,
        pointer.dy * canvas.scale + canvas.y);
    _overlay();
  }

  bool get _overClear {
    final viewport = xrViewport(source.size, viewportSize, fit, pan);
    if (viewport.isEmpty) return false;
    final position =
        viewport.topLeft + pointer * (viewport.width / source.width);
    return xrClearTarget(viewport, viewportSize, density).contains(position);
  }

  void button(bool secondary, bool down) {
    if (!secondary && down && _overClear) {
      _clearPressed = true;
      clearText();
      return;
    }
    if (!secondary && !down && _clearPressed) {
      _clearPressed = false;
      return;
    }
    if (down) {
      if (!canInput) return;
      // A pointer click can move remote focus. Never commit a pending hypothesis afterward.
      cancelSpeech();
      _buttons.add(secondary);
    } else if (!_buttons.remove(secondary)) {
      return;
    }
    _mouse({
      'type': down ? 'down' : 'up',
      'buttons': secondary ? 'right' : 'left'
    });
  }

  void _mouse(Map<String, dynamic> values) {
    unawaited(bind.sessionSendMouse(
        sessionId: ffi.sessionId,
        msg: jsonEncode(ffi.inputModel.modify(values))));
  }

  void scroll(ui.Offset delta) {
    if (!canInput) return;
    final x = delta.dx.round(), y = delta.dy.round();
    if (x != 0 || y != 0) _mouse({'type': 'trackpad', 'x': '$x', 'y': '$y'});
  }

  void toggleFit() {
    fit = !fit;
    _overlay();
    notifyListeners();
  }

  void toggleFullSourceBuffer() {
    fullSourceBuffer = !fullSourceBuffer;
    fit = true;
    _overlay();
    notifyListeners();
  }

  void setPan(ui.Offset value) {
    pan = value;
    _overlay();
    notifyListeners();
  }

  void setSensitivity(double value) {
    sensitivity = value;
    unawaited(_call('sensitivity', {'value': value}));
    notifyListeners();
  }

  void setHolding(bool value) {
    holding = value;
    _overlay();
    notifyListeners();
  }

  void setLocked(bool value) {
    release();
    locked = value;
    holding = false;
    _overlay();
    notifyListeners();
  }

  void toggleEnabled() => unawaited(_call('enabled', {'value': !enabled}));
  void reopen() => unawaited(_call('reopen'));

  void dictate() {
    if (!canType) {
      status = 'Enable keyboard input in RustDesk before dictating';
      notifyListeners();
      return;
    }
    if (recognizing) {
      unawaited(_call('speechStop'));
      return;
    }
    _draft = transcript.isEmpty ? '' : '$transcript\n';
    recognizing = true;
    unawaited(_call('speechStart'));
    notifyListeners();
  }

  void cancelSpeech() {
    recognizing = listening = false;
    if (_begun) unawaited(_call('speechCancel'));
    _overlay();
  }

  void clearText() {
    cancelSpeech();
    transcript = _draft = '';
    _overlay();
    notifyListeners();
  }

  void release() {
    if (!active && _buttons.isEmpty && !recognizing && !listening) return;
    for (final secondary in _buttons.toList()) {
      button(secondary, false);
    }
    _clearPressed = false;
    if (recognizing || listening) cancelSpeech();
    ffi.inputModel.enterOrLeave(false);
  }

  @override
  void dispose() {
    release();
    XrVideo.stop();
    unawaited(_call('end'));
    _disposed = true;
    ffi.ffiModel.removeListener(_sessionChanged);
    ffi.cursorModel.removeListener(_cursorChanged);
    _channel.setMethodCallHandler(null);
    XrSession.onEvent = null;
    HardwareKeyboard.instance.removeHandler(_localKey);
    super.dispose();
  }
}
