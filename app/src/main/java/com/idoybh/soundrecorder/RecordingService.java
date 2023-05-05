package com.idoybh.soundrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import java.io.File;
import java.io.Serializable;

public class RecordingService extends Service {
    public static final String EXTRA_START = "extra_start";
    public static final String EXTRA_OPTS = "extra_opts";
    private static final String NOTIFICATION_CHANNEL = "Recording Service";
    private static final int NOTIFICATION_ID = 0x01;
    private final IBinder binder = new LocalBinder();
    private RecordOptions mOptions;
    private boolean mIsPaused = false;
    public RecordingService() {
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent.getBooleanExtra(EXTRA_START, false)) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL, NOTIFICATION_CHANNEL,
                    NotificationManager.IMPORTANCE_DEFAULT);
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
            mOptions = intent.getSerializableExtra(EXTRA_OPTS, RecordOptions.class);
            startRecording();
        }
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

    private synchronized void startRecording() {

    }

    private synchronized void stopRecording() {

    }

    public synchronized void pauseResumeRecording() {
        mIsPaused = !mIsPaused;
    }

    public synchronized boolean getIsPaused() {
        return mIsPaused;
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
    public static class RecordOptions implements Serializable {
        private final File mFile;
        private long mSizeLimit = 0;
        private boolean mSaveLocation = false;

        /**
         * Recording options collection
         * @param file the recording file to save to
         * @param sizeLimit size limit in bytes. 0 = unlimited
         * @param saveLocation whether to save geographical location to the file metadata
         */
        public RecordOptions(File file, long sizeLimit, boolean saveLocation) {
            mFile = file;
            mSizeLimit = sizeLimit;
            mSaveLocation = saveLocation;
        }

        public File getFile() {
            return mFile;
        }

        public long getSizeLimit() {
            return mSizeLimit;
        }

        public boolean getSaveLocation() {
            return mSaveLocation;
        }
    }
}