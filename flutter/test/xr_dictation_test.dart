import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_hbb/mobile/xr/xr_dictation.dart';

void main() {
  test(
      'preview accumulates utterances and waits for both recognition and the third-tap choice',
      () {
    for (final finishFirst in [false, true]) {
      final draft = XrDictation()..begin();
      draft.accept('hello', complete: false);
      expect(draft.preview, 'hello');
      expect(draft.takeResult(), isNull);
      draft.accept('hello world', complete: true);
      draft.accept('one more', complete: false);
      draft.stop();
      if (!finishFirst) {
        draft.choose(XrDictationAction.enter);
        expect(draft.preview, isEmpty);
        expect(draft.previewVisible, isFalse);
      }
      expect(draft.takeResult(), isNull);
      draft.accept('one more sentence', complete: true);
      draft.finish();
      if (finishFirst) {
        expect(draft.takeResult(), isNull);
        draft.choose(XrDictationAction.enter);
      }
      expect(draft.preview, isEmpty);
      expect(draft.previewVisible, isFalse);
      expect(draft.takeResult(),
          (text: 'hello world one more sentence', enter: true));
      expect(draft.takeResult(), isNull);
      expect(draft.preview, isEmpty);
    }
  });

  test('pinch outward discards the whole preview and ignores late recognition',
      () {
    final draft = XrDictation()..begin();
    draft.accept('first sentence', complete: true);
    draft.accept('second', complete: false);
    draft.stop();
    draft.choose(XrDictationAction.discard);
    expect(draft.preview, isEmpty);
    expect(draft.previewVisible, isFalse);
    expect(draft.takeResult(), (text: '', enter: false));
    draft.accept('second sentence', complete: true);
    draft.finish();
    expect(draft.preview, isEmpty);
    expect(draft.takeResult(), isNull);
    expect(draft.active, isFalse);
  });

  test(
      'release sends without Enter; cancellation cannot send or complete an old action',
      () {
    final draft = XrDictation()..begin();
    draft.accept('text', complete: true);
    draft.stop();
    draft.choose(XrDictationAction.send);
    draft.finish();
    expect(draft.takeResult(), (text: 'text', enter: false));
    draft.begin();
    final generation = draft.generation;
    draft.accept('cancel me', complete: false);
    draft.stop();
    draft.choose(XrDictationAction.enter);
    draft.cancel();
    draft.finish();
    expect(draft.takeResult(), isNull);
    expect(draft.generation, greaterThan(generation));
    draft.begin();
    expect(draft.preview, isEmpty);
  });
}
