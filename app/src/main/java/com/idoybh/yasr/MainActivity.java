package com.idoybh.yasr;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.MimeTypeMap;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.idoybh.yasr.databinding.ActivityMainBinding;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 0x1A;
    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);

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

        List<File> recordings = new ArrayList<>();
        File fileDir = getFilesDir();
        File[] files = fileDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) continue;
            final String fileName = file.getName();
            final String ext = fileName.substring(fileName.lastIndexOf(".") + 1);
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null && (mime.contains("audio") || mime.contains("video"))) {
                recordings.add(file);
            }
        }
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