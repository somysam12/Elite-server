package com.bgmi;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatButton;
import androidx.cardview.widget.CardView;
import androidx.core.content.FileProvider;

import net_62v.external.MetaActivationManager;
import com.mundo.MundoCore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;

import com.bgmi.utils.Downtwo;
import com.bgmi.utils.IntegrityGuard;
import com.bgmi.utils.PanelCrypto;
import com.bgmi.utils.Prefs;

@SuppressWarnings({"unused", "deprecation"})
public class LoginActivity extends BaseActivity {

    static {
        try { System.loadLibrary("ZeroKill"); }
        catch (UnsatisfiedLinkError ignored) {}
    }

    private static final String USER   = "USER";
    private static final String PKG_FB = "com.facebook.katana";
    private static final String TAG_FB = "ARK_FB";

    private CardView fbBtn;
    private TextView fbBtnText;
    private volatile boolean fbBusy = false;
    private volatile long lastFbLaunchMs = 0L;

    private Prefs prefs;
    private EditText etKey;
    private androidx.cardview.widget.CardView btnLogin;
    private View btnPaste;
    private ImageButton eyeToggle;
    private boolean passwordVisible = false;

    // Once set, login is blocked until server confirms update not needed
    private volatile boolean updateBlocked = false;

    private LinearLayout processingCard;
    private ProgressBar progressBar, downloadProgressBar;
    private TextView loadingText, progressText, percentText, downloadSizeText, speedText;
    private View speedRow;
    private AppCompatButton okButton;

    private long dlStartMs = 0;

    private static final int RC_STORAGE  = 100;
    private static final int RC_INSTALL  = 200;
    private static final int RC_OVERLAY  = 300;
    private static final String KEY_PERMS_DONE = "ark_perms_done";

    // All URLs from native — no hardcoded panel endpoints
    private static native String nativeApkKillUrl();
    private static native String nativeApkDownloadUrl();
    private static native boolean nativeVerifyLoginToken(String token);
    private static native String nativeGetSerial();
    private static native String Check(android.content.Context ctx, String key);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF04070F);
        getWindow().setNavigationBarColor(0xFF04070F);
        setContentView(R.layout.tron_two);

        IntegrityGuard.verifyOrDie(this);

        prefs = Prefs.with(this);
        bindViews();
        setTitle();
        setupListeners();
        etKey.setText(prefs.read(USER, ""));
        checkPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreFbBtnState(); // FB se wapas aane par CLONE/LAUNCH text update karo
        if (updateBlocked) {
            showProcessing("Checking...");
            checkApkUpdate();
        }
    }

    @Override
    public void onBackPressed() {
        if (updateBlocked) return;
        super.onBackPressed();
    }

    // ── Permission chain ────────────────────────────────────────────────────
    private void checkPermissions() {
        if (!hasStorage()) { askStorage(); return; }
        if (!hasInstall()) { askInstall(); return; }
        if (!hasOverlay()) { askOverlay(); return; }
        onPermissionsReady();
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            return Environment.isExternalStorageManager();
        return androidx.core.content.ContextCompat.checkSelfPermission(this,
            android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            return getPackageManager().canRequestPackageInstalls();
        return true;
    }
    private boolean hasOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            return Settings.canDrawOverlays(this);
        return true;
    }

    private void askStorage() {
        showSheet("Storage Permission", "Zero Kill needs storage access to copy game files.",
            "Grant", () -> {
                dismissSheet();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())), RC_STORAGE);
                } else {
                    androidx.core.app.ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                     android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, RC_STORAGE);
                }
            },
            "Exit", () -> { dismissSheet(); finish(); System.exit(0); }
        );
    }

    private void askInstall() {
        showSheet("Install Unknown Apps",
            "Zero Kill needs permission to install game components. Please allow Install from Unknown Sources.",
            "Grant", () -> {
                dismissSheet();
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())), RC_INSTALL);
            },
            "Exit", () -> { dismissSheet(); finish(); System.exit(0); }
        );
    }

    private void askOverlay() {
        showSheet("Display Over Apps",
            "Zero Kill needs to display over other apps to show the cheat menu while in-game.",
            "Grant", () -> {
                dismissSheet();
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), RC_OVERLAY);
            },
            "Exit", () -> { dismissSheet(); finish(); System.exit(0); }
        );
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == RC_STORAGE) {
            if (!hasInstall()) { askInstall(); return; }
            if (!hasOverlay()) { askOverlay(); return; }
            onPermissionsReady();
        } else if (req == RC_INSTALL) {
            if (!hasOverlay()) { askOverlay(); return; }
            onPermissionsReady();
        } else if (req == RC_OVERLAY) {
            if (!prefs.read(KEY_PERMS_DONE, "").equals("1")) {
                prefs.write(KEY_PERMS_DONE, "1");
                showSheet("Restart Required",
                    "Please restart Zero Kill once so it can initialize properly.",
                    "Restart", () -> {
                        dismissSheet();
                        android.os.Process.killProcess(android.os.Process.myPid());
                        System.exit(0);
                    },
                    "Skip", () -> { dismissSheet(); onPermissionsReady(); }
                );
            } else {
                onPermissionsReady();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == RC_STORAGE) {
            if (!hasInstall()) { askInstall(); return; }
            if (!hasOverlay()) { askOverlay(); return; }
            onPermissionsReady();
        }
    }

    private void onPermissionsReady() {
        checkApkUpdate();
    }

    // ── APK Update / Kill Check ─────────────────────────────────────────────
    // Panel returns XOR-encoded JSON. kill=1 → process kill. force_update=1 → block login.
    // Network error → retry dialog (updateBlocked stays as-is, not cleared).
    private void checkApkUpdate() {
        new Thread(() -> {
            try {
                String panelUrl = nativeApkKillUrl();
                HttpURLConnection c = (HttpURLConnection)
                    new URL(panelUrl + "?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                c.setRequestProperty("Cache-Control", "no-cache");
                c.connect();
                if (c.getResponseCode() != 200) {
                    runOnUiThread(this::showRetryDialog); return;
                }
                java.util.Scanner sc = new java.util.Scanner(c.getInputStream(), "UTF-8");
                String encoded = sc.hasNextLine() ? sc.nextLine().trim() : "";
                sc.close(); c.disconnect();

                if (encoded.isEmpty()) { runOnUiThread(this::showLoginUI); return; }

                String json = PanelCrypto.xorDecode(encoded);
                org.json.JSONObject obj = new org.json.JSONObject(json);

                if (obj.optInt("kill", 0) == 1) {
                    final String msg = obj.optString("msg", "App disabled by developer.");
                    runOnUiThread(() -> showMandatoryUpdateSheet(
                        "Service Unavailable", msg,
                        "Exit", () -> {
                            android.os.Process.killProcess(android.os.Process.myPid());
                            System.exit(0);
                        }
                    ));
                    return;
                }

                if (obj.optInt("force_update", 0) == 1) {
                    updateBlocked = true;
                    String ver = obj.optString("version", "");
                    runOnUiThread(() -> showUpdateDialog(ver));
                } else {
                    runOnUiThread(this::showLoginUI);
                }

            } catch (Exception e) {
                runOnUiThread(this::showRetryDialog);
            }
        }).start();
    }

    private void showRetryDialog() {
        showSheet("Connection Error", "Could not reach server. Check internet and retry.",
            "Retry", () -> { dismissSheet(); showProcessing("Checking..."); checkApkUpdate(); },
            "Exit", () -> { dismissSheet(); finish(); System.exit(0); }
        );
    }

    private void showUpdateDialog(String newVersion) {
        if (etKey != null) etKey.setEnabled(false);
        if (btnLogin != null) btnLogin.setEnabled(false);
        String body = newVersion.isEmpty()
            ? "A new version is required.\nYou must update to keep using Zero Kill."
            : "Version " + newVersion + " is required.\nYou must update to keep using Zero Kill.";
        showMandatoryUpdateSheet("Update Required", body,
            "Update Now", () -> { dismissSheet(); downloadAndInstallApk(); }
        );
    }

    @SuppressLint("SetTextI18n")
    private void downloadAndInstallApk() {
        runOnUiThread(() -> {
            if (processingCard != null) processingCard.setVisibility(View.VISIBLE);
            if (loadingText != null)   loadingText.setText("DOWNLOADING UPDATE");
            if (progressText != null)  progressText.setText("Please wait...");
            if (progressBar != null)   progressBar.setVisibility(View.GONE);
            if (downloadProgressBar != null) {
                downloadProgressBar.setVisibility(View.VISIBLE);
                downloadProgressBar.setProgress(0);
            }
            if (speedRow != null) speedRow.setVisibility(View.VISIBLE);
            if (okButton != null) okButton.setVisibility(View.GONE);
        });

        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                    new URL(nativeApkDownloadUrl()).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000); conn.setReadTimeout(120000);
                conn.connect();

                long total = conn.getContentLengthLong();
                InputStream in = conn.getInputStream();
                File apk = new File(getCacheDir(), "ZeroKill_update.apk");
                FileOutputStream out = new FileOutputStream(apk);
                byte[] buf = new byte[32768]; long done = 0; int n;
                dlStartMs = System.currentTimeMillis();
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n); done += n;
                    final long dF = done, tF = total;
                    final int pct = total > 0 ? (int)((done * 100L) / total) : 0;
                    runOnUiThread(() -> updateDownloadUI(pct, dF, tF));
                }
                out.flush(); out.close(); in.close(); conn.disconnect();
                final File apkFinal = apk;
                runOnUiThread(() -> installApk(apkFinal));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (processingCard != null) processingCard.setVisibility(View.GONE);
                    Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    showRetryDialog();
                });
            }
        }).start();
    }

    @SuppressLint("SetTextI18n")
    private void updateDownloadUI(int pct, long done, long total) {
        if (progressBar != null)    progressBar.setVisibility(View.GONE);
        if (progressText != null)   progressText.setText("Downloading update...");
        if (downloadProgressBar != null) {
            downloadProgressBar.setVisibility(View.VISIBLE);
            downloadProgressBar.setProgress(pct);
        }
        if (percentText != null) { percentText.setVisibility(View.VISIBLE); percentText.setText(pct + "%"); }
        if (downloadSizeText != null) {
            downloadSizeText.setVisibility(View.VISIBLE);
            downloadSizeText.setText(total > 0 ? fmt(done) + " / " + fmt(total) : fmt(done));
        }
        if (speedText != null && dlStartMs > 0) {
            long elapsed = System.currentTimeMillis() - dlStartMs;
            if (elapsed > 300) {
                if (speedRow != null) speedRow.setVisibility(View.VISIBLE);
                speedText.setVisibility(View.VISIBLE);
                speedText.setText(fmt((long)(done / (elapsed / 1000.0))) + "/s");
            }
        }
    }

    private void installApk(File apk) {
        if (processingCard != null) processingCard.setVisibility(View.GONE);
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            // updateBlocked stays true — onResume re-checks server
        } catch (Exception e) {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }
    }

    private static String fmt(long b) {
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / (1024.0 * 1024));
    }

    // ── Login UI ────────────────────────────────────────────────────────────
    private void showLoginUI() {
        if (updateBlocked) return;
        if (processingCard != null) processingCard.setVisibility(View.GONE);
        if (etKey != null)    etKey.setEnabled(true);
        if (btnLogin != null) btnLogin.setEnabled(true);
        if (fbBtn != null) {
            fbBtn.setVisibility(View.VISIBLE);
            fbBtn.setAlpha(0f);
            fbBtn.animate().alpha(1f).setDuration(280).start();
            restoreFbBtnState();
        }
        getWindow().getDecorView().post(this::runEntryAnim);
        setTitle();
    }

    private void runEntryAnim() {
        View logo = findViewById(R.id.splashLogo);
        if (logo == null) return;
        View[] views = {
            logo,
            findViewById(R.id.splashTitle),
            findViewById(R.id.et1),
            findViewById(R.id.img1),
            findViewById(R.id.btnSignIn)
        };
        long delay = 0;
        for (View v : views) {
            if (v == null) { delay += 80; continue; }
            v.setAlpha(0f); v.setTranslationY(48f);
            ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 0f, 1f);
            ObjectAnimator t = ObjectAnimator.ofFloat(v, "translationY", 48f, 0f);
            a.setDuration(460); t.setDuration(460);
            a.setStartDelay(delay); t.setStartDelay(delay);
            a.setInterpolator(new DecelerateInterpolator(1.6f));
            t.setInterpolator(new DecelerateInterpolator(1.6f));
            if (delay == 0) {
                v.setScaleX(0.7f); v.setScaleY(0.7f);
                ObjectAnimator sx = ObjectAnimator.ofFloat(v, "scaleX", 0.7f, 1f);
                ObjectAnimator sy = ObjectAnimator.ofFloat(v, "scaleY", 0.7f, 1f);
                sx.setDuration(600); sy.setDuration(600);
                sx.setInterpolator(new OvershootInterpolator(1.5f));
                sy.setInterpolator(new OvershootInterpolator(1.5f));
                AnimatorSet s = new AnimatorSet(); s.playTogether(a, t, sx, sy); s.start();
            } else {
                AnimatorSet s = new AnimatorSet(); s.playTogether(a, t); s.start();
            }
            delay += 90;
        }
    }

    private void bindViews() {
        etKey               = findViewById(R.id.et1);
        btnLogin            = (androidx.cardview.widget.CardView) findViewById(R.id.btnSignIn);
        btnPaste            = findViewById(R.id.img1);
        eyeToggle           = findViewById(R.id.eyeToggle);
        processingCard      = findViewById(R.id.processingCard);
        progressBar         = findViewById(R.id.progressBar);
        downloadProgressBar = findViewById(R.id.downloadProgressBar);
        loadingText         = findViewById(R.id.loadingText);
        progressText        = findViewById(R.id.progressText);
        percentText         = findViewById(R.id.percentText);
        downloadSizeText    = findViewById(R.id.downloadSizeText);
        speedText           = findViewById(R.id.speedText);
        speedRow            = findViewById(R.id.speedRow);
        okButton            = findViewById(R.id.okButton);
        fbBtn               = findViewById(R.id.btnFbClone);
        fbBtnText           = findViewById(R.id.btnFbCloneText);
    }

    private void setupListeners() {
        if (etKey != null) etKey.setTransformationMethod(PasswordTransformationMethod.getInstance());
        if (eyeToggle != null) {
            eyeToggle.setImageResource(R.drawable.ic_eye_off);
            eyeToggle.setOnClickListener(v -> {
                passwordVisible = !passwordVisible;
                etKey.setTransformationMethod(passwordVisible
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
                etKey.setSelection(etKey.getText().length());
                eyeToggle.setImageResource(passwordVisible ? R.drawable.ic_eye_open : R.drawable.ic_eye_off);
            });
        }
        if (btnPaste != null) {
            btnPaste.setOnClickListener(v -> {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cb != null && cb.hasPrimaryClip()) {
                    ClipData.Item item = cb.getPrimaryClip().getItemAt(0);
                    if (item != null && item.getText() != null) {
                        String txt = item.getText().toString().trim();
                        if (txt.length() > 4) etKey.setText(txt);
                        else Toast.makeText(this, "Invalid key", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
        if (btnLogin != null) {
            btnLogin.setOnClickListener(v -> {
                String key = etKey.getText().toString().trim();
                if (key.isEmpty()) {
                    etKey.setError("Enter your license key");
                    return;
                }
                prefs.write(USER, key);
                doLogin(key);
            });
        }
        if (fbBtn != null) {
            fbBtn.setOnClickListener(v -> {
                if (fbBusy) return;
                long now = System.currentTimeMillis();
                if (isFbClonedSafe()) {
                    if (now - lastFbLaunchMs < 4000L) {
                        Toast.makeText(this, "Opening Facebook...", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    lastFbLaunchMs = now;
                    setFbBtnText("OPENING...");
                    new Thread(() -> { launchClonedFb(); runOnUiThread(this::restoreFbBtnState); }).start();
                } else {
                    fbBusy = true;
                    setFbBtnText("CLONING...");
                    new Thread(() -> { cloneFb(); fbBusy = false; runOnUiThread(this::restoreFbBtnState); }).start();
                }
            });
            fbBtn.setOnLongClickListener(v -> {
                if (fbBusy) return true;
                fbBusy = true;
                setFbBtnText("RE-CLONING...");
                new Thread(() -> {
                    try { MundoCore.get().uninstallPackageAsUser(PKG_FB, 0); } catch (Throwable ignored) {}
                    cloneFb(); fbBusy = false; runOnUiThread(this::restoreFbBtnState);
                }).start();
                return true;
            });
        }
    }

    // ── Facebook clone/launch ────────────────────────────────────────────────
    private boolean isFbClonedSafe() {
        try { return MundoCore.get() != null && MundoCore.get().isInstalled(PKG_FB, 0); }
        catch (Throwable t) { return false; }
    }
    private void setFbBtnText(String text) {
        runOnUiThread(() -> { if (fbBtnText != null) fbBtnText.setText(text); });
    }
    private void restoreFbBtnState() {
        if (fbBtnText == null) return;
        fbBtnText.setText(isFbClonedSafe() ? "LAUNCH FACEBOOK" : "CLONE FACEBOOK");
    }
    private void ensureMundoActivated() {
        try {
            boolean active = false;
            try { active = MetaActivationManager.getActivatedStatus(); } catch (Throwable ignored) {}
            if (!active) {
                try { MetaActivationManager.activateSdk(ArkApplication.getSdkKey()); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
    private void cloneFb() {
        try {
            android.util.Log.i(TAG_FB, "cloneFb start");

            // FIX 1: Engine PEHLE activate karo — bina is ke install silently 0 return karta hai
            ensureMundoActivated();

            try { MundoCore.get().uninstallPackageAsUser(PKG_FB, 0); } catch (Throwable ignored) {}
            android.util.Log.i(TAG_FB, "after uninstall isFbCloned=" + isFbClonedSafe());

            ApplicationInfo ai = getPackageManager().getApplicationInfo(PKG_FB, 0);
            int nSplits = (ai.splitSourceDirs == null) ? 0 : ai.splitSourceDirs.length;
            android.util.Log.i(TAG_FB, "host base=" + ai.sourceDir + " splits=" + nSplits);

            // FIX 2: Scratch dir clean karo pehle — old broken APKs confuse engine
            File scratch = new File(getExternalFilesDir(null), "fbclone");
            if (scratch.exists()) {
                File[] old = scratch.listFiles();
                if (old != null) for (File f : old) f.delete();
            } else {
                scratch.mkdirs();
            }

            File baseDst = new File(scratch, "base.apk");
            copyFileFast(new File(ai.sourceDir), baseDst);
            int copied = 1;
            if (ai.splitSourceDirs != null) {
                for (String split : ai.splitSourceDirs) {
                    if (split == null) continue;
                    File src = new File(split);
                    String nm = src.getName();
                    if (!nm.toLowerCase().endsWith(".apk")) nm = nm + ".apk";
                    copyFileFast(src, new File(scratch, nm));
                    copied++;
                }
            }
            String basePath = baseDst.getAbsolutePath();
            String dirPath  = scratch.getAbsolutePath();
            android.util.Log.i(TAG_FB, "copied " + copied + " apks → " + dirPath);

            com.mundo.entity.pm.InstallResult installResult = null;
            try { installResult = MundoCore.get().installPackageAsUser(PKG_FB, 0); }
            catch (Throwable t) { android.util.Log.e(TAG_FB, "installPackageAsUser threw " + t); }
            android.util.Log.i(TAG_FB, "installPackageAsUser result=" + installResult + " isFbCloned=" + isFbClonedSafe());

            boolean ok = isFbClonedSafe();
            android.util.Log.i(TAG_FB, "clone result=" + ok);
            runOnUiThread(() -> {
                restoreFbBtnState();
                Toast.makeText(this,
                    ok ? "Facebook cloned! Tap to launch."
                       : "Could not clone Facebook. Make sure it is installed and try again.",
                    Toast.LENGTH_LONG).show();
            });
        } catch (Exception e) {
            android.util.Log.e(TAG_FB, "cloneFb FAIL: " + e, e);
            runOnUiThread(() -> {
                restoreFbBtnState();
                Toast.makeText(this, "Facebook not installed on device", Toast.LENGTH_LONG).show();
            });
        }
    }

    private void launchClonedFb() {
        // FIX 5: Debounce sirf click listener mein hai — yahan se hatao
        // Pehle yahan bhi tha (lastFbLaunchMs check) jo click listener ke set karne ke
        // turant baad trigger hota tha → now - lastFbLaunchMs = 0 < 4000 → NEVER launched!
        ensureMundoActivated();
        try { MundoCore.get().launchApk(PKG_FB, 0); }
        catch (Throwable t1) {
            try { MundoCore.get().launchApk(PKG_FB, 0); } catch (Throwable t2) {
                android.util.Log.e(TAG_FB, "launchClonedFb failed: " + t2.getMessage());
            }
        }
    }
    private static void copyFileFast(File src, File dst) throws Exception {
        dst.getParentFile().mkdirs();
        try (FileInputStream fin = new FileInputStream(src);
             FileOutputStream fout = new FileOutputStream(dst);
             FileChannel inCh = fin.getChannel();
             FileChannel outCh = fout.getChannel()) {
            long size = inCh.size(), pos = 0L;
            final long CHUNK = 16L * 1024 * 1024;
            while (pos < size) {
                long t = inCh.transferTo(pos, Math.min(CHUNK, size - pos), outCh);
                if (t <= 0) break;
                pos += t;
            }
            outCh.force(true);
        }
    }

    // ── Login ────────────────────────────────────────────────────────────────
    private void doLogin(String key) {
        if (updateBlocked) {
            showProcessing("Checking...");
            checkApkUpdate();
            return;
        }
        showProcessing("Verifying License");
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper(),
            msg -> {
                if (msg.what == 0) {
                    hideProcessing();
                    String serial = "";
                    try { serial = nativeGetSerial(); } catch (Throwable ignored) {}
                    writeLicFile(key, serial);
                    try {
                        // Pass the native serial (the SAME device id the panel registered
                        // at login) so the heartbeat always matches — not raw ANDROID_ID.
                    } catch (Throwable ignored) {}
                    startDownloadLib(key, serial);
                } else {
                    showError("Invalid Key", (String) msg.obj);
                }
                return true;
            });
        new Thread(() -> {
            String result = "";
            boolean loginSuccess = false;
            try {
                for (int attempt = 0; attempt < 3; attempt++) {
                    result = Check(this, key);
                    // Check() returns the login token on success — verify it natively
                    // (anti-smali-patch). nativeVerifyLoginToken consumes the token, so
                    // it is called exactly once per attempt.
                    if (result != null && !result.isEmpty() && nativeVerifyLoginToken(result)) {
                        loginSuccess = true;
                        break;
                    }
                    if (result != null && (
                            result.toLowerCase().contains("invalid server") ||
                            result.toLowerCase().contains("response") ||
                            result.toLowerCase().contains("json") ||
                            result.toLowerCase().contains("timeout") ||
                            result.toLowerCase().contains("network") ||
                            result.toLowerCase().contains("mismatch") ||
                            result.toLowerCase().contains("failed") ||
                            result.toLowerCase().contains("curl"))) {
                        try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    break;
                }
            } catch (Throwable t) {
                result = t.getMessage() != null ? t.getMessage() : "Error";
            }
            android.os.Message m = android.os.Message.obtain();
            m.what = loginSuccess ? 0 : 1;
            m.obj  = loginSuccess ? null : ((result != null && !result.isEmpty()) ? result : "Key is invalid");
            h.sendMessage(m);
        }).start();
    }

    // Writes Android/ZeroKill.lic with key, serial, packageName (New UI only)
    private void writeLicFile(String key, String serial) {
        try {
            java.io.File dir = new java.io.File(Environment.getExternalStorageDirectory(), "Android");
            if (!dir.exists()) dir.mkdirs();
            java.io.File lic = new java.io.File(dir, "ZeroKill.lic");
            String data = (key != null ? key : "") + "\n"
                        + (serial != null ? serial : "") + "\n"
                        + getPackageName();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(lic, false);
            fos.write(data.getBytes("UTF-8"));
            fos.flush(); fos.close();
            try { lic.setReadable(true, false); } catch (Exception ignored) {}
        } catch (Exception e) {
            android.util.Log.e("ARK_LIC", "writeLicFile failed: " + e.getMessage());
        }
    }

    private void startDownloadLib(String key, String serial) {
        showProcessing("Downloading Components");
        dlStartMs = System.currentTimeMillis();
        Downtwo dl = new Downtwo(this, success -> {
            hideProcessing();
            // Download failure is NON-FATAL (Mundo flow) — the loader can still fetch the
            // library later. Never trap the user on the login screen over a download hiccup.
            if (!success) {
                Toast.makeText(this, "Download failed, proceeding...", Toast.LENGTH_SHORT).show();
            }
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        });
        dl.setCredentials(key, serial, getPackageName());
        dl.setSizeProgressListener((pct, done, total) ->
            runOnUiThread(() -> updateDownloadUI(pct, done, total)));
        dl.execute();
    }

    // ── Processing helpers ──────────────────────────────────────────────────
    private void showProcessing(String msg) {
        runOnUiThread(() -> {
            if (processingCard != null) processingCard.setVisibility(View.VISIBLE);
            if (fbBtn != null)          fbBtn.setVisibility(View.GONE);
            if (loadingText != null)    loadingText.setText(msg.toUpperCase());
            if (progressText != null)   progressText.setText("Please wait...");
            if (progressBar != null)    progressBar.setVisibility(View.VISIBLE);
            if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.GONE);
            if (percentText != null)    percentText.setVisibility(View.GONE);
            if (downloadSizeText != null) downloadSizeText.setVisibility(View.GONE);
            if (speedRow != null)       speedRow.setVisibility(View.GONE);
            if (okButton != null)       okButton.setVisibility(View.GONE);
        });
    }

    private void hideProcessing() {
        if (processingCard != null) processingCard.setVisibility(View.GONE);
    }

    @SuppressLint("SetTextI18n")
    private void showError(String title, String msg) {
        runOnUiThread(() -> {
            if (processingCard != null) processingCard.setVisibility(View.VISIBLE);
            if (fbBtn != null)          fbBtn.setVisibility(View.GONE);
            if (loadingText != null)    { loadingText.setText(title); loadingText.setTextColor(0xFFFF4060); }
            if (progressText != null)   progressText.setText(msg);
            if (progressBar != null)    progressBar.setVisibility(View.GONE);
            if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.GONE);
            if (percentText != null)    percentText.setVisibility(View.GONE);
            if (downloadSizeText != null) { downloadSizeText.setVisibility(View.GONE); downloadSizeText.setText(""); }
            if (speedRow != null)       speedRow.setVisibility(View.GONE);
            if (speedText != null)      { speedText.setVisibility(View.GONE); speedText.setText(""); }
            if (okButton != null) {
                okButton.setVisibility(View.VISIBLE);
                okButton.setOnClickListener(v -> {
                    if (loadingText != null) loadingText.setTextColor(0xFFFFFFFF);
                    hideProcessing();
                    if (fbBtn != null) {
                        fbBtn.setVisibility(View.VISIBLE);
                        fbBtn.setAlpha(0f);
                        fbBtn.animate().alpha(1f).setDuration(280).start();
                        restoreFbBtnState();
                    }
                });
            }
        });
    }

    private void setTitle() {
        TextView tv = findViewById(R.id.splashTitle);
        if (tv == null) return;
        try {
            tv.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.pacifico));
        } catch (Throwable ignored) {}
        tv.setShadowLayer(26f, 0f, 0f, 0xFFC0003A);
        android.text.SpannableString ss = new android.text.SpannableString("ZERO KILL");
        ss.setSpan(new android.text.style.ForegroundColorSpan(0xFFC0003A), 0, 4,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFFFFF), 4, 9,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(ss);
    }
}
