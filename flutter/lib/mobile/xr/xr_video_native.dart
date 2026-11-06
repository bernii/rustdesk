import 'dart:ffi';
import 'dart:io';
import 'package:ffi/ffi.dart';

typedef _SubmitNative = Int32 Function(Pointer<Uint8>, IntPtr, Int32, Int32);
typedef _Submit = int Function(Pointer<Uint8>, int, int, int);

class XrVideo {
  static _Submit? _submit;
  static Pointer<Uint8> Function(Pointer<Utf8>, int)? _frame;
  static String? _session;
  static int _display = 0, _width = 0, _height = 0;

  static void configure(String session, int display, int width, int height) {
    if (!Platform.isAndroid) return;
    _submit ??= DynamicLibrary.open('librustdesk_xr.so')
        .lookupFunction<_SubmitNative, _Submit>('xr_submit_frame');
    _frame ??= DynamicLibrary.open('librustdesk.so').lookupFunction<
        Pointer<Uint8> Function(Pointer<Utf8>, IntPtr),
        Pointer<Uint8> Function(Pointer<Utf8>, int)>('session_get_rgba');
    _session = session;
    _display = display;
    _width = width;
    _height = height;
  }

  static void stop() => _session = null;

  static bool renderFrame(String session, int display, int length) {
    if (_session != session ||
        _display != display ||
        _submit == null ||
        _frame == null) return false;
    final id = session.toNativeUtf8();
    try {
      final bytes = _frame!(id, display);
      return bytes != nullptr && _submit!(bytes, length, _width, _height) == 1;
    } finally {
      malloc.free(id);
    }
  }
}
