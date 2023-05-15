package com.idoybh.yasr;

import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import java.util.HashMap;
import java.util.Map;

public class SettingsFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener {
    private static final String THEME_KEY = "ui_mode";
    public static final String SORT_KEY = "sort_mode";

    private static final Map<Integer, Integer> THEME_VALUE_TO_UI_MODE = new HashMap<>(Map.of(
            0, UiModeManager.MODE_NIGHT_NO,
            1, UiModeManager.MODE_NIGHT_YES,
            2, UiModeManager.MODE_NIGHT_AUTO
    ));

    private static final Map<Integer, Integer> UI_MODE_TO_THEME_VALUE = new HashMap<>(Map.of(
            UiModeManager.MODE_NIGHT_NO, 0,
            UiModeManager.MODE_NIGHT_YES, 1,
            UiModeManager.MODE_NIGHT_AUTO, 2
    ));

    private SharedPreferences mDefaultPrefs;
    private UiModeManager mUiManager;

    private ListPreference mThemeListPref;
    private ListPreference mSortListPref;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        mDefaultPrefs = PreferenceManager.getDefaultSharedPreferences(requireActivity());
        mUiManager = (UiModeManager) requireContext()
                .getSystemService(Context.UI_MODE_SERVICE);

        mThemeListPref = findPreference(THEME_KEY);
        final int prefValue = Integer.parseInt(mThemeListPref.getValue());
        mThemeListPref.setSummary(mThemeListPref.getEntries()[prefValue]);
        mThemeListPref.setOnPreferenceChangeListener(this);

        mSortListPref = findPreference(SORT_KEY);
        final int sortValue = Integer.parseInt(mDefaultPrefs.getString(SORT_KEY, "1"));
        mSortListPref.setSummary(mSortListPref.getEntries()[sortValue]);
        mSortListPref.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
        if (preference == mThemeListPref) {
            final int mode = Integer.parseInt((String) newValue);
            mUiManager.setApplicationNightMode(THEME_VALUE_TO_UI_MODE.get(mode));
            mThemeListPref.setSummary(mThemeListPref.getEntries()[mode]);
            return true;
        } else if (preference == mSortListPref) {
            final int value = Integer.parseInt((String) newValue);
            mSortListPref.setSummary(mSortListPref.getEntries()[value]);
            return true;
        }
        return false;
    }
}