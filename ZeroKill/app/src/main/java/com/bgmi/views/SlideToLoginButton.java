package com.bgmi.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

public class SlideToLoginButton extends View {

    public interface OnSlideCompleteListener {
        void onSlideComplete();
    }

    private Paint trackPaint, thumbPaint, thumbGlowPaint, textPaint, shimmerPaint;
    private RectF trackRect = new RectF();
    private RectF thumbRect = new RectF();

    private float thumbX = 0f;
    private float thumbRadius = 0f;
    private float touchStartX = 0f;
    private float trackPadding = 4f;
    private boolean isSliding = false;
    private boolean completed = false;

    private float shimmerX = -1f;
    private ValueAnimator shimmerAnim;
    private ValueAnimator successAnim;

    private OnSlideCompleteListener listener;

    // Purple theme colors
    private static final int COLOR_TRACK_START = 0x1A6600CC;
    private static final int COLOR_TRACK_END   = 0x1AA020FF;
    private static final int COLOR_THUMB_START = 0xFFCC5500;
    private static final int COLOR_THUMB_END   = 0xFFFF8C00;
    private static final int COLOR_GLOW        = 0x55C060FF;
    private static final int COLOR_TEXT        = 0xFFFFAA44;
    private static final int COLOR_STROKE      = 0x66A020FF;

    public SlideToLoginButton(Context context) {
        super(context);
        init();
    }

    public SlideToLoginButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SlideToLoginButton(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setStyle(Paint.Style.FILL);

        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setStyle(Paint.Style.FILL);

        thumbGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbGlowPaint.setStyle(Paint.Style.FILL);
        thumbGlowPaint.setColor(COLOR_GLOW);
        thumbGlowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(
            dp(12), android.graphics.BlurMaskFilter.Blur.NORMAL));

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(COLOR_TEXT);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(sp(13));
        textPaint.setFakeBoldText(true);
        textPaint.setLetterSpacing(0.12f);

        shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shimmerPaint.setStyle(Paint.Style.FILL);

        startShimmer();
    }

    private void startShimmer() {
        shimmerAnim = ValueAnimator.ofFloat(-0.3f, 1.3f);
        shimmerAnim.setDuration(2000);
        shimmerAnim.setRepeatCount(ValueAnimator.INFINITE);
        shimmerAnim.setRepeatMode(ValueAnimator.RESTART);
        shimmerAnim.setStartDelay(800);
        shimmerAnim.addUpdateListener(a -> {
            shimmerX = (float) a.getAnimatedValue();
            invalidate();
        });
        shimmerAnim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float pad = dp(4);
        thumbRadius = (h - pad * 2) / 2f;
        trackRect.set(0, 0, w, h);
        thumbX = thumbRadius + pad;

        // Update paint shaders now we know size
        updateThumbShader();
        updateTrackShader(w, h);
    }

    private void updateTrackShader(int w, int h) {
        LinearGradient trackGrad = new LinearGradient(0, 0, w, 0,
            new int[]{0x1A6600CC, 0x22A020FF, 0x1A6600CC},
            new float[]{0f, 0.5f, 1f},
            Shader.TileMode.CLAMP);
        trackPaint.setShader(trackGrad);
    }

    private void updateThumbShader() {
        float cx = thumbX;
        float cy = getHeight() / 2f;
        android.graphics.RadialGradient rg = new android.graphics.RadialGradient(
            cx, cy, thumbRadius,
            new int[]{0xFFFFAA44, 0xFFFF8C00, 0xFFCC5500},
            new float[]{0f, 0.5f, 1f},
            Shader.TileMode.CLAMP);
        thumbPaint.setShader(rg);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float pad = dp(4);
        float cornerRadius = h / 2f;

        // Draw track background
        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1));
        strokePaint.setColor(COLOR_STROKE);
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint);
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, strokePaint);

        // Draw shimmer on text area (only when not sliding much)
        if (shimmerX >= 0 && thumbX < w * 0.3f && !completed) {
            float shimmerCenter = shimmerX * w;
            LinearGradient sg = new LinearGradient(
                shimmerCenter - dp(40), 0, shimmerCenter + dp(40), 0,
                new int[]{0x00D080FF, 0x40D080FF, 0x00D080FF},
                null, Shader.TileMode.CLAMP);
            shimmerPaint.setShader(sg);
            canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, shimmerPaint);
        }

        // Draw filled progress behind thumb
        if (thumbX > thumbRadius + pad + dp(2)) {
            RectF fill = new RectF(0, 0, thumbX, h);
            Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            LinearGradient fg = new LinearGradient(0, 0, thumbX, 0,
                new int[]{0x336600CC, 0x44A020FF},
                null, Shader.TileMode.CLAMP);
            fillPaint.setShader(fg);
            canvas.drawRoundRect(fill, cornerRadius, cornerRadius, fillPaint);
        }

        // Draw "SLIDE TO LOGIN" text (fades out as user slides)
        if (!completed) {
            float progress = (thumbX - thumbRadius - pad) / (w - 2 * thumbRadius - 2 * pad);
            float alpha = Math.max(0f, 1f - progress * 2.5f);
            textPaint.setAlpha((int)(255 * alpha));
            // text centered in the remaining right area
            float textX = (thumbX + w) / 2f;
            float textY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText("SLIDE TO LOGIN", textX, textY, textPaint);
            textPaint.setAlpha(255);
        } else {
            // Draw "UNLOCKED" when complete
            textPaint.setColor(0xFFFF8C00);
            float textY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText("✓  LOGGING IN...", w / 2f, textY, textPaint);
            textPaint.setColor(COLOR_TEXT);
        }

        // Draw thumb glow
        float cy = h / 2f;
        canvas.drawCircle(thumbX, cy, thumbRadius + dp(6), thumbGlowPaint);

        // Draw thumb
        updateThumbShader();
        canvas.drawCircle(thumbX, cy, thumbRadius, thumbPaint);

        // Draw arrow icon on thumb
        Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(0xFFFFFFFF);
        arrowPaint.setTextSize(sp(16));
        arrowPaint.setTextAlign(Paint.Align.CENTER);
        arrowPaint.setFakeBoldText(true);
        float arrowY = cy - (arrowPaint.descent() + arrowPaint.ascent()) / 2f;
        canvas.drawText("›", thumbX, arrowY, arrowPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (completed || !isEnabled()) return true;
        float pad = dp(4);
        float maxX = getWidth() - thumbRadius - pad;

        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Only start if touching thumb
                if (Math.abs(e.getX() - thumbX) < thumbRadius * 2) {
                    isSliding = true;
                    touchStartX = e.getX() - thumbX;
                    if (shimmerAnim != null) shimmerAnim.cancel();
                    return true;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (isSliding) {
                    thumbX = Math.min(maxX, Math.max(thumbRadius + pad, e.getX() - touchStartX));
                    invalidate();
                    // Check if completed
                    if (thumbX >= maxX - dp(4)) {
                        onSlideCompleted();
                    }
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isSliding && !completed) {
                    isSliding = false;
                    snapBack();
                }
                break;
        }
        return true;
    }

    private void onSlideCompleted() {
        if (completed) return;
        completed = true;
        isSliding = false;
        // Animate thumb to fill track
        ValueAnimator fill = ValueAnimator.ofFloat(thumbX, getWidth() - dp(4));
        fill.setDuration(200);
        fill.setInterpolator(new DecelerateInterpolator());
        fill.addUpdateListener(a -> {
            thumbX = (float) a.getAnimatedValue();
            invalidate();
        });
        fill.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (listener != null) {
                    postDelayed(() -> listener.onSlideComplete(), 150);
                }
            }
        });
        fill.start();
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void snapBack() {
        float startX = thumbX;
        float endX = thumbRadius + dp(4);
        ValueAnimator anim = ValueAnimator.ofFloat(startX, endX);
        anim.setDuration(350);
        anim.setInterpolator(new DecelerateInterpolator(2f));
        anim.addUpdateListener(a -> {
            thumbX = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                startShimmer();
            }
        });
        anim.start();
    }

    public void reset() {
        completed = false;
        thumbX = thumbRadius + dp(4);
        invalidate();
        startShimmer();
    }

    public void setOnSlideCompleteListener(OnSlideCompleteListener l) {
        this.listener = l;
    }

    private float dp(float v) {
        return v * getContext().getResources().getDisplayMetrics().density;
    }
    private float sp(float v) {
        return v * getContext().getResources().getDisplayMetrics().scaledDensity;
    }
}
