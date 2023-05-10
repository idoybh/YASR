package com.idoybh.yasr;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

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

public class RecordFragment extends Fragment {
    private static final String SHARED_PREF_FILE = "SoundRecorder";
    private static final String PREF_INPUT_DEVICE = "input_device";
    private static final String PREF_OUTPUT_EXT = "output_ext";
    private static final String PREF_OUTPUT_QUALITY = "output_quality";
    private static final String PREF_CHANNELS = "output_channels";
    private static final String PREF_LIMIT_MODE = "limit_mode";
    private static final String PREF_LIMIT_VALUE = "limit_value";

    public static final int LIMIT_MODE_SIZE = 0;
    public static final int LIMIT_MODE_TIME = 1;

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
    private String mDefaultName;
    private String mTotalDurationStr;
    private RecordingService mService;
    private boolean isStarted = false;

    @Override
    public View onCreateView(
            @NotNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        binding = FragmentRecordBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // polling & filtering input audio devices
        AudioManager am = (AudioManager) requireContext().getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo[] allDevices = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
        mAudioDevices = new ArrayList<>();
        for (AudioDeviceInfo device : allDevices) {
            boolean found = false;
            for (AudioDeviceInfo device2 : mAudioDevices) {
                if (device2.getProductName().equals(device.getProductName())) {
                    found = true;
                    break;
                }
            }
            if (found) continue;
            mAudioDevices.add(device);
        }
        final String inputPref = getPrefs().getString(PREF_INPUT_DEVICE, null);
        int c = 0;
        ArrayList<String> deviceNames = new ArrayList<>();
        for (AudioDeviceInfo device : mAudioDevices) {
            final String deviceName = device.getProductName().toString();
            if (deviceName.isEmpty()) continue;
            deviceNames.add(deviceName);
            if (deviceName.equals(inputPref))
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

        // settings up the rest of the views + listeners
        binding.deviceMenu.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mSelectedDeviceIndex = position;
                updateInfoText();
                registerToMicAmp();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                binding.deviceMenu.setListSelection(0);
                binding.deviceMenu.setText(mAudioDevices.get(0).getProductName(), false);
            }
        });

        binding.limitToggle.addOnButtonCheckedListener((group, checkedId, isChecked) ->
                setLimitMode(checkedId == binding.limitBtnTime.getId() && isChecked
                        ? LIMIT_MODE_TIME : LIMIT_MODE_SIZE));
        setLimitMode(binding.limitToggle.getCheckedButtonId() == binding.limitBtnTime.getId()
                ? LIMIT_MODE_TIME : LIMIT_MODE_SIZE);

        Calendar now = Calendar.getInstance();
        mDefaultName = String.format(Locale.ENGLISH,"%04d-%02d-%02d_%02d%02d",
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE));
        binding.recordingNameInputText.setText(mDefaultName);
        binding.recordButton.setOnClickListener(this::onRecordingClicked);
        binding.saveButton.setOnClickListener(this::onSaveClicked);
        binding.qualityToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> updateInfoText());

        // loading shared prefs / defaults
        final String outPref = getPrefs().getString(PREF_OUTPUT_EXT, RecordingService.MPEG_4_EXT);
        int outID = binding.outputBtnMP4.getId();
        if (outPref.equals(RecordingService.OGG_EXT))
            outID = binding.outputBtnOGG.getId();
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

        mOptionViews = Arrays.asList(
                binding.deviceMenu,
                binding.recordingNameInputText,
                binding.outputToggle,
                binding.qualityToggle,
                binding.limitToggle,
                binding.limitSlider,
                binding.channelToggle
        );

        updateInfoText();
        registerToMicAmp();
    }

    @Override
    public void onDestroyView() {
        mNoiseTimer.cancel();
        mNoiseTimer.purge();
        super.onDestroyView();
        mRecorder = null;
        binding = null;
    }

    private void onRecordingClicked(View view) {
        if (!isStarted) {
            Intent intent = new Intent(requireContext(), RecordingService.class);
            requireContext().startForegroundService(intent); // run until we stop it
            requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE);

            // save current settings as prefs for the next time we run
            SharedPreferences.Editor editor = getPrefs().edit();
            editor.putString(PREF_INPUT_DEVICE, binding.deviceMenu.getText().toString());
            if (binding.outputToggle.getCheckedButtonId() == R.id.outputBtnMP4)
                editor.putString(PREF_OUTPUT_EXT, RecordingService.MPEG_4_EXT);
            else if (binding.outputToggle.getCheckedButtonId() == R.id.outputBtnOGG)
                editor.putString(PREF_OUTPUT_EXT, RecordingService.OGG_EXT);
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
            return;
        }
        mService.pauseResumeRecording();
    }

    private void onSaveClicked(View view) {
        Intent intent = new Intent(requireContext(), RecordingService.class);
        requireContext().unbindService(connection);
        requireContext().stopService(intent);
        registerToDuration(false);
        isStarted = false;
        binding.timeText.setText("");
        binding.progressBar.setVisibility(View.INVISIBLE);
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
            RecordingService.LocalBinder binder = (RecordingService.LocalBinder) service;
            mService = binder.getService();
            isStarted = true;
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
            if (binding.outputToggle.getCheckedButtonId() == R.id.outputBtnOGG)
                ext = RecordingService.OGG_EXT;
            final Editable editText = binding.recordingNameInputText.getText();
            final String fileName = (editText == null || editText.toString().isEmpty()
                    ? mDefaultName : editText.toString()) + "." + ext;
            mCurrentRecordingFile = new File(requireContext().getFilesDir(), fileName);
            RecordingService.RecordOptions opts = new RecordingService.RecordOptions(
                    mCurrentRecordingFile, info, mSampleRate, mEncodeRate, channels, limit);
            mService.setOptions(opts);
            mService.addListener(mStatusListener);
            mService.startRecording();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mService.removeListener(mStatusListener);
            mService = null;
            isStarted = false;
        }
    };

    private final StatusListener mStatusListener = new StatusListener();
    private class StatusListener implements RecordingService.StatusListener {
        @Override
        public void onStatusChanged(int status, int extra) {
            switch (status) {
                case RecordingService.Status.FAILED:
                case RecordingService.Status.MAX_REACHED:
                case RecordingService.Status.IDLE:
                    binding.recordButton.setImageResource(R.drawable.baseline_mic_24);
                    binding.progressBar.setVisibility(View.INVISIBLE);
                    binding.timeText.setText("");
                    showSaveButton(false);
                    enableOptionViews(true);
                    registerToDuration(false);
                    break;
                case RecordingService.Status.STARTED:
                    binding.recordButton.setImageResource(R.drawable.baseline_pause_24);
                    binding.progressBar.setVisibility(View.VISIBLE);
                    showSaveButton(true);
                    enableOptionViews(false);
                    registerToDuration(true);
                    break;
                case RecordingService.Status.PAUSED:
                    binding.recordButton.setImageResource(R.drawable.baseline_play_arrow_24);
                    binding.progressBar.setVisibility(View.INVISIBLE);
                    showSaveButton(true);
                    enableOptionViews(false);
                    registerToDuration(false);
                    break;
            }
        }
    }

    private void showSaveButton(final boolean show) {
        binding.saveButton.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        binding.saveButton.setClickable(show);
    }

    private void enableOptionViews(final boolean enable) {
        for (View v : mOptionViews) v.setEnabled(enable);
    }

    private void updateInfoText() {
        updateInfoText(mAudioDevices.get(mSelectedDeviceIndex));
    }

    private void updateInfoText(final AudioDeviceInfo info) {
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
        binding.infoTxt.setText(String.format(getString(R.string.info_txt), mSampleRate / 1000f, mEncodeRate));
    }

    private final Timer mNoiseTimer = new Timer();
    private final TimerTask mNoiseTimerTask = new TimerTask() {
        @Override
        public void run() {
            if (mRecorder == null) {
                return;
            }
            final int noise = mRecorder.getMaxAmplitude();
            binding.audioBar.getHandler().post(() -> {
                if (binding != null) binding.audioBar.setProgress(noise, true);
            });
        }
    };

    private synchronized void registerToMicAmp() {
        if (mRecorder != null) {
            mRecorder.stop();
            mRecorder.release();
            mRecorder = null;
            mNoiseTimer.cancel();
            final File tmpFile = new File(requireContext().getCacheDir()
                    + File.separator + "tmp.3gp");
            if (tmpFile.exists()) tmpFile.delete();
        }
        final AudioDeviceInfo info = mAudioDevices.get(mSelectedDeviceIndex);
        mRecorder = new MediaRecorder(requireContext());
        mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mRecorder.setPreferredDevice(info);
        mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
        mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);
        try {
            mRecorder.setOutputFile(File.createTempFile("tmp", ".3gp",
                    requireContext().getCacheDir()));
            mRecorder.prepare();
            mRecorder.start();
        } catch (IllegalStateException | IOException e) {
            e.printStackTrace();
        }
        binding.audioBar.setMin(mRecorder.getMaxAmplitude() /* 0 */);
        binding.audioBar.setMax(10000); // consider making dynamic again
        mNoiseTimer.scheduleAtFixedRate(mNoiseTimerTask, 0, 75);
    }

    private Timer mDurationTimer;
    private TimerTask mDurationTimerTask;
    private class DurationTimerTask extends TimerTask {
        @Override
        public void run() {
            if (!isStarted || mService == null) {
                return;
            }
            final long sec = mService.getDuration() / 1000;
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
                    totalStr = "00:" + totalStr.replace("s", "");
                text += "/" + totalStr;
                binding.progressBar.getHandler().post(() ->
                        binding.progressBar.setProgress((int) sec, true));
            } else if (mLimitMode == LIMIT_MODE_SIZE) {
                int size = Math.round(mCurrentRecordingFile.length() / 1000f /* bytes to kB */);
                binding.progressBar.getHandler().post(() ->
                        binding.progressBar.setProgress(size, true));
            }
            final String res = text;
            binding.timeText.getHandler().post(() ->
                    binding.timeText.setText(res));
        }
    };

    private void registerToDuration(final boolean register) {
        if (register && mDurationTimer == null && mDurationTimerTask == null) {
            mDurationTimer = new Timer();
            mDurationTimerTask = new DurationTimerTask();
            mDurationTimer.scheduleAtFixedRate(mDurationTimerTask, 0, 250);
            return;
        }
        if (mDurationTimer == null || mDurationTimerTask == null)
            return;
        mDurationTimer.cancel();
        mDurationTimer = null;
        mDurationTimerTask.cancel();
        mDurationTimerTask = null;
    }

    private SharedPreferences getPrefs() {
        if (mSharedPrefs != null) return mSharedPrefs;
        mSharedPrefs = requireContext().getSharedPreferences(SHARED_PREF_FILE, Context.MODE_PRIVATE);
        return mSharedPrefs;
    }
}