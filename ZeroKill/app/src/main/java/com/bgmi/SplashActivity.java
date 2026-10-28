package com.bgmi;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.AnimationDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

@SuppressWarnings({"unused","deprecation"})
public class SplashActivity extends AppCompatActivity {

    public static boolean launched = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launched = true;

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        setContentView(R.layout.tron_first);

        // Start animated gradient background
        View root = findViewById(R.id.splashRoot);
        if (root != null && root.getBackground() instanceof AnimationDrawable) {
            ((AnimationDrawable) root.getBackground()).start();
        }

        handler.postDelayed(this::exitSplash, 2800);
    }

    private void exitSplash() {
        if (isFinishing() || isDestroyed()) return;
        Class<?> next = needsPermissions() ? PermissionsActivity.class : LoginActivity.class;
        startActivity(new Intent(SplashActivity.this, next));
        overridePendingTransition(R.anim.slide_up_enter, R.anim.splash_exit);
        finish();
    }

    private boolean needsPermissions() {
        if (!Settings.canDrawOverlays(this)) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) return true;
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
