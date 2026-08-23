package se.lublin.mumla.service;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaPlayer;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import se.lublin.mumla.R;

/**
 * Global key listener for the physical PTT button on the Inrico T320.
 *
 * <p>This phase records the first DOWN and matching UP and plays local PTT cue sounds. It still
 * deliberately does not call Mumla's transmit code and does not consume the key event. The start
 * cue finishes before the future TX gate becomes ready; the end cue starts only after that gate
 * has been closed. This prevents either cue from entering the future Mumble microphone stream.</p>
 */
public class T320PttAccessibilityService extends AccessibilityService {
    public static final String LOG_TAG = "MumlaT320PTT";

    private static final int T320_PTT_KEY_CODE = KeyEvent.KEYCODE_LAST_CHANNEL;
    private static final int T320_PTT_SCAN_CODE = 88;

    private boolean pttPressed;
    private MediaPlayer cuePlayer;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        pttPressed = false;
        // T320 user firmware sets the global app log threshold to ERROR (log.tag=E).
        Log.e(LOG_TAG, "service=CONNECTED mode=LOCAL_CUES_ONLY tx=OFF consumed=false");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != T320_PTT_KEY_CODE
                || event.getScanCode() != T320_PTT_SCAN_CODE) {
            return false;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            boolean duplicateDown = pttPressed;
            pttPressed = true;
            logPttEvent("PTT_DOWN", event, "duplicateDown=" + duplicateDown);
            if (!duplicateDown) {
                playLocalCue(R.raw.t320_before_my_tx, "BEFORE_TX");
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean hadDown = pttPressed;
            pttPressed = false;
            logPttEvent("PTT_UP", event, "hadDown=" + hadDown);
            if (hadDown) {
                playLocalCue(R.raw.t320_end_of_my_tx, "END_TX");
            }
        }

        // Diagnostic phase: observe the event without stealing it from the system or another app.
        return false;
    }

    private void logPttEvent(String eventName, KeyEvent event, String pairingState) {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        InputDevice inputDevice = event.getDevice();

        boolean interactive = powerManager != null && powerManager.isInteractive();
        boolean keyguardLocked = keyguardManager != null && keyguardManager.isKeyguardLocked();
        String deviceName = inputDevice == null ? "unknown" : inputDevice.getName();

        Log.e(LOG_TAG, "event=" + eventName
                + " keyCode=" + event.getKeyCode()
                + " scanCode=" + event.getScanCode()
                + " action=" + actionToString(event.getAction())
                + " repeatCount=" + event.getRepeatCount()
                + " deviceId=" + event.getDeviceId()
                + " deviceName=" + deviceName
                + " interactive=" + interactive
                + " keyguardLocked=" + keyguardLocked
                + " eventTime=" + event.getEventTime()
                + " downTime=" + event.getDownTime()
                + " " + pairingState
                + " consumed=false");
    }

    private void playLocalCue(int resourceId, final String cueName) {
        stopLocalCue("REPLACED_BY_" + cueName);

        final MediaPlayer player = MediaPlayer.create(this, resourceId);
        if (player == null) {
            Log.e(LOG_TAG, "cue=" + cueName + " state=CREATE_FAILED localOnly=true tx=OFF");
            return;
        }

        cuePlayer = player;
        player.setOnCompletionListener(completedPlayer -> {
            if (cuePlayer == completedPlayer) {
                cuePlayer = null;
            }
            completedPlayer.release();
            Log.e(LOG_TAG, "cue=" + cueName + " state=COMPLETED localOnly=true tx=OFF"
                    + " pttPressed=" + pttPressed);
            if ("BEFORE_TX".equals(cueName) && pttPressed) {
                // Future TX integration belongs here, after the local start cue is inaudible.
                Log.e(LOG_TAG, "txGate=READY_AFTER_BEFORE_CUE tx=OFF pttPressed=true");
            }
        });
        player.setOnErrorListener((failedPlayer, what, extra) -> {
            if (cuePlayer == failedPlayer) {
                cuePlayer = null;
            }
            failedPlayer.release();
            Log.e(LOG_TAG, "cue=" + cueName + " state=ERROR what=" + what
                    + " extra=" + extra + " localOnly=true tx=OFF");
            return true;
        });

        try {
            player.start();
            Log.e(LOG_TAG, "cue=" + cueName + " state=STARTED localOnly=true tx=OFF");
        } catch (IllegalStateException error) {
            if (cuePlayer == player) {
                cuePlayer = null;
            }
            player.release();
            Log.e(LOG_TAG, "cue=" + cueName + " state=START_FAILED localOnly=true tx=OFF", error);
        }
    }

    private void stopLocalCue(String reason) {
        MediaPlayer player = cuePlayer;
        cuePlayer = null;
        if (player == null) {
            return;
        }

        player.setOnCompletionListener(null);
        player.setOnErrorListener(null);
        try {
            player.stop();
        } catch (IllegalStateException ignored) {
            // Releasing below is sufficient if playback had not reached the started state.
        }
        player.release();
        Log.e(LOG_TAG, "cue=ACTIVE state=STOPPED reason=" + reason + " localOnly=true tx=OFF");
    }

    private static String actionToString(int action) {
        if (action == KeyEvent.ACTION_DOWN) {
            return "ACTION_DOWN";
        }
        if (action == KeyEvent.ACTION_UP) {
            return "ACTION_UP";
        }
        return Integer.toString(action);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Window events are not needed; only onKeyEvent is used by this diagnostic service.
    }

    @Override
    public void onInterrupt() {
        pttPressed = false;
        stopLocalCue("SERVICE_INTERRUPTED");
        Log.e(LOG_TAG, "service=INTERRUPTED");
    }

    @Override
    public void onDestroy() {
        pttPressed = false;
        stopLocalCue("SERVICE_DESTROYED");
        Log.e(LOG_TAG, "service=DESTROYED");
        super.onDestroy();
    }

    public static boolean isEnabled(Context context) {
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) {
            return false;
        }

        ComponentName expected = new ComponentName(context, T320PttAccessibilityService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            ComponentName enabled = ComponentName.unflattenFromString(splitter.next());
            if (expected.equals(enabled)) {
                return true;
            }
        }
        return false;
    }
}
