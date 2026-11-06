package com.carriez.flutter_hbb.xr;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/** One foreground utterance at a time; no automatic restart or retained audio. */
final class DictationController {
    interface Sink {
        void text(String value, boolean complete);
        void status(String value, boolean active);
        void listening(boolean value);
    }

    private final Context context;
    private final Sink sink;
    private SpeechRecognizer recognizer;

    DictationController(Context context, Sink sink) {
        this.context = context;
        this.sink = sink;
    }

    boolean active() { return recognizer != null; }

    void start() {
        if (active()) return;
        boolean local = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
        if (!local && !SpeechRecognizer.isRecognitionAvailable(context)) {
            sink.status("Speech recognition unavailable. Use Keys → Gboard microphone.", false);
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
                    if (complete) finish(values == null || values.isEmpty()
                            ? "No words recognized. Tap mic to retry." : "Dictation complete");
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
                @Override public void onError(int error) { if (current()) finish(errorText(error)); }
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
        if (recognizer != null) {
            sink.listening(false);
            sink.status("Finishing dictation…", true);
            recognizer.stopListening();
        }
    }

    void cancel() {
        if (recognizer != null) finish("Dictation stopped · preview kept");
    }

    private void finish(String status) {
        SpeechRecognizer old = recognizer;
        recognizer = null;
        if (old != null) old.destroy();
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
