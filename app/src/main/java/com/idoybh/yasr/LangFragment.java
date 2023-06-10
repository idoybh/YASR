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
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LangFragment extends Fragment {
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
            final LocaleListCompat avail = getAvailLocales();
            mSelectedLocale = LocaleListCompat.getDefault().get(0);
            List<Locale> locales = new ArrayList<>();
            locales.add(systemLocale);
            for (int i = 0; i < avail.size(); i++) {
                final Locale locale = avail.get(i);
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

    private LocaleListCompat getAvailLocales() {
        StringBuilder sb = new StringBuilder();
        boolean added = false;
        try {
            XmlPullParser xpp = requireContext().getResources().getXml(R.xml.locales_config);
            while (xpp.getEventType() != XmlPullParser.END_DOCUMENT) {
                if (xpp.getEventType() == XmlPullParser.START_TAG) {
                    if (xpp.getName().equals("locale")) {
                        if (added) sb.append(",");
                        else added = true;
                        sb.append(xpp.getAttributeValue(0));
                    }
                }
                xpp.next();
            }
        } catch (XmlPullParserException | IOException e) {
            e.printStackTrace();
        }

        return LocaleListCompat.forLanguageTags(sb.toString());
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
                            LocaleListCompat apply = LocaleListCompat.getEmptyLocaleList();
                            if (!isDefault) apply = LocaleListCompat.create(pressed);
                            AppCompatDelegate.setApplicationLocales(apply);
                        });
            });
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
