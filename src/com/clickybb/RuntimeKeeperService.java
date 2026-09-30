package com.clickybb;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground Service + Partial WakeLock + Silent AudioTrack Anchor.
 *
 * BlackBerry 10's QNX audio server (io-audio) only opens the physical hardware
 * routing and mixer controls for STREAM_MUSIC when an active audio session is held.
 * This service runs a lightweight in-memory silent AudioTrack looping buffer with 0% CPU overhead,
 * keeping the hardware audio bridge wide open while ClickyBB is active or in active frame.
 */
public class RuntimeKeeperService extends Service {

    private static final int NOTIFICATION_ID = 9001;

    public static final String ACTION_START = "com.clickybb.action.START_KEEPER";
    public static final String ACTION_STOP  = "com.clickybb.action.STOP_KEEPER";

    private static volatile RuntimeKeeperService sInstance;
    private PowerManager.WakeLock mWakeLock;
    private AudioTrack mSilentAudioTrack;

    public static void start(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, RuntimeKeeperService.class);
            intent.setAction(ACTION_START);
            context.startService(intent);
        } catch (Throwable ignored) {}
        if (sInstance != null) {
            sInstance.startKeeper();
        }
    }

    public static void stop(Context context) {
        if (sInstance != null) {
            sInstance.stopKeeper();
        }
        if (context != null) {
            try {
                Intent intent = new Intent(context, RuntimeKeeperService.class);
                intent.setAction(ACTION_STOP);
                context.stopService(intent);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClickyBB:KeeperWakeLock");
            mWakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopKeeper();
            stopSelf();
            return START_NOT_STICKY;
        }
        startKeeper();
        return START_STICKY;
    }

    public synchronized void startKeeper() {
        acquireWakeLock();
        startForegroundNotification();
        startSilentAudioAnchor();
    }

    public synchronized void stopKeeper() {
        stopSilentAudioAnchor();
        releaseWakeLock();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {}
    }

    private void acquireWakeLock() {
        if (mWakeLock != null && !mWakeLock.isHeld()) {
            try {
                mWakeLock.acquire();
            } catch (Throwable ignored) {}
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            try {
                mWakeLock.release();
            } catch (Throwable ignored) {}
        }
    }

    private void startForegroundNotification() {
        try {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT
            );

            Notification.Builder builder = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("ClickyBB")
                    .setContentText("Audio bridge active")
                    .setContentIntent(pendingIntent)
                    .setOngoing(true);

            startForeground(NOTIFICATION_ID, builder.build());
        } catch (Throwable ignored) {}
    }

    private void startSilentAudioAnchor() {
        if (mSilentAudioTrack != null) {
            try {
                if (mSilentAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    return;
                }
            } catch (Throwable ignored) {}
        }

        stopSilentAudioAnchor();

        try {
            int sampleRate = 44100;
            int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            if (minBuf <= 0) {
                sampleRate = 8000;
                minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
                if (minBuf <= 0) minBuf = 4096;
            }

            // 1-second in-memory silent PCM buffer
            int frameCount = sampleRate;
            int bufferSize = Math.max(minBuf * 2, frameCount * 2);
            frameCount = bufferSize / 2;

            byte[] silence = new byte[bufferSize]; // All zeros = pure digital silence

            mSilentAudioTrack = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize,
                    AudioTrack.MODE_STATIC
            );

            if (mSilentAudioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                mSilentAudioTrack.write(silence, 0, silence.length);
                try {
                    mSilentAudioTrack.setLoopPoints(0, frameCount, -1);
                } catch (Throwable t1) {
                    try {
                        mSilentAudioTrack.setLoopPoints(0, frameCount - 1, -1);
                    } catch (Throwable ignored) {}
                }
                mSilentAudioTrack.play();
            }
        } catch (Throwable ignored) {}
    }

    private void stopSilentAudioAnchor() {
        if (mSilentAudioTrack != null) {
            try {
                if (mSilentAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    mSilentAudioTrack.stop();
                }
            } catch (Throwable ignored) {}
            try {
                mSilentAudioTrack.release();
            } catch (Throwable ignored) {}
            mSilentAudioTrack = null;
        }
    }

    @Override
    public void onDestroy() {
        stopKeeper();
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
