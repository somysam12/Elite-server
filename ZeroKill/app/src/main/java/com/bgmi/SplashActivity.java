package com.bgmi;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;


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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        // Build splash UI programmatically — Zero Kill cyber dragon theme
        RelativeLayout root = buildSplashLayout();
        setContentView(root);

        // Start animation after first frame
        root.post(() -> runSplashSequence(root));
    }

    // ── Build layout programmatically ─────────────────────────────────────
    private RelativeLayout buildSplashLayout() {
        RelativeLayout root = new RelativeLayout(this);
        // Dark gradient background
        GradientDrawable bg = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{ 0xFF060A10, 0xFF0C1220, 0xFF060A10 });
        root.setBackground(bg);

        // Soft electric-blue radial glow behind the centre (esports badge vibe)
        View glow = new View(this);
        glow.setTag("glow");
        GradientDrawable glowBg = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{ 0x55C0003A, 0x22C0003A, 0x00C0003A });
        glowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowBg.setGradientRadius(dp(240));
        glow.setBackground(glowBg);
        RelativeLayout.LayoutParams glp = new RelativeLayout.LayoutParams(dp(460), dp(460));
        glp.addRule(RelativeLayout.CENTER_IN_PARENT);
        glow.setLayoutParams(glp);
        glow.setAlpha(0f);
        root.addView(glow);

        // ── Center column: logo on top, title below — vertical LinearLayout ──
        android.widget.LinearLayout center = new android.widget.LinearLayout(this);
        center.setOrientation(android.widget.LinearLayout.VERTICAL);
        center.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        RelativeLayout.LayoutParams clp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.addRule(RelativeLayout.CENTER_IN_PARENT);
        center.setLayoutParams(clp);

        // Logo — framed in the concentric electric ring for a premium, consistent look
        android.widget.FrameLayout logoFrame = new android.widget.FrameLayout(this);
        logoFrame.setTag("logo");
        // BeastCrown logo already has its own ring
        android.widget.LinearLayout.LayoutParams framelp =
            new android.widget.LinearLayout.LayoutParams(dp(216), dp(216));
        framelp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        logoFrame.setLayoutParams(framelp);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(30);
        logo.setPadding(pad, pad, pad, pad);
        logoFrame.addView(logo, new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        logoFrame.setAlpha(0f);
        logoFrame.setScaleX(0.7f);
        logoFrame.setScaleY(0.7f);
        center.addView(logoFrame);

        // Title BELOW logo — LinearLayout guarantees no overlap
        TextView title = new TextView(this);
        title.setTag("title");
        try { title.setTypeface(ResourcesCompat.getFont(this, R.font.pacifico)); }
        catch (Exception ignored) {}
        title.setTextSize(38);
        title.setGravity(android.view.Gravity.CENTER);
        title.setShadowLayer(30f, 0f, 0f, 0xFFC0003A);
        SpannableString ss = new SpannableString("ZERO KILL");
        ss.setSpan(new ForegroundColorSpan(0xFFC0003A), 0, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new ForegroundColorSpan(0xFFFFFFFF), 4, 9, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(ss);
        title.setLetterSpacing(0.06f);
        title.setAlpha(0f);
        title.setTranslationY(dp(20));
        android.widget.LinearLayout.LayoutParams titlelp =
            new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titlelp.topMargin = dp(30);   // gap between logo bottom and title
        title.setLayoutParams(titlelp);
        center.addView(title);

        root.addView(center);

        // ── Modern loading dots near bottom ──
        android.widget.LinearLayout dots = new android.widget.LinearLayout(this);
        dots.setTag("dots");
        dots.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        dots.setGravity(android.view.Gravity.CENTER);
        dots.setAlpha(0f);
        RelativeLayout.LayoutParams dotslp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotslp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        dotslp.bottomMargin = dp(64);
        dots.setLayoutParams(dotslp);
        for (int i = 0; i < 3; i++) {
            View dot = new View(this);
            dot.setTag("dot" + i);
            GradientDrawable dd = new GradientDrawable();
            dd.setShape(GradientDrawable.OVAL);
            dd.setColor(0xFF00E5FF);
            dot.setBackground(dd);
            android.widget.LinearLayout.LayoutParams dlp =
                new android.widget.LinearLayout.LayoutParams(dp(8), dp(8));
            dlp.leftMargin = dp(5); dlp.rightMargin = dp(5);
            dot.setLayoutParams(dlp);
            dots.addView(dot);
        }
        root.addView(dots);

        return root;
    }

    // ── Animation Sequence ───────────────────────────────────────────────
    private void runSplashSequence(RelativeLayout root) {
        View glow  = root.findViewWithTag("glow");
        View logo  = root.findViewWithTag("logo");
        View title = root.findViewWithTag("title");
        View dots  = root.findViewWithTag("dots");

        // Glow: gentle fade-in + slow breathing pulse
        ObjectAnimator glowIn = ObjectAnimator.ofFloat(glow, "alpha", 0f, 1f);
        glowIn.setDuration(900);
        glowIn.setInterpolator(new DecelerateInterpolator());
        ObjectAnimator glowPulse = ObjectAnimator.ofFloat(glow, "scaleX", 0.92f, 1.08f);
        ObjectAnimator glowPulseY = ObjectAnimator.ofFloat(glow, "scaleY", 0.92f, 1.08f);
        for (ObjectAnimator a : new ObjectAnimator[]{glowPulse, glowPulseY}) {
            a.setDuration(2200);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.setInterpolator(new AccelerateDecelerateInterpolator());
            a.start();
        }

        // Logo: smooth fade + scale up (decelerate, no cartoonish overshoot)
        ObjectAnimator logoAlpha = ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f);
        ObjectAnimator logoSx    = ObjectAnimator.ofFloat(logo, "scaleX", 0.7f, 1f);
        ObjectAnimator logoSy    = ObjectAnimator.ofFloat(logo, "scaleY", 0.7f, 1f);
        logoAlpha.setDuration(700);
        logoSx.setDuration(820); logoSy.setDuration(820);
        logoSx.setInterpolator(new DecelerateInterpolator(2.2f));
        logoSy.setInterpolator(new DecelerateInterpolator(2.2f));
        logoAlpha.setStartDelay(200);
        logoSx.setStartDelay(200); logoSy.setStartDelay(200);

        // Logo: gentle floating bob once settled (subtle, premium)
        ObjectAnimator logoFloat = ObjectAnimator.ofFloat(logo, "translationY", -dp(6), dp(6));
        logoFloat.setDuration(2600);
        logoFloat.setRepeatCount(ValueAnimator.INFINITE);
        logoFloat.setRepeatMode(ValueAnimator.REVERSE);
        logoFloat.setInterpolator(new AccelerateDecelerateInterpolator());
        logoFloat.setStartDelay(1000);

        // Title: fade + slide up
        ObjectAnimator titleA = ObjectAnimator.ofFloat(title, "alpha", 0f, 1f);
        ObjectAnimator titleT = ObjectAnimator.ofFloat(title, "translationY", dp(20), 0f);
        titleA.setDuration(560); titleT.setDuration(560);
        titleA.setStartDelay(760); titleT.setStartDelay(760);
        titleA.setInterpolator(new DecelerateInterpolator(1.6f));
        titleT.setInterpolator(new DecelerateInterpolator(1.6f));

        // Dots: fade in then loop a travelling pulse
        ObjectAnimator dotsA = ObjectAnimator.ofFloat(dots, "alpha", 0f, 1f);
        dotsA.setDuration(400);
        dotsA.setStartDelay(1100);

        AnimatorSet master = new AnimatorSet();
        master.playTogether(glowIn, logoAlpha, logoSx, logoSy, titleA, titleT, dotsA);
        master.start();
        logoFloat.start();

        // Travelling dot pulse loop
        final android.widget.LinearLayout dotsRow = (android.widget.LinearLayout) dots;
        handler.postDelayed(new Runnable() {
            int i = 0;
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                for (int k = 0; k < dotsRow.getChildCount(); k++) {
                    View d = dotsRow.getChildAt(k);
                    boolean on = (k == i);
                    d.animate().alpha(on ? 1f : 0.35f)
                        .scaleX(on ? 1.4f : 1f).scaleY(on ? 1.4f : 1f)
                        .setDuration(240).start();
                }
                i = (i + 1) % dotsRow.getChildCount();
                handler.postDelayed(this, 260);
            }
        }, 1300);

        handler.postDelayed(() -> fadeOutAndGo(root), 2700);
    }

    private void fadeOutAndGo(View root) {
        if (isFinishing() || isDestroyed()) return;
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(root, "alpha", 1f, 0f);
        fadeOut.setDuration(350);
        fadeOut.setInterpolator(new AccelerateDecelerateInterpolator());
        fadeOut.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                goLogin();
            }
        });
        fadeOut.start();
    }

    private void goLogin() {
        if (isFinishing() || isDestroyed()) return;
        startActivity(new Intent(this, LoginActivity.class));
        overridePendingTransition(R.anim.slide_up_enter, R.anim.splash_exit);
        finish();
    }

    // ── Drawable helpers ─────────────────────────────────────────────────
    private int dp(float v) {
        return (int) android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, v,
            getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
