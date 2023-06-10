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

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.elevation.SurfaceColors;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.idoybh.yasr.databinding.ActivityMainBinding;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_RECORDING = "com.idoybh.yasr.RECORDING";
    private static final int PERMISSION_REQUEST_CODE = 0x1A;

    private SharedPreferences mSharedPreferences;
    private FirebaseAnalytics mFirebaseAnalytics;
    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);

        // statusbar color
        getWindow().setStatusBarColor(SurfaceColors.SURFACE_2.getColor(this));

        // permission setup
        List<String> missingPerms = new ArrayList<>(List.of(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.RECORD_AUDIO
        ));
        missingPerms.removeIf(perm -> getApplicationContext().checkSelfPermission(perm)
                == PackageManager.PERMISSION_GRANTED);
        if (missingPerms.size() != 0) {
            showCriticalExitDialog(R.string.permission_dialog_msg, (dialog, which) -> {
                String[] arr = new String[missingPerms.size()];
                arr = missingPerms.toArray(arr);
                requestPermissions(arr, PERMISSION_REQUEST_CODE);
            });
        }

        if (maybeResumeRecording(getIntent())) return;
        maybeResumeRecordingPref();
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        maybeResumeRecording(intent);
    }

    private boolean maybeResumeRecording(Intent intent) {
        if (intent.hasExtra(EXTRA_RECORDING)) return false;
        final String extra = intent.getStringExtra(EXTRA_RECORDING);
        if (extra == null || extra.isEmpty()) return false;
        // we resumed from the recording notification - resume showing
        Bundle bundle = new Bundle();
        bundle.putString(RecordFragment.BUNDLE_ARG1, extra);
        Navigation.findNavController(this, R.id.nav_host_fragment_content_main)
                .navigate(R.id.action_FirstFragment_to_RecordFragment, bundle);
        return true;
    }

    private void maybeResumeRecordingPref() {
        final String extra = getPrefs().getString(RecordingService.PREF_STARTED, null);
        if (extra == null || extra.isEmpty()) return;
        // we're in progress but the activity was killed - resume showing
        Bundle bundle = new Bundle();
        bundle.putString(RecordFragment.BUNDLE_ARG1, extra);
        Navigation.findNavController(this, R.id.nav_host_fragment_content_main)
                .navigate(R.id.action_FirstFragment_to_RecordFragment, bundle);
    }

    protected SharedPreferences getPrefs() {
        if (mSharedPreferences != null)
            return mSharedPreferences;
        mSharedPreferences = getSharedPreferences(
                RecordFragment.SHARED_PREF_FILE, Context.MODE_PRIVATE);
        return mSharedPreferences;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NotNull String[] permissions, @NotNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) return;
        for (Integer result : grantResults)
            if (result != PackageManager.PERMISSION_GRANTED)
                finishAndRemoveTask();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            Navigation.findNavController(this, R.id.nav_host_fragment_content_main)
                    .navigate(R.id.open_settings_fragment);
            return true;
        } else if (id == R.id.action_about) {
            View dialogView = LayoutInflater.from(this)
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
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.action_about)
                    .setView(dialogView)
                    .setPositiveButton(R.string.button_ok, (dialog, which) -> dialog.dismiss())
                    .show();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            View view = getCurrentFocus();
            if (view instanceof EditText) {
                Rect outRect = new Rect();
                view.getGlobalVisibleRect(outRect);
                if (!outRect.contains((int)event.getRawX(), (int)event.getRawY())) {
                    view.clearFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private void showCriticalExitDialog(int msgResID, DialogInterface.OnClickListener listener) {
        (new MaterialAlertDialogBuilder(MainActivity.this)
                .setMessage(msgResID)
                .setPositiveButton(R.string.button_ok, listener)
                .setNegativeButton(R.string.button_exit, (dialog, which) -> finishAndRemoveTask())
                .setOnCancelListener(dialog -> finishAndRemoveTask())
        ).show();
    }
}
