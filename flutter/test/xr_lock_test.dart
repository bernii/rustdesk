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
  @override
  bool get isViewCamera => false;
  @override
  bool keyboardInputAllowed = true;
  @override
  void enterOrLeave(bool enter) {}
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
  Future<void> show(WidgetTester tester, {bool reduceMotion = false}) async {
    tester.view.physicalSize = const Size(412, 916);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await controller.begin();
    await tester.pumpWidget(MaterialApp(
        home: MediaQuery(
      data: MediaQueryData(
          size: const Size(412, 916), disableAnimations: reduceMotion),
      child: XrRemoteView(
          controller: controller,
          toolbar: const SizedBox(height: 48),
          keyboard: const SizedBox(),
          openKeyboard: () {},
          close: () {}),
    )));
  }

  Finder getLock() =>
      find.bySemanticsLabel('Hold for two seconds to unlock controls');

  testWidgets('full source buffer can be compared with output-sized rendering',
      (tester) async {
    final overlays = <Map>[];
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel,
        (call) async {
      if (call.method == 'begin') {
        return {'active': true, 'enabled': true, 'available': true};
      }
      if (call.method == 'overlay')
        overlays.add(call.arguments['values'] as Map);
      return null;
    });
    await show(tester);
    expect(find.text('Full-res buffer: on'), findsOneWidget);
    expect(overlays.last['fullSourceBuffer'], isTrue);
    controller.locked = false;
    await tester.pumpWidget(const SizedBox());
    await show(tester);
    await tester.tap(find.text('Full-res buffer: on'));
    await tester.pump();
    expect(overlays.last['fullSourceBuffer'], isFalse);
    expect(find.text('Full-res buffer: off'), findsOneWidget);
    await tester.tap(find.text('1:1 / pan'));
    await tester.pump();
    expect(overlays.last['fit'], isFalse);
    await tester.tap(find.text('Full-res buffer: off'));
    await tester.pump();
    expect(overlays.last['fullSourceBuffer'], isTrue);
    expect(overlays.last['fit'], isTrue);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
      'controls unlock only after two seconds, including with reduced motion',
      (tester) async {
    await show(tester, reduceMotion: true);
    expect(controller.locked, isTrue);
    final touch = await tester.startGesture(tester.getCenter(getLock()));
    await tester.pump();
    expect(find.text('Hold to unlock'), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 1999));
    expect(controller.locked, isTrue);
    await tester.pump(const Duration(milliseconds: 17));
    expect(controller.locked, isFalse);
    await touch.up();
    await tester.pump(const Duration(milliseconds: 600));
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('moving away or releasing early leaves controls locked',
      (tester) async {
    await show(tester);
    var touch = await tester.startGesture(tester.getCenter(getLock()));
    await tester.pump(const Duration(milliseconds: 500));
    await touch.up();
    await tester.pump(const Duration(seconds: 2));
    expect(controller.locked, isTrue);
    touch = await tester.startGesture(tester.getCenter(getLock()));
    await touch.moveBy(const Offset(-20, 0));
    await tester.pump(const Duration(seconds: 2));
    expect(controller.locked, isTrue);
    await touch.up();
    await tester.pumpWidget(const SizedBox());
    expect(tester.takeException(), isNull);
  });
}
