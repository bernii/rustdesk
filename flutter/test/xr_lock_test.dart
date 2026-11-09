import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hbb/common.dart' show SessionID;
import 'package:flutter_hbb/models/input_model.dart';
import 'package:flutter_hbb/models/model.dart';
import 'package:flutter_hbb/mobile/xr/xr_controller.dart';
import 'package:flutter_hbb/mobile/xr/xr_remote_view.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:uuid/uuid.dart';

class _Input extends Fake implements InputModel {
  final moves = <Offset>[];
  @override
  Future<void> moveMouse(double x, double y) async {
    moves.add(Offset(x, y));
  }

  @override
  bool get isViewCamera => false;
  @override
  bool keyboardInputAllowed = true;
  @override
  void enterOrLeave(bool enter) {}
  final keys = <String>[];
  @override
  void inputKey(String name, {bool? down, bool? press}) {
    keys.add(name);
  }
}

class _Session extends Fake implements FFI {
  _Session() {
    ffiModel = FfiModel(WeakReference(this));
    ffiModel.pi.displays.add(Display()
      ..width = 3840
      ..height = 2160);
    cursorModel = CursorModel(WeakReference(this));
    canvasModel = CanvasModel(WeakReference(this));
  }
  @override
  final SessionID sessionId = const Uuid().v4obj();
  @override
  bool get closed => false;
  @override
  late final FfiModel ffiModel;
  @override
  late final CursorModel cursorModel;
  @override
  late final CanvasModel canvasModel;
  @override
  final InputModel inputModel = _Input();
}

void main() {
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('rustdesk-xr');
  late XrController controller;
  setUp(() {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel,
        (call) async {
      if (call.method == 'begin')
        return {'active': true, 'enabled': true, 'available': true};
      return null;
    });
    controller = XrController(_Session());
  });
  tearDown(() {
    controller.dispose();
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });
  Future<void> show(WidgetTester tester, ValueNotifier<int> dialogs) async {
    await controller.begin();
    await tester.pumpWidget(MaterialApp(
        home: XrRemoteView(
            controller: controller,
            toolbar: const SizedBox(height: 48),
            keyboard: const SizedBox(),
            requestKeyboard: (allowed) => allowed(),
            dialogs: dialogs,
            close: () {})));
  }

  /// What the native trackpad would send.
  Future<void> action(String name, [Map<String, Object?> values = const {}]) =>
      binding.defaultBinaryMessenger.handlePlatformMessage(
          channel.name,
          channel.codec.encodeMethodCall(MethodCall('trackpad',
              {'session': controller.session, 'action': name, ...values})),
          (_) {});

  test('starting XR sends the centered cursor to the remote host once',
      () async {
    await controller.begin();
    final input = controller.ffi.inputModel as _Input;
    expect(input.moves, [const Offset(1920, 1080)]);
    expect(controller.pointer, const Offset(1920, 1080));
    controller.ffi.ffiModel.notifyListeners();
    expect(input.moves, hasLength(1));
  });

  test('trackpad pointer motion is scaled by the saved sensitivity', () async {
    await controller.begin();
    final input = controller.ffi.inputModel as _Input;
    input.moves.clear();
    await action('move', {'dx': 10.0, 'dy': -5.0});
    expect(input.moves, [const Offset(1950, 1065)]);
    await action('center');
    expect(input.moves.last, const Offset(1920, 1080));
  });

  test('settings from the glass reach the glasses overlay and tracker',
      () async {
    final calls = <MethodCall>[];
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel,
        (call) async {
      calls.add(call);
      if (call.method == 'begin') {
        return {'active': true, 'enabled': true, 'available': true};
      }
      return null;
    });
    await controller.begin();
    // A pointer move and its cursor update share one message, sent a microtask later.
    await Future<void>.delayed(Duration.zero);
    Map last() => calls
        .lastWhere((call) => call.method == 'overlay')
        .arguments['values'] as Map;
    expect(last()['anchored'], isTrue);
    await action('anchored', {'value': false});
    expect(last()['anchored'], isFalse);
    expect(last()['fullSourceBuffer'], isTrue);
    await action('fullBuffer', {'value': false});
    expect(last()['fullSourceBuffer'], isFalse);
    await action('fit', {'value': false});
    expect(last()['fit'], isFalse);
    await action('fullBuffer', {'value': true});
    expect(last()['fullSourceBuffer'], isTrue);
    expect(last()['fit'], isTrue);
    await action('pan', {'x': .25, 'y': .75});
    expect(last()['panX'], .25);
    expect(last()['panY'], .75);
    await action('recenter');
    await action('recheckCalibration');
    await action('calibrateStationary');
    for (final method in [
      'recenter',
      'recheckCalibration',
      'calibrateStationary'
    ]) {
      expect(calls.where((call) => call.method == method), hasLength(1));
    }
    await action('sensitivity', {'value': 5.0});
    expect(controller.sensitivity, 5);
    await action('locked', {'value': false});
    expect(controller.locked, isFalse);
  });

  testWidgets('the glass steps aside for dialogs and the session menu',
      (tester) async {
    final shown = <bool>[];
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel,
        (call) async {
      if (call.method == 'begin') {
        return {'active': true, 'enabled': true, 'available': true};
      }
      if (call.method == 'trackpad') shown.add(call.arguments['visible']);
      return null;
    });
    final dialogs = ValueNotifier(0);
    await show(tester, dialogs);
    await tester.pump();
    expect(shown.last, isTrue);
    dialogs.value = 1;
    await tester.pump();
    expect(shown.last, isFalse);
    dialogs.value = 0;
    await tester.pump();
    expect(shown.last, isTrue);
    await action('sessionMenu');
    await tester.pump();
    expect(shown.last, isFalse);
    await tester.tap(find.text('Back to trackpad'));
    await tester.pump();
    expect(shown.last, isTrue);
    await tester.pumpWidget(const SizedBox());
    expect(shown.last, isFalse);
    expect(tester.takeException(), isNull);
  });

  test('typing sends what changed, and clearing the field sends nothing',
      () async {
    await controller.begin();
    final input = controller.ffi.inputModel as _Input;
    await action('softInput', {'text': 'a'});
    await action('softInput', {'text': 'ab'});
    await action('softInput', {'text': 'a'});
    await action('softInput', {'text': 'a '});
    expect(input.keys, ['a', 'b', 'VK_BACK', 'VK_SPACE']);
    await action('softReset');
    await action('softInput', {'text': 'c'});
    expect(input.keys.last, 'c');
    await action('softKey', {'key': 'VK_RETURN'});
    expect(input.keys.last, 'VK_RETURN');
  });
}
