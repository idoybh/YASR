package com.idoybh.soundrecorder;

import android.os.Bundle;
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
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}