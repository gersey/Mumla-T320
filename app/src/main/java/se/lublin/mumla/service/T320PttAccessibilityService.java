package se.lublin.mumla.service;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import se.lublin.mumla.service.ipc.TalkBroadcastReceiver;

/**
 * Global key listener for the physical PTT button on the Inrico T320.
 *
 * <p>The accessibility service only recognizes and forwards the hardware edges. MumlaService owns
 * channel-busy detection, local cue playback, and the ordered TX state machine.</p>
 */
public class T320PttAccessibilityService extends AccessibilityService {
    public static final String LOG_TAG = "MumlaT320PTT";

    private static final int T320_PTT_KEY_CODE = KeyEvent.KEYCODE_LAST_CHANNEL;
    private static final int T320_PTT_SCAN_CODE = 88;
    private boolean pttPressed;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        pttPressed = false;
        // T320 user firmware sets the global app log threshold to ERROR (log.tag=E).
        Log.e(LOG_TAG, "service=CONNECTED mode=MUMLA_PTT_STATE_MACHINE consumed=true");
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
                sendT320PttCommand(TalkBroadcastReceiver.TALK_STATUS_T320_PTT_DOWN);
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean hadDown = pttPressed;
            pttPressed = false;
            logPttEvent("PTT_UP", event, "hadDown=" + hadDown);
            if (hadDown) {
                sendT320PttCommand(TalkBroadcastReceiver.TALK_STATUS_T320_PTT_UP);
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

    private void sendT320PttCommand(String status) {
        Intent intent = new Intent(TalkBroadcastReceiver.BROADCAST_TALK);
        intent.setPackage(getPackageName());
        intent.putExtra(TalkBroadcastReceiver.EXTRA_TALK_STATUS, status);
        sendBroadcast(intent);
        Log.e(LOG_TAG, "pttCommand=" + status + " state=SENT");
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
        if (pttPressed) {
            sendT320PttCommand(TalkBroadcastReceiver.TALK_STATUS_T320_PTT_UP);
        }
        pttPressed = false;
        Log.e(LOG_TAG, "pttState=RELEASED reason=" + reason);
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
