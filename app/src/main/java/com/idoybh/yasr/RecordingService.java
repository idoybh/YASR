/*
 * Copyright (C) 2023 Ido Ben-Hur
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.idoybh.yasr;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main application service - manages all recording procedure
 */
public class RecordingService extends Service {
    public static final String PREF_STARTED = "service_started";
    public static final String EXTRA_INTENT = "regular";
    public static final String MPEG_4_EXT = "m4a";
    public static final String OGG_EXT = "ogg";
    public static final String WAV_EXT = "wav";
    private static final String WAKELOCK_TAG = "YASR::RecordingWakelock";
    private static final String NOTIFICATION_CHANNEL = "Recording Service";
    private static final int NOTIFICATION_ID = 0x01;
    private static final Map<String, Integer> EXT_TO_OUT = new HashMap<>(Map.of(
            MPEG_4_EXT, MediaRecorder.OutputFormat.MPEG_4,
            OGG_EXT, MediaRecorder.OutputFormat.OGG
    ));
    private static final Map<Integer, Integer> OUT_TO_ENCODER = new HashMap<>(Map.of(
            MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC,
            MediaRecorder.OutputFormat.OGG, MediaRecorder.AudioEncoder.OPUS
    ));
    private final IBinder binder = new LocalBinder();
    protected RecordOptions mOptions;
    private MediaRecorder mRecorder;
    private AudioManager mAudioManager;
    private AudioFocusRequest mAudioFocusRequest;
    private SharedPreferences mSharedPreferences;
    private PowerManager.WakeLock mWakeLock;
    protected int mStatus = Status.IDLE;
    private int mStatusExtra = 0;
    protected Calendar mStartTime;
    protected long mDuration = 0;

    private final List<StatusListener> mListeners = new ArrayList<>();

    public interface StatusListener {
        void onStatusChanged(int status, int extra);
    }

    public RecordingService() {
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL, NOTIFICATION_CHANNEL,
                NotificationManager.IMPORTANCE_LOW);
        channel.setBlockable(false);
        channel.setSound(null, null);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(channel);
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.putExtra(MainActivity.EXTRA_RECORDING, getExtraIntentString());
        PendingIntent contentPI = PendingIntent.getActivity(this, NOTIFICATION_ID,
                contentIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.service_notification_text))
                .setSmallIcon(R.drawable.baseline_mic_24)
                .setContentIntent(contentPI)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        updateListeners(mStatus);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRecording();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        updateListeners(mStatus);
        return binder;
    }

    public synchronized RecordOptions getOptions() {
        return mOptions;
    }

    public synchronized void setOptions(RecordOptions options) {
        mOptions = options;
    }

    @SuppressWarnings("ConstantConditions")
    public synchronized void startRecording() {
        if (mStatus != Status.IDLE) {
            updateListeners(mStatus);
            return;
        }
        final File recordFile = mOptions.getFile();
        final int outFormat = EXT_TO_OUT.getOrDefault(getFileExtension(recordFile), -1);
        mRecorder = new MediaRecorder(this);
        mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mRecorder.setPreferredDevice(mOptions.getSource());
        mRecorder.setOutputFormat(outFormat);
        mRecorder.setAudioEncoder(OUT_TO_ENCODER.get(outFormat));
        mRecorder.setAudioSamplingRate(mOptions.getSamplingRate());
        mRecorder.setAudioEncodingBitRate(mOptions.getSamplingRate() * mOptions.getEncodingRate());
        mRecorder.setAudioChannels(mOptions.getChannels());
        final int[] limit = mOptions.getLimit();
        if (limit != null) {
            if (limit[0] == RecordFragment.LIMIT_MODE_TIME) {
                mRecorder.setMaxDuration(limit[1] * 1000 /* s to ms */);
            } else if (limit[0] == RecordFragment.LIMIT_MODE_SIZE) {
                mRecorder.setMaxFileSize(limit[1] * 1000L /* kB to bytes */);
            }
        }
        mRecorder.setOutputFile(mOptions.getFile());
        mRecorder.setOnInfoListener((mr, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                updateListeners(Status.MAX_REACHED);
                returnAudioFocus();
            } else {
                updateListeners(Status.FAILED, extra);
                returnAudioFocus();
            }
        });
        mRecorder.setOnErrorListener((mr, what, extra) -> updateListeners(Status.FAILED, extra));
        try {
            requestAudioFocus();
            mRecorder.prepare();
            mRecorder.start();
        } catch (Exception e) {
            e.printStackTrace();
            updateListeners(Status.FAILED, -1);
            returnAudioFocus();
            return;
        }
        mStartTime = Calendar.getInstance();
        mDuration = 0;
        updateListeners(Status.STARTED);
    }

    public synchronized void eraseRecording() {
        if (mRecorder != null) {
            if (mStatus == Status.PAUSED)
                mRecorder.resume();
            mRecorder.stop();
            mRecorder.release();
            mRecorder = null;
        }
        //noinspection ResultOfMethodCallIgnored
        mOptions.getFile().delete();
        returnAudioFocus();
        updateListeners(Status.IDLE);
    }

    public synchronized void stopRecording() {
        if (mRecorder != null) {
            if (mStatus == Status.PAUSED)
                mRecorder.resume();
            mRecorder.stop();
            mRecorder.release();
            mRecorder = null;
        }
        returnAudioFocus();
        updateListeners(Status.IDLE);
    }

    public synchronized void pauseResumeRecording() {
        if (mStatus == Status.PAUSED) {
            mStartTime = Calendar.getInstance();
            if (!suspendRecord(false)) return;
            updateListeners(Status.STARTED);
        } else if (mStatus == Status.STARTED) {
            mDuration += Calendar.getInstance().getTimeInMillis() - mStartTime.getTimeInMillis();
            mStartTime = null;
            if (!suspendRecord(true)) return;
            updateListeners(Status.PAUSED);
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    protected synchronized boolean suspendRecord(boolean suspend) {
        if (mRecorder == null) return false;
        if (suspend) {
            mRecorder.pause();
            returnAudioFocus();
            return true;
        }
        requestAudioFocus();
        mRecorder.resume();
        return true;
    }

    /**
     * Get the total duration of the current recording
     * @return the total duration in milliseconds
     */
    public synchronized long getDuration() {
        if (mStartTime == null) return mDuration; // when paused
        return mDuration + Calendar.getInstance().getTimeInMillis() - mStartTime.getTimeInMillis();
    }

    public synchronized int getStatus() {
        return mStatus;
    }

    public synchronized int getStatusExtra() {
        return mStatusExtra;
    }

    public synchronized void addListener(StatusListener listener) {
        if (mListeners.contains(listener)) return;
        mListeners.add(listener);
    }

    public synchronized void removeListener(StatusListener listener) {
        mListeners.remove(listener);
    }

    public synchronized void clearListeners() {
        mListeners.clear();
    }

    public synchronized void requestUpdate() {
        updateListeners(mStatus, mStatusExtra);
    }

    protected synchronized void updateListeners(final int status) {
        updateListeners(status, 0);
    }

    @SuppressLint("ApplySharedPref")
    private synchronized void updateListeners(final int status, final int extra) {
        if (status != Status.STARTED && status != Status.PAUSED) {
            getPrefs().edit().remove(PREF_STARTED).commit();
            updateWakelock(false);
        } else {
            getPrefs().edit().putString(PREF_STARTED, getExtraIntentString()).commit();
            updateWakelock(true);
        }
        mStatus = status;
        mStatusExtra = extra;
        for (StatusListener listener : mListeners) {
            listener.onStatusChanged(status, extra);
        }
    }

    @SuppressLint("WakelockTimeout")
    protected synchronized void updateWakelock(final boolean acquire) {
        if (acquire) {
            if (mWakeLock == null) {
                PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
                mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
            }
            if (mWakeLock.isHeld()) return;
            mWakeLock.acquire();
            return;
        }
        if (mWakeLock == null || !mWakeLock.isHeld()) return;
        mWakeLock.release();
    }

    protected SharedPreferences getPrefs() {
        if (mSharedPreferences != null)
            return mSharedPreferences;
        mSharedPreferences = getSharedPreferences(
                RecordFragment.SHARED_PREF_FILE, Context.MODE_PRIVATE);
        return mSharedPreferences;
    }

    private AudioManager getAudioManager() {
        if (mAudioManager == null) {
            mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            AudioAttributes attr = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            mAudioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attr)
                    .setAcceptsDelayedFocusGain(false)
                    .build();
        }
        return mAudioManager;
    }

    private static String getFileExtension(final File file) {
        final String name = file.getName();
        return name.substring(name.lastIndexOf(".") + 1);
    }

    protected String getExtraIntentString() {
        return EXTRA_INTENT;
    }

    protected void requestAudioFocus() {
        getAudioManager().requestAudioFocus(mAudioFocusRequest);
    }

    protected void returnAudioFocus() {
        getAudioManager().abandonAudioFocusRequest(mAudioFocusRequest);
    }

    public class LocalBinder extends Binder {
        RecordingService getService() {
            // Return this instance of LocalService so clients can call public methods.
            return RecordingService.this;
        }
    }

    /**
     * Recording options container class
     */
    public static class RecordOptions {
        private final File mFile;
        private final AudioDeviceInfo mSource;
        private final int mSamplingRate;
        private final int mEncodingRate;
        private final int mChannels;
        private final int[] mLimit;

        /**
         * Recording options collection
         * @param file the recording file to save to
         * @param source the audio device to record via
         * @param samplingRate the sampling rate per second
         * @param encodingRate the encoding rate in bits per second
         * @param channels the number of channels to record with
         * @param limit array of { limit mode, limit } where limit is either in seconds or MB
         *              null disables
         */
        public RecordOptions(File file, AudioDeviceInfo source, int samplingRate, int encodingRate,
                             int channels, int[] limit) {
            mFile = file;
            mSource = source;
            mSamplingRate = samplingRate;
            mEncodingRate = encodingRate;
            mChannels = channels;
            mLimit = limit;
        }

        public File getFile() {
            return mFile;
        }

        public AudioDeviceInfo getSource() {
            return mSource;
        }

        public int getSamplingRate() {
            return mSamplingRate;
        }
        public int getEncodingRate() { return mEncodingRate; }

        public int getChannels() {
            return mChannels;
        }

        public int[] getLimit() {
            return mLimit;
        }
    }

    public static final class Status {
        public static final int IDLE = 0;
        public static final int STARTED = 1;
        public static final int PAUSED = 2;
        public static final int MAX_REACHED = 3;
        public static final int FAILED = -1;
    }
}
