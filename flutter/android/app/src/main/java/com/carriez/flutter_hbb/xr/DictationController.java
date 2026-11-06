package com.carriez.flutter_hbb.xr;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/** Foreground dictation until stopped; completed utterances remain a local preview. */
final class DictationController {
    interface Sink {
        void text(String value, boolean complete);
        void status(String value, boolean active);
        void listening(boolean value);
    }

    private final Context context;
    private final Sink sink;
    private SpeechRecognizer recognizer;
    private boolean wanted;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable restart = this::listen;

    DictationController(Context context, Sink sink) {
        this.context = context;
        this.sink = sink;
    }

    boolean active() { return recognizer != null; }

    void start() {
        if (wanted) return;
        wanted = true;
        listen();
    }

    private void listen() {
        if (!wanted || active()) return;
        boolean local = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
        if (!local && !SpeechRecognizer.isRecognitionAvailable(context)) {
            finish("Speech recognition unavailable. Use Keys → Gboard microphone.");
            return;
        }
        try {
            SpeechRecognizer current = local ? SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    : SpeechRecognizer.createSpeechRecognizer(context);
            recognizer = current;
            current.setRecognitionListener(new RecognitionListener() {
                private boolean current() { return recognizer == current; }
                private void result(Bundle results, boolean complete) {
                    if (!current()) return;
                    ArrayList<String> values = results == null ? null
                            : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (values != null && !values.isEmpty()) sink.text(values.get(0), complete);
                    if (complete) {
                        if (wanted) continueListening();
                        else finish("Dictation ready");
                    }
                }
                @Override public void onReadyForSpeech(Bundle params) {
                    if (current()) {
                        sink.listening(true);
                        sink.status("Listening · " + (local ? "on-device" : "system speech service"), true);
                    }
                }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rms) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {
                    if (current()) {
                        sink.listening(false);
                        sink.status("Finishing dictation…", true);
                    }
                }
                @Override public void onError(int error) {
                    if (!current()) return;
                    if (wanted && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                        continueListening();
                    } else finish(errorText(error));
                }
                @Override public void onResults(Bundle results) { result(results, true); }
                @Override public void onPartialResults(Bundle results) { result(results, false); }
                @Override public void onEvent(int event, Bundle params) {}
            });
            Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            sink.status(local ? "Starting on-device dictation…" : "Starting system speech service (may use network)…", true);
            current.startListening(request);
        } catch (RuntimeException error) {
            finish("Cannot start dictation. Check microphone permission and speech service.");
        }
    }

    void stop() {
        wanted = false;
        handler.removeCallbacks(restart);
        if (recognizer != null) {
            sink.listening(false);
            sink.status("Finishing dictation…", true);
            try { recognizer.stopListening(); }
            catch (RuntimeException error) { finish("Speech service stopped; preview kept"); }
        } else finish("Dictation ready");
    }

    void cancel() {
        if (wanted || recognizer != null) finish("Dictation stopped · preview kept");
    }

    private void continueListening() {
        releaseRecognizer();
        sink.listening(false);
        sink.status("Dictating · two-finger tap to finish", true);
        handler.postDelayed(restart, 250);
    }

    private void releaseRecognizer() {
        SpeechRecognizer old = recognizer;
        recognizer = null;
        if (old != null) old.destroy();
    }

    private void finish(String status) {
        wanted = false;
        handler.removeCallbacks(restart);
        releaseRecognizer();
        sink.listening(false);
        sink.status(status, false);
    }

    private static String errorText(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "No speech recognized. Tap mic to retry.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Microphone permission required. Tap mic to retry.";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "Speech language unavailable. Download its speech model or use Gboard.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "Speech service busy. Stop other dictation and retry.";
            case SpeechRecognizer.ERROR_AUDIO: return "Microphone unavailable. Check Android microphone access.";
            default: return "Dictation failed (" + error + "). Tap mic to retry; preview kept.";
        }
    }
}
