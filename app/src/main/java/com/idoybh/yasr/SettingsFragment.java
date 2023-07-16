package com.idoybh.yasr;

import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreference;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.analytics.FirebaseAnalytics;

import java.util.HashMap;
import java.util.Map;

public class SettingsFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener, Preference.OnPreferenceClickListener {
    private static final String THEME_KEY = "ui_mode";
    public static final String SORT_KEY = "sort_mode";
    private static final String LANG_KEY = "lang_pref";
    private static final String ANALYTICS_KEY = "analytics";
    private static final String ANALYTICS_RESET_KEY = "analytics_reset";

    private static final Map<Integer, Integer> THEME_VALUE_TO_UI_MODE = new HashMap<>(Map.of(
            0, UiModeManager.MODE_NIGHT_NO,
            1, UiModeManager.MODE_NIGHT_YES,
            2, UiModeManager.MODE_NIGHT_AUTO
    ));

    private UiModeManager mUiManager;
    private FirebaseAnalytics mAnalytics;

    private ListPreference mThemeListPref;
    private ListPreference mSortListPref;
    private Preference mLangPref;
    private SwitchPreference mAnalyticsPref;
    private Preference mAnalyticsResetPref;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireActivity());
        mUiManager = (UiModeManager) requireContext()
                .getSystemService(Context.UI_MODE_SERVICE);
        mAnalytics = FirebaseAnalytics.getInstance(requireContext());

        mThemeListPref = findPreference(THEME_KEY);
        final int prefValue = Integer.parseInt(mThemeListPref.getValue());
        mThemeListPref.setSummary(mThemeListPref.getEntries()[prefValue]);
        mThemeListPref.setOnPreferenceChangeListener(this);

        mSortListPref = findPreference(SORT_KEY);
        final int sortValue = Integer.parseInt(prefs.getString(SORT_KEY, "1"));
        mSortListPref.setSummary(mSortListPref.getEntries()[sortValue]);
        mSortListPref.setOnPreferenceChangeListener(this);

        mLangPref = findPreference(LANG_KEY);
        mLangPref.setOnPreferenceClickListener(this);

        mAnalyticsPref = findPreference(ANALYTICS_KEY);
        mAnalyticsPref.setOnPreferenceChangeListener(this);

        mAnalyticsResetPref = findPreference(ANALYTICS_RESET_KEY);
        mAnalyticsResetPref.setOnPreferenceClickListener(this);
        updateAnalyticsSummary();
    }

    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
        if (preference == mLangPref) {
            NavHostFragment.findNavController(SettingsFragment.this)
                    .navigate(R.id.action_settingsFragment_to_langFragment);
            return true;
        } else if (preference == mAnalyticsResetPref) {
            mAnalytics.resetAnalyticsData();
            updateAnalyticsSummary();
            Toast.makeText(requireContext(), getString(R.string.analytics_reset_done),
                    Toast.LENGTH_LONG).show();
            return true;
        }
        return false;
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
        } else if (preference == mAnalyticsPref) {
            final boolean checked = (Boolean) newValue;
            mAnalytics.setAnalyticsCollectionEnabled(checked);
            updateAnalyticsSummary();
            return true;
        }
        return false;
    }

    private void updateAnalyticsSummary() {
        final String summary = getString(R.string.analytics_reset_summary);
        final Task<String> task = mAnalytics.getAppInstanceId();
        task.addOnCompleteListener(task1 -> {
            final String id = task1.getResult();
            mAnalyticsResetPref.setSummary(String.format(summary, id != null ? id : ""));
        });
    }
}