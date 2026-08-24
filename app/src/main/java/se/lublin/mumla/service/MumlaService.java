/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.service;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Binder;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import se.lublin.humla.Constants;
import se.lublin.humla.HumlaService;
import se.lublin.humla.exception.AudioException;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IMessage;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.Message;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver;
import se.lublin.mumla.util.HtmlUtils;

/**
 * An extension of the Humla service with some added Mumla-exclusive non-standard Mumble features.
 * Created by andrew on 28/07/13.
 */
public class MumlaService extends HumlaService implements
        SharedPreferences.OnSharedPreferenceChangeListener,
        MumlaConnectionNotification.OnActionListener,
        MumlaReconnectNotification.OnActionListener, IMumlaService {
    private static final String TAG = MumlaService.class.getName();

    /** Undocumented constant that permits a proximity-sensing wake lock. */
    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK = 32;
    public static final int TTS_THRESHOLD = 250; // Maximum number of characters to read
    public static final int RECONNECT_DELAY = 10000;
    private static final long T320_START_CUE_TX_SETTLE_DELAY_MS = 200L;
    private static final long T320_END_CUE_TX_SETTLE_DELAY_MS = 100L;
    private static final long T320_END_CUE_MIN_PRESS_DURATION_MS = 2000L;
    private static final int T320_BUSY_BEEP_COUNT = 3;
    private static final int T320_BUSY_BEEP_DURATION_MS = 120;
    private static final long T320_BUSY_BEEP_INTERVAL_MS = 220L;
    private static final String T320_FLOOR_PROTOCOL = "[T320-FLOOR/1]";
    private static final String T320_FLOOR_GATEWAY_NAME = "HA1GSY-Gateway";
    private static final long T320_FLOOR_REQUEST_TIMEOUT_MS = 1800L;

    private Settings mSettings;
    private MumlaConnectionNotification mNotification;
    private MumlaMessageNotification mMessageNotification;
    private MumlaReconnectNotification mReconnectNotification;
    /** Channel view overlay. */
    private MumlaOverlay mChannelOverlay;
    /** Proximity lock for handset mode. */
    private PowerManager.WakeLock mProximityLock;
    /** Play sound when push to talk key is pressed */
    private boolean mPTTSoundEnabled;
    /** Suppresses Mumla's legacy key-click while the T320 uses its dedicated local cue. */
    private boolean mT320PttActive;
    private boolean mT320PttPressed;
    private boolean mT320StartPending;
    private boolean mT320TxActive;
    private boolean mT320BusyBlocked;
    private boolean mT320FloorRequestPending;
    private long mT320PttDownElapsedRealtime;
    private String mT320FloorToken;
    private int mT320FloorGatewaySession = -1;
    private Handler mT320Handler;
    private MediaPlayer mT320CuePlayer;
    private ToneGenerator mT320BusyTone;
    private int mT320BusyBeepsPlayed;
    /** True while Android reports USB/AC/wireless power, so charging keeps LED ownership. */
    private boolean mT320ExternalPowerConnected = true;
    private final Runnable mStartT320TxAfterCue = this::startT320TxAfterCue;
    private final Runnable mT320FloorRequestTimeout = () -> {
        if (!mT320FloorRequestPending) {
            return;
        }
        mT320FloorRequestPending = false;
        mT320BusyBlocked = true;
        Log.e(T320PttAccessibilityService.LOG_TAG,
                "floor=TIMEOUT tx=OFF token=" + mT320FloorToken);
        releaseT320Floor();
        playT320BusySignal();
    };
    private final Runnable mPlayT320EndCue = () -> {
        if (!mT320PttPressed && !mT320BusyBlocked) {
            playT320Cue(R.raw.t320_end_of_my_tx, "END_TX", null);
        }
    };
    private final Runnable mPlayNextT320BusyBeep = new Runnable() {
        @Override
        public void run() {
            if (mT320BusyTone == null) {
                return;
            }
            if (mT320BusyBeepsPlayed >= T320_BUSY_BEEP_COUNT) {
                stopT320BusySignal("COMPLETED");
                return;
            }
            mT320BusyTone.startTone(ToneGenerator.TONE_PROP_BEEP, T320_BUSY_BEEP_DURATION_MS);
            mT320BusyBeepsPlayed++;
            Log.e(T320PttAccessibilityService.LOG_TAG, "busyCue=BEEP index="
                    + mT320BusyBeepsPlayed + " of=" + T320_BUSY_BEEP_COUNT
                    + " localOnly=true tx=OFF");
            mT320Handler.postDelayed(this, T320_BUSY_BEEP_INTERVAL_MS);
        }
    };
    /** Try to shorten spoken messages when using TTS */
    private boolean mShortTtsMessagesEnabled;
    /**
     * True if an error causing disconnection has been dismissed by the user.
     * This should serve as a hint not to bother the user.
     */
    private boolean mErrorShown;
    private List<IChatMessage> mMessageLog;
    private boolean mSuppressNotifications;

    private TextToSpeech mTTS;
    private TextToSpeech.OnInitListener mTTSInitListener = new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            if(status == TextToSpeech.ERROR)
                logWarning(getString(R.string.tts_failed));
        }
    };

    /** The view representing the hot corner. */
    private MumlaHotCorner mHotCorner;
    private MumlaHotCorner.MumlaHotCornerListener mHotCornerListener = new MumlaHotCorner.MumlaHotCornerListener() {
        @Override
        public void onHotCornerDown() {
            onTalkKeyDown();
        }

        @Override
        public void onHotCornerUp() {
            onTalkKeyUp();
        }
    };

    private BroadcastReceiver mTalkReceiver;

    private final BroadcastReceiver mT320BatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            if (!Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                return;
            }
            boolean externalPowerConnected = intent.getIntExtra(
                    BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (mT320ExternalPowerConnected != externalPowerConnected) {
                mT320ExternalPowerConnected = externalPowerConnected;
                updateT320ActivityLed();
            }
        }
    };

    private HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onConnecting() {
            // Remove old notification left from reconnect,
            if (mReconnectNotification != null) {
                mReconnectNotification.hide();
                mReconnectNotification = null;
            }

            final String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
            mNotification = MumlaConnectionNotification.create(MumlaService.this,
                    getString(R.string.mumlaConnecting) + tor,
                    MumlaService.this);
            mNotification.show();

            mErrorShown = false;
        }

        @Override
        public void onConnected() {
            if (mNotification != null) {
                final String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
                mNotification.setCustomContentText(getString(R.string.connected) + tor);
                mNotification.setActionsShown(true);
                mNotification.show();
            }
            updateT320ActivityLed();
        }

        @Override
        public void onDisconnected(HumlaException e) {
            if (mNotification != null) {
                mNotification.hide();
                mNotification = null;
            }
            if (e != null && !mSuppressNotifications) {
                mReconnectNotification =
                        MumlaReconnectNotification.show(MumlaService.this,
                                e.getMessage() + (mSettings.isTorEnabled() ? " (Tor)" : ""),
                                isReconnecting(), MumlaService.this);
            }
        }

        @Override
        public void onUserConnected(IUser user) {
            if (user.getTextureHash() != null &&
                    user.getTexture() == null) {
                // Request avatar data if available.
                requestAvatar(user.getSession());
            }
        }

        @Override
        public void onUserStateUpdated(IUser user) {
            if (user == null) {
                return;
            }

            int selfSession;
            try {
                selfSession = getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserStateUpdated: " + e);
                return;
            }

            if (user.getSession() == selfSession) {
                mSettings.setMutedAndDeafened(user.isSelfMuted(), user.isSelfDeafened()); // Update settings mute/deafen state
                if(mNotification != null) {
                    String contentText;
                    if (user.isSelfMuted() && user.isSelfDeafened())
                        contentText = getString(R.string.status_notify_muted_and_deafened);
                    else if (user.isSelfMuted())
                        contentText = getString(R.string.status_notify_muted);
                    else
                        contentText = getString(R.string.connected);
                    mNotification.setCustomContentText(contentText);
                    mNotification.show();
                }
            }

            if (user.getTextureHash() != null && user.getTexture() == null) {
                // Update avatar data if available.
                requestAvatar(user.getSession());
            }
        }

        @Override
        public void onMessageLogged(IMessage message) {
            if (handleT320FloorMessage(message)) {
                return;
            }
            // Split on / strip all HTML tags.
            Document parsedMessage = Jsoup.parseBodyFragment(message.getMessage());
            String strippedMessage = parsedMessage.text();

            String ttsMessage;
            if(mShortTtsMessagesEnabled) {
                for (Element anchor : parsedMessage.getElementsByTag("A")) {
                    // Get just the domain portion of links
                    String href = anchor.attr("href");
                    // Only shorten anchors without custom text
                    if (href != null && href.equals(anchor.text())) {
                        String urlHostname = HtmlUtils.getHostnameFromLink(href);
                        if (urlHostname != null) {
                            anchor.text(getString(R.string.chat_message_tts_short_link, urlHostname));
                        }
                    }
                }
                ttsMessage = parsedMessage.text();
            } else {
                ttsMessage = strippedMessage;
            }

            String formattedTtsMessage = getString(R.string.notification_message,
                    message.getActorName(), ttsMessage);

            // Read if TTS is enabled, the message is less than threshold, is a text message, and not deafened
            if(mSettings.isTextToSpeechEnabled() &&
                    mTTS != null &&
                    formattedTtsMessage.length() <= TTS_THRESHOLD &&
                    getSessionUser() != null &&
                    !getSessionUser().isSelfDeafened()) {
                mTTS.speak(formattedTtsMessage, TextToSpeech.QUEUE_ADD, null);
            }

            // TODO: create a customizable notification sieve
            if (mSettings.isChatNotifyEnabled()) {
                mMessageNotification.show(message);
            }

            mMessageLog.add(new IChatMessage.TextMessage(message));
        }

        @Override
        public void onLogInfo(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, message));
        }

        @Override
        public void onLogWarning(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING, message));
        }

        @Override
        public void onLogError(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.ERROR, message));
        }

        @Override
        public void onPermissionDenied(String reason) {
            if(mNotification != null && !mSuppressNotifications) {
                mNotification.show();
            }
        }

        @Override
        public void onUserTalkStateUpdated(IUser user) {
            int selfSession = -1;
            try {
                selfSession = getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserTalkStateUpdated: " + e);
            }

            if (isConnectionEstablished() &&
                    user.getSession() == selfSession &&
                    getTransmitMode() == Constants.TRANSMIT_PUSH_TO_TALK &&
                    user.getTalkState() == TalkState.TALKING &&
                    mPTTSoundEnabled &&
                    !mT320PttActive) {
                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1);
            }
            updateT320ActivityLed();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mT320Handler = new Handler(getMainLooper());
        registerObserver(mObserver);

        // Register for preference changes
        mSettings = Settings.getInstance(this);
        mPTTSoundEnabled = mSettings.isPttSoundEnabled();
        mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);

        // Manually set theme to style overlay views
        // XML <application> theme does NOT do this!
        setTheme(R.style.Theme_Mumla);

        mMessageLog = new ArrayList<>();
        mMessageNotification = new MumlaMessageNotification(MumlaService.this);

        // Instantiate overlay view
        mChannelOverlay = new MumlaOverlay(this);
        mHotCorner = new MumlaHotCorner(this, mSettings.getHotCornerGravity(), mHotCornerListener);

        // Set up TTS
        if(mSettings.isTextToSpeechEnabled())
            mTTS = new TextToSpeech(this, mTTSInitListener);

        mTalkReceiver = new TalkBroadcastReceiver(this);
        registerReceiver(mT320BatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new MumlaBinder(this);
    }

    @Override
    public void onDestroy() {
        resetT320PttState("SERVICE_DESTROYED");
        if (mNotification != null) {
            mNotification.hide();
            mNotification = null;
        }
        if (mReconnectNotification != null) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        try {
            unregisterReceiver(mTalkReceiver);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }
        try {
            unregisterReceiver(mT320BatteryReceiver);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }

        unregisterObserver(mObserver);
        if(mTTS != null) mTTS.shutdown();
        mMessageLog = null;
        mMessageNotification.dismiss();
        super.onDestroy();
    }

    @Override
    public void onConnectionSynchronized() {
        // TODO? We seem to be getting a RuntimeException here, from the call
        //  to the superclass function (in HumlaService). In there,
        //  mConnect.getSession() finds that isSynchronized==false and throws
        //  NotSynchronizedException (which is re-thrown as the
        //  RuntimeException). But how can it be !isSynchronized? -- A server
        //  msg triggers HumlaConnection.messageServerSync(), which sets up
        //  mSession and mSynchronized==true and then proceeds to call us from
        //  a Runnable post()ed to a Handler. The reason could only be that
        //  HumlaConnect.connect() or disconnect() is called again in the
        //  middle of all this? And it's made possible by the Handler?
        try {
            super.onConnectionSynchronized();
        } catch (RuntimeException e) {
            Log.d(TAG, "exception in onConnectionSynchronized: " + e);
            return;
        }

        // Restore mute/deafen state
        if(mSettings.isMuted() || mSettings.isDeafened()) {
            setSelfMuteDeafState(mSettings.isMuted(), mSettings.isDeafened());
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(mTalkReceiver, new IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK), RECEIVER_EXPORTED);
        } else {
            registerReceiver(mTalkReceiver, new IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK));
        }

        if (mSettings.isHotCornerEnabled()) {
            mHotCorner.setShown(true);
        }
        // Configure proximity sensor
        if (mSettings.isHandsetMode()) {
            setProximitySensorOn(true);
        }
    }

    @Override
    public void onConnectionDisconnected(HumlaException e) {
        resetT320PttState("CONNECTION_DISCONNECTED");
        super.onConnectionDisconnected(e);
        try {
            unregisterReceiver(mTalkReceiver);
        } catch (IllegalArgumentException iae) {
        }

        // Remove overlay if present.
        mChannelOverlay.hide();

        mHotCorner.setShown(false);

        setProximitySensorOn(false);

        clearMessageLog();
        mMessageNotification.dismiss();
    }

    /**
     * Called when the user makes a change to their preferences.
     * Should update all preferences relevant to the service.
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Bundle changedExtras = new Bundle();
        boolean requiresReconnect = false;
        switch (key) {
            case Settings.PREF_INPUT_METHOD:
                /* Convert input method defined in settings to an integer format used by Humla. */
                int inputMethod = mSettings.getHumlaInputMethod();
                changedExtras.putInt(HumlaService.EXTRAS_TRANSMIT_MODE, inputMethod);
                mChannelOverlay.setPushToTalkShown(inputMethod == Constants.TRANSMIT_PUSH_TO_TALK);
                break;
            case Settings.PREF_HANDSET_MODE:
                setProximitySensorOn(isConnectionEstablished() && mSettings.isHandsetMode());
                changedExtras.putInt(HumlaService.EXTRAS_AUDIO_STREAM, mSettings.isHandsetMode() ?
                                     AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
                break;
            case Settings.PREF_THRESHOLD:
                changedExtras.putFloat(HumlaService.EXTRAS_DETECTION_THRESHOLD,
                        mSettings.getDetectionThreshold());
                break;
            case Settings.PREF_HOT_CORNER_KEY:
                mHotCorner.setGravity(mSettings.getHotCornerGravity());
                mHotCorner.setShown(isConnectionEstablished() && mSettings.isHotCornerEnabled());
                break;
            case Settings.PREF_USE_TTS:
                if (mTTS == null && mSettings.isTextToSpeechEnabled())
                    mTTS = new TextToSpeech(this, mTTSInitListener);
                else if (mTTS != null && !mSettings.isTextToSpeechEnabled()) {
                    mTTS.shutdown();
                    mTTS = null;
                }
                break;
            case Settings.PREF_SHORT_TTS_MESSAGES:
                mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled();
                break;
            case Settings.PREF_AMPLITUDE_BOOST:
                changedExtras.putFloat(EXTRAS_AMPLITUDE_BOOST,
                        mSettings.getAmplitudeBoostMultiplier());
                break;
            case Settings.PREF_HALF_DUPLEX:
                changedExtras.putBoolean(EXTRAS_HALF_DUPLEX, mSettings.isHalfDuplex());
                break;
            case Settings.PREF_PREPROCESSOR_ENABLED:
                changedExtras.putBoolean(EXTRAS_ENABLE_PREPROCESSOR,
                        mSettings.isPreprocessorEnabled());
                break;
            case Settings.PREF_ECHO_CANCELLATION_METHOD:
                changedExtras.putString(EXTRAS_ECHO_CANCELLATION_METHOD,
                        mSettings.getEchoCancellationMethod());
                break;
            case Settings.PREF_PTT_SOUND:
                mPTTSoundEnabled = mSettings.isPttSoundEnabled();
                break;
            case Settings.PREF_INPUT_QUALITY:
                changedExtras.putInt(EXTRAS_INPUT_QUALITY, mSettings.getInputQuality());
                break;
            case Settings.PREF_INPUT_RATE:
                changedExtras.putInt(EXTRAS_INPUT_RATE, mSettings.getInputSampleRate());
                break;
            case Settings.PREF_FRAMES_PER_PACKET:
                changedExtras.putInt(EXTRAS_FRAMES_PER_PACKET, mSettings.getFramesPerPacket());
                break;
            case Settings.PREF_CERT_ID:
            case Settings.PREF_FORCE_TCP:
            case Settings.PREF_USE_TOR:
            case Settings.PREF_DISABLE_OPUS:
                // These are settings we flag as 'requiring reconnect'.
                requiresReconnect = true;
                break;
        }
        if (changedExtras.size() > 0) {
            try {
                // Reconfigure the service appropriately.
                requiresReconnect |= configureExtras(changedExtras);
            } catch (AudioException e) {
                e.printStackTrace();
            }
        }

        if (requiresReconnect && isConnectionEstablished()) {
            Toast.makeText(this, R.string.change_requires_reconnect, Toast.LENGTH_LONG).show();
        }
    }

    private void setProximitySensorOn(boolean on) {
        if(on) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            mProximityLock = pm.newWakeLock(PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Mumla:Proximity");
            mProximityLock.acquire();
        } else {
            if(mProximityLock != null) mProximityLock.release();
            mProximityLock = null;
        }
    }

    @Override
    public void onMuteToggled() {
        IUser user = getSessionUser();
        if (isConnectionEstablished() && user != null) {
            boolean muted = !user.isSelfMuted();
            boolean deafened = user.isSelfDeafened() && muted;
            setSelfMuteDeafState(muted, deafened);
        }
    }

    @Override
    public void onDeafenToggled() {
        IUser user = getSessionUser();
        if (isConnectionEstablished() && user != null) {
            setSelfMuteDeafState(!user.isSelfDeafened(), !user.isSelfDeafened());
        }
    }

    @Override
    public void onOverlayToggled() {
        // Ditch notification shade/panel to make overlay presence/permission request visible.
        // But on Android 12 that's no longer allowed.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Intent close = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            getApplicationContext().sendBroadcast(close);
        }

        if (!mChannelOverlay.isShown()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(getApplicationContext())) {
                    Intent showSetting = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    showSetting.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(showSetting);
                    Toast.makeText(this, R.string.grant_perm_draw_over_apps, Toast.LENGTH_LONG).show();
                    return;
                }
            }
            mChannelOverlay.show();
        } else {
            mChannelOverlay.hide();
        }
    }

    @Override
    public void onReconnectNotificationDismissed() {
        mErrorShown = true;
    }

    @Override
    public void reconnect() {
        connect();
    }

    @Override
    public void cancelReconnect() {
        if (mReconnectNotification != null) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }
        super.cancelReconnect();
    }

    @Override
    public void setOverlayShown(boolean showOverlay) {
        if(!mChannelOverlay.isShown()) {
            mChannelOverlay.show();
        } else {
            mChannelOverlay.hide();
        }
    }

    @Override
    public boolean isOverlayShown() {
        return mChannelOverlay.isShown();
    }

    @Override
    public void clearChatNotifications() {
        mMessageNotification.dismiss();
    }

    @Override
    public void markErrorShown() {
        mErrorShown = true;
        // Dismiss the reconnection prompt if a reconnection isn't in progress.
        if (mReconnectNotification != null && !isReconnecting()) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }
    }

    @Override
    public boolean isErrorShown() {
        return mErrorShown;
    }

    /**
     * Called when a user presses a talk key down (i.e. when they want to talk).
     * Accounts for talk logic if toggle PTT is on.
     */
    @Override
    public void onTalkKeyDown() {
        if(isConnectionEstablished()
                && Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())) {
            if (!mSettings.isPushToTalkToggle() && !isTalking()) {
                setTalkingState(true); // Start talking
            }
        }
    }

    /**
     * Called when a user releases a talk key (i.e. when they do not want to talk).
     * Accounts for talk logic if toggle PTT is on.
     */
    @Override
    public void onTalkKeyUp() {
        if(isConnectionEstablished()
                && Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())) {
            if (mSettings.isPushToTalkToggle()) {
                setTalkingState(!isTalking()); // Toggle talk state
            } else if (isTalking()) {
                setTalkingState(false); // Stop talking
            }
        }
    }

    /** Handles T320 DOWN, including local pre-TX and channel-busy cues. */
    @Override
    public void onT320PttDown() {
        if (mT320PttPressed) {
            Log.e(T320PttAccessibilityService.LOG_TAG, "pttState=DOWN_IGNORED reason=DUPLICATE");
            return;
        }

        mT320Handler.removeCallbacks(mPlayT320EndCue);
        mT320Handler.removeCallbacks(mStartT320TxAfterCue);
        stopT320Cue("NEW_PTT_DOWN");
        stopT320BusySignal("NEW_PTT_DOWN");
        mT320PttPressed = true;
        mT320PttDownElapsedRealtime = SystemClock.elapsedRealtime();
        mT320BusyBlocked = false;

        if (!isT320PttEligible()) {
            Log.e(T320PttAccessibilityService.LOG_TAG,
                    "pttState=DOWN_IGNORED reason=NOT_CONNECTED_OR_NOT_PTT");
            return;
        }

        int busyTalkers = countAudibleRemoteTalkers();
        if (busyTalkers > 0) {
            mT320BusyBlocked = true;
            playT320BusySignal();
            Log.e(T320PttAccessibilityService.LOG_TAG, "channel=BUSY remoteTalkers="
                    + busyTalkers + " tx=OFF");
            return;
        }

        requestT320Floor();
    }

    /** Stops T320 transmission before the local end cue is allowed to play. */
    @Override
    public void onT320PttUp() {
        boolean hadPress = mT320PttPressed;
        boolean wasBusyBlocked = mT320BusyBlocked;
        long pressDurationMs = hadPress && mT320PttDownElapsedRealtime > 0
                ? SystemClock.elapsedRealtime() - mT320PttDownElapsedRealtime
                : 0L;
        boolean shouldPlayEndCue = hadPress && !wasBusyBlocked
                && (mT320StartPending || mT320TxActive)
                && pressDurationMs > T320_END_CUE_MIN_PRESS_DURATION_MS;

        releaseT320Floor();
        mT320PttPressed = false;
        mT320PttDownElapsedRealtime = 0L;
        mT320StartPending = false;
        mT320BusyBlocked = false;
        mT320Handler.removeCallbacks(mStartT320TxAfterCue);
        stopT320Cue("PTT_UP");

        if (mT320TxActive && isT320PttEligible() && isTalking()) {
            setTalkingState(false);
        }
        mT320TxActive = false;
        mT320PttActive = false;
        updateT320ActivityLed();

        if (shouldPlayEndCue) {
            mT320Handler.postDelayed(mPlayT320EndCue, T320_END_CUE_TX_SETTLE_DELAY_MS);
        }
        Log.e(T320PttAccessibilityService.LOG_TAG, "txCommand=UP handled=" + hadPress
                + " busyBlocked=" + wasBusyBlocked + " talking="
                + (isT320PttEligible() && isTalking())
                + " pressDurationMs=" + pressDurationMs
                + " endCue=" + (shouldPlayEndCue ? "PLAY" : "SKIP"));
    }

    private void startT320TxAfterCue() {
        mT320StartPending = false;
        if (!mT320PttPressed || !isT320PttEligible()) {
            Log.e(T320PttAccessibilityService.LOG_TAG,
                    "txCommand=DOWN_CANCELLED reason=RELEASED_OR_NOT_READY");
            return;
        }

        int busyTalkers = countAudibleRemoteTalkers();
        if (busyTalkers > 0) {
            mT320BusyBlocked = true;
            releaseT320Floor();
            playT320BusySignal();
            Log.e(T320PttAccessibilityService.LOG_TAG, "channel=BECAME_BUSY remoteTalkers="
                    + busyTalkers + " tx=OFF");
            return;
        }

        if (!isTalking()) {
            mT320PttActive = true;
            mT320TxActive = true;
            setTalkingState(true);
        }
        updateT320ActivityLed();
        Log.e(T320PttAccessibilityService.LOG_TAG, "txCommand=DOWN handled=true talking="
                + isTalking());
    }

    private void requestT320Floor() {
        IUser gateway = findT320FloorGateway(getRootChannel());
        if (gateway == null) {
            mT320BusyBlocked = true;
            playT320BusySignal();
            Log.e(T320PttAccessibilityService.LOG_TAG,
                    "floor=DENIED reason=GATEWAY_NOT_FOUND tx=OFF");
            return;
        }

        mT320FloorToken = Long.toHexString(SystemClock.elapsedRealtime())
                + "-" + Integer.toHexString(getSessionId());
        mT320FloorGatewaySession = gateway.getSession();
        mT320FloorRequestPending = true;
        mT320Handler.removeCallbacks(mT320FloorRequestTimeout);
        mT320Handler.postDelayed(mT320FloorRequestTimeout, T320_FLOOR_REQUEST_TIMEOUT_MS);
        try {
            super.sendUserTextMessage(
                    mT320FloorGatewaySession,
                    T320_FLOOR_PROTOCOL + " CLAIM " + mT320FloorToken);
            Log.e(T320PttAccessibilityService.LOG_TAG,
                    "floor=CLAIM_SENT gatewaySession=" + mT320FloorGatewaySession
                            + " token=" + mT320FloorToken);
        } catch (RuntimeException error) {
            mT320Handler.removeCallbacks(mT320FloorRequestTimeout);
            mT320FloorRequestPending = false;
            mT320BusyBlocked = true;
            playT320BusySignal();
            Log.e(T320PttAccessibilityService.LOG_TAG,
                    "floor=CLAIM_FAILED tx=OFF", error);
        }
    }

    private boolean handleT320FloorMessage(IMessage message) {
        if (message == null || !T320_FLOOR_GATEWAY_NAME.equals(message.getActorName())) {
            return false;
        }
        String text = Jsoup.parseBodyFragment(message.getMessage()).text();
        if (!text.startsWith(T320_FLOOR_PROTOCOL + " ")) {
            return false;
        }

        String[] parts = text.split("\\s+", 4);
        if (parts.length < 3 || mT320FloorToken == null
                || !mT320FloorToken.equals(parts[2])) {
            return true;
        }

        String command = parts[1];
        if ("GRANT".equals(command)) {
            mT320Handler.removeCallbacks(mT320FloorRequestTimeout);
            mT320FloorRequestPending = false;
            if (!mT320PttPressed || !isT320PttEligible()) {
                releaseT320Floor();
                return true;
            }
            int busyTalkers = countAudibleRemoteTalkers();
            if (busyTalkers > 0) {
                mT320BusyBlocked = true;
                releaseT320Floor();
                playT320BusySignal();
                Log.e(T320PttAccessibilityService.LOG_TAG,
                        "floor=GRANTED_BUT_LOCAL_BUSY remoteTalkers=" + busyTalkers
                                + " tx=OFF");
                return true;
            }
            mT320StartPending = true;
            playT320Cue(
                    R.raw.t320_before_my_tx,
                    "BEFORE_TX",
                    this::scheduleT320TxAfterCue);
            Log.e(T320PttAccessibilityService.LOG_TAG,
                    "floor=GRANTED token=" + mT320FloorToken);
        } else if ("BUSY".equals(command)) {
            mT320Handler.removeCallbacks(mT320FloorRequestTimeout);
            mT320FloorRequestPending = false;
            mT320FloorToken = null;
            mT320FloorGatewaySession = -1;
            mT320BusyBlocked = true;
            playT320BusySignal();
            String owner = parts.length >= 4 ? parts[3] : "ISMERETLEN";
            Log.e(T320PttAccessibilityService.LOG_TAG,
                    "floor=BUSY owner=" + owner + " tx=OFF");
        } else if ("REVOKE".equals(command)) {
            mT320Handler.removeCallbacks(mT320FloorRequestTimeout);
            mT320Handler.removeCallbacks(mStartT320TxAfterCue);
            mT320FloorRequestPending = false;
            mT320StartPending = false;
            mT320FloorToken = null;
            mT320FloorGatewaySession = -1;
            stopT320Cue("FLOOR_REVOKED");
            if (mT320TxActive && isT320PttEligible() && isTalking()) {
                setTalkingState(false);
            }
            mT320TxActive = false;
            mT320PttActive = false;
            updateT320ActivityLed();
            mT320BusyBlocked = true;
            playT320BusySignal();
            Log.e(T320PttAccessibilityService.LOG_TAG,
                    "floor=REVOKED reason=" + (parts.length >= 4 ? parts[3] : "UNKNOWN")
                            + " tx=OFF");
        }
        return true;
    }

    private void releaseT320Floor() {
        mT320Handler.removeCallbacks(mT320FloorRequestTimeout);
        String token = mT320FloorToken;
        int gatewaySession = mT320FloorGatewaySession;
        mT320FloorRequestPending = false;
        mT320FloorToken = null;
        mT320FloorGatewaySession = -1;
        if (token == null || gatewaySession < 0 || !isConnectionEstablished()) {
            return;
        }
        try {
            super.sendUserTextMessage(
                    gatewaySession,
                    T320_FLOOR_PROTOCOL + " RELEASE " + token);
            Log.e(T320PttAccessibilityService.LOG_TAG,
                    "floor=RELEASE_SENT token=" + token);
        } catch (RuntimeException error) {
            Log.e(T320PttAccessibilityService.LOG_TAG,
                    "floor=RELEASE_FAILED token=" + token, error);
        }
    }

    private IUser findT320FloorGateway(IChannel channel) {
        if (channel == null) {
            return null;
        }
        for (IUser user : channel.getUsers()) {
            if (user != null && T320_FLOOR_GATEWAY_NAME.equals(user.getName())) {
                return user;
            }
        }
        for (IChannel child : channel.getSubchannels()) {
            IUser found = findT320FloorGateway(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void scheduleT320TxAfterCue() {
        if (!mT320PttPressed || !mT320StartPending) {
            return;
        }
        mT320Handler.removeCallbacks(mStartT320TxAfterCue);
        mT320Handler.postDelayed(
                mStartT320TxAfterCue,
                T320_START_CUE_TX_SETTLE_DELAY_MS);
        Log.e(T320PttAccessibilityService.LOG_TAG,
                "cue=BEFORE_TX state=SETTLING delayMs="
                        + T320_START_CUE_TX_SETTLE_DELAY_MS
                        + " localOnly=true tx=OFF");
    }

    private boolean isT320PttEligible() {
        return isConnectionEstablished()
                && Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod());
    }

    private int countAudibleRemoteTalkers() {
        try {
            IUser self = getSessionUser();
            IChannel root = getRootChannel();
            if (self == null || root == null) {
                return 0;
            }
            return countTalkingUsers(root, self.getSession());
        } catch (IllegalStateException error) {
            Log.e(T320PttAccessibilityService.LOG_TAG,
                    "channel=BUSY_CHECK_FAILED tx=OFF", error);
            return 0;
        }
    }

    /**
     * Green indicates reception and red indicates transmission on the T320. Charging always
     * wins: Mumla withdraws its light request whenever external power is connected.
     */
    private void updateT320ActivityLed() {
        if (mNotification == null) {
            return;
        }

        int color = 0;
        if (!mT320ExternalPowerConnected && isConnectionEstablished()) {
            boolean transmitting = mT320TxActive;
            try {
                IUser self = getSessionUser();
                transmitting = transmitting || (self != null
                        && self.getTalkState() != TalkState.PASSIVE);
            } catch (IllegalStateException ignored) {
                // Connection state changed while the LED state was being refreshed.
            }

            if (transmitting) {
                color = Color.RED;
            } else if (countAudibleRemoteTalkers() > 0) {
                color = Color.GREEN;
            }
        }
        mNotification.setActivityLedColor(color);
        Log.e(T320PttAccessibilityService.LOG_TAG,
                "activityLed=" + (color == Color.RED ? "RED"
                        : color == Color.GREEN ? "GREEN" : "OFF")
                        + " externalPower=" + mT320ExternalPowerConnected);
    }

    private int countTalkingUsers(IChannel channel, int selfSession) {
        int count = 0;
        for (IUser user : channel.getUsers()) {
            if (user != null && user.getSession() != selfSession
                    && user.getTalkState() != TalkState.PASSIVE) {
                count++;
            }
        }
        for (IChannel child : channel.getSubchannels()) {
            if (child != null) {
                count += countTalkingUsers(child, selfSession);
            }
        }
        return count;
    }

    private void playT320Cue(int resourceId, final String cueName, final Runnable onFinished) {
        stopT320Cue("REPLACED_BY_" + cueName);
        final MediaPlayer player = MediaPlayer.create(this, resourceId);
        if (player == null) {
            Log.e(T320PttAccessibilityService.LOG_TAG,
                    "cue=" + cueName + " state=CREATE_FAILED localOnly=true");
            if (onFinished != null) {
                onFinished.run();
            }
            return;
        }

        mT320CuePlayer = player;
        player.setOnCompletionListener(completedPlayer -> {
            boolean current = mT320CuePlayer == completedPlayer;
            if (current) {
                mT320CuePlayer = null;
            }
            completedPlayer.release();
            Log.e(T320PttAccessibilityService.LOG_TAG, "cue=" + cueName
                    + " state=COMPLETED localOnly=true");
            if (current && onFinished != null) {
                onFinished.run();
            }
        });
        player.setOnErrorListener((failedPlayer, what, extra) -> {
            boolean current = mT320CuePlayer == failedPlayer;
            if (current) {
                mT320CuePlayer = null;
            }
            failedPlayer.release();
            Log.e(T320PttAccessibilityService.LOG_TAG, "cue=" + cueName
                    + " state=ERROR what=" + what + " extra=" + extra + " localOnly=true");
            if (current && onFinished != null) {
                onFinished.run();
            }
            return true;
        });

        try {
            player.start();
            Log.e(T320PttAccessibilityService.LOG_TAG,
                    "cue=" + cueName + " state=STARTED localOnly=true");
        } catch (IllegalStateException error) {
            if (mT320CuePlayer == player) {
                mT320CuePlayer = null;
            }
            player.release();
            Log.e(T320PttAccessibilityService.LOG_TAG,
                    "cue=" + cueName + " state=START_FAILED localOnly=true", error);
            if (onFinished != null) {
                onFinished.run();
            }
        }
    }

    private void stopT320Cue(String reason) {
        MediaPlayer player = mT320CuePlayer;
        mT320CuePlayer = null;
        if (player == null) {
            return;
        }
        player.setOnCompletionListener(null);
        player.setOnErrorListener(null);
        try {
            player.stop();
        } catch (IllegalStateException ignored) {
        }
        player.release();
        Log.e(T320PttAccessibilityService.LOG_TAG,
                "cue=ACTIVE state=STOPPED reason=" + reason + " localOnly=true");
    }

    private void playT320BusySignal() {
        stopT320Cue("CHANNEL_BUSY");
        stopT320BusySignal("RESTARTED");
        try {
            mT320BusyTone = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            mT320BusyBeepsPlayed = 0;
            mT320Handler.post(mPlayNextT320BusyBeep);
        } catch (RuntimeException error) {
            mT320BusyTone = null;
            Log.e(T320PttAccessibilityService.LOG_TAG,
                    "busyCue=CREATE_FAILED localOnly=true tx=OFF", error);
        }
    }

    private void stopT320BusySignal(String reason) {
        if (mT320Handler != null) {
            mT320Handler.removeCallbacks(mPlayNextT320BusyBeep);
        }
        ToneGenerator tone = mT320BusyTone;
        mT320BusyTone = null;
        if (tone != null) {
            tone.stopTone();
            tone.release();
            Log.e(T320PttAccessibilityService.LOG_TAG,
                    "busyCue=STOPPED reason=" + reason + " localOnly=true tx=OFF");
        }
        mT320BusyBeepsPlayed = 0;
    }

    private void resetT320PttState(String reason) {
        if (mT320Handler != null) {
            mT320Handler.removeCallbacks(mPlayT320EndCue);
            mT320Handler.removeCallbacks(mStartT320TxAfterCue);
            mT320Handler.removeCallbacks(mT320FloorRequestTimeout);
        }
        stopT320Cue(reason);
        stopT320BusySignal(reason);
        if (mT320TxActive && isConnectionEstablished() && isTalking()) {
            setTalkingState(false);
        }
        mT320PttPressed = false;
        mT320PttDownElapsedRealtime = 0L;
        mT320StartPending = false;
        mT320TxActive = false;
        mT320BusyBlocked = false;
        mT320FloorRequestPending = false;
        mT320FloorToken = null;
        mT320FloorGatewaySession = -1;
        mT320PttActive = false;
    }

    @Override
    public List<IChatMessage> getMessageLog() {
        return Collections.unmodifiableList(mMessageLog);
    }

    @Override
    public void clearMessageLog() {
        if (mMessageLog != null) {
            mMessageLog.clear();
        }
    }

    /**
     * Sets whether or not notifications should be suppressed.
     *
     * It's typically a good idea to do this when the main activity is foreground, so that the user
     * is not bombarded with redundant alerts.
     *
     * <b>Chat notifications are NOT suppressed.</b> They may be if a chat indicator is added in the
     * activity itself. For now, the user may disable chat notifications manually.
     *
     * @param suppressNotifications true if Mumla is to disable notifications.
     */
    @Override
    public void setSuppressNotifications(boolean suppressNotifications) {
        mSuppressNotifications = suppressNotifications;
    }

    public static class MumlaBinder extends Binder {
        private final MumlaService mService;

        private MumlaBinder(MumlaService service) {
            mService = service;
        }

        public IMumlaService getService() {
            return mService;
        }
    }

    @Override
    public Message sendUserTextMessage(int session, String message) {
        Message msg = super.sendUserTextMessage(session, message);

        mMessageLog.add(new IChatMessage.TextMessage(msg));
        return msg;
    }

    @Override
    public Message sendChannelTextMessage(int channel, String message, boolean tree) {
        Message msg = super.sendChannelTextMessage(channel, message, tree);

        mMessageLog.add(new IChatMessage.TextMessage(msg));
        return msg;
    }
}
