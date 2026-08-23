package se.lublin.mumla.service;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import se.lublin.mumla.R;
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver;

/**
 * Global key listener for the physical PTT button on the Inrico T320.
 *
 * <p>The start cue finishes before Mumla receives PTT DOWN. On release Mumla receives PTT UP,
 * then the end cue starts after a short capture-settle delay. This prevents either local cue from
 * entering the Mumble microphone stream.</p>
 */
public class T320PttAccessibilityService extends AccessibilityService {
    public static final String LOG_TAG = "MumlaT320PTT";

    private static final int T320_PTT_KEY_CODE = KeyEvent.KEYCODE_LAST_CHANNEL;
    private static final int T320_PTT_SCAN_CODE = 88;
    private static final long END_CUE_TX_SETTLE_DELAY_MS = 100L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean pttPressed;
    private boolean txDownSent;
    private MediaPlayer cuePlayer;
    private final Runnable playEndCue = () -> {
        if (!pttPressed) {
            playLocalCue(R.raw.t320_end_of_my_tx, "END_TX");
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        pttPressed = false;
        txDownSent = false;
        // T320 user firmware sets the global app log threshold to ERROR (log.tag=E).
        Log.e(LOG_TAG, "service=CONNECTED mode=MUMLA_PTT_WITH_LOCAL_CUES consumed=true");
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
            mainHandler.removeCallbacks(playEndCue);
            logPttEvent("PTT_DOWN", event, "duplicateDown=" + duplicateDown);
            if (!duplicateDown) {
                txDownSent = false;
                playLocalCue(R.raw.t320_before_my_tx, "BEFORE_TX");
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean hadDown = pttPressed;
            pttPressed = false;
            logPttEvent("PTT_UP", event, "hadDown=" + hadDown);
            if (hadDown) {
                sendT320PttCommand(TalkBroadcastReceiver.TALK_STATUS_T320_PTT_UP);
                txDownSent = false;
                stopLocalCue("PTT_UP");
                mainHandler.postDelayed(playEndCue, END_CUE_TX_SETTLE_DELAY_MS);
            }
        }

        // Mumla owns the recognized T320 PTT key, including repeated DOWN events while held.
        return true;
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
                + " consumed=true");
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
                txDownSent = true;
                sendT320PttCommand(TalkBroadcastReceiver.TALK_STATUS_T320_PTT_DOWN);
            }
        });
        player.setOnErrorListener((failedPlayer, what, extra) -> {
            if (cuePlayer == failedPlayer) {
                cuePlayer = null;
            }
            failedPlayer.release();
            Log.e(LOG_TAG, "cue=" + cueName + " state=ERROR what=" + what
                    + " extra=" + extra + " localOnly=true tx=OFF");
            if ("BEFORE_TX".equals(cueName) && pttPressed) {
                txDownSent = true;
                sendT320PttCommand(TalkBroadcastReceiver.TALK_STATUS_T320_PTT_DOWN);
            }
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
            if ("BEFORE_TX".equals(cueName) && pttPressed) {
                txDownSent = true;
                sendT320PttCommand(TalkBroadcastReceiver.TALK_STATUS_T320_PTT_DOWN);
            }
        }
    }

    private void sendT320PttCommand(String status) {
        Intent intent = new Intent(TalkBroadcastReceiver.BROADCAST_TALK);
        intent.setPackage(getPackageName());
        intent.putExtra(TalkBroadcastReceiver.EXTRA_TALK_STATUS, status);
        sendBroadcast(intent);
        Log.e(LOG_TAG, "txCommand=" + status + " state=SENT txDownSent=" + txDownSent);
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
        releaseT320Ptt("SERVICE_INTERRUPTED");
        Log.e(LOG_TAG, "service=INTERRUPTED");
    }

    private void releaseT320Ptt(String reason) {
        mainHandler.removeCallbacks(playEndCue);
        if (pttPressed || txDownSent) {
            sendT320PttCommand(TalkBroadcastReceiver.TALK_STATUS_T320_PTT_UP);
        }
        pttPressed = false;
        txDownSent = false;
        stopLocalCue(reason);
    }

    @Override
    public void onDestroy() {
        releaseT320Ptt("SERVICE_DESTROYED");
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
