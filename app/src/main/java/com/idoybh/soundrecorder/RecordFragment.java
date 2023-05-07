package com.idoybh.soundrecorder;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.DataSetObserver;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioProfile;
import android.media.CamcorderProfile;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageButton;
import android.widget.ListAdapter;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.idoybh.soundrecorder.databinding.FragmentRecordBinding;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class RecordFragment extends Fragment {
    private static final String SHARED_PREF_FILE = "SoundRecorder";
    private static final String PREF_OUTPUT_EXT = "output_ext";
    private static final String PREF_OUTPUT_QUALITY = "output_quality";
    private static final String PREF_CHANNELS = "output_channels";
    private static final String PREF_LIMIT_MODE = "limit_mode";
    private static final String PREF_LIMIT_VALUE = "limit_value";
    private static final String PREF_SAVE_LOCATION = "save_location";

    public static final int LIMIT_MODE_SIZE = 0;
    public static final int LIMIT_MODE_TIME = 1;

    private FragmentRecordBinding binding;
    private SharedPreferences mSharedPrefs;
    private FusedLocationProviderClient mLocationClient;
    private Location mLocation;
    private List<AudioDeviceInfo> mAudioDevices = new ArrayList<>();
    private int mSelectedDeviceIndex = 0;
    private int mLimitMode = LIMIT_MODE_SIZE;
    private List<View> mOptionViews = new ArrayList<>();
    private String mDefaultName;
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
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backCallback);

        // polling & filtering input audio devices
        AudioManager am = (AudioManager) requireContext().getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo[] allDevices = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
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
        ArrayList<String> deviceNames = new ArrayList<>();
        for (AudioDeviceInfo device : mAudioDevices)
            deviceNames.add(device.getProductName().toString());
        String[] names = new String[deviceNames.size()];
        names = deviceNames.toArray(names);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, names);
        binding.deviceMenu.setAdapter(adapter);
        if (names.length > 0) {
            binding.deviceMenu.setListSelection(0);
            binding.deviceMenu.setText(names[0], false);
            updateInputCapabilities(0);
        }

        // settings up the rest of the views + listeners
        binding.deviceMenu.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mSelectedDeviceIndex = position;
                updateInputCapabilities(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                binding.deviceMenu.setListSelection(0);
                binding.deviceMenu.setText(mAudioDevices.get(0).getProductName(), false);
            }
        });

        binding.limitToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            setLimitMode(checkedId == binding.limitBtnTime.getId() && isChecked
                    ? LIMIT_MODE_TIME : LIMIT_MODE_SIZE);
        });
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
        binding.locationToggle.addOnButtonCheckedListener(this::onLocationClicked);

        // loading shared prefs / defaults
        final String outPref = getPrefs().getString(PREF_OUTPUT_EXT, RecordingService.MPEG_4_EXT);
        int outID = binding.outputBtnMP4.getId();
        if (outPref.equals(RecordingService.THREE_GPP_EXT))
            outID = binding.outputBtn3GP.getId();
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
        final boolean saveLocationPref = getPrefs().getBoolean(PREF_SAVE_LOCATION, false);
        binding.locationToggle.check(saveLocationPref
                ? binding.locationOnButton.getId() : binding.locationOffButton.getId());
        if (saveLocationPref) addLocation();

        mOptionViews = Arrays.asList(
                binding.deviceMenu,
                binding.recordingNameInputText,
                binding.locationToggle,
                binding.outputToggle,
                binding.qualityToggle,
                binding.limitToggle,
                binding.limitSlider,
                binding.channelToggle
        );
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    OnBackPressedCallback backCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            // do nothing
        }
    };

    private void onRecordingClicked(View view) {
        if (!isStarted) {
            Intent intent = new Intent(requireContext(), RecordingService.class);
            requireContext().startForegroundService(intent); // run until we stop it
            requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE);

            // save current settings as prefs for the next time we run
            SharedPreferences.Editor editor = getPrefs().edit();
            if (binding.outputToggle.getCheckedButtonId() == R.id.outputBtnMP4)
                editor.putString(PREF_OUTPUT_EXT, RecordingService.MPEG_4_EXT);
            else if (binding.outputToggle.getCheckedButtonId() == R.id.outputBtn3GP)
                editor.putString(PREF_OUTPUT_EXT, RecordingService.THREE_GPP_EXT);
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
            editor.putBoolean(PREF_SAVE_LOCATION,
                    binding.locationToggle.getCheckedButtonId() == R.id.locationOnButton);
            editor.apply();
            return;
        }
        mService.pauseResumeRecording();
    }

    private void onSaveClicked(View view) {
        Intent intent = new Intent(requireContext(), RecordingService.class);
        requireContext().unbindService(connection);
        requireContext().stopService(intent);
        isStarted = false;
    }

    private void setLimitMode(final int mode) {
        mLimitMode = mode;
        final float upperLimit = mode == LIMIT_MODE_TIME ? 300f : 1000f;
        final float stepSize = mode == LIMIT_MODE_TIME ? 5f : 10f;
        binding.limitSlider.setLabelFormatter(value -> {
            if (value < 1) return getString(R.string.unlimited_txt);
            int val = (int) value;
            if (mLimitMode == LIMIT_MODE_TIME) {
                if (value < 60)
                    return String.format(Locale.getDefault(), "%d%s", val, getString(R.string.unit_seconds));
                return String.format(Locale.getDefault(), "%02d:%02d", val / 60, val % 60);
            }
            return String.format(Locale.getDefault(), "%d%s", val, getString(R.string.unit_mb));
        });
        binding.limitSlider.setStepSize(stepSize);
        binding.limitSlider.setValueTo(upperLimit);
        binding.limitSlider.setValue(0); // refresh the label, ensure in bounds
    }

    private void onLocationClicked(MaterialButtonToggleGroup group, int checkedId, boolean isChecked) {
        if (checkedId != R.id.locationOnButton) return;
        if (!isChecked) {
            mLocationClient = null;
            mLocation = null;
            binding.locationText.setText("");
            return;
        }
        if (checkOrAskForLocationPerm()) {
            addLocation();
            return;
        }
        binding.locationText.setText(R.string.location_access_denied);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance.
            RecordingService.LocalBinder binder = (RecordingService.LocalBinder) service;
            mService = binder.getService();
            isStarted = true;
            final AudioDeviceInfo info = mAudioDevices.get(mSelectedDeviceIndex);
            final int[] rates = info.getSampleRates();
            Arrays.sort(rates);
            int rate = rates[rates.length - 1];
            if (binding.qualityToggle.getCheckedButtonId() ==  R.id.qualityBtnStandard)
                rate = rates[rates.length / 2];
            else if (binding.qualityToggle.getCheckedButtonId() == R.id.qualityBtnLow)
                rate = rates[0];
            final int channels = binding.channelToggle.getCheckedButtonId() ==
                    R.id.channelBtn1 ? 1 : 2;
            int[] limit = null;
            final int limitValue = (int) binding.limitSlider.getValue();
            if (limitValue > 0) {
                limit = new int[2];
                limit[0] = mLimitMode;
                limit[1] = limitValue;
            }
            final String ext = binding.outputToggle.getCheckedButtonId() == R.id.outputBtn3GP
                    ? RecordingService.THREE_GPP_EXT : RecordingService.MPEG_4_EXT;
            final Editable editText = binding.recordingNameInputText.getText();
            final String fileName = (editText == null || editText.toString().isEmpty()
                    ? mDefaultName : editText.toString().toString()) + "." + ext;
            final File file = new File(requireContext().getFilesDir(), fileName);
            RecordingService.RecordOptions opts = new RecordingService.RecordOptions(
                    file, info, rate, channels, limit, mLocation);
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
                case RecordingService.Status.IDLE:
                    backCallback.setEnabled(false);
                    binding.recordButton.setImageResource(R.drawable.baseline_mic_24);
                    showSaveButton(false);
                    enableOptionViews(true);
                    break;
                case RecordingService.Status.STARTED:
                    backCallback.setEnabled(true);
                    binding.recordButton.setImageResource(R.drawable.baseline_pause_24);
                    showSaveButton(true);
                    enableOptionViews(false);
                    break;
                case RecordingService.Status.PAUSED:
                    backCallback.setEnabled(false);
                    binding.recordButton.setImageResource(R.drawable.baseline_play_arrow_24);
                    showSaveButton(true);
                    enableOptionViews(false);
                    break;
                case RecordingService.Status.MAX_REACHED:
                    // TODO: Handle max here - file should be saved and all
                    backCallback.setEnabled(false);
                    binding.recordButton.setImageResource(R.drawable.baseline_mic_24);
                    showSaveButton(false);
                    enableOptionViews(true);
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

    private void updateInputCapabilities(final int index) {
        final AudioDeviceInfo info = mAudioDevices.get(index);
        final int[] rates = info.getSampleRates();
        Arrays.sort(rates);
        // TODO: Update ch vis
    }

    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), map -> {
                for (Boolean result : map.values()) {
                    if (result) {
                        addLocation();
                        return;
                    }
                }
            });

    private boolean checkOrAskForLocationPerm() {
        if (requireContext().checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                requireContext().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        final String[] perms = { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION };
        requestPermissionLauncher.launch(perms);
        return false;
    }

    private void addLocation() {
        binding.locationText.setText(getString(R.string.locating));
        if (mLocationClient == null)
            mLocationClient = LocationServices.getFusedLocationProviderClient(requireContext());
        mLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(location -> {
                    // Got last known location. In some rare situations this can be null.
                    if (location == null) return;
                    mLocation = location;
                    String locationStr = location.getLatitude() + " : " + location.getLongitude();
                    if (location.hasAccuracy())
                        locationStr += " ±" + Math.round(location.getAccuracy()) + getString(R.string.unit_meters);
                    binding.locationText.setText(locationStr);
                });
    }

    private SharedPreferences getPrefs() {
        if (mSharedPrefs != null) return mSharedPrefs;
        mSharedPrefs = requireContext().getSharedPreferences(SHARED_PREF_FILE, Context.MODE_PRIVATE);
        return mSharedPrefs;
    }
}