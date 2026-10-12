package org.privateview.quietscreen;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.net.TrafficStats;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.accessibilityservice.AccessibilityServiceInfo;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

/** Framework-only black-box checks. No shared Kotlin/AndroidX test ABI is
 * loaded from the optimized app, and no production test API is added. */
public final class TrialNativeInstrumentation extends Instrumentation {
    private Activity activity;
    private int passed;
    private String query;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); query = arguments == null ? null : arguments.getString("query"); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_OK;
        android.content.SharedPreferences updatePreferences = getTargetContext().getSharedPreferences("updates", android.content.Context.MODE_PRIVATE);
        boolean originalAutomaticUpdates = updatePreferences.getBoolean("automatic", true);
        // Idle-network checks run with optional update traffic disabled, then restore the preference.
        updatePreferences.edit().putBoolean("automatic", false).commit();
        try {
            if (query == null) {
                security(); passed++;
                remote(); passed++;
                playback(); passed++;
                result.putString("stream", "\nOK (3 native checks)\n");
            } else {
                require(query.length() > 0 && query.length() <= 200, "Invalid probe query");
                opening(query); passed++;
                result.putString("stream", "\nOK (1 opening check)\n");
            }
            result.putInt("passed", passed);
        } catch (Throwable failure) {
            result.putString("stream", "\nFAIL after " + passed + " checks: " + failure + "\n");
            result.putString("failure", failure.toString());
            result.putInt("passed", passed);
            resultCode = Activity.RESULT_CANCELED;
        } finally {
            if (activity != null) runOnMainSync(() -> activity.finish());
            updatePreferences.edit().putBoolean("automatic", originalAutomaticUpdates).commit();
        }
        finish(resultCode, result);
    }
    private void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void marker(String text) { Bundle status = new Bundle(); status.putString("stream", text + "\n"); sendStatus(0, status); }
    private UiAutomation ui() {
        UiAutomation ui = getUiAutomation();
        AccessibilityServiceInfo info = ui.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        ui.setServiceInfo(info);
        return ui;
    }
    private List<AccessibilityNodeInfo> nodes() {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        for (AccessibilityWindowInfo window : ui().getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && getTargetContext().getPackageName().contentEquals(root.getPackageName())) visit(root, nodes);
        }
        return nodes;
    }
    private void visit(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> nodes) {
        if (nodes.size() >= 2000) return;
        nodes.add(node);
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) visit(child, nodes); }
    }
    private String texts() {
        StringBuilder text = new StringBuilder();
        for (AccessibilityNodeInfo node : nodes()) { if (node.getText() != null) text.append(node.getText()).append('\n'); if (node.getContentDescription() != null) text.append(node.getContentDescription()).append('\n'); }
        return text.toString();
    }
    private String label(AccessibilityNodeInfo node) {
        StringBuilder text = new StringBuilder();
        if (node.getText() != null) text.append(node.getText());
        if (node.getContentDescription() != null) text.append(node.getContentDescription());
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) text.append(' ').append(label(child)); }
        return text.toString();
    }
    private String focused() { StringBuilder text = new StringBuilder(); for (AccessibilityNodeInfo node : nodes()) if (node.isFocused()) text.append(label(node)); return text.toString(); }
    private String waitFor(String text, int seconds, boolean reveal) {
        long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
        String last = "";
        while (SystemClock.elapsedRealtime() < deadline) {
            last = texts(); if (last.contains(text)) return last;
            if (reveal && last.contains("No compatible unencrypted stream was found."))
                throw new AssertionError("Playback failed: " + last.substring(0, Math.min(700, last.length())));
            if (reveal && last.contains("Try playback again"))
                throw new AssertionError("Playback failed: " + last.substring(0, Math.min(700, last.length())));
            if (reveal && !last.contains("Playback ")) key(KeyEvent.KEYCODE_DPAD_UP);
            SystemClock.sleep(400);
        }
        throw new AssertionError("Expected " + text + "; UI: " + last.substring(0, Math.min(700, last.length())));
    }
    private void key(int code) {
        long now = SystemClock.uptimeMillis();
        require(ui().injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), true), "Key down refused");
        require(ui().injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0), true), "Key up refused");
        SystemClock.sleep(350);
    }
    private Intent intent() { return new Intent().setClassName(getTargetContext().getPackageName(), "org.privateview.quietscreen.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); }
    private void launch() { activity = startActivitySync(intent().addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)); waitFor("Search videos", 20, false); }
    private void security() throws Exception {
        Class<?> helper = Class.forName("org.schabi.newpipe.extractor.utils.JavaScript", true, getTargetContext().getClassLoader());
        try { helper.getMethod("compileOrThrow", String.class).invoke(null, "while(true){}"); throw new AssertionError("Script compilation enabled"); }
        catch (InvocationTargetException expected) { denied(expected); }
        try { helper.getMethod("run", String.class, String.class, String[].class).invoke(null, "function f(){while(true){}}", "f", new String[]{"ignored"}); throw new AssertionError("Script evaluation enabled"); }
        catch (InvocationTargetException expected) { denied(expected); }
        marker("TRIAL_SECURITY compileDenied=true runDenied=true noScriptExecuted=true");
    }
    private void denied(InvocationTargetException error) { require(error.getCause() instanceof SecurityException && "QUIETSCREEN_JS_DISABLED".equals(error.getCause().getMessage()), "Unexpected denial"); }
    private void remote() {
        launch(); SystemClock.sleep(1500);
        int uid = getTargetContext().getApplicationInfo().uid;
        long tx = TrafficStats.getUidTxBytes(uid), rx = TrafficStats.getUidRxBytes(uid);
        SystemClock.sleep(2500);
        if (tx >= 0 && rx >= 0) {
            require(tx == TrafficStats.getUidTxBytes(uid) && rx == TrafficStats.getUidRxBytes(uid), "Own-UID traffic increased on idle Home");
            marker("TRIAL_IDLE ownUidTrafficSupported=true txDelta=0 rxDelta=0");
        } else marker("TRIAL_IDLE ownUidTrafficSupported=false coverage=unknown");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER);
        String settings = waitFor("Maximum video resolution", 20, false);
        require(settings.contains("480p ✓") || settings.contains("720p ✓") || settings.contains("1080p ✓") || settings.contains("4K ✓"), "Saved preference not observable");
        int original = settings.contains("480p ✓") ? 0 : settings.contains("720p ✓") ? 1 : settings.contains("1080p ✓") ? 2 : 3;
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("720p ✓", 10, false);
        // Return to the original preference using remote events before leaving.
        if (original == 0) key(KeyEvent.KEYCODE_DPAD_LEFT);
        else for (int i = 1; i < original; i++) key(KeyEvent.KEYCODE_DPAD_RIGHT);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        key(KeyEvent.KEYCODE_BACK); waitFor("Search videos", 10, false);
        require(focused().contains("Settings"), "Home did not restore Settings focus");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("About QuietScreen", 10, false);
        key(KeyEvent.KEYCODE_BACK); waitFor("Search videos", 10, false);
        marker("TRIAL_REMOTE settingsSelection=true homeFocusRestored=true about=true back=true preferencesRestored=true");
    }
    private void search(String query) {
        launch(); key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("Title, channel or video ID", 20, false);
        AccessibilityNodeInfo edit = null;
        for (AccessibilityNodeInfo node : nodes()) if (node.isEditable()) { edit = node; break; }
        require(edit != null, "Search input unavailable");
        Bundle value = new Bundle(); value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query);
        require(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value), "Query entry refused");
        // The emulator IME is sometimes absent from the accessible-window list
        // while still consuming arrows/OK. Explicitly dismiss it before testing
        // the app's remote navigation; keyboard interaction is a separate gate.
        runOnMainSync(() -> ((android.view.inputmethod.InputMethodManager)
            activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0));
        SystemClock.sleep(700);
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER);
    }
    private void opening(String query) {
        search(query);
        waitFor("PLAY", 60, false);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        String firstResult = focused();
        marker("TRIAL_OPENING firstResult=" + firstResult.substring(0, Math.min(300, firstResult.length())));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("Playback Playing", 90, true);
        key(KeyEvent.KEYCODE_MEDIA_PAUSE);
        waitFor("Playback Paused", 10, true);
        marker("TRIAL_OPENING started=true paused=true observedOpeningOnly=true");
    }
    private void playback() {
        search("dQw4w9WgXcQ");
        waitFor("Never Gonna Give You Up", 60, false);
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("Playback Playing", 90, true);
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("Playback Paused", 10, true);
        long pausedAt = position(texts());
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        for (int i = 0; i < 3 && !focused().contains("Quality"); i++) key(KeyEvent.KEYCODE_DPAD_LEFT);
        require(focused().contains("Quality"), "Quality unreachable by remote");
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("Auto (up to", 10, false);
        for (int i = 0; i < 10 && !focused().contains("1080p"); i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        require(focused().contains("1080p"), "Supported 1080p choice unavailable");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("rendered 1080p", 60, true);
        require(texts().contains("Playback Paused"), "Quality switch resumed paused playback");
        require(Math.abs(position(texts()) - pausedAt) <= 2000, "Quality switch lost paused position");
        key(KeyEvent.KEYCODE_BACK); waitFor("Never Gonna Give You Up", 10, false);
        require(focused().contains("Never Gonna Give You Up"), "Result focus lost on Back");
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("Playback Playing", 90, true);
        key(KeyEvent.KEYCODE_HOME); SystemClock.sleep(1500);
        getTargetContext().startActivity(intent());
        waitFor("Never Gonna Give You Up", 20, false);
        require(!texts().contains("Playback Playing"), "Reentry auto-played");
        marker("TRIAL_PLAYBACK liveSearch=true nativeDecode=true pause=true fixed1080p=true pausedPositionPreserved=true resultFocusRestored=true homeReentryNoAutoPlay=true");
    }
    private long position(String text) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("(\\d+:\\d{2}(?::\\d{2})?) / ").matcher(text);
        require(match.find(), "Playback position not observable");
        long seconds = 0;
        for (String part : match.group(1).split(":")) seconds = seconds * 60 + Integer.parseInt(part);
        return seconds * 1000;
    }
}
