package com.idoybh.soundrecorder;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import com.idoybh.soundrecorder.databinding.FragmentRecordBinding;
import org.jetbrains.annotations.NotNull;

import java.util.Calendar;
import java.util.Locale;

public class RecordFragment extends Fragment {

    private FragmentRecordBinding binding;
    private String mDefaultName;
    private RecordingService mService;
    private boolean isStarted = false;
    private boolean isPaused = false;

    @Override
    public View onCreateView(
            @NotNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        binding = FragmentRecordBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Calendar now = Calendar.getInstance();
        mDefaultName = String.format(Locale.ENGLISH,"%04d%02d%02d-%02d%02d",
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE));
        binding.recordingNameInputText.setText(mDefaultName);
        binding.recordButton.setOnClickListener(this::onRecordingClicked);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    public void onRecordingClicked(View view) {
        Intent intent = new Intent(requireContext(), RecordingService.class);
        intent.putExtra(RecordingService.EXTRA_START, !isStarted);
        if (!isStarted) {
            intent.putExtra(RecordingService.EXTRA_OPTS, getOptionsFromPrefs());
            requireContext().startForegroundService(intent); // run until we stop it
            requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE);
            return;
        }
        mService.pauseResumeRecording();
        binding.recordButton.setImageResource(mService.getIsPaused()
                ? R.drawable.baseline_play_arrow_24
                : R.drawable.baseline_pause_24);
        // todo: move to save button
//        requireContext().unbindService(connection);
//        requireContext().stopService(intent);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance.
            RecordingService.LocalBinder binder = (RecordingService.LocalBinder) service;
            mService = binder.getService();
            isStarted = true;
            binding.recordButton.setImageResource(R.drawable.baseline_pause_24);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isStarted = false;
        }
    };

    private RecordingService.RecordOptions getOptionsFromPrefs() {
        RecordingService.RecordOptions opts = new RecordingService.RecordOptions(null, 0, false);
        return opts;
    }

}