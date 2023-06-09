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
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LangFragment extends Fragment {
    public static final String KEY_OVERRIDE_LOCALE = "override_locale";
    private Locale mSelectedLocale;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.lang_item_list, container, false);

        // Set the adapter
        if (view instanceof RecyclerView recyclerView) {
            final Locale systemLocale = Resources.getSystem().getConfiguration().getLocales().get(0);
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            final String selected = prefs.getString(KEY_OVERRIDE_LOCALE, null);
            mSelectedLocale = selected != null ? new Locale(selected) : systemLocale;
            Log.i("IDO", mSelectedLocale.getLanguage());
            final List<Locale> avail = new ArrayList<>(List.of(
                    Locale.ENGLISH,
                    new Locale("iw")
            ));
            List<Locale> locales = new ArrayList<>();
            locales.add(systemLocale);
            for (Locale locale : avail) {
                if (!locales.contains(locale)) locales.add(locale);
            }
            LinearLayoutManager manager = new LinearLayoutManager(requireContext());
            manager.setOrientation(LinearLayoutManager.VERTICAL);
            recyclerView.setHasFixedSize(true);
            recyclerView.setLayoutManager(manager);
            recyclerView.setAdapter(new LangRecyclerViewAdapter(locales));
        }
        return view;
    }

    private class LangRecyclerViewAdapter extends RecyclerView.Adapter<LangRecyclerViewAdapter.ViewHolder> {
        private final List<Locale> mLocales;

        public LangRecyclerViewAdapter(List<Locale> locales) {
            mLocales = locales;
        }

        @NonNull
        @Override
        public LangRecyclerViewAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.lang_item, parent, false);
            return new LangRecyclerViewAdapter.ViewHolder(view);
        }

        @SuppressLint("ApplySharedPref")
        @Override
        public void onBindViewHolder(final LangRecyclerViewAdapter.ViewHolder holder, int position) {
            final Locale locale = mLocales.get(position);
            final Configuration config = Resources.getSystem().getConfiguration();
            final boolean isDefault = config.getLocales().get(0).equals(locale);
            holder.mSelectedImg.setVisibility(locale.equals(mSelectedLocale)
                    ? View.VISIBLE : View.INVISIBLE);
            holder.mTitle.setText(isDefault
                    ? getString(R.string.default_lang) : locale.getDisplayLanguage(locale));
            holder.mSummary.setText(locale.getDisplayLanguage());
            holder.mLayout.setOnClickListener(v -> {
                final Locale pressed = mLocales.get(holder.getAdapterPosition());
                if (pressed.equals(mSelectedLocale)) return;
                FirstFragment.displayAreYouSureDialog(requireActivity(),
                        getString(R.string.lang_restart_msg),
                        (dialog, which) -> {
                            final SharedPreferences prefs =
                                    PreferenceManager.getDefaultSharedPreferences(requireContext());
                            if (isDefault) {
                                prefs.edit().remove(KEY_OVERRIDE_LOCALE).commit();
                                restartApp();
                                return;
                            }
                            prefs.edit().putString(KEY_OVERRIDE_LOCALE, locale.getLanguage()).commit();
                            Log.i("IDO", "put: " + locale.getLanguage());
                            restartApp();
                        });
            });
        }

        private void restartApp() {
            Intent intent = new Intent(requireActivity(), MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            requireActivity().startActivity(intent);
            requireActivity().finish();
            Runtime.getRuntime().exit(0);
        }

        @Override
        public int getItemCount() {
            return mLocales.size();
        }

        public class ViewHolder extends RecyclerView.ViewHolder {
            public final LinearLayout mLayout;
            public final ImageView mSelectedImg;
            public final TextView mTitle;
            public final TextView mSummary;

            public ViewHolder(View view) {
                super(view);
                mLayout = view.requireViewById(R.id.item_layout);
                mSelectedImg = view.requireViewById(R.id.selected_img);
                mTitle = view.requireViewById(R.id.lang_title);
                mSummary = view.requireViewById(R.id.lang_desc);
            }
        }
    }
}
