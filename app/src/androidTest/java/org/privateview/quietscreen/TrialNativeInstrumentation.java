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
    private String check;
    private String historyStage;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        query = arguments == null ? null : arguments.getString("query");
        check = arguments == null ? null : arguments.getString("check");
        historyStage = arguments == null ? null : arguments.getString("historyStage");
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_OK;
        android.content.SharedPreferences updatePreferences = getTargetContext().getSharedPreferences("updates", android.content.Context.MODE_PRIVATE);
        boolean originalAutomaticUpdates = updatePreferences.getBoolean("automatic", true);
        android.content.SharedPreferences genrePreferences = getTargetContext().getSharedPreferences("genre_preferences", android.content.Context.MODE_PRIVATE);
        java.util.Map<String, ?> originalGenres = genrePreferences.getAll();
        android.content.SharedPreferences continuing = getTargetContext().getSharedPreferences("continue_watching", android.content.Context.MODE_PRIVATE);
        String originalContinue = continuing.getString("records", null);
        if ("browse".equals(check) || "continue".equals(check) || "searchHistory".equals(check) || "playerOverlay".equals(check)) continuing.edit().clear().commit();
        if ("playerOverlay".equals(check)) genrePreferences.edit().clear().putBoolean("enabled", true).putFloat("MUSIC", 30f).commit();
        if ("browse".equals(check) || "searchHistory".equals(check)) genrePreferences.edit().clear().putBoolean("enabled", true).commit();
        // Idle-network checks run with optional update traffic disabled, then restore the preference.
        updatePreferences.edit().putBoolean("automatic", false).commit();
        try {
            if ("browse".equals(check)) {
                security(); passed++;
                browse(); passed++;
                result.putString("stream", "\nOK (2 browsing native checks)\n");
            } else if ("updateCheck".equals(check)) {
                updateCheck(); passed++;
                result.putString("stream", "\nOK (1 production update check)\n");
            } else if ("continue".equals(check)) {
                continueWatching(); passed++;
                result.putString("stream", "\nOK (1 continue watching native check)\n");
            } else if ("searchReturn".equals(check)) {
                searchReturn(); passed++;
                result.putString("stream", "\nOK (1 search return check)\n");
            } else if ("searchHistory".equals(check)) {
                searchHistory(); passed++;
                result.putString("stream", "\nOK (1 search history check)\n");
            } else if ("playerOverlay".equals(check)) {
                playerOverlay(); passed++;
                result.putString("stream", "\nOK (1 player overlay live check)\n");
            } else if ("searchCursor".equals(check)) {
                searchCursor(); passed++;
                result.putString("stream", "\nOK (1 search cursor check)\n");
            } else if (query == null) {
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
            waitForIdleSync();
            updatePreferences.edit().putBoolean("automatic", originalAutomaticUpdates).commit();
            android.content.SharedPreferences.Editor restore = genrePreferences.edit().clear();
            for (java.util.Map.Entry<String, ?> entry : originalGenres.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Float) restore.putFloat(entry.getKey(), (Float) value);
                else if (value instanceof Boolean) restore.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof String) restore.putString(entry.getKey(), (String) value);
                else if (value instanceof Integer) restore.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof Long) restore.putLong(entry.getKey(), (Long) value);
            }
            restore.commit();
            android.content.SharedPreferences.Editor resumeRestore = continuing.edit().clear();
            if (originalContinue != null) resumeRestore.putString("records", originalContinue);
            resumeRestore.commit();
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
        if (android.os.Build.VERSION.SDK_INT >= 33) ui().clearCache();
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
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER);
        String settings = waitFor("Video quality", 20, false);
        int uid = getTargetContext().getApplicationInfo().uid;
        long tx = TrafficStats.getUidTxBytes(uid), rx = TrafficStats.getUidRxBytes(uid);
        SystemClock.sleep(2500);
        if (tx >= 0 && rx >= 0) {
            require(tx == TrafficStats.getUidTxBytes(uid) && rx == TrafficStats.getUidRxBytes(uid), "Own-UID traffic increased on idle Settings");
            marker("TRIAL_IDLE ownUidTrafficSupported=true txDelta=0 rxDelta=0");
        } else marker("TRIAL_IDLE ownUidTrafficSupported=false coverage=unknown");
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
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("About Aprion TV", 10, false);
        key(KeyEvent.KEYCODE_BACK); waitFor("Search videos", 10, false);
        marker("TRIAL_REMOTE settingsSelection=true homeFocusRestored=true about=true back=true preferencesRestored=true");
    }
    private void search(String query) {
        launch(); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER);
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
        // Pin submission separately from editor/IME focus, which can arrive late
        // on a cold emulator. Cursor behavior is deliberately outside this check.
        AccessibilityNodeInfo submit = null;
        for (AccessibilityNodeInfo node : nodes()) if (node.isClickable() && label(node).trim().equals("Search")) { submit = node; break; }
        require(submit != null && submit.performAction(AccessibilityNodeInfo.ACTION_FOCUS), "Search submit focus unavailable");
        require(focused().trim().equals("Search"), "Search submission target was not focused");
        runOnMainSync(() -> ((android.view.inputmethod.InputMethodManager)
            activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0));
        SystemClock.sleep(400);
        AccessibilityNodeInfo refreshedSubmit = null;
        for (AccessibilityNodeInfo node : nodes()) if (node.isClickable() && label(node).trim().equals("Search")) { refreshedSubmit = node; break; }
        require(refreshedSubmit != null && refreshedSubmit.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Search button activation refused");
    }
    private AccessibilityNodeInfo searchInput() {
        for (AccessibilityNodeInfo node : nodes()) if (node.isEditable()) return node;
        throw new AssertionError("Search input unavailable");
    }
    private void browse() {
        launch();
        waitFor("Science & technology", 15, false);
        long deadline = SystemClock.elapsedRealtime() + 60000;
        String pinned = "";
        while (SystemClock.elapsedRealtime() < deadline) {
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            pinned = focused();
            if (!pinned.contains("Home") && !pinned.contains("Cancel loading") && !pinned.contains("Try again") && pinned.length() > 15) break;
            key(KeyEvent.KEYCODE_BACK);
            SystemClock.sleep(500);
        }
        require(!pinned.contains("Home") && !pinned.contains("Try again") && !pinned.contains("Cancel loading"), "Browse catalog did not produce a playable card");
        java.util.regex.Pattern longVideo = java.util.regex.Pattern.compile("\\b[1-9]\\d{0,3}:[0-5]\\d\\b");
        for (int i = 0; i < 6 && !longVideo.matcher(pinned).find(); i++) { key(KeyEvent.KEYCODE_DPAD_RIGHT); pinned = focused(); }
        require(longVideo.matcher(pinned).find(), "No suitably long card for the bounded watch-time check");
        marker("TRIAL_BROWSE selectedCard=" + pinned.substring(0, Math.min(240, pinned.length())));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("Playback Playing", 75, true);
        observePlayback(35);
        key(KeyEvent.KEYCODE_MEDIA_PAUSE);
        waitFor("Playback Paused", 10, true);
        key(KeyEvent.KEYCODE_BACK);
        waitFor("Find your next watch", 10, false);
        require(pinned.equals(focused()), "Browse card focus was not restored after playback");
        require(getTargetContext().getSharedPreferences("genre_preferences", android.content.Context.MODE_PRIVATE).getFloat("SCIENCE", 0f) > 0f,
            "Actual genre playback did not record watch-time preferences");
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        require(!pinned.equals(focused()), "Down did not move to another genre row");
        key(KeyEvent.KEYCODE_DPAD_UP);
        require(pinned.equals(focused()), "Up did not return to the corresponding card");
        key(KeyEvent.KEYCODE_HOME); SystemClock.sleep(1500);
        getTargetContext().startActivity(intent());
        waitFor("Find your next watch", 20, false);
        require(!texts().contains("Playback Playing"), "Reentry auto-played");
        marker("TRIAL_BROWSE nativePlayback=true boundedWatch35s=true genreLearningRecorded=true paused=true exactCardFocusRestored=true remoteVerticalRows=true homeReentryNoAutoPlay=true");
    }
    private void updateCheck() {
        launch(); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN);
        require(focused().contains("Settings"), "Settings focus not selected");
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("Video quality", 15, false);
        AccessibilityNodeInfo checkButton = null;
        for (int i = 0; i < 12 && checkButton == null; i++) {
            for (AccessibilityNodeInfo node : nodes()) if (node.isClickable() && label(node).trim().equals("Check for updates")) { checkButton = node; break; }
            if (checkButton == null) key(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        require(checkButton != null, "Manual update button unavailable");
        require(checkButton.performAction(AccessibilityNodeInfo.ACTION_FOCUS), "Manual update button focus refused");
        SystemClock.sleep(500);
        require(focused().contains("Check for updates"), "Manual update focus not verified");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        long deadline = SystemClock.elapsedRealtime() + 90000;
        String result = "";
        while (SystemClock.elapsedRealtime() < deadline) {
            result = texts();
            if (result.contains("QS-U-OK")) break;
            if (result.contains("Update failed while")) throw new AssertionError("Manual update: " + result.substring(Math.max(0, result.indexOf("Update failed while")), Math.min(result.length(), result.indexOf("Update failed while") + 700)));
            SystemClock.sleep(300);
        }
        require(result.contains("QS-U-OK") && result.contains("Android API") && result.contains("TV time"), "Successful update diagnostic missing");
        require(result.contains("You have the latest version."), "Unexpected update state");
        marker("UPDATE_PRODUCTION manualCheck=true signedFeedVerified=true actionableDetail=true sdk=" + android.os.Build.VERSION.SDK_INT);
    }
    private void continueWatching() throws Exception {
        String record = "{\"videos\":[{\"id\":\"jNQXAC9IVRw\",\"title\":\"Me at the zoo\",\"channel\":\"jawed\",\"position\":5000,\"duration\":19000,\"genre\":\"\"}],\"finished\":[]}";
        android.content.SharedPreferences prefs = getTargetContext().getSharedPreferences("continue_watching", android.content.Context.MODE_PRIVATE);
        prefs.edit().putString("records", record).commit();
        launch(); waitFor("Continue watching", 20, false);
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_DOWN);
        require(focused().contains("Me at the zoo") && focused().contains("Resume 0:05"), "Continue watching card did not receive focus in the second row");
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("Playback Playing", 45, true);
        boolean resumed = false;
        for (AccessibilityNodeInfo node : nodes()) {
            String value = node.getText() == null ? "" : node.getText().toString();
            if (value.matches("0:(0[5-9]|1[0-9]) / .*")) resumed = true;
        }
        require(resumed, "Playback did not start from the saved position");
        waitFor("Playback Ended", 30, true); key(KeyEvent.KEYCODE_BACK);
        waitFor("Find your next watch", 10, false);
        require(!texts().contains("Continue watching"), "Empty Continue watching row remained visible");
        org.json.JSONObject saved = new org.json.JSONObject(prefs.getString("records", "{}"));
        require(saved.getJSONArray("videos").length() == 0 && saved.getJSONArray("finished").toString().contains("jNQXAC9IVRw"), "Finished video was not removed and remembered");
        require(!focused().isEmpty(), "Focus was lost when the empty row disappeared");
        marker("TRIAL_CONTINUE secondRow=true savedPositionResumed=true actualEndRemoved=true emptyRowHidden=true focusPreserved=true");
    }
    private void searchReturn() {
        android.content.SharedPreferences genres = getTargetContext().getSharedPreferences("genre_preferences", android.content.Context.MODE_PRIVATE);
        java.util.Map<String, ?> before = genres.getAll();
        search("jNQXAC9IVRw");
        waitFor("Me at the zoo", 60, false);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        require(focused().contains("Me at the zoo"), "Pinned search result was not focused before opening");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("Playback Playing", 75, true);
        key(KeyEvent.KEYCODE_MEDIA_PAUSE);
        waitFor("Playback Paused", 10, true);
        key(KeyEvent.KEYCODE_BACK);
        waitFor("Me at the zoo", 15, false);
        require(focused().contains("Me at the zoo"), "Search result focus was not restored after playback");
        require(before.equals(genres.getAll()), "Search playback trained genre preferences");
        marker("TRIAL_SEARCH_RETURN pinnedTitle=true nativePlayback=true paused=true searchOriginRestored=true exactResultFocusRestored=true genrePreferencesUnchanged=true");
    }
    private void searchHistory() throws Exception {
        if (!"music".equals(historyStage)) {
        search("jNQXAC9IVRw"); waitFor("Me at the zoo", 60, false);
        openPinnedResult("Me at the zoo"); waitFor("Playback Playing", 75, true);
        SystemClock.sleep(2000);
        key(KeyEvent.KEYCODE_MEDIA_PAUSE); waitFor("Playback Paused", 10, true);
        key(KeyEvent.KEYCODE_BACK); waitFor("Me at the zoo", 15, false);
        require(focused().contains("Me at the zoo"), "Brief playback did not restore search focus");
        android.content.SharedPreferences prefs = getTargetContext().getSharedPreferences("continue_watching", android.content.Context.MODE_PRIVATE);
        org.json.JSONArray records = new org.json.JSONObject(prefs.getString("records", "{}")).getJSONArray("videos");
        require(records.length() == 1 && records.getJSONObject(0).getLong("position") >= 1000,
            "Brief Search playback was not saved before navigating back");
        require(records.getJSONObject(0).getLong("position") < 10000, "Brief observation ran too long");
        key(KeyEvent.KEYCODE_BACK); waitFor("Find your next watch", 15, false);
        waitFor("Continue watching", 15, false);
        key(KeyEvent.KEYCODE_DPAD_UP);
        require(focused().contains("Home"), "Home rail not selected");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_DOWN);
        require(focused().contains("Me at the zoo") && focused().contains("Resume"), "Brief search resume row not second or card unfocused");
        marker("SEARCH_HISTORY briefSearch2s=true savedBeforeBack=true searchFocusRestored=true continueSecondRow=true");
        }
        // Start the independent learning check with a fresh activity; Search retains its query.
        if (activity != null) {
        runOnMainSync(() -> activity.finish()); waitForIdleSync(); activity = null;
        }
        search("dQw4w9WgXcQ"); waitFor("Never Gonna Give You Up", 60, false);
        openPinnedResult("Never Gonna Give You Up", true); waitFor("Playback Playing", 75, true);
        observePlayback(35);
        key(KeyEvent.KEYCODE_MEDIA_PAUSE); waitFor("Playback Paused", 10, true);
        key(KeyEvent.KEYCODE_BACK); waitFor("Never Gonna Give You Up", 15, false);
        require(focused().contains("Never Gonna Give You Up"), "Music Search focus not restored");
        require(getTargetContext().getSharedPreferences("genre_preferences", android.content.Context.MODE_PRIVATE).getFloat("MUSIC", 0f) > 0,
            "Search music watch did not train preferences");
        key(KeyEvent.KEYCODE_BACK); waitFor("Find your next watch", 15, false);
        waitFor("Recommended", 15, false); waitFor("More of what you enjoy", 15, false);
        long readyDeadline = SystemClock.elapsedRealtime() + 45000;
        while ((texts().contains("Cancel\n") || texts().contains("Cancel loading")) && SystemClock.elapsedRealtime() < readyDeadline) SystemClock.sleep(500);
        key(KeyEvent.KEYCODE_DPAD_UP);
        require(focused().contains("Home"), "Home rail not focused before inspecting recommendations");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        require(!focused().contains("Try again") && !focused().contains("Cancel loading") && !focused().contains("Home"),
            "Recommended did not produce a focused card after Search learning");
        // Capture the verified screen to an emulator-only shell temp file before
        // the activity closes; no production file access or test API is added.
        try (android.os.ParcelFileDescriptor.AutoCloseInputStream command = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                ui().executeShellCommand("screencap -p /data/local/tmp/quietscreen-recommended-home.png"))) {
            byte[] buffer = new byte[1024]; int total = 0, read;
            while ((read = command.read(buffer)) >= 0) { total += read; require(total <= 8192, "Screenshot command output exceeded bound"); }
        }
        marker("SEARCH_HISTORY musicSearch35s=true learnedPrivately=true recommendedUpdatedOnHome=true boundedPlaybackStopped=true");
    }
    private void openPinnedResult(String title) {
        openPinnedResult(title, false);
    }
    private long playbackSeconds() {
        java.util.regex.Pattern position = java.util.regex.Pattern.compile("Playback position (\\d+) of (\\d+) seconds");
        for (AccessibilityNodeInfo node : nodes()) {
            java.util.regex.Matcher match = position.matcher(node.getContentDescription() == null ? "" : node.getContentDescription().toString());
            if (match.find()) return Long.parseLong(match.group(1));
        }
        throw new AssertionError("Accessible playback position unavailable");
    }
    private void waitPosition(long expected) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < deadline) { if (playbackSeconds() == expected) return; SystemClock.sleep(100); }
        throw new AssertionError("Expected paused position " + expected + ", found " + playbackSeconds());
    }
    private void waitFocused(String expected) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < deadline) { if (focused().contains(expected)) return; SystemClock.sleep(100); }
        throw new AssertionError("Expected focus " + expected + "; actual=" + focused());
    }
    private void capturePlayer(String path) throws Exception {
        require(path.equals("/data/local/tmp/quietscreen-player-controls.png") || path.equals("/data/local/tmp/quietscreen-player-recommendations.png") ||
            path.equals("/data/local/tmp/quietscreen-settings-explanations.png"), "Unexpected screenshot destination");
        try (android.os.ParcelFileDescriptor.AutoCloseInputStream command = new android.os.ParcelFileDescriptor.AutoCloseInputStream(ui().executeShellCommand("screencap -p " + path))) {
            byte[] buffer = new byte[1024]; int total = 0, read;
            while ((read = command.read(buffer)) >= 0) { total += read; require(total <= 8192, "Screenshot command output exceeded bound"); }
        }
    }
    private void waitPlayerHidden() {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < deadline) { if (!texts().contains("Recommended") && !texts().contains("Captions aren't") && !texts().contains("Video quality")) return; SystemClock.sleep(100); }
        throw new AssertionError("Back did not hide all playback menus");
    }
    private void playerOverlay() throws Exception {
        search("dQw4w9WgXcQ"); waitFor("Never Gonna Give You Up", 60, false);
        openPinnedResult("Never Gonna Give You Up", true); waitFor("Playback Playing", 75, true);
        observePlayback(2);
        key(KeyEvent.KEYCODE_MEDIA_PAUSE); waitFor("Playback Paused", 10, true);
        // The reveal helper may reach Options while waiting for the first
        // accessibility frame. Pause preserves that legitimate focus context.
        if (focused().contains("Captions") || focused().contains("Settings")) key(KeyEvent.KEYCODE_DPAD_DOWN);
        waitFocused("Play video");
        long before = playbackSeconds();
        key(KeyEvent.KEYCODE_DPAD_RIGHT); waitFocused("Captions"); waitPosition(before);
        key(KeyEvent.KEYCODE_DPAD_RIGHT); waitFocused("Settings"); waitPosition(before);
        key(KeyEvent.KEYCODE_DPAD_RIGHT); waitFocused("Play video");
        key(KeyEvent.KEYCODE_BACK); waitPlayerHidden();
        key(KeyEvent.KEYCODE_DPAD_RIGHT); require(!texts().contains("Recommended"), "Hidden seek revealed navigation buttons");
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFocused("Play video"); waitPosition(before + 10);
        key(KeyEvent.KEYCODE_BACK); waitPlayerHidden(); key(KeyEvent.KEYCODE_DPAD_LEFT);
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFocused("Play video"); waitPosition(before);
        SystemClock.sleep(1100); capturePlayer("/data/local/tmp/quietscreen-player-controls.png");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); waitFocused("Captions");
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("Captions aren't available in this version yet.", 10, false);
        key(KeyEvent.KEYCODE_BACK); waitPlayerHidden();
        key(KeyEvent.KEYCODE_DPAD_UP); waitFocused("Captions");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); require(focused().contains("Settings"), "Options Right did not navigate to Settings");
        waitPosition(before);
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("Video quality", 10, false);
        require(texts().contains("Audio track"), "Audio settings unavailable");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); waitFocused("Audio track");
        key(KeyEvent.KEYCODE_DPAD_LEFT); waitFocused("Video quality");
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("Auto (up to", 10, false);
        key(KeyEvent.KEYCODE_BACK); waitPlayerHidden();
        key(KeyEvent.KEYCODE_DPAD_UP); waitFocused("Settings");
        key(KeyEvent.KEYCODE_DPAD_DOWN); require(focused().contains("Play video"), "Down did not return to transport");
        waitPosition(before);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        long readyDeadline = SystemClock.elapsedRealtime() + 60000;
        while (SystemClock.elapsedRealtime() < readyDeadline &&
            (focused().contains("Cancel loading") || focused().contains("Try again") || focused().contains("Refresh") || focused().contains("Play video"))) SystemClock.sleep(300);
        String pinned = focused();
        require(!pinned.isEmpty() && !pinned.contains("Play video") && !pinned.contains("Try again") && !pinned.contains("Cancel loading") && !pinned.contains("Refresh"), "No focused recommendation card");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); waitPosition(before);
        key(KeyEvent.KEYCODE_DPAD_LEFT); require(pinned.equals(focused()), "Shelf arrows lost exact card focus");
        capturePlayer("/data/local/tmp/quietscreen-player-recommendations.png");
        marker("PLAYER_UX hiddenSeekRight10=true hiddenSeekLeft10=true visibleButtonsNavigate=true menuArrowsNavigate=true backHidesAllMenus=true ccExplained=true shelfArrowsNavigate=true screenshotsCaptured=true");
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("Playback Playing", 75, true);
        SystemClock.sleep(2000); key(KeyEvent.KEYCODE_MEDIA_PAUSE); waitFor("Playback Paused", 10, true);
        key(KeyEvent.KEYCODE_BACK); waitPlayerHidden();
        key(KeyEvent.KEYCODE_BACK); waitFor("Find your next watch", 15, false);
        require(!texts().contains("Title, channel or video ID"), "Second Back returned to Search instead of Home");
        org.json.JSONArray videos = new org.json.JSONObject(getTargetContext().getSharedPreferences("continue_watching", android.content.Context.MODE_PRIVATE)
            .getString("records", "{}")).getJSONArray("videos");
        require(videos.length() >= 2, "Recommendation switch lost previous/new resume checkpoints");
        AccessibilityNodeInfo settingsButton = null;
        for (AccessibilityNodeInfo node : nodes()) if (node.isClickable() && label(node).trim().equals("Settings")) { settingsButton = node; break; }
        require(settingsButton != null && settingsButton.performAction(AccessibilityNodeInfo.ACTION_FOCUS), "Home Settings unavailable");
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("Video quality", 15, false);
        for (int i = 0; i < 12 && !focused().contains("Automatic updates"); i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        require(focused().contains("Automatic updates"), "Update settings not reachable with remote");
        capturePlayer("/data/local/tmp/quietscreen-settings-explanations.png");
        marker("PLAYER_UX explicitNextVideoStarted=true bothResumeRecordsSaved=true secondBackReturnsHome=true updateSettingsReachable=true boundedPlaybackStopped=true");
    }
    private void openPinnedResult(String title, boolean longVideo) {
        AccessibilityNodeInfo result = null;
        for (int attempt = 0; attempt < 8 && result == null; attempt++) {
            for (AccessibilityNodeInfo node : nodes()) if (node.isClickable() && label(node).contains(title) &&
                (!longVideo || label(node).matches("(?s).*\\b[1-9]\\d{0,3}:[0-5]\\d\\b.*"))) { result = node; break; }
            if (result == null) {
                boolean scrolled = false;
                for (AccessibilityNodeInfo node : nodes()) if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { scrolled = true; break; }
                if (!scrolled) break;
                SystemClock.sleep(400);
            }
        }
        require(result != null && result.performAction(AccessibilityNodeInfo.ACTION_FOCUS), "Pinned result focus refused: " + title);
        SystemClock.sleep(400);
        require(focused().contains(title), "Pinned result focus not verified: " + title + "; focus=" + focused());
        result = null;
        for (AccessibilityNodeInfo node : nodes()) if (node.isClickable() && label(node).contains(title) && node.isFocused()) { result = node; break; }
        require(result != null && result.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Pinned result activation refused: " + title);
    }
    private void observePlayback(int seconds) {
        long until = SystemClock.elapsedRealtime() + seconds * 1000L;
        while (SystemClock.elapsedRealtime() < until) {
            require(!texts().contains("Try playback again"), "Player error during bounded watch observation");
            SystemClock.sleep(500);
        }
    }
    private void waitForStartCursor(String action) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        AccessibilityNodeInfo edit;
        do {
            edit = searchInput();
            if (edit.isFocused() && edit.getTextSelectionStart() == 0 && edit.getTextSelectionEnd() == 0) return;
            SystemClock.sleep(250);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError(action + ": focused=" + edit.isFocused() + ", selection=" + edit.getTextSelectionStart() + ":" + edit.getTextSelectionEnd());
    }
    private void searchCursor() {
        launch(); key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("Title, channel or video ID", 20, false);
        String original = "quietscreen cursor check";
        Bundle value = new Bundle();
        value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, original);
        require(searchInput().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value), "Query entry refused");
        SystemClock.sleep(500);
        runOnMainSync(() -> ((android.view.inputmethod.InputMethodManager)
            activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0));
        SystemClock.sleep(700);
        key(KeyEvent.KEYCODE_BACK); waitFor("Search videos", 10, false);
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor(original, 10, false);
        waitForStartCursor("Returning to Search did not place the caret at the beginning");
        key(KeyEvent.KEYCODE_A); key(KeyEvent.KEYCODE_B);
        require(("ab" + original).contentEquals(searchInput().getText()), "Typing moved the caret or lost the preserved query");
        // Refocusing the editor must reset its caret without resetting the query.
        runOnMainSync(() -> ((android.view.inputmethod.InputMethodManager)
            activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0));
        SystemClock.sleep(700);
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_LEFT);
        waitForStartCursor("Refocusing Search did not reset the caret");
        marker("TRIAL_SEARCH_CURSOR returnAtStart=true queryPreserved=true typingCaretPreserved=true refocusAtStart=true noSearchNetworkOrPlayback=true");
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
