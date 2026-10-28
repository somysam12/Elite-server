package com.bgmi;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

@SuppressWarnings({"unused", "deprecation"})
public class PermissionsActivity extends AppCompatActivity {

    private static final int REQ_OVERLAY   = 1001;
    private static final int REQ_STORAGE   = 1002;
    private static final int REQ_INSTALL   = 1003;

    private TextView statusText;
    private Button retryBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        setContentView(R.layout.activity_permissions);

        statusText = findViewById(R.id.permissionStatus);
        retryBtn   = findViewById(R.id.permissionRetry);

        retryBtn.setOnClickListener(v -> requestNext());
        requestNext();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (allGranted()) {
            proceed();
        }
    }

    private void requestNext() {
        if (!hasOverlay()) {
            statusText.setText(getString(R.string.permissions_overlay));
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_OVERLAY);
            return;
        }
        if (!hasStorage()) {
            statusText.setText(getString(R.string.permissions_storage));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_STORAGE);
            } else {
                requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQ_STORAGE);
            }
            return;
        }
        if (!hasInstall()) {
            statusText.setText(getString(R.string.permissions_install));
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_INSTALL);
            return;
        }
        proceed();
    }

    private boolean allGranted() {
        return hasOverlay() && hasStorage() && hasInstall();
    }

    private boolean hasOverlay() {
        return Settings.canDrawOverlays(this);
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    private void proceed() {
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        requestNext();
    }
}
