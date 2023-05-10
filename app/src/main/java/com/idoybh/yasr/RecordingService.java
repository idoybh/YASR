package com.idoybh.yasr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.IBinder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main application service - manages all recording procedure
 */
public class RecordingService extends Service {
    public static final String EXTRA_OPTS = "extra_opts";
    public static final String MPEG_4_EXT = "m4a";
    public static final String OGG_EXT = "ogg";
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
    private RecordOptions mOptions;
    private MediaRecorder mRecorder;
    private int mStatus = Status.IDLE;
    private int mStatusExtra = 0;
    private Calendar mStartTime;
    private long mDuration = 0;

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
        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.service_notification_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
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
        return binder;
    }

    public synchronized void setOptions(RecordOptions options) {
        mOptions = options;
    }

    @SuppressWarnings("ConstantConditions")
    public synchronized void startRecording() {
        if (mStatus == Status.STARTED) return;
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
            } else {
                updateListeners(Status.FAILED, extra);
            }
        });
        mRecorder.setOnErrorListener((mr, what, extra) -> updateListeners(Status.FAILED, extra));
        try {
            mRecorder.prepare();
            mRecorder.start();
        } catch (Exception e) {
            e.printStackTrace();
            updateListeners(Status.FAILED, -1);
        }
        mStartTime = Calendar.getInstance();
        mDuration = 0;
        updateListeners(Status.STARTED);
    }

    public synchronized void stopRecording() {
        if (mRecorder != null) {
            mRecorder.stop();
            mRecorder.release();
            mRecorder = null;
        }
        updateListeners(Status.IDLE);
    }

    public synchronized void pauseResumeRecording() {
        if (mRecorder == null) return;
        if (mStatus == Status.PAUSED) {
            mStartTime = Calendar.getInstance();
            mRecorder.resume();
            updateListeners(Status.STARTED);
        } else if (mStatus == Status.STARTED) {
            mDuration += Calendar.getInstance().getTimeInMillis() - mStartTime.getTimeInMillis();
            mStartTime = null;
            mRecorder.pause();
            updateListeners(Status.PAUSED);
        }
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

    private synchronized void updateListeners(final int status) {
        updateListeners(status, 0);
    }

    private synchronized void updateListeners(final int status, final int extra) {
        if (status == mStatus) return;
        mStatus = status;
        mStatusExtra = extra;
        for (StatusListener listener : mListeners) {
            listener.onStatusChanged(status, extra);
        }
    }

    private static String getFileExtension(final File file) {
        final String name = file.getName();
        return name.substring(name.lastIndexOf(".") + 1);
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