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

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.gms.tasks.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
    private SwitchPreferenceCompat mAnalyticsPref;
    private Preference mAnalyticsResetPref;

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
            }

            @Override
            public void onPrepareMenu(@NonNull Menu menu) {
                MenuItem item = menu.findItem(R.id.action_settings);
                if (item == null) return;
                item.setVisible(false);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == R.id.action_about) {
                    View dialogView = LayoutInflater.from(getContext())
                            .inflate(R.layout.about_dialog, null, false);
                    TextView msg = dialogView.requireViewById(R.id.msgTxt);
                    ImageButton gitBtn = dialogView.requireViewById(R.id.githubButton);
                    ImageButton mailBtn = dialogView.requireViewById(R.id.mailButton);
                    ImageButton telegramBtn = dialogView.requireViewById(R.id.telegramButton);
                    msg.setText(Html.fromHtml(getString(R.string.about), Html.FROM_HTML_MODE_LEGACY));
                    msg.setMovementMethod(LinkMovementMethod.getInstance());
                    gitBtn.setOnClickListener(v -> {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setData(Uri.parse(getString(R.string.about_github)));
                        startActivity(intent);
                    });
                    mailBtn.setOnClickListener(v -> {
                        Intent intent = new Intent(Intent.ACTION_SENDTO);
                        intent.setData(Uri.parse("mailto:")); // only email apps should handle this
                        intent.putExtra(Intent.EXTRA_EMAIL, new String[] {getString(R.string.about_mail)});
                        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_mail_title));
                        startActivity(intent);
                    });
                    telegramBtn.setOnClickListener(v -> {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setData(Uri.parse(getString(R.string.about_telegram)));
                        startActivity(intent);
                    });
                    new MaterialAlertDialogBuilder(getContext())
                            .setTitle(R.string.action_about)
                            .setView(dialogView)
                            .setPositiveButton(R.string.button_ok, (dialog, which) -> dialog.dismiss())
                            .show();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

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