enum XrDictationAction { send, enter, discard }

/// Recognition results and the held third tap may finish in either order.
class XrDictation {
  bool active = false, stopping = false, finished = false;
  int generation = 0;
  String _words = '', _partial = '';
  XrDictationAction? _action;
  String get _text => [_words, _partial].where((s) => s.isNotEmpty).join(' ');
  String get preview => _action == null ? _text : '';
  bool get previewVisible => active && _action == null;

  void begin() {
    generation++;
    active = true;
    stopping = finished = false;
    _words = _partial = '';
    _action = null;
  }

  void accept(String text, {required bool complete}) {
    if (!active || finished) return;
    if (complete) {
      _words = [_words, text.trim()].where((s) => s.isNotEmpty).join(' ');
      _partial = '';
    } else {
      _partial = text.trim();
    }
  }

  void stop() {
    if (active) stopping = true;
  }

  void finish() {
    if (active) finished = true;
  }

  void choose(XrDictationAction action) {
    if (active && _action == null) _action = action;
  }

  ({String text, bool enter})? takeResult() {
    if (!active || _action == null) return null;
    if (_action == XrDictationAction.discard) {
      cancel(clear: true);
      return (text: '', enter: false);
    }
    if (!finished) return null;
    active = false;
    final text = _text;
    _words = _partial = '';
    return (text: text, enter: _action == XrDictationAction.enter);
  }

  void cancel({bool clear = false}) {
    generation++;
    active = stopping = false;
    _action = null;
    if (clear) _words = _partial = '';
  }
}
