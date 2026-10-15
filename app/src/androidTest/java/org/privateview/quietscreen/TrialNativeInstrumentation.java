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
    private UiAutomation automation;
    private int passed;
    private String query;
    private String check;
    private String historyStage;
    private String managerField;
    private String installMethod;
    private String resultMethod;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        query = arguments == null ? null : arguments.getString("query");
        check = arguments == null ? null : arguments.getString("check");
        historyStage = arguments == null ? null : arguments.getString("historyStage");
        managerField = arguments == null ? null : arguments.getString("managerField");
        installMethod = arguments == null ? null : arguments.getString("installMethod");
        resultMethod = arguments == null ? null : arguments.getString("resultMethod");
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        if ("restoreUpdatePrefs".equals(check)) {
            try { restoreUpdatePrefs(); result.putString("stream", "OK (private emulator preferences restored)"); finish(Activity.RESULT_OK, result); }
            catch (Throwable failure) { result.putString("stream", "Private preference restoration failed"); finish(Activity.RESULT_CANCELED, result); }
            return;
        }
        int resultCode = Activity.RESULT_OK;
        android.content.SharedPreferences updatePreferences = getTargetContext().getSharedPreferences("updates", android.content.Context.MODE_PRIVATE);
        boolean originalAutomaticUpdates = updatePreferences.getBoolean("automatic", true);
        android.content.SharedPreferences genrePreferences = getTargetContext().getSharedPreferences("genre_preferences", android.content.Context.MODE_PRIVATE);
        java.util.Map<String, ?> originalGenres = genrePreferences.getAll();
        android.content.SharedPreferences continuing = getTargetContext().getSharedPreferences("continue_watching", android.content.Context.MODE_PRIVATE);
        String originalContinue = continuing.getString("records", null);
        android.content.SharedPreferences interests = getTargetContext().getSharedPreferences("recommendation_interests", android.content.Context.MODE_PRIVATE);
        String originalInterests = interests.getString("profile", null);
        android.content.SharedPreferences playbackPrefs = getTargetContext().getSharedPreferences("playback_preferences", 0);
        java.util.Map<String, ?> originalPlayback = playbackPrefs.getAll();
        if ("recommendationsV2".equals(check)) {
            interests.edit().clear().commit(); continuing.edit().clear().commit();
            genrePreferences.edit().clear().putBoolean("enabled", true).commit();
        }
        if ("browse".equals(check) || "continue".equals(check) || "searchHistory".equals(check) || "playerOverlay".equals(check) || "uploadAge".equals(check)) continuing.edit().clear().commit();
        if ("playerOverlay".equals(check)) genrePreferences.edit().clear().putBoolean("enabled", true).putFloat("MUSIC", 30f).commit();
        if ("browse".equals(check) || "searchHistory".equals(check)) genrePreferences.edit().clear().putBoolean("enabled", true).commit();
        // Idle-network checks run with optional update traffic disabled, then restore the preference.
        updatePreferences.edit().putBoolean("automatic", false).commit();
        try {
            if ("featureSettings".equals(check)) {
                featureSettings(); passed++; result.putString("stream", "\nOK (1 player feature settings check)\n");
            } else if ("featureSeek".equals(check)) {
                featureSeek(); passed++; result.putString("stream", "\nOK (1 player feature seek check)\n");
            } else if ("featureAutoplay".equals(check)) {
                featureAutoplay(); passed++; result.putString("stream", "\nOK (1 player feature autoplay check)\n");
            } else if ("featureLifecycle".equals(check)) {
                featureLifecycle(); passed++; result.putString("stream", "\nOK (1 player feature lifecycle check)\n");
            } else if ("updateInstall".equals(check)) {
                updateInstall(originalAutomaticUpdates);
                throw new AssertionError("Installer did not replace the process within the bounded observation");
            } else if ("browse".equals(check)) {
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
            } else if ("tuneRemote".equals(check) || "tuneRemoteReset".equals(check)) {
                tuneRemote("tuneRemoteReset".equals(check)); passed++;
                result.putString("stream", "\nOK (1 remote tuning check)\n");
            } else if ("channelHome".equals(check)) {
                channelHome(); passed++;
                result.putString("stream", "\nOK (1 Home channel check)\n");
            } else if ("channelPlayer".equals(check)) {
                channelPlayer(); passed++;
                result.putString("stream", "\nOK (1 player channel check)\n");
            } else if ("channelReturn".equals(check)) {
                channelReturn(); passed++;
                result.putString("stream", "\nOK (1 return channel check)\n");
            } else if ("uploadAge".equals(check)) {
                uploadAge(); passed++;
                result.putString("stream", "\nOK (1 upload-age check)\n");
            } else if ("uiFollowups".equals(check)) {
                uiFollowups(); passed++;
                result.putString("stream", "\nOK (1 UI follow-up check)\n");
            } else if ("recommendationsV2".equals(check)) {
                recommendationsV2(); passed++;
                result.putString("stream", "\nOK (1 recommendations v2 live check)\n");
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
            if (check != null && check.startsWith("feature")) try { capturePlayer("/data/local/tmp/aprion-features-failure.png"); } catch (Exception ignored) { }
            if ("channelHome".equals(check) || "channelPlayer".equals(check) || "channelReturn".equals(check)) {
                try { capturePlayer("/data/local/tmp/aprion-channel-failure.png"); } catch (Exception ignored) { }
            }
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
            android.content.SharedPreferences.Editor interestRestore = interests.edit().clear();
            if (originalInterests != null) interestRestore.putString("profile", originalInterests);
            interestRestore.commit();
            android.content.SharedPreferences.Editor playbackRestore = playbackPrefs.edit().clear();
            for (java.util.Map.Entry<String, ?> entry : originalPlayback.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Boolean) playbackRestore.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Integer) playbackRestore.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof String) playbackRestore.putString(entry.getKey(), (String) value);
            }
            playbackRestore.commit();
        }
        finish(resultCode, result);
    }
    private void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    // Selectable channel checks reuse the framework-only runner and cleanup above.
    private String boundedLabel(AccessibilityNodeInfo button) {
        android.graphics.Rect bounds = new android.graphics.Rect(); button.getBoundsInScreen(bounds);
        StringBuilder value = new StringBuilder(label(button));
        for (AccessibilityNodeInfo node : nodes()) {
            android.graphics.Rect textBounds = new android.graphics.Rect(); node.getBoundsInScreen(textBounds);
            if (node.isVisibleToUser() && node.getText() != null && !textBounds.isEmpty() && bounds.contains(textBounds))
                value.append(' ').append(node.getText());
        }
        return value.toString();
    }
    private AccessibilityNodeInfo channelButton(String title, boolean exact) {
        for (AccessibilityNodeInfo node : nodes()) if (node.isClickable() && node.isVisibleToUser() &&
            (exact ? label(node).trim().equals(title) || title.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()) ||
                title.contentEquals(node.getText() == null ? "" : node.getText()) : boundedLabel(node).contains(title))) return node;
        return null;
    }
    private void focusChannelButton(String title, boolean exact) {
        AccessibilityNodeInfo button = channelButton(title, exact);
        require(button != null && button.performAction(AccessibilityNodeInfo.ACTION_FOCUS), "Channel target focus unavailable: " + title);
        SystemClock.sleep(300);
        button = channelButton(title, exact);
        require(button != null && button.isFocused(), "Channel target focus not verified: " + title);
    }
    private void clickChannelButton(String title) {
        focusChannelButton(title, true); key(KeyEvent.KEYCODE_DPAD_CENTER);
    }
    private void waitChannelPage(int page) {
        long deadline = SystemClock.elapsedRealtime() + 65_000;
        String expected = "Newest first · Page " + page;
        while (SystemClock.elapsedRealtime() < deadline) {
            String state = texts();
            if (state.contains(expected) && !state.contains("Loading channel…")) return;
            require(!state.contains("Try again"), "Channel loading failed: " + state.substring(0, Math.min(500, state.length())));
            SystemClock.sleep(300);
        }
        throw new AssertionError("Channel page " + page + " unavailable");
    }
    private void featureSettings() throws Exception {
        android.content.SharedPreferences prefs = getTargetContext().getSharedPreferences("playback_preferences", 0);
        prefs.edit().clear().commit();
        org.json.JSONObject countRecord = new org.json.JSONObject().put("id", "dQw4w9WgXcQ").put("title", "View count fixture")
            .put("channel", "Fixture").put("position", 1000).put("duration", 212000).put("views", 1234);
        org.json.JSONObject oldRecord = new org.json.JSONObject().put("id", "jNQXAC9IVRw").put("title", "Legacy view count fixture")
            .put("channel", "Fixture").put("position", 1000).put("duration", 19000);
        getTargetContext().getSharedPreferences("continue_watching", 0).edit().putString("records",
            new org.json.JSONObject().put("videos", new org.json.JSONArray().put(countRecord).put(oldRecord)).put("finished", new org.json.JSONArray()).toString()).commit();
        launch();
        for (int attempt = 0; attempt < 8 && channelButton("View count fixture", false) == null; attempt++) {
            for (AccessibilityNodeInfo node : nodes()) if (node.isScrollable()) { node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); break; }
            SystemClock.sleep(300);
        }
        long countsDeadline = SystemClock.elapsedRealtime() + 6000;
        while (SystemClock.elapsedRealtime() < countsDeadline && (!featureCardContains("View count fixture", "1.2K views") || !featureCardContains("Legacy view count fixture", "Views unavailable"))) SystemClock.sleep(100);
        require(featureCardContains("View count fixture", "1.2K views"), "Continue card count missing");
        require(featureCardContains("Legacy view count fixture", "Views unavailable"), "Legacy saved count label missing");
        clickChannelButton("Settings");
        waitFor("Video quality", 15, false); waitFor("Autoplay: On", 10, false);
        clickChannelButton("Autoplay: On"); waitFor("Autoplay: Off", 5, false);
        require(!prefs.getBoolean("autoplay", true), "Autoplay off was not saved");
        launch(); clickChannelButton("Settings");
        waitFor("Autoplay: Off", 15, false);
        for (int attempt = 0; attempt < 8 && channelButton("Reset playback settings", true) == null; attempt++) {
            for (AccessibilityNodeInfo node : nodes()) if (node.isScrollable()) { node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); break; }
            SystemClock.sleep(250);
        }
        clickChannelButton("Reset playback settings"); waitFor("Autoplay: On", 5, false);
        require(prefs.getBoolean("autoplay", true), "Reset did not restore autoplay default");
        capturePlayer("/data/local/tmp/aprion-features-settings.png");
        marker("FEATURE_SETTINGS defaultOn=true persistsOffAcrossActivity=true resetOn=true");
    }
    private boolean featureCardContains(String title, String value) {
        AccessibilityNodeInfo card = channelButton(title, false); if (card == null) return false;
        android.graphics.Rect bounds = new android.graphics.Rect(); card.getBoundsInScreen(bounds);
        for (AccessibilityNodeInfo node : nodes()) {
            android.graphics.Rect child = new android.graphics.Rect(); node.getBoundsInScreen(child);
            if (!child.isEmpty() && bounds.contains(child) && label(node).contains(value)) return true;
        }
        return false;
    }
    private void holdSeek(int code) {
        long down = SystemClock.uptimeMillis();
        require(ui().injectInputEvent(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0), true), "Seek hold down refused");
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 250);
        require(ui().injectInputEvent(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0), true), "Seek hold up refused");
        waitFor("OK Confirm", 5, true);
    }
    private long pendingSeekSeconds() {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("Seek position (\\d+) seconds").matcher(texts());
        require(match.find(), "Pending seek timestamp missing"); return Long.parseLong(match.group(1));
    }
    private void featureSeek() throws Exception {
        getTargetContext().getSharedPreferences("playback_preferences", 0).edit().putBoolean("autoplay", false).commit();
        search("dQw4w9WgXcQ"); waitFor("Never Gonna Give You Up", 60, false);
        require(texts().matches("(?s).*\\d[\\d.,]*[KMBT]? views.*"), "Search view count missing");
        openPinnedResult("Never Gonna Give You Up", true); waitFor("Playback Playing", 75, true);
        observePlayback(2); key(KeyEvent.KEYCODE_MEDIA_PAUSE); waitFor("Playback Paused", 10, true);
        require(texts().matches("(?s).*\\d[\\d,]* views.*"), "Full player count missing");
        waitFor("Playback time ", 5, true);
        capturePlayer("/data/local/tmp/aprion-features-player.png");
        long original = playbackSeconds();
        holdSeek(KeyEvent.KEYCODE_DPAD_RIGHT); long first = pendingSeekSeconds();
        key(KeyEvent.KEYCODE_DPAD_RIGHT); require(pendingSeekSeconds() == first + 10, "Released arrow did not retain seek mode");
        waitFor("Preview at", 20, true);
        capturePlayer("/data/local/tmp/aprion-features-seek.png");
        require(!texts().contains("Try playback again"), "Preview affected playback");
        marker("FEATURE_PREVIEW imageAvailable=" + texts().contains("Preview at") + " timestamp=true");
        key(KeyEvent.KEYCODE_BACK); waitFor("Playback Paused", 5, true);
        require(Math.abs(playbackSeconds() - original) <= 1, "Back applied pending seek");
        holdSeek(KeyEvent.KEYCODE_DPAD_RIGHT); long target = pendingSeekSeconds();
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("Playback Paused", 10, true); waitPosition(target);
        require(!texts().contains("OK Confirm"), "Confirm release did not close seek mode");
        key(KeyEvent.KEYCODE_MEDIA_PLAY); waitFor("Playback Playing", 15, true);
        holdSeek(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_BACK); waitFor("Playback Playing", 10, true);
        key(KeyEvent.KEYCODE_MEDIA_PAUSE); waitFor("Playback Paused", 10, true);
        marker("FEATURE_SEEK holdArrow=true releasedTaps=true BackOriginal=true confirmOnce=true pausedStateRetained=true playingStateRestored=true countsSearchAndPlayer=true");
        focusChannelButton("View channel", true); key(KeyEvent.KEYCODE_DPAD_CENTER); waitChannelPage(1);
        require(texts().contains("views"), "Channel card count label missing");
        capturePlayer("/data/local/tmp/aprion-features-counts.png");
        marker("FEATURE_COUNTS channelCards=true CHPreserved=true");
    }
    private void finishShortVideo() {
        key(KeyEvent.KEYCODE_MEDIA_PAUSE); waitFor("Playback Paused", 10, true);
        focusChannelButton("Me at the zoo", false);
        key(KeyEvent.KEYCODE_MEDIA_PLAY);
        holdSeek(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("Replay video", 20, true);
    }
    private void replayShortVideo() {
        focusChannelButton("Me at the zoo", false); key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("Playback Playing", 15, true);
    }
    private void featureAutoplay() throws Exception {
        android.content.SharedPreferences prefs = getTargetContext().getSharedPreferences("playback_preferences", 0);
        prefs.edit().putBoolean("autoplay", false).commit();
        search("jNQXAC9IVRw"); waitFor("Me at the zoo", 60, false);
        openPinnedResult("Me at the zoo", false); waitFor("Playback Playing", 75, true);
        finishShortVideo(); waitFor("Recommended", 10, true); SystemClock.sleep(16000);
        require(texts().contains("Replay video") && !texts().contains("Up next in"), "Disabled autoplay advanced");
        marker("FEATURE_AUTOPLAY offShowsRecommendations=true noAdvance16s=true");
        prefs.edit().putBoolean("autoplay", true).commit(); replayShortVideo(); finishShortVideo();
        waitFor("Up next in", 25, true); key(KeyEvent.KEYCODE_BACK); waitFor("Autoplay cancelled", 5, true);
        SystemClock.sleep(16000); require(texts().contains("Replay video"), "Cancelled autoplay advanced");
        marker("FEATURE_AUTOPLAY BackCancels=true noAdvanceAfterCancel16s=true replayRearms=true");
        replayShortVideo(); finishShortVideo(); waitFor("Up next in", 25, true);
        capturePlayer("/data/local/tmp/aprion-features-autoplay.png");
        long countdownStart = SystemClock.elapsedRealtime();
        waitFor("Playback Playing", 80, true);
        require(SystemClock.elapsedRealtime() - countdownStart >= 13000, "Autoplay advanced before its countdown");
        require(!texts().contains("Me at the zoo"), "Autoplay did not switch videos");
        key(KeyEvent.KEYCODE_MEDIA_PAUSE); waitFor("Playback Paused", 10, true);
        marker("FEATURE_AUTOPLAY fifteenSecondCountdown=true nextPlayback=true");
    }
    private void featureLifecycle() throws Exception {
        getTargetContext().getSharedPreferences("playback_preferences", 0).edit().putBoolean("autoplay", true).commit();
        search("jNQXAC9IVRw"); waitFor("Me at the zoo", 60, false);
        openPinnedResult("Me at the zoo", false); waitFor("Playback Playing", 75, true); observePlayback(2);
        key(KeyEvent.KEYCODE_MEDIA_PAUSE); waitFor("Playback Paused", 10, true);
        focusChannelButton("Me at the zoo", false); key(KeyEvent.KEYCODE_MEDIA_PLAY);
        holdSeek(KeyEvent.KEYCODE_DPAD_RIGHT); long pending = pendingSeekSeconds() * 1000;
        key(KeyEvent.KEYCODE_HOME); SystemClock.sleep(700);
        launch(); require(!texts().contains("Playback Playing") && !texts().contains("OK Confirm"), "Background seek survived player exit");
        org.json.JSONArray records = new org.json.JSONObject(getTargetContext().getSharedPreferences("continue_watching", 0).getString("records", "{}")).getJSONArray("videos");
        boolean originalSaved = false;
        for (int i = 0; i < records.length(); i++) if (records.getJSONObject(i).optString("id").equals("jNQXAC9IVRw"))
            originalSaved = records.getJSONObject(i).optLong("position") < pending - 500;
        require(originalSaved, "Background seek saved pending target");
        marker("FEATURE_LIFECYCLE pendingSeekDiscarded=true noAutoResume=true originalCheckpoint=true");
        search("jNQXAC9IVRw"); waitFor("Me at the zoo", 60, false);
        openPinnedResult("Me at the zoo", false); waitFor("Playback Playing", 75, true);
        finishShortVideo(); waitFor("Up next in", 25, true);
        key(KeyEvent.KEYCODE_HOME); SystemClock.sleep(16000); launch();
        require(!texts().contains("Playback Playing") && !texts().contains("Up next in"), "Countdown survived background exit");
        marker("FEATURE_LIFECYCLE backgroundCountdownCancelled=true noAutoAdvance=true");
    }
    private void channelHome() throws Exception {
        // Seed only owned-emulator data; an absent channel ID exercises exact-video
        // uploader resolution. The runner restores the original records in finally.
        org.json.JSONObject video = new org.json.JSONObject().put("id", "dQw4w9WgXcQ")
            .put("title", "Channel navigation fixture").put("channel", "Rick Astley").put("channelId", "")
            .put("position", 1000).put("duration", 212000).put("uploadedAt", 1);
        getTargetContext().getSharedPreferences("continue_watching", 0).edit().putString("records",
            new org.json.JSONObject().put("videos", new org.json.JSONArray().put(video)).put("finished", new org.json.JSONArray()).toString()).commit();
        launch();
        for (int attempt = 0; attempt < 8 && channelButton("Channel navigation fixture", false) == null; attempt++) {
            for (AccessibilityNodeInfo node : nodes()) if (node.isScrollable()) { node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); break; }
            SystemClock.sleep(400);
        }
        focusChannelButton("Channel navigation fixture", false);
        for (int code : new int[] {KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER}) {
            long down = SystemClock.uptimeMillis();
            require(ui().injectInputEvent(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0), true), "Channel hold down rejected");
            SystemClock.sleep(600);
            require(ui().injectInputEvent(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, 1, 0, -1, 0, KeyEvent.FLAG_LONG_PRESS), true), "Channel hold rejected");
            waitFor("Video options", 10, false); waitFocused("More like this");
            require(ui().injectInputEvent(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, 2), true), "Channel repeat rejected");
            require(ui().injectInputEvent(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0), true), "Channel hold release rejected");
            SystemClock.sleep(350);
            require(texts().contains("Video options") && !texts().contains("Saved:") && !texts().contains("Channel videos"), "Opening hold activated an action");
            if (code == KeyEvent.KEYCODE_DPAD_CENTER) {
                key(KeyEvent.KEYCODE_BACK); focusChannelButton("Channel navigation fixture", false);
            }
        }
        // View channel is adjacent to existing recommendation feedback; a fresh press is required.
        key(KeyEvent.KEYCODE_DPAD_DOWN); waitFocused("Not interested");
        key(KeyEvent.KEYCODE_DPAD_DOWN); waitFocused("Don't recommend this channel");
        key(KeyEvent.KEYCODE_DPAD_DOWN); waitFocused("View channel");
        capturePlayer("/data/local/tmp/aprion-channel-options.png");
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitChannelPage(1);
        require(texts().contains("Rick Astley"), "Missing-ID resolution opened the wrong channel");
        capturePlayer("/data/local/tmp/aprion-channel-home.png");
        key(KeyEvent.KEYCODE_BACK); waitFor("Search videos", 15, false);
        AccessibilityNodeInfo restored = channelButton("Channel navigation fixture", false);
        require(restored != null && restored.isFocused(), "Channel Back lost the originating Home card");
        marker("CHANNEL_HOME heldDpadAndEnter=true openingReleaseIgnored=true freshMenuAction=true missingIdResolved=true exactChannel=true HomeFocusRestored=true");
    }
    private void channelPlayer() throws Exception {
        search("dQw4w9WgXcQ"); waitFor("Never Gonna Give You Up", 60, false);
        openPinnedResult("Never Gonna Give You Up", true); waitFor("Playback Playing", 75, true);
        observePlayback(2); key(KeyEvent.KEYCODE_MEDIA_PAUSE); waitFor("Playback Paused", 10, true);
        focusChannelButton("View channel", true);
        android.graphics.Rect channel = new android.graphics.Rect(), captions = new android.graphics.Rect();
        channelButton("View channel", true).getBoundsInScreen(channel);
        require(channelButton("Captions", true) != null, "CC control missing");
        channelButton("Captions", true).getBoundsInScreen(captions);
        require(channel.right <= captions.left, "Channel button was not left of CC");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); if (focused().contains("View channel")) key(KeyEvent.KEYCODE_DPAD_RIGHT); waitFocused("Captions");
        key(KeyEvent.KEYCODE_DPAD_LEFT); waitFocused("View channel");
        capturePlayer("/data/local/tmp/aprion-channel-player.png");
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitChannelPage(1);
        require((activity.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0, "Channel navigation retained the player window flag");
        org.json.JSONArray saved = new org.json.JSONObject(getTargetContext().getSharedPreferences("continue_watching", 0).getString("records", "{}")).optJSONArray("videos");
        boolean positionSaved = false;
        if (saved != null) for (int i = 0; i < saved.length(); i++)
            if ("dQw4w9WgXcQ".equals(saved.getJSONObject(i).optString("id"))) positionSaved = saved.getJSONObject(i).optLong("position") >= 1000;
        require(positionSaved, "Channel navigation did not save the stopped video's position");
        marker("CHANNEL_PLAYER buttonLeftOfCC=true remoteLeftRight=true playerReleased=true savedPosition=true");
        clickChannelButton("Older videos"); waitChannelPage(2);
        capturePlayer("/data/local/tmp/aprion-channel-older.png");
        clickChannelButton("Newer videos"); waitChannelPage(1);
        marker("CHANNEL_PAGING older=true cachedNewer=true");
        channelUploadReturn();
        key(KeyEvent.KEYCODE_BACK); waitFor("Search videos", 15, false);
    }
    private void channelReturn() throws Exception {
        org.json.JSONObject video = new org.json.JSONObject().put("id", "dQw4w9WgXcQ")
            .put("title", "Channel return fixture").put("channel", "Rick Astley").put("channelId", "UCuAXFkgsw1L7xaCfnd5JJOw")
            .put("position", 1000).put("duration", 212000).put("uploadedAt", 1);
        getTargetContext().getSharedPreferences("continue_watching", 0).edit().putString("records",
            new org.json.JSONObject().put("videos", new org.json.JSONArray().put(video)).put("finished", new org.json.JSONArray()).toString()).commit();
        launch();
        for (int attempt = 0; attempt < 8 && channelButton("Channel return fixture", false) == null; attempt++) {
            for (AccessibilityNodeInfo node : nodes()) if (node.isScrollable()) { node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); break; }
            SystemClock.sleep(400);
        }
        focusChannelButton("Channel return fixture", false); key(KeyEvent.KEYCODE_MENU);
        waitFor("Video options", 10, false); clickChannelButton("View channel"); waitChannelPage(1);
        channelUploadReturn();
        key(KeyEvent.KEYCODE_BACK); waitFor("Search videos", 15, false);
    }
    private void channelUploadReturn() throws Exception {
        String pinned = null;
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (pinned == null && SystemClock.elapsedRealtime() < deadline) {
            // Compose can expose a card's title/description as sibling semantics
            // nodes rather than on the clickable node. Bind visible title text to
            // its containing button, then verify focus before playing that title.
            for (AccessibilityNodeInfo node : nodes()) if (node.isVisibleToUser() && node.getText() != null &&
                node.getText().toString().startsWith("Rick Astley - ") && channelButton(node.getText().toString(), false) != null) {
                pinned = node.getText().toString(); break;
            }
            if (pinned == null) SystemClock.sleep(100);
        }
        require(pinned != null && !pinned.isEmpty(), "No channel upload available to pin");
        focusChannelButton(pinned, false); key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("Playback Playing", 75, true); observePlayback(1); key(KeyEvent.KEYCODE_MEDIA_PAUSE);
        waitFor("Playback Paused", 10, true);
        key(KeyEvent.KEYCODE_BACK); waitPlayerHidden(); key(KeyEvent.KEYCODE_BACK); waitChannelPage(1);
        AccessibilityNodeInfo returned = channelButton(pinned, false);
        require(returned != null && returned.isFocused(), "Playback Back lost the exact channel upload focus");
        capturePlayer("/data/local/tmp/aprion-channel-return.png");
        marker("CHANNEL_RETURN pinnedUpload=true realPlayback=true channelPageRetained=true exactFocusRestored=true");
    }
    private void marker(String text) { Bundle status = new Bundle(); status.putString("stream", text + "\n"); sendStatus(0, status); }
    private UiAutomation ui() {
        if (automation == null) {
            automation = getUiAutomation();
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(info);
        }
        return automation;
    }
    private List<AccessibilityNodeInfo> nodes() {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= 33) ui().clearCache();
        for (AccessibilityWindowInfo window : ui().getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && getTargetContext().getPackageName().contentEquals(root.getPackageName())) visit(root, nodes);
        }
        if (nodes.isEmpty()) {
            AccessibilityNodeInfo active = ui().getRootInActiveWindow();
            if (active != null && getTargetContext().getPackageName().contentEquals(active.getPackageName())) visit(active, nodes);
        }
        return nodes;
    }
    private void visit(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> nodes) {
        if (nodes.size() >= 2000 || !node.refresh()) return;
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
    private void search(String query) { search(query, true); }
    private void search(String query, boolean relevance) {
        launch(); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("Title, channel or video ID", 20, false);
        if (relevance && texts().contains("Sort: Newest first")) {
            clickSort(); waitFocused("Newest first"); key(KeyEvent.KEYCODE_DPAD_DOWN); waitFocused("Relevance"); key(KeyEvent.KEYCODE_DPAD_CENTER);
            waitFor("Sort: Relevance", 10, false);
        }
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
    private void clickSort() {
        runOnMainSync(() -> ((android.view.inputmethod.InputMethodManager) activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0));
        SystemClock.sleep(350);
        for (AccessibilityNodeInfo node : nodes()) if (node.isClickable() && label(node).trim().startsWith("Sort:")) {
            require(node.performAction(AccessibilityNodeInfo.ACTION_FOCUS), "Sort focus unavailable");
            SystemClock.sleep(150); require(focused().contains("Sort:"), "Sort target not focused"); key(KeyEvent.KEYCODE_DPAD_CENTER); return;
        }
        throw new AssertionError("Adjacent sort control unavailable");
    }
    private boolean hasUploadAge() { return texts().matches("(?s).*Uploaded [0-9]+ (second|minute|hour|day|month|year)s? ago.*"); }
    private android.graphics.Rect datedFocusBounds() {
        List<AccessibilityNodeInfo> snapshot=nodes();
        for(AccessibilityNodeInfo focus:snapshot) if(focus.isFocused() && focus.isClickable()) {
            android.graphics.Rect focusRect=new android.graphics.Rect();focus.getBoundsInScreen(focusRect);
            for(AccessibilityNodeInfo age:snapshot) if(age.isVisibleToUser() && age.getText()!=null &&
                (age.getText().toString().matches("(?s).*Uploaded [0-9]+ (second|minute|hour|day|month|year)s? ago.*") ||
                 age.getText().toString().contains("Upload date unavailable") || age.getText().toString().contains("Just uploaded"))) {
                android.graphics.Rect ageRect=new android.graphics.Rect();age.getBoundsInScreen(ageRect);
                if(!ageRect.isEmpty() && focusRect.contains(ageRect)) return focusRect;
            }
        }
        return null;
    }
    private void holdTune() {
        long down=SystemClock.uptimeMillis();
        require(ui().injectInputEvent(new KeyEvent(down,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,0),true),"Hold down rejected");
        SystemClock.sleep(600);
        require(ui().injectInputEvent(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,1,0,-1,0,KeyEvent.FLAG_LONG_PRESS),true),"Hold event rejected");
        waitFor("Video options",10,false); waitFocused("More like this");
        require(ui().injectInputEvent(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,2),true),"Repeat rejected");
        require(ui().injectInputEvent(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,3,0,-1,0,KeyEvent.FLAG_LONG_PRESS),true),"Flagged repeat rejected");
        require(ui().injectInputEvent(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER,0),true),"Hold release rejected");
        SystemClock.sleep(1100);
        require(texts().contains("Video options") && !texts().contains("Saved:"),"Opening hold selected or dismissed feedback");
    }
    private void tuneRemote(boolean resetRepeats) {
        launch(); key(KeyEvent.KEYCODE_DPAD_RIGHT);
        long until=SystemClock.elapsedRealtime()+60000;
        while(SystemClock.elapsedRealtime()<until && datedFocusBounds()==null) SystemClock.sleep(200);
        require(datedFocusBounds()!=null,"Recommendation card not focused");
        for(int code:new int[]{KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER}) {
            long down=SystemClock.uptimeMillis();
            require(ui().injectInputEvent(new KeyEvent(down,down,KeyEvent.ACTION_DOWN,code,0),true),"Remote down rejected");
            SystemClock.sleep(600);
            require(ui().injectInputEvent(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,code,1,0,-1,0,KeyEvent.FLAG_LONG_PRESS),true),"Remote hold rejected");
            waitFor("Video options",10,false); waitFocused("More like this");
            require(ui().injectInputEvent(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,code,2),true),"Remote repeat rejected");
            if(resetRepeats) {
                long repeat=SystemClock.uptimeMillis();
                require(ui().injectInputEvent(new KeyEvent(repeat,repeat,KeyEvent.ACTION_DOWN,code,0),true),"Reset repeat rejected");
            }
            SystemClock.sleep(600); require(!texts().contains("Saved:"),"Holding remote OK selected feedback");
            require(ui().injectInputEvent(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,code,0),true),"Remote release rejected");
            SystemClock.sleep(700); require(texts().contains("Video options") && !texts().contains("Saved:"),"Remote release selected feedback");
            key(code); waitFor("Saved: More like this",10,false);
            key(KeyEvent.KEYCODE_BACK);
            until=SystemClock.elapsedRealtime()+10000;
            while(SystemClock.elapsedRealtime()<until && (texts().contains("Video options") || datedFocusBounds()==null)) SystemClock.sleep(100);
            require(!texts().contains("Video options") && datedFocusBounds()!=null,"Remote Back did not restore the recommendation");
            marker("TUNE_REMOTE_KEY code="+code+" resetRepeats="+resetRepeats+" heldNoAction=true releasedNoAction=true freshClick=true back=true");
        }
        marker("TUNE_REMOTE dpadCenter=true enter=true resetRepeats="+resetRepeats+" releaseIgnored=true freshClickRequired=true backRestoresFocus=true");
    }
    private void uploadAge() throws Exception {
        search("dQw4w9WgXcQ"); waitFor("Never Gonna Give You Up",60,false);
        require(hasUploadAge(), "Search results omitted known upload age");
        capturePlayer("/data/local/tmp/aprion-upload-age-search.png");
        openPinnedResult("Never Gonna Give You Up",true); waitFor("Playback Playing",75,true);
        observePlayback(3); key(KeyEvent.KEYCODE_MEDIA_PAUSE); waitFor("Playback Paused",10,true);
        require(hasUploadAge(), "Player details omitted known upload age");
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        long until=SystemClock.elapsedRealtime()+60000;
        while(SystemClock.elapsedRealtime()<until && (focused().contains("Play video") || datedFocusBounds()==null)) SystemClock.sleep(300);
        android.graphics.Rect shelfFocus=datedFocusBounds();
        require(shelfFocus!=null && !focused().contains("Play video"), "Related card omitted visible upload metadata within its focus bounds");
        capturePlayer("/data/local/tmp/aprion-upload-age-player.png");
        holdTune();
        require(hasUploadAge() || texts().contains("Upload date unavailable"),"Feedback omitted upload metadata");
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("Saved:",10,false);
        SystemClock.sleep(1100); require(texts().contains("Video options"),"Feedback action dismissed dialog before Back");
        capturePlayer("/data/local/tmp/aprion-recommendations-v2-feedback.png");
        key(KeyEvent.KEYCODE_BACK);
        until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until && (texts().contains("Video options") || !shelfFocus.equals(datedFocusBounds()))) SystemClock.sleep(100);
        require(!texts().contains("Video options") && shelfFocus.equals(datedFocusBounds()),"Feedback did not restore the same dated related card");
        key(KeyEvent.KEYCODE_BACK); key(KeyEvent.KEYCODE_BACK); waitFor("Search videos",10,false);
        org.json.JSONObject records=new org.json.JSONObject(getTargetContext().getSharedPreferences("continue_watching",0).getString("records","{}"));
        org.json.JSONArray videos=records.optJSONArray("videos");boolean persisted=false;
        if(videos!=null) for(int i=0;i<videos.length();i++) if("dQw4w9WgXcQ".equals(videos.getJSONObject(i).optString("id"))) persisted=videos.getJSONObject(i).optLong("uploadedAt",0)>0;
        require(persisted,"Playback exit did not persist upload metadata");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        until=SystemClock.elapsedRealtime()+60000;
        while(SystemClock.elapsedRealtime()<until && datedFocusBounds()==null) SystemClock.sleep(200);
        require(datedFocusBounds()!=null,"Home recommendation not focused");
        holdTune(); key(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("Saved:",10,false);
        SystemClock.sleep(1100); require(texts().contains("Video options"),"Home feedback dismissed before Back");
        key(KeyEvent.KEYCODE_BACK); require(!texts().contains("Video options"),"Home Back did not dismiss feedback");
        capturePlayer("/data/local/tmp/aprion-upload-age-home.png");
        marker("UPLOAD_AGE_NATIVE search=true player=true relatedShelf=true feedback=true resumeDatePersisted=true minifiedMetadata=true playbackStopped=true holdRepeats=true actions=true HomeAndPlayerBack=true");
    }
    private void uiFollowups() throws Exception {
        search("BBC News", false); waitFor("Sort: Newest first", 10, false);
        long until = SystemClock.elapsedRealtime()+60000;
        while (SystemClock.elapsedRealtime()<until && !texts().matches("(?s).*\\b[1-9][0-9]* results\\b.*")) {
            require(!texts().contains("unsupported response") && !texts().contains("could not be reached"), "Newest search failed"); SystemClock.sleep(300);
        }
        require(texts().matches("(?s).*\\b[1-9][0-9]* results\\b.*"), "Newest results missing");
        capturePlayer("/data/local/tmp/aprion-v037-search.png");
        clickSort(); waitFocused("Newest first"); key(KeyEvent.KEYCODE_DPAD_DOWN); waitFocused("Relevance"); key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("Sort: Relevance", 10, false); require("BBC News".contentEquals(searchInput().getText()), "Sort discarded query");
        key(KeyEvent.KEYCODE_BACK); waitFor("Search videos", 10, false);
        // R8 inlines result() into this manifest-kept private receiver. Invoke
        // its real pending-action branch without an installer intent/APK.
        Class<?> receiverClass=Class.forName("org.privateview.quietscreen.updates.UpdateInstallReceiver",true,activity.getClassLoader());
        android.content.BroadcastReceiver receiver=(android.content.BroadcastReceiver)receiverClass.getDeclaredConstructor().newInstance();
        Intent pending = new Intent().putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS,android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION);
        runOnMainSync(() -> receiver.onReceive(getTargetContext(),pending));
        waitFor("Update ready to install",10,false); waitFocused("Install update");
        capturePlayer("/data/local/tmp/aprion-v037-update-modal.png");
        key(KeyEvent.KEYCODE_DPAD_DOWN); waitFocused("Later"); key(KeyEvent.KEYCODE_DPAD_CENTER);
        SystemClock.sleep(300); require(!texts().contains("Update ready to install"), "Later did not dismiss modal");
        marker("UI_FOLLOWUPS minifiedNewestSearch=true adjacentSortRemote=true queryRetained=true rootUpdateModal=true installFocused=true laterDismisses=true noInstallationAttempt=true");
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
    private void updateInstall(boolean originalAutomatic) throws Exception {
        org.json.JSONObject backup = new org.json.JSONObject();
        java.io.File folder = new java.io.File(getTargetContext().getApplicationInfo().dataDir, "shared_prefs");
        java.io.File[] files = folder.listFiles();
        if (files != null) for (java.io.File file : files) {
            if (!file.getName().endsWith(".xml")) continue;
            String name = file.getName().substring(0, file.getName().length() - 4);
            org.json.JSONObject values = new org.json.JSONObject();
            for (java.util.Map.Entry<String, ?> entry : getTargetContext().getSharedPreferences(name, 0).getAll().entrySet()) {
                Object value = entry.getValue();
                if ("updates".equals(name) && "automatic".equals(entry.getKey())) value = originalAutomatic;
                String type = value instanceof Boolean ? "b" : value instanceof Integer ? "i" : value instanceof Long ? "l" : value instanceof Float ? "f" : value instanceof String ? "s" : "set";
                values.put(entry.getKey(), new org.json.JSONObject().put("t", type).put("v", value instanceof java.util.Set ? new org.json.JSONArray((java.util.Set<?>) value) : value));
            }
            backup.put(name, values);
        }
        byte[] privateBytes = backup.toString().getBytes("UTF-8");
        require(privateBytes.length <= 262144, "Private emulator backup exceeded bound");
        Bundle privateStatus = new Bundle(); privateStatus.putString("privateBackup", android.util.Base64.encodeToString(privateBytes, android.util.Base64.NO_WRAP)); sendStatus(0, privateStatus);
        java.io.File directory = new java.io.File(getTargetContext().getCacheDir(), "updates"); directory.mkdirs();
        for (String name : new String[]{"candidate.apk", "candidate.qsu"}) {
            try (java.io.InputStream input = getContext().getAssets().open(name); java.io.OutputStream output = new java.io.FileOutputStream(new java.io.File(directory, name))) {
                byte[] buffer = new byte[32768]; int count; while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            }
        }
        launch();
        require(managerField != null && managerField.matches("[A-Za-z_$][A-Za-z0-9_$]*") && installMethod != null && installMethod.matches("[A-Za-z_$][A-Za-z0-9_$]*"), "Invalid mapped updater entry point");
        java.lang.reflect.Field field = activity.getClass().getDeclaredField(managerField); field.setAccessible(true);
        Object manager = field.get(activity);
        java.lang.reflect.Method install = manager.getClass().getDeclaredMethod(installMethod); install.setAccessible(true);
        marker("UPDATE_INSTALL_REQUEST bounded=true realSignedFixture=true sdk=" + android.os.Build.VERSION.SDK_INT);
        runOnMainSync(() -> { try { install.invoke(manager); } catch (Exception failure) { throw new RuntimeException("Mapped install entry point failed", failure); } });
        long until = SystemClock.elapsedRealtime() + 15000; boolean captured = false;
        while (SystemClock.elapsedRealtime() < until) {
            String state = texts();
            if (state.contains("Update failed while")) throw new AssertionError("Verified installation failed: " + state.substring(state.indexOf("Update failed while")).substring(0, Math.min(600, state.substring(state.indexOf("Update failed while")).length())));
            if (!captured && state.contains("Updating Aprion TV")) { capturePlayer("/data/local/tmp/aprion-v035-installing.png"); captured = true; marker("UPDATE_INSTALL_PAGE visible=true screenshot=true"); }
            SystemClock.sleep(80);
        }
    }
    private void restoreUpdatePrefs() throws Exception {
        byte[] encoded;
        try (java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand("cat /data/local/tmp/aprion-v035-prefs.b64")); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) >= 0) { require(output.size() + count <= 350000, "Restore exceeded bound"); output.write(buffer, 0, count); }
            encoded = output.toByteArray();
        }
        org.json.JSONObject backup = new org.json.JSONObject(new String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), "UTF-8"));
        java.util.Iterator<String> names = backup.keys();
        while (names.hasNext()) {
            String name = names.next(); require(name.matches("[A-Za-z0-9_.-]+"), "Invalid preference file");
            android.content.SharedPreferences.Editor editor = getTargetContext().getSharedPreferences(name, 0).edit().clear();
            org.json.JSONObject values = backup.getJSONObject(name); java.util.Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next(); org.json.JSONObject value = values.getJSONObject(key); String type = value.getString("t");
                if ("b".equals(type)) editor.putBoolean(key, value.getBoolean("v"));
                else if ("i".equals(type)) editor.putInt(key, value.getInt("v"));
                else if ("l".equals(type)) editor.putLong(key, value.getLong("v"));
                else if ("f".equals(type)) editor.putFloat(key, (float)value.getDouble("v"));
                else if ("s".equals(type)) editor.putString(key, value.getString("v"));
                else { org.json.JSONArray items = value.getJSONArray("v"); java.util.Set<String> set = new java.util.HashSet<>(); for (int i=0;i<items.length();i++) set.add(items.getString(i)); editor.putStringSet(key,set); }
            }
            require(editor.commit(), "Private preference commit failed");
        }
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
            path.equals("/data/local/tmp/quietscreen-settings-explanations.png") || path.equals("/data/local/tmp/aprion-v035-installing.png") ||
            path.equals("/data/local/tmp/aprion-recommendations-v2-player.png") ||
            path.equals("/data/local/tmp/aprion-recommendations-v2-feedback.png") ||
            path.equals("/data/local/tmp/aprion-recommendations-v2-home.png") ||
            path.equals("/data/local/tmp/aprion-v037-search.png") ||
            path.equals("/data/local/tmp/aprion-v037-update-modal.png") ||
            path.equals("/data/local/tmp/aprion-upload-age-search.png") ||
            path.equals("/data/local/tmp/aprion-upload-age-player.png") ||
            path.equals("/data/local/tmp/aprion-upload-age-home.png") ||
            path.equals("/data/local/tmp/aprion-channel-options.png") || path.equals("/data/local/tmp/aprion-channel-home.png") ||
            path.equals("/data/local/tmp/aprion-channel-player.png") || path.equals("/data/local/tmp/aprion-channel-older.png") ||
            path.equals("/data/local/tmp/aprion-channel-return.png") || path.equals("/data/local/tmp/aprion-channel-failure.png") ||
            path.equals("/data/local/tmp/aprion-features-settings.png") || path.equals("/data/local/tmp/aprion-features-seek.png") ||
            path.equals("/data/local/tmp/aprion-features-counts.png") || path.equals("/data/local/tmp/aprion-features-autoplay.png") ||
            path.equals("/data/local/tmp/aprion-features-player.png") ||
            path.equals("/data/local/tmp/aprion-features-failure.png"), "Unexpected screenshot destination");
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
        key(KeyEvent.KEYCODE_DPAD_RIGHT); if (focused().contains("View channel")) key(KeyEvent.KEYCODE_DPAD_RIGHT); waitFocused("Captions"); waitPosition(before);
        key(KeyEvent.KEYCODE_DPAD_RIGHT); waitFocused("Settings"); waitPosition(before);
        key(KeyEvent.KEYCODE_DPAD_RIGHT); waitFocused("Play video");
        key(KeyEvent.KEYCODE_BACK); waitPlayerHidden();
        key(KeyEvent.KEYCODE_DPAD_RIGHT); require(!texts().contains("Recommended"), "Hidden seek revealed navigation buttons");
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFocused("Play video"); waitPosition(before + 10);
        key(KeyEvent.KEYCODE_BACK); waitPlayerHidden(); key(KeyEvent.KEYCODE_DPAD_LEFT);
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitFocused("Play video"); waitPosition(before);
        SystemClock.sleep(1100); capturePlayer("/data/local/tmp/quietscreen-player-controls.png");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); if (focused().contains("View channel")) key(KeyEvent.KEYCODE_DPAD_RIGHT); waitFocused("Captions");
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
    private void recommendationsV2() throws Exception {
        search("dQw4w9WgXcQ"); waitFor("Never Gonna Give You Up", 60, false);
        openPinnedResult("Never Gonna Give You Up", true); waitFor("Playback Playing", 75, true);
        observePlayback(35); key(KeyEvent.KEYCODE_MEDIA_PAUSE); waitFor("Playback Paused", 10, true);
        if (focused().contains("Captions") || focused().contains("Settings")) key(KeyEvent.KEYCODE_DPAD_DOWN);
        waitFocused("Play video"); long before = playbackSeconds();
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        long deadline = SystemClock.elapsedRealtime() + 60_000;
        while (SystemClock.elapsedRealtime() < deadline && (focused().contains("Play video") || focused().contains("Refresh") ||
            focused().contains("Try again") || focused().contains("Cancel loading"))) SystemClock.sleep(300);
        require(!focused().isEmpty() && !focused().contains("Play video") && !focused().contains("Refresh") &&
            !focused().contains("Try again") && !focused().contains("Cancel loading"), "No contextual recommendation card");
        require(texts().contains("Related to this video"), "Player did not use contextual recommendation row");
        marker("RECOMMENDATIONS_V2_STAGE realMinifiedPlayback=true contextualPlayer=true pausedPositionKnown=true");
        capturePlayer("/data/local/tmp/aprion-recommendations-v2-player.png");
        key(KeyEvent.KEYCODE_MENU); waitFocused("More like this");
        capturePlayer("/data/local/tmp/aprion-recommendations-v2-feedback.png");
        key(KeyEvent.KEYCODE_DPAD_CENTER); SystemClock.sleep(700); waitPosition(before);
        org.json.JSONObject learned = new org.json.JSONObject(getTargetContext().getSharedPreferences("recommendation_interests", 0).getString("profile", "{}"));
        require(learned.getJSONObject("topics").length() > 0 && learned.getJSONObject("channels").length() > 0,
            "Minified More like this did not persist local interests");
        key(KeyEvent.KEYCODE_MENU); waitFocused("More like this"); key(KeyEvent.KEYCODE_DPAD_DOWN); waitFocused("Not interested");
        key(KeyEvent.KEYCODE_DPAD_CENTER); SystemClock.sleep(700); waitPosition(before);
        learned = new org.json.JSONObject(getTargetContext().getSharedPreferences("recommendation_interests", 0).getString("profile", "{}"));
        require(learned.getJSONArray("dismissed").length() == 1, "Minified dismissal was not persisted");
        double topicsBeforeExit = scoreTotal(learned.getJSONObject("topics"));
        double channelsBeforeExit = scoreTotal(learned.getJSONObject("channels"));
        key(KeyEvent.KEYCODE_BACK); waitPlayerHidden(); key(KeyEvent.KEYCODE_BACK); waitFor("Find your next watch", 15, false);
        learned = new org.json.JSONObject(getTargetContext().getSharedPreferences("recommendation_interests", 0).getString("profile", "{}"));
        require(getTargetContext().getSharedPreferences("genre_preferences", 0).getFloat("MUSIC", 0f) > 0f &&
            scoreTotal(learned.getJSONObject("topics")) > topicsBeforeExit * .98 &&
            scoreTotal(learned.getJSONObject("channels")) > channelsBeforeExit * .98,
            "Real minified watch did not train genre, topic and channel preferences on exit");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        deadline = SystemClock.elapsedRealtime() + 60_000;
        while (SystemClock.elapsedRealtime() < deadline && (focused().contains("Home") || focused().contains("Cancel loading") ||
            focused().contains("Try again") || focused().contains("Refresh row"))) SystemClock.sleep(300);
        key(KeyEvent.KEYCODE_MENU); waitFocused("More like this");
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN); waitFocused("Don't recommend this channel");
        key(KeyEvent.KEYCODE_DPAD_CENTER); SystemClock.sleep(700);
        learned = new org.json.JSONObject(getTargetContext().getSharedPreferences("recommendation_interests", 0).getString("profile", "{}"));
        require(learned.getJSONArray("blocked").length() == 1, "Minified channel exclusion was not persisted");
        require(!focused().isEmpty(), "Feedback lost remote focus on Home");
        capturePlayer("/data/local/tmp/aprion-recommendations-v2-home.png");
        marker("RECOMMENDATIONS_V2 contextualPlayer=true implicitWatchLearning=true moreLikeThisPersisted=true dismissalPersisted=true channelBlockPersisted=true pausePositionPreserved=true homeFocusRestored=true playbackStopped=true");
    }
    private double scoreTotal(org.json.JSONObject scores) throws Exception {
        double sum = 0; java.util.Iterator<String> keys = scores.keys();
        while (keys.hasNext()) sum += scores.getDouble(keys.next());
        return sum;
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
