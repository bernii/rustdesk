import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'xr_controller.dart';

/// Hosts the native glass trackpad, which draws over Flutter, for as long as XR is
/// active. Whenever a Flutter dialog or popup needs the screen, or the user opens
/// the session menu, the glass steps aside and what's built here shows instead.
class XrRemoteView extends StatefulWidget {
  const XrRemoteView(
      {super.key,
      required this.controller,
      required this.toolbar,
      required this.keyboard,
      required this.requestKeyboard,
      required this.close,
      required this.dialogs});
  final XrController controller;
  final Widget toolbar, keyboard;

  /// Passes any keyboard gate, then calls back when the glass field may take the keyboard.
  final void Function(VoidCallback allowed) requestKeyboard;
  final VoidCallback close;

  /// How many Flutter dialogs are open.
  final ValueListenable<int> dialogs;
  @override
  State<XrRemoteView> createState() => _XrRemoteViewState();
}

class _XrRemoteViewState extends State<XrRemoteView>
    with WidgetsBindingObserver {
  XrController get xr => widget.controller;
  bool _menu = false, _routeCurrent = true;

  @override
  void initState() {
    super.initState();
    xr.addListener(_changed);
    xr.onTrackpadBack = widget.close;
    xr.onSessionMenu = () => setState(() {
          _menu = true;
          _sync();
        });
    xr.onKeyboardRequest =
        () => widget.requestKeyboard(xr.showTrackpadKeyboard);
    widget.dialogs.addListener(_sync);
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _routeCurrent = ModalRoute.of(context)?.isCurrent ?? true;
    _sync();
  }

  bool get _glassShown =>
      !_menu && _routeCurrent && widget.dialogs.value == 0;

  void _sync() => xr.showTrackpad(_glassShown);

  void _changed() {
    if (mounted && !_glassShown) setState(() {});
  }

  void _resumeTrackpad() {
    setState(() => _menu = false);
    _sync();
  }

  Future<void> _back() async {
    if (_menu) {
      _resumeTrackpad();
    } else if (!await xr.trackpadBack() && mounted && !xr.locked) {
      widget.close();
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) xr.release();
  }

  @override
  void dispose() {
    xr.removeListener(_changed);
    xr.onTrackpadBack = xr.onSessionMenu = xr.onKeyboardRequest = null;
    widget.dialogs.removeListener(_sync);
    WidgetsBinding.instance.removeObserver(this);
    xr.showTrackpad(false, closed: true);
    xr.release();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    const background = Color(0xffd9541a), ink = Color(0xfffff4e9);
    return PopScope<Object?>(
        canPop: false,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop) unawaited(_back());
        },
        child: Scaffold(
            backgroundColor: background,
            // Nothing of this shows under the glass, so nothing of it is built.
            body: ValueListenableBuilder<int>(
                valueListenable: widget.dialogs,
                builder: (context, _, __) => _glassShown
                    ? const SizedBox.shrink()
                    : _fallback(ink))));
  }

  Widget _fallback(Color ink) => Stack(children: [
              SafeArea(
                  child: Column(children: [
                Expanded(
                    child: Center(
                        child: Padding(
                            padding: const EdgeInsets.all(20),
                            child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Text('Session menu',
                                      style: TextStyle(
                                          color: ink, fontSize: 28)),
                                  const SizedBox(height: 12),
                                  if (xr.status.isNotEmpty)
                                    Text(xr.status,
                                        textAlign: TextAlign.center,
                                        maxLines: 3,
                                        overflow: TextOverflow.ellipsis,
                                        style: TextStyle(color: ink)),
                                  const SizedBox(height: 12),
                                  TextButton.icon(
                                      onPressed: _resumeTrackpad,
                                      style: TextButton.styleFrom(
                                          foregroundColor: ink),
                                      icon: const Icon(Icons.touch_app_outlined),
                                      label: const Text('Back to trackpad')),
                                ])))),
                Listener(
                    onPointerDown: (_) => xr.cancelSpeech(),
                    child: widget.toolbar),
              ])),
              widget.keyboard,
            ]);
}
