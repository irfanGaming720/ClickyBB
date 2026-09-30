package com.clickybb;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.lang.ref.WeakReference;

public class MainActivity extends Activity implements
        SeekBar.OnSeekBarChangeListener,
        View.OnClickListener,
        View.OnTouchListener,
        Runnable {

    // Module A: Native Media Volume Controller
    private AudioManager mAudioManager;
    private SeekBar mVolumeSeekBar;
    private TextView mTvVolumePercent;
    private int mMaxVolume = 15;
    private int mLastVolume = 7;

    // Module B: OLED Standby & Touch-Trap Immunity
    private FrameLayout mBlackoutOverlay;
    private Button mBtnEnterStandby;
    private boolean mIsStandbyActive = false;

    // Module C: Lifecycle Process Retention & Watchdog
    private static final long IDLE_TIMEOUT_MS = 180000L; // 3 minutes
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mLastInteractionTime = System.currentTimeMillis();
    private ScreenOffReceiver mScreenOffReceiver;
    private boolean mIsTerminating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Keep QNX Android Runtime daemon active while in use
        RuntimeKeeperService.start(this);

        initStandbyControl();
        initVolumeControl();
        registerScreenOffReceiver();
        recordInteraction();
    }

    private void initVolumeControl() {
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mVolumeSeekBar = (SeekBar) findViewById(R.id.seek_bar_volume);
        mTvVolumePercent = (TextView) findViewById(R.id.tv_volume_percent);

        if (mAudioManager == null || mVolumeSeekBar == null) return;

        mMaxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (mMaxVolume <= 0) mMaxVolume = 15;

        int current = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (current > 0) {
            mLastVolume = current;
        }

        mVolumeSeekBar.setMax(mMaxVolume);
        mVolumeSeekBar.setProgress(current);
        updatePercentageText(current);

        mVolumeSeekBar.setOnSeekBarChangeListener(this);

        View btnMinus = findViewById(R.id.btn_vol_minus);
        if (btnMinus != null) btnMinus.setOnClickListener(this);

        View btnPlus = findViewById(R.id.btn_vol_plus);
        if (btnPlus != null) btnPlus.setOnClickListener(this);

        View btn50 = findViewById(R.id.btn_vol_50);
        if (btn50 != null) btn50.setOnClickListener(this);

        View btnMute = findViewById(R.id.btn_vol_mute);
        if (btnMute != null) btnMute.setOnClickListener(this);
    }

    private void initStandbyControl() {
        mBtnEnterStandby = (Button) findViewById(R.id.btnEnterStandby);
        mBlackoutOverlay = (FrameLayout) findViewById(R.id.blackoutOverlay);

        // Force dismissal immediately to eliminate touch traps
        if (mBlackoutOverlay != null) {
            mBlackoutOverlay.setVisibility(View.GONE);
            mBlackoutOverlay.setOnTouchListener(this);
        }

        if (mBtnEnterStandby != null) {
            mBtnEnterStandby.setOnClickListener(this);
        }
    }

    private void registerScreenOffReceiver() {
        try {
            mScreenOffReceiver = new ScreenOffReceiver(this);
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            registerReceiver(mScreenOffReceiver, filter);
        } catch (Throwable ignored) {}
    }

    private void recordInteraction() {
        mLastInteractionTime = System.currentTimeMillis();
    }

    private void updatePercentageText(int current) {
        if (mTvVolumePercent != null && mMaxVolume > 0) {
            int percent = (int) (((float) current / mMaxVolume) * 100);
            mTvVolumePercent.setText(percent + "%");
        }
    }

    private void applyVolume(int target) {
        if (mAudioManager == null || mVolumeSeekBar == null) return;
        target = Math.max(0, Math.min(mMaxVolume, target));
        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        mVolumeSeekBar.setProgress(target);
        updatePercentageText(target);
        if (target > 0) {
            mLastVolume = target;
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser && mAudioManager != null) {
            recordInteraction();
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
            updatePercentageText(progress);
            if (progress > 0) {
                mLastVolume = progress;
            }
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        recordInteraction();
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        recordInteraction();
    }

    @Override
    public void onClick(View v) {
        recordInteraction();
        int id = v.getId();
        if (id == R.id.btn_vol_minus) {
            if (mAudioManager != null) {
                int cur = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                int step = Math.max(1, mMaxVolume / 10);
                int target = Math.max(0, cur - step);
                applyVolume(target);
            }
        } else if (id == R.id.btn_vol_plus) {
            if (mAudioManager != null) {
                int cur = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                int step = Math.max(1, mMaxVolume / 10);
                int target = Math.min(mMaxVolume, cur + step);
                applyVolume(target);
            }
        } else if (id == R.id.btn_vol_50) {
            int target = mMaxVolume / 2;
            applyVolume(target);
        } else if (id == R.id.btn_vol_mute) {
            if (mAudioManager != null) {
                int cur = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (cur > 0) {
                    mLastVolume = cur;
                    applyVolume(0);
                } else {
                    int restore = (mLastVolume > 0) ? mLastVolume : (mMaxVolume / 2);
                    applyVolume(restore);
                }
            }
        } else if (id == R.id.btnEnterStandby) {
            showBlackout();
        }
    }

    // Module B: Standby Display & Wake Implementation
    private void showBlackout() {
        mIsStandbyActive = true;
        if (mBlackoutOverlay != null) {
            mBlackoutOverlay.setVisibility(View.VISIBLE);
        }
        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.screenBrightness = 0.001f;
            window.setAttributes(lp);
        }
    }

    private void hideBlackout() {
        mIsStandbyActive = false;
        if (mBlackoutOverlay != null) {
            mBlackoutOverlay.setVisibility(View.GONE);
        }
        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            window.setAttributes(lp);
        }
    }

    public void onScreenOff() {
        // If Standby was explicitly activated by user, kill process on screen turn-off
        if (mIsStandbyActive) {
            terminateProcess();
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        // Swallows all touches on the blackout overlay
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mBlackoutOverlay != null && mBlackoutOverlay.getVisibility() == View.VISIBLE) {
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mBlackoutOverlay != null && mBlackoutOverlay.getVisibility() == View.VISIBLE) {
            int keyCode = event.getKeyCode();
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_BACK) {
                    hideBlackout();
                    recordInteraction();
                    return true;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                return super.dispatchKeyEvent(event);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (mBlackoutOverlay != null && mBlackoutOverlay.getVisibility() == View.VISIBLE) {
            hideBlackout();
            recordInteraction();
            return;
        }
        // Exiting normal screen: terminate process completely
        terminateProcess();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.removeCallbacks(this);
        recordInteraction();
        hideBlackout();
        RuntimeKeeperService.start(this);
        if (mAudioManager != null && mVolumeSeekBar != null) {
            mMaxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (mMaxVolume <= 0) mMaxVolume = 15;
            mVolumeSeekBar.setMax(mMaxVolume);
            int current = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            mVolumeSeekBar.setProgress(current);
            updatePercentageText(current);
        }
    }

    @Override
    protected void onPause() {
        hideBlackout();
        super.onPause();
    }

    @Override
    protected void onStop() {
        hideBlackout();
        super.onStop();
        // Start 3-minute idle watchdog timer from last interaction
        long elapsed = System.currentTimeMillis() - mLastInteractionTime;
        long remaining = Math.max(1000L, IDLE_TIMEOUT_MS - elapsed);
        mHandler.removeCallbacks(this);
        mHandler.postDelayed(this, remaining);
    }

    @Override
    public void run() {
        // 3-minute idle watchdog expired in background -> terminate process to free QNX Android container
        terminateProcess();
    }

    @Override
    protected void onDestroy() {
        terminateProcess();
        super.onDestroy();
    }

    private void terminateProcess() {
        if (mIsTerminating) return;
        mIsTerminating = true;

        RuntimeKeeperService.stop(this);

        if (mHandler != null) {
            mHandler.removeCallbacks(this);
        }
        if (mScreenOffReceiver != null) {
            try {
                unregisterReceiver(mScreenOffReceiver);
            } catch (Throwable ignored) {}
            mScreenOffReceiver = null;
        }
        try {
            finishAffinity();
        } catch (Throwable ignored) {
            try {
                finish();
            } catch (Throwable ignored2) {}
        }
        try {
            Process.killProcess(Process.myPid());
        } catch (Throwable ignored) {}
        System.exit(0);
    }

    // Static nested BroadcastReceiver prevents D8 compiler synthetic parameter issues
    public static class ScreenOffReceiver extends BroadcastReceiver {
        private final WeakReference<MainActivity> mActivityRef;

        public ScreenOffReceiver(MainActivity activity) {
            this.mActivityRef = new WeakReference<MainActivity>(activity);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            MainActivity activity = mActivityRef.get();
            if (activity != null && intent != null) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    activity.onScreenOff();
                }
            }
        }
    }
}
