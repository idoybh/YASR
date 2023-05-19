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
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Calendar;

public class RawRecordingService extends RecordingService {
    public static final String EXTRA_INTENT = "raw";
    private final IBinder binder = new RawRecordingService.LocalBinder();
    private AudioRecord mRecorder;
    private AudioFormat mFormat;
    private HandlerThread mRecodingThread = new HandlerThread("RawRecordingThread");
    private File tmpFile;
    private int mBufferSize;

    public RawRecordingService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @SuppressLint("MissingPermission")
    @Override
    public synchronized void startRecording() {
        if (mStatus != Status.IDLE) {
            updateListeners(mStatus);
            return;
        }
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        if (mOptions.getEncodingRate() == 32)
            encoding = AudioFormat.ENCODING_PCM_32BIT;
        else if (mOptions.getEncodingRate() == 24)
            encoding = AudioFormat.ENCODING_PCM_24BIT_PACKED;
        else if (mOptions.getEncodingRate() == 8)
            encoding = AudioFormat.ENCODING_PCM_8BIT;
        final int channels = mOptions.getChannels() > 1
                ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
        final int sRate = mOptions.getSamplingRate();
        mFormat = new AudioFormat.Builder()
                .setSampleRate(sRate)
                .setChannelMask(channels)
                .setEncoding(encoding).build();
        mBufferSize = AudioRecord.getMinBufferSize(sRate, channels, encoding);
        mRecorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                mFormat.getSampleRate(),
                mFormat.getChannelMask(),
                mFormat.getEncoding(),
                mBufferSize);
        mRecorder.setPreferredDevice(mOptions.getSource());
        mRecorder.startRecording();
        mRecodingThread.start();
        Handler handler = new Handler(mRecodingThread.getLooper());
        handler.post(() -> {
            try {
                tmpFile = File.createTempFile("tmp", ".pcm", getCacheDir());
            } catch (IOException e) {
                e.printStackTrace();
                updateListeners(Status.FAILED);
                return;
            }
            final int[] limit = mOptions.getLimit();
            byte[] data = new byte[mBufferSize / 2];
            int written = 36;
            try (FileOutputStream out = new FileOutputStream(tmpFile)) {
                while (getStatus() != Status.IDLE) {
                    int read = mRecorder.read(data, 0, data.length);
                    if (getStatus() != Status.STARTED) continue;
                    out.write(data);
                    written += read;
                    if (limit == null) continue;
                    if (limit[0] == RecordFragment.LIMIT_MODE_TIME &&
                            getDuration() / 1000 >= limit[1]) {
                        // time limit reached
                        stopRecording();
                        break;
                    }
                    if (limit[0] != RecordFragment.LIMIT_MODE_SIZE ||
                            written < limit[1] * 1000) continue;
                    // size limit reached
                    stopRecording();
                    break;
                }
            } catch (Exception e) {
                e.printStackTrace();
                updateListeners(Status.FAILED);
            }
        });
        mStartTime = Calendar.getInstance();
        mDuration = 0;
        updateListeners(Status.STARTED);
    }

    @Override
    protected synchronized boolean suspendRecord(boolean suspend) {
        if (mRecorder == null) return false;
        if (suspend) {
            mRecorder.stop();
            return true;
        }
        mRecorder.startRecording();
        return true;
    }

    @Override
    public synchronized void eraseRecording() {
        if (mRecorder != null) {
            mRecorder.stop();
            mRecorder.release();
            mRecorder = null;
            mRecodingThread.quit();
            mRecodingThread = null;
        }
        if (tmpFile != null && tmpFile.delete()) {
            updateListeners(Status.IDLE);
            return;
        }
        updateListeners(Status.FAILED);
    }

    @Override
    public synchronized void stopRecording() {
        if (mRecorder != null) {
            mRecorder.stop();
            mRecorder.release();
            mRecorder = null;
            mRecodingThread.quit();
            mRecodingThread = null;
        }
        if (tmpFile == null || !tmpFile.exists()) {
            updateListeners(Status.FAILED);
            return;
        }
        final boolean res = convertToWav(tmpFile, mOptions.getFile());
        updateListeners(res ? Status.IDLE : Status.FAILED);
        stopSelf();
    }

    @Override
    protected String getExtraIntentString() {
        return EXTRA_INTENT;
    }

    public class LocalBinder extends Binder {
        RawRecordingService getService() {
            // Return this instance of LocalService so clients can call public methods.
            return RawRecordingService.this;
        }
    }

    // NOTE! this is essentially copying the raw data underneath a WAVE header,
    // essentially the same as storing raw... Codecs aren't made easy to work with platform side
    // so the rest is outside the scope of this project ;)
    private boolean convertToWav(File source, File target) {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] data = new byte[mBufferSize];
            final int len = in.available();
            writeWavHeader(out, len);
            while (in.read(data) != -1) out.write(data);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    // see: https://android.googlesource.com/platform/frameworks/base/+/android-4.4_r1/core/java/android/speech/srec/WaveHeader.java
    /**
     * Write a WAVE file header.
     * @param out {@link java.io.FileOutputStream} to receive the header.
     * @throws IOException on any write error
     */
    private void writeWavHeader(FileOutputStream out, int len) throws IOException {
        /* RIFF header */
        writeId(out, "RIFF");
        writeInt(out, 36 + len);
        writeId(out, "WAVE");
        /* fmt chunk */
        writeId(out, "fmt ");
        writeInt(out, 16);
        writeShort(out, (short) 1); // PCM
        writeShort(out, (short) mOptions.getChannels());
        writeInt(out, mFormat.getSampleRate());
        writeInt(out, mFormat.getChannelMask() * mFormat.getSampleRate() * mOptions.getEncodingRate() / 8);
        writeShort(out, (short) (mOptions.getChannels() * mOptions.getEncodingRate() / 8));
        writeShort(out, (short) mOptions.getEncodingRate());
        /* data chunk */
        writeId(out, "data");
        writeInt(out, len);
    }
    private static void writeId(FileOutputStream out, String id) throws IOException {
        for (int i = 0; i < id.length(); i++) out.write(id.charAt(i));
    }
    private static void writeInt(FileOutputStream out, int val) throws IOException {
        out.write(val);
        out.write(val >> 8);
        out.write(val >> 16);
        out.write(val >> 24);
    }
    private static void writeShort(FileOutputStream out, short val) throws IOException {
        out.write(val);
        out.write(val >> 8);
    }
}