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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnticipateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.idoybh.yasr.databinding.FragmentRecordBinding;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;

public class RecordFragment extends Fragment {
    public static final String BUNDLE_ARG1 = "recording";
    public static final String SHARED_PREF_FILE = "yasr_prefs";
    private static final String PREF_INPUT_DEVICE = "input_device_selected";
    private static final String PREF_OUTPUT_EXT = "output_ext";
    private static final String PREF_OUTPUT_QUALITY = "output_quality";
    private static final String PREF_CHANNELS = "output_channels";
    private static final String PREF_LIMIT_MODE = "limit_mode";
    private static final String PREF_LIMIT_VALUE = "limit_value";

    public static final int LIMIT_MODE_SIZE = 0;
    public static final int LIMIT_MODE_TIME = 1;

    private final Handler mUiHandler = FirstFragment.mUiHandler;
    private final Executor mExecutor = FirstFragment.mExecutor;
    private FragmentRecordBinding binding;
    private MediaRecorder mRecorder;
    private SharedPreferences mSharedPrefs;
    private List<AudioDeviceInfo> mAudioDevices;
    private int mSelectedDeviceIndex = 0;
    private int mSampleRate;
    private int mEncodeRate;
    private int mLimitMode = LIMIT_MODE_SIZE;
    private List<View> mOptionViews;
    private File mCurrentRecordingFile;
    private File mTempAudioFile;
    private String mDefaultName;
    private String mTotalDurationStr;
    private RecordingService mService;
    private RawRecordingService mRawService;
    private boolean isStarted = false;
    private boolean isRawStarted = false;
    private boolean isResumed = false;
    private Timer mNoiseTimer;
    private TimerTask mNoiseTimerTask;

    @Override
    public View onCreateView(
            @NotNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        binding = FragmentRecordBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mOptionViews = Arrays.asList(
                binding.deviceMenu,
                binding.recordingNameInputText,
                binding.outputToggle,
                binding.qualityToggle,
                binding.limitToggle,
                binding.limitSlider,
                binding.channelToggle
        );

        updateAudioDevices();
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.ACTION_HEADSET_PLUG);
        requireContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int mic = intent.getIntExtra("microphone", 0);
                if (mic != 1) return;
                updateAudioDevices();
            }
        }, filter);

        // settings up the rest of the views + listeners
        binding.deviceMenu.setOnItemClickListener((parent, view1, position, id) -> {
            mSelectedDeviceIndex = position;
            updateInfoText();
            registerToMicAmp();
        });

        binding.limitToggle.addOnButtonCheckedListener((group, checkedId, isChecked) ->
                setLimitMode(checkedId == binding.limitBtnTime.getId() && isChecked
                        ? LIMIT_MODE_TIME : LIMIT_MODE_SIZE));
        setLimitMode(binding.limitToggle.getCheckedButtonId() == binding.limitBtnTime.getId()
                ? LIMIT_MODE_TIME : LIMIT_MODE_SIZE);

        refreshDefaultName();
        binding.recordButton.setOnClickListener(this::onRecordingClicked);
        binding.saveButton.setOnClickListener(this::onSaveClicked);
        binding.discardButton.setOnClickListener(this::onDiscardClicked);
        binding.mediaButton.setOnClickListener(this::onMediaClicked);
        binding.outputToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> updateInfoText());
        binding.qualityToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> updateInfoText());
        requireActivity().getOnBackPressedDispatcher().addCallback(onBackCallback);

        // loading shared prefs / defaults
        final String outPref = getPrefs().getString(PREF_OUTPUT_EXT, RecordingService.OGG_EXT);
        int outID = binding.outputBtnMP4.getId();
        if (outPref.equals(RecordingService.OGG_EXT))
            outID = binding.outputBtnOGG.getId();
        else if (outPref.equals(RecordingService.WAV_EXT))
            outID = binding.outputBtnWAV.getId();
        binding.outputToggle.check(outID);
        final int qualityPref = getPrefs().getInt(PREF_OUTPUT_QUALITY, 0);
        binding.qualityToggle.check(binding.qualityToggle.getChildAt(qualityPref).getId());
        final int channelsPref = getPrefs().getInt(PREF_CHANNELS, 2);
        binding.channelToggle.check(channelsPref == 2
                ? binding.channelBtn2.getId() : binding.channelBtn1.getId());
        final int limitModePref = getPrefs().getInt(PREF_LIMIT_MODE, LIMIT_MODE_SIZE);
        binding.limitToggle.check(limitModePref == LIMIT_MODE_TIME
                ? binding.limitBtnTime.getId() : binding.limitBtnSize.getId());
        final int limitValPref = getPrefs().getInt(PREF_LIMIT_VALUE, 0);
        binding.limitSlider.setValue(limitValPref);

        updateInfoText();
        registerToMicAmp();

        Bundle bundle = getArguments();
        if (bundle == null) return;
        final String extra = bundle.getString(BUNDLE_ARG1, null);
        if (extra == null || extra.isEmpty()) return;
        if (isStarted || isRawStarted) return;
        // resume showing progress by connecting to the service again
        isResumed = true;
        Intent intent = new Intent(requireContext(), extra.equals(RecordingService.EXTRA_INTENT)
                ? RecordingService.class : RawRecordingService.class);
        requireContext().bindService(intent, connection, Context.BIND_ABOVE_CLIENT);
    }

    private final OnBackPressedCallback onBackCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            // do nothing when enabled
        }
    };

    private void refreshDefaultName() {
        Calendar now = Calendar.getInstance();
        mDefaultName = String.format(Locale.ENGLISH,"%04d-%02d-%02d_%02d%02d%02d",
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE),
                now.get(Calendar.SECOND));
        binding.recordingNameInputText.setText(mDefaultName);
    }

    private void updateAudioDevices() {
        // polling & filtering input audio devices
        AudioManager am = (AudioManager) requireContext().getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo[] allDevices = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
        mAudioDevices = new ArrayList<>();
        boolean builtInAdded = false;
        for (AudioDeviceInfo device : allDevices) {
            final int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) continue;
            if (type == AudioDeviceInfo.TYPE_TELEPHONY) continue;
            if (type == AudioDeviceInfo.TYPE_FM_TUNER) continue;
            AudioDeviceInfo cDevice = device;
            AudioDeviceInfo found = null;
            for (AudioDeviceInfo device2 : mAudioDevices) {
                final String fullName = device.getProductName() + device.getAddress();
                final String fullName2 = device2.getProductName() + device2.getAddress();
                if (fullName.equals(fullName2)) {
                    found = device2;
                    break;
                }
            }
            if (found != null && isAudioDeviceBetter(found, device)) {
                cDevice = found;
            } else if (found != null) {
                continue;
            }
            if (cDevice.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC && !builtInAdded) {
                builtInAdded = true;
                mAudioDevices.add(0, cDevice);
                continue;
            }
            mAudioDevices.add(cDevice);
        }
        final int inputPref = getPrefs().getInt(PREF_INPUT_DEVICE, 0);
        int c = 0;
        ArrayList<String> deviceNames = new ArrayList<>();
        for (AudioDeviceInfo device : mAudioDevices) {
            String deviceName = device.getProductName().toString();
            String addr = device.getAddress();
            if (addr != null && !addr.isEmpty())
                deviceName += " (" + addr + ")";
            if (deviceName.isEmpty()) continue;
            deviceNames.add(deviceName);
            if (device.getId() == inputPref)
                mSelectedDeviceIndex = c;
            c++;
        }
        String[] names = new String[deviceNames.size()];
        names = deviceNames.toArray(names);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, names);
        binding.deviceMenu.setAdapter(adapter);
        if (names.length > 0) {
            binding.deviceMenu.setListSelection(mSelectedDeviceIndex);
            binding.deviceMenu.setText(names[mSelectedDeviceIndex], false);
        }
    }

    private static boolean isAudioDeviceBetter(AudioDeviceInfo device1, AudioDeviceInfo device2) {
        // by # input channels
        final int[] channels1 = device1.getChannelCounts();
        int maxChannels1 = channels1.length == 0 ? Integer.MAX_VALUE : 1;
        for (Integer c : channels1)
            if (c > maxChannels1) maxChannels1 = c;
        final int[] channels2 = device2.getChannelCounts();
        int maxChannels2 = channels2.length == 0 ? Integer.MAX_VALUE : 1;
        for (Integer c : channels2)
            if (c > maxChannels2) maxChannels2 = c;
        if (maxChannels2 > maxChannels1)
            return true; // 2nd device has more input channels

        // by sample rate
        final int[] samples1 = device1.getSampleRates();
        int maxSamples1 = samples1.length == 0 ? Integer.MAX_VALUE : 1;
        for (Integer c : samples1)
            if (c > maxSamples1) maxSamples1 = c;
        final int[] samples2 = device2.getSampleRates();
        int maxSamples2 = samples2.length == 0 ? Integer.MAX_VALUE : 1;
        for (Integer c : samples2)
            if (c > maxSamples2) maxSamples2 = c;
        return maxSamples2 > maxSamples1; // 2nd device has a higher sample rate
    }

    @Override
    public void onStop() {
        registerToDuration(false);
        mExecutor.execute(() -> {
            if (mNoiseTimer != null) {
                mNoiseTimer.cancel();
                mNoiseTimer.purge();
            }
            if (mNoiseTimerTask != null)
                mNoiseTimerTask.cancel();
            if (mRecorder != null) {
                mRecorder.stop();
                mRecorder.release();
            }
            mRecorder = null;
        });
        saveUserPrefs();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        onBackCallback.setEnabled(false);
        if (mRawService != null && isRawStarted || mService != null && isStarted) {
            if (mRawService != null) mRawService.removeListener(mStatusListener);
            if (mService != null) mService.removeListener(mStatusListener);
            if (isStarted || isRawStarted) {
                requireContext().unbindService(connection);
            }
        }
        if (mTempAudioFile != null && mTempAudioFile.exists() && mTempAudioFile.delete())
            mTempAudioFile = null;
        binding = null;
        super.onDestroyView();
    }

    private void onRecordingClicked(View view) {
        final int checkedOutputID = binding.outputToggle.getCheckedButtonId();
        if (!isStarted && !isRawStarted) {
            Intent intent = new Intent(requireContext(), checkedOutputID != R.id.outputBtnWAV
                    ? RecordingService.class : RawRecordingService.class);
            requireContext().startForegroundService(intent); // run until we stop it
            requireContext().bindService(intent, connection, Context.BIND_ABOVE_CLIENT);

            // save current settings as prefs for the next time we run
            saveUserPrefs();
            return;
        }
        if (mService != null) {
            mService.pauseResumeRecording();
            return;
        }
        mRawService.pauseResumeRecording();
    }

    private void onSaveClicked(View view) {
        if (isStarted || isRawStarted) {
            Intent intent = new Intent(requireContext(),
                    isStarted ? RecordingService.class : RawRecordingService.class);
            requireContext().unbindService(connection);
            requireContext().stopService(intent);
        }
        registerToDuration(false);
        isStarted = false;
        isRawStarted = false;
        binding.timeText.setText("");
        binding.progressBar.setVisibility(View.INVISIBLE);
    }

    public void onDiscardClicked(View view) {
        DialogInterface.OnClickListener listener = (dialog, which) -> {
            if (isStarted) mService.eraseRecording();
            else mRawService.eraseRecording();
            onSaveClicked(view);
        };
        Context ctx = requireContext();
        FirstFragment.displayAreYouSureDialog(
                ctx, ctx.getString(R.string.are_you_sure_msg), listener);
    }

    public void onMediaClicked(View view) {
        NavHostFragment.findNavController(RecordFragment.this)
                .navigate(R.id.action_RecordFragment_to_FirstFragment);
    }

    private void setLimitMode(final int mode) {
        mLimitMode = mode;
        final float upperLimit = mode == LIMIT_MODE_TIME ? 300f : 20000f;
        final float stepSize = mode == LIMIT_MODE_TIME ? 5f : 100f;
        binding.limitSlider.setLabelFormatter(value -> {
            if (value < 1) {
                mTotalDurationStr = null;
                binding.progressBar.setIndeterminate(true);
                return getString(R.string.unlimited_txt);
            }
            int val = (int) value;
            binding.progressBar.setIndeterminate(false);
            binding.progressBar.setMax(val);
            if (mLimitMode == LIMIT_MODE_TIME) {
                if (value < 60) {
                    mTotalDurationStr = String.format(Locale.getDefault(),
                            "%d%s", val, getString(R.string.unit_seconds));
                    return mTotalDurationStr;
                }
                mTotalDurationStr = String.format(Locale.getDefault(),
                        "%02d:%02d", val / 60, val % 60);
                return mTotalDurationStr;
            }
            mTotalDurationStr = null;
            if (value >= 1000) {
                return String.format(Locale.getDefault(),
                        "%d.%d%s", val / 1000, (val % 1000) / 100, getString(R.string.unit_mb));
            }
            return String.format(Locale.getDefault(),
                    "%d%s", val, getString(R.string.unit_kb));
        });
        binding.limitSlider.setStepSize(stepSize);
        binding.limitSlider.setValueTo(upperLimit);
        binding.limitSlider.setValue(0); // refresh the label, ensure in bounds
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance.
            if (className.getClassName().equals(RawRecordingService.class.getName())) {
                RawRecordingService.LocalBinder binder = (RawRecordingService.LocalBinder) service;
                mRawService = binder.getService();
                isRawStarted = true;
            } else {
                RecordingService.LocalBinder binder = (RecordingService.LocalBinder) service;
                mService = binder.getService();
                isStarted = true;
            }
            final AudioDeviceInfo info = mAudioDevices.get(mSelectedDeviceIndex);
            updateInfoText(info);
            final int channels = binding.channelToggle.getCheckedButtonId() ==
                    R.id.channelBtn1 ? 1 : 2;
            int[] limit = null;
            final int limitValue = (int) binding.limitSlider.getValue();
            if (limitValue > 0) {
                limit = new int[2];
                limit[0] = mLimitMode;
                limit[1] = limitValue;
            }
            String ext = RecordingService.MPEG_4_EXT;
            final int checkedOutputID = binding.outputToggle.getCheckedButtonId();
            if (checkedOutputID == R.id.outputBtnOGG)
                ext = RecordingService.OGG_EXT;
            else if (checkedOutputID == R.id.outputBtnWAV)
                ext = RecordingService.WAV_EXT;
            final Editable editText = binding.recordingNameInputText.getText();
            final String fileName = (editText == null || editText.toString().isEmpty()
                    ? mDefaultName : editText.toString()) + "." + ext;
            mCurrentRecordingFile = new File(requireContext().getFilesDir(), fileName);
            RecordingService.RecordOptions opts = new RecordingService.RecordOptions(
                    mCurrentRecordingFile, info, mSampleRate, mEncodeRate, channels, limit);
            if (mRawService != null) {
                if (isResumed) {
                    mRawService.clearListeners();
                    String fn = mRawService.getOptions().getFile().getName();
                    fn = fn.substring(0, fn.lastIndexOf("."));
                    mDefaultName = fn;
                    binding.recordingNameInputText.setText(fn);
                } else {
                    mRawService.setOptions(opts);
                }
                mRawService.addListener(mStatusListener);
                mRawService.startRecording();
                return;
            }
            if (isResumed) {
                mService.clearListeners();
                String fn = mService.getOptions().getFile().getName();
                fn = fn.substring(0, fn.lastIndexOf("."));
                mDefaultName = fn;
                binding.recordingNameInputText.setText(fn);
            } else {
                mService.setOptions(opts);
            }
            mService.addListener(mStatusListener);
            mService.startRecording();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            if (mService != null) {
                mService.removeListener(mStatusListener);
                mService = null;
                isStarted = false;
                return;
            }
            mRawService.removeListener(mStatusListener);
            mRawService = null;
            isRawStarted = false;
        }
    };

    private void showSaveButton(final boolean show) {
        if (show && binding.saveButton.getVisibility() == View.VISIBLE ||
                !show && binding.saveButton.getVisibility() == View.INVISIBLE) {
            return;
        }
        if (show) {
            animateButtonTransition(
                    List.of(binding.saveButton, binding.discardButton),
                    List.of(binding.mediaButton)
            );
            return;
        }
        animateButtonTransition(
                List.of(binding.mediaButton),
                List.of(binding.saveButton, binding.discardButton)
        );
    }

    private void updateRecordAndProgress(final int status) {
        int progressVis = View.VISIBLE;
        int recordDrawableID = R.drawable.baseline_mic_24;
        String recordTooltip = getString(R.string.tooltip_start_recording);
        switch (status) {
            case RecordingService.Status.FAILED, RecordingService.Status.MAX_REACHED,
                    RecordingService.Status.IDLE -> progressVis = View.INVISIBLE;
            case RecordingService.Status.STARTED -> {
                recordDrawableID = R.drawable.baseline_pause_24;
                recordTooltip = getString(R.string.tooltip_pause_recording);
            }
            case RecordingService.Status.PAUSED -> {
                recordDrawableID = R.drawable.baseline_play_arrow_24;
                recordTooltip = getString(R.string.tooltip_resume_recording);
                if (mLimitMode != LIMIT_MODE_TIME || binding.limitSlider.getValue() < 1)
                    progressVis = View.INVISIBLE;
            }
        }
        binding.recordButton.setImageResource(recordDrawableID);
        binding.recordButton.setTooltipText(recordTooltip);
        binding.recordButton.setContentDescription(recordTooltip);
        binding.progressBar.setVisibility(progressVis);
    }

    private void enableOptionViews(final boolean enable) {
        for (View v : mOptionViews) v.setEnabled(enable);
    }

    private void updateInfoText() {
        updateInfoText(mAudioDevices.get(mSelectedDeviceIndex));
    }

    private void updateInfoText(final AudioDeviceInfo info) {
        if (binding == null) return;
        final int[] encodings = info.getEncodings();
        final List<Integer> eRates = new ArrayList<>();
        if (encodings.length == 0) {
            // supports everything
            eRates.add(0, 8);
            eRates.add(32);
        } else {
            int max = 16; // always supported
            for (Integer encoder : encodings) {
                if (encoder == AudioFormat.ENCODING_PCM_8BIT) {
                    eRates.add(0, 8);
                } else if (encoder == AudioFormat.ENCODING_PCM_24BIT_PACKED ||
                        encoder == AudioFormat.ENCODING_PCM_FLOAT) {
                    max = 24;
                } else if (encoder == AudioFormat.ENCODING_PCM_32BIT) {
                    max = 32;
                    break;
                }
            }
            eRates.add(max);
        }
        final int[] sRates = info.getSampleRates();
        Arrays.sort(sRates);
        mSampleRate = sRates[sRates.length - 1];
        mEncodeRate = eRates.get(eRates.size() - 1);
        if (binding.qualityToggle.getCheckedButtonId() ==  R.id.qualityBtnStandard) {
            mSampleRate = sRates[sRates.length / 2];
            if (mSampleRate < 44100f && sRates[sRates.length - 1] > 44100f)
                mSampleRate = 44100; // capture whole spectrum in SD
            mEncodeRate = 16; // always supported, always standard
        } else if (binding.qualityToggle.getCheckedButtonId() == R.id.qualityBtnLow) {
            mSampleRate = sRates[0];
            mEncodeRate = eRates.get(0);
        }
        final int selectedFormat = binding.outputToggle.getCheckedButtonId();
        final String encoderTxt = selectedFormat == binding.outputBtnWAV.getId() ?
                getString(R.string.info_txt_lossless) : getString(R.string.info_txt_lossy);
        binding.infoTxt.setText(String.format(getString(R.string.info_txt),
                mSampleRate / 1000f, mEncodeRate, encoderTxt));
    }

    private void registerToDuration(final boolean register) {
        mExecutor.execute(() -> {
            if (register && mDurationTimer == null && mDurationTimerTask == null) {
                mDurationTimer = new Timer();
                mDurationTimerTask = new DurationTimerTask();
                mDurationTimer.scheduleAtFixedRate(mDurationTimerTask, 0, 250);
                return;
            }
            if (mDurationTimer == null || mDurationTimerTask == null)
                return;
            updateDurationText();
            mDurationTimer.cancel();
            mDurationTimer.purge();
            mDurationTimer = null;
            mDurationTimerTask.cancel();
            mDurationTimerTask = null;
        });
    }

    private SharedPreferences getPrefs() {
        if (mSharedPrefs != null) return mSharedPrefs;
        mSharedPrefs = requireContext().getSharedPreferences(SHARED_PREF_FILE, Context.MODE_PRIVATE);
        return mSharedPrefs;
    }

    private synchronized void registerToMicAmp() {
        if (mRecorder != null) {
            mRecorder.stop();
            mRecorder.release();
            mRecorder = null;
            if (mNoiseTimer != null) mNoiseTimer.cancel();
            if (mNoiseTimerTask != null) mNoiseTimerTask.cancel();
            if (mTempAudioFile != null && mTempAudioFile.exists() && mTempAudioFile.delete())
                mTempAudioFile = null;
        }
        mNoiseTimer = new Timer();
        mNoiseTimerTask = new TimerTask() {
            @Override
            public void run() {
                if (mRecorder == null) {
                    return;
                }
                try {
                    final int noise = mRecorder.getMaxAmplitude();
                    binding.audioBar.getHandler().post(() -> {
                        if (binding != null) binding.audioBar.setProgress(noise, true);
                    });
                } catch (Exception ignored) { }
            }
        };
        final AudioDeviceInfo info = mAudioDevices.get(mSelectedDeviceIndex);
        mRecorder = new MediaRecorder(requireContext());
        mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mRecorder.setPreferredDevice(info);
        mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        try {
            mTempAudioFile = File.createTempFile("tmp-noise", ".m4a", null);
            mTempAudioFile.deleteOnExit();
            mRecorder.setOutputFile(mTempAudioFile);
            mRecorder.prepare();
            mRecorder.start();
            //noinspection ResultOfMethodCallIgnored
            mTempAudioFile.delete(); // delete when we stop writing to it
        } catch (RuntimeException | IOException e) {
            e.printStackTrace();
            mRecorder = null;
            Toast.makeText(requireContext(), getString(R.string.mic_in_use), Toast.LENGTH_LONG).show();
            for (View v : mOptionViews) v.setEnabled(false);
            binding.outputToggle.setEnabled(true);
            binding.recordButton.setEnabled(false);
            return;
        }
        binding.audioBar.setMin(mRecorder.getMaxAmplitude() /* 0 */);
        binding.audioBar.setMax(10000); // consider making dynamic again
        mNoiseTimer.scheduleAtFixedRate(mNoiseTimerTask, 0, 75);
    }

    private void setBackEnabled(final boolean enabled) {
        onBackCallback.setEnabled(!enabled); // callback should be enabled when back is disabled
    }

    private final StatusListener mStatusListener = new StatusListener();
    private class StatusListener implements RecordingService.StatusListener {
        @Override
        public void onStatusChanged(int status, int extra) {
            mUiHandler.removeCallbacksAndMessages(null);
            mUiHandler.post(() -> {
                switch (status) {
                    case RecordingService.Status.FAILED, RecordingService.Status.MAX_REACHED,
                            RecordingService.Status.IDLE -> {
                        updateRecordAndProgress(status);
                        showSaveButton(false);
                        enableOptionViews(true);
                        registerToDuration(false);
                        setBackEnabled(true);
                        refreshDefaultName();
                        binding.timeText.setText("");
                    }
                    case RecordingService.Status.STARTED -> {
                        updateRecordAndProgress(status);
                        showSaveButton(true);
                        enableOptionViews(false);
                        registerToDuration(true);
                        setBackEnabled(false);
                    }
                    case RecordingService.Status.PAUSED -> {
                        updateRecordAndProgress(status);
                        showSaveButton(true);
                        enableOptionViews(false);
                        registerToDuration(false);
                        setBackEnabled(false);
                    }
                }
            });
        }
    }

    private Timer mDurationTimer;
    private TimerTask mDurationTimerTask;
    private class DurationTimerTask extends TimerTask {
        @Override
        public void run() {
            updateDurationText();
        }
    }

    private synchronized void updateDurationText() {
        if (binding == null) return;
        long sec = -1;
        if (isStarted && mService != null)
            sec = mService.getDuration() / 1000;
        else if (isRawStarted && mRawService != null)
            sec = mRawService.getDuration() / 1000;
        if (sec == -1) return;
        String text = "";
        if (sec > 3600 /* 1 hour */) {
            text += String.format(Locale.ENGLISH, "%02d:%02d", sec / 3600, sec % 3600);
        } else {
            text += String.format(Locale.ENGLISH, "%02d", sec / 60);
        }
        text += String.format(Locale.ENGLISH, ":%02d", sec % 60);
        if (mTotalDurationStr != null) {
            String totalStr = mTotalDurationStr;
            if (totalStr.contains("s"))
                totalStr = "00:" + String.format(Locale.ENGLISH,
                        "%02d", Integer.valueOf(totalStr.replace("s", "")));
            text += "/" + totalStr;
            final int finalSec = (int) sec;
            mUiHandler.post(() ->
                    binding.progressBar.setProgress(finalSec, true));
        } else if (mLimitMode == LIMIT_MODE_SIZE) {
            int size = Math.round(mCurrentRecordingFile.length() / 1000f /* bytes to kB */);
            mUiHandler.post(() ->
                    binding.progressBar.setProgress(size, true));
        }
        final String res = text;
        mUiHandler.post(() ->
                binding.timeText.setText(res));
    }

    private void saveUserPrefs() {
        final int checkedOutputID = binding.outputToggle.getCheckedButtonId();
        SharedPreferences.Editor editor = getPrefs().edit();
        editor.putInt(PREF_INPUT_DEVICE, mAudioDevices.get(mSelectedDeviceIndex).getId());
        if (checkedOutputID == R.id.outputBtnMP4)
            editor.putString(PREF_OUTPUT_EXT, RecordingService.MPEG_4_EXT);
        else if (checkedOutputID == R.id.outputBtnOGG)
            editor.putString(PREF_OUTPUT_EXT, RecordingService.OGG_EXT);
        else if (checkedOutputID == R.id.outputBtnWAV)
            editor.putString(PREF_OUTPUT_EXT, RecordingService.WAV_EXT);
        for (int i = 0; i < binding.qualityToggle.getChildCount(); i++) {
            final int id = binding.qualityToggle.getChildAt(i).getId();
            if (id == binding.qualityToggle.getCheckedButtonId()) {
                editor.putInt(PREF_OUTPUT_QUALITY, i);
                break;
            }
        }
        editor.putInt(PREF_CHANNELS,
                binding.channelToggle.getCheckedButtonId() == R.id.channelBtn1 ? 1 : 2);
        editor.putInt(PREF_LIMIT_MODE, mLimitMode);
        editor.putInt(PREF_LIMIT_VALUE, (int) binding.limitSlider.getValue());
        editor.apply();
    }

    private void animateButtonTransition(List<View> inViews, List<View> outViews) {
        ArrayList<ObjectAnimator> animatorsList = new ArrayList<>();
        for (View view : inViews) {
            final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams)
                    view.getLayoutParams();
            final float viewHeight = view.getMeasuredHeight() + params.bottomMargin;
            ObjectAnimator animator = ObjectAnimator.ofFloat(view, "translationY",
                    viewHeight, 0);
            animator.setInterpolator(new OvershootInterpolator());
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    super.onAnimationStart(animation);
                    view.setTranslationY(-viewHeight); // get the fab out of the screen before:
                    view.setVisibility(View.VISIBLE); // making it visible
                }
            });
            animatorsList.add(animator);
        }
        for (View view : outViews) {
            final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams)
                    view.getLayoutParams();
            final float viewHeight = view.getMeasuredHeight() + params.bottomMargin;
            ObjectAnimator animator = ObjectAnimator.ofFloat(view, "translationY",
                    0, viewHeight);
            animator.setInterpolator(new AnticipateInterpolator());
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    view.setVisibility(View.INVISIBLE); // make the fab invisible before:
                    view.setTranslationY(viewHeight); // putting it back to its place
                }
            });
            animatorsList.add(animator);
        }
        AnimatorSet animSet = new AnimatorSet();
        animSet.playTogether(animatorsList.toArray(new ObjectAnimator[0]));
        animSet.setDuration(400);
        animSet.start();
    }
}
