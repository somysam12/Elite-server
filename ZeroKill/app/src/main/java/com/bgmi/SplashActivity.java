package com.bgmi;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

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
        getWindow().getDecorView().post(this::runSplashAnimations);
    }

    private void runSplashAnimations() {
        View glow    = findViewById(R.id.center_glow);
        View logo    = findViewById(R.id.img1);
        View title   = findViewById(R.id.tv_app_name);
        View tagline = findViewById(R.id.tv_tagline);
        View line    = findViewById(R.id.fire_line);
        View ver     = findViewById(R.id.tv_version);

        // Glow pulse — starts invisible, fades in slowly
        if (glow != null) {
            ObjectAnimator ga = ObjectAnimator.ofFloat(glow, "alpha", 0f, 0.65f);
            ga.setDuration(1200);
            ga.setInterpolator(new DecelerateInterpolator());
            ga.start();
        }

        // Dragon logo — fade + smooth scale-up with slight overshoot
        if (logo != null) {
            logo.setScaleX(0.78f); logo.setScaleY(0.78f);
            ObjectAnimator la = ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f);
            ObjectAnimator sx = ObjectAnimator.ofFloat(logo, "scaleX", 0.78f, 1f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(logo, "scaleY", 0.78f, 1f);
            la.setDuration(800); sx.setDuration(950); sy.setDuration(950);
            la.setStartDelay(100); sx.setStartDelay(100); sy.setStartDelay(100);
            sx.setInterpolator(new OvershootInterpolator(1.05f));
            sy.setInterpolator(new OvershootInterpolator(1.05f));
            AnimatorSet s = new AnimatorSet();
            s.playTogether(la, sx, sy);
            s.start();
        }

        // Title — fade + slide up from 32px below
        if (title != null) {
            title.setTranslationY(32f);
            ObjectAnimator ta = ObjectAnimator.ofFloat(title, "alpha", 0f, 1f);
            ObjectAnimator tt = ObjectAnimator.ofFloat(title, "translationY", 32f, 0f);
            ta.setDuration(550); tt.setDuration(550);
            ta.setStartDelay(650); tt.setStartDelay(650);
            ta.setInterpolator(new DecelerateInterpolator(1.5f));
            tt.setInterpolator(new DecelerateInterpolator(1.5f));
            AnimatorSet s = new AnimatorSet();
            s.playTogether(ta, tt);
            s.start();
        }

        // Tagline — fade in after title
        if (tagline != null) {
            ObjectAnimator tga = ObjectAnimator.ofFloat(tagline, "alpha", 0f, 1f);
            tga.setDuration(500); tga.setStartDelay(950);
            tga.setInterpolator(new DecelerateInterpolator());
            tga.start();
        }

        // Fire line — fade in
        if (line != null) {
            ObjectAnimator la = ObjectAnimator.ofFloat(line, "alpha", 0f, 0.85f);
            la.setDuration(500); la.setStartDelay(1150);
            la.start();
        }

        // Version — fade in last, subtle
        if (ver != null) {
            ObjectAnimator va = ObjectAnimator.ofFloat(ver, "alpha", 0f, 0.55f);
            va.setDuration(500); va.setStartDelay(1350);
            va.start();
        }

        handler.postDelayed(this::exitSplash, 2900);
    }

    private void exitSplash() {
        if (isFinishing() || isDestroyed()) return;
        View root = getWindow().getDecorView().getRootView();
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(root, "alpha", 1f, 0f);
        fadeOut.setDuration(280);
        fadeOut.setInterpolator(new AccelerateDecelerateInterpolator());
        fadeOut.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (isFinishing() || isDestroyed()) return;
                startActivity(new Intent(SplashActivity.this, LoginActivity.class));
                overridePendingTransition(R.anim.slide_up_enter, R.anim.splash_exit);
                finish();
            }
        });
        fadeOut.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
