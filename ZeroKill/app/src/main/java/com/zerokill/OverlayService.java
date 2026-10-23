package com.zerokill;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.core.content.res.ResourcesCompat;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

@SuppressWarnings({"unused","deprecation"})
public class OverlayService extends Service {

    // ── Zero Kill Cyber Dragon Palette ──────────────────────────────────────
    static final int BG          = Color.parseColor("#060A10");
    static final int SURFACE     = Color.parseColor("#0C1220");
    static final int SURFACE_ALT = Color.parseColor("#0F1828");
    static final int CARD        = Color.parseColor("#111B2A");
    static final int BORDER      = Color.parseColor("#1E2D42");
    static final int FIRE_DARK   = Color.parseColor("#8B0020");
    static final int FIRE_MID    = Color.parseColor("#C0003A");
    static final int FIRE_BRIGHT = Color.parseColor("#E5004A");
    static final int FIRE_GLOW   = Color.parseColor("#FF1A5C");
    static final int ICE_DARK    = Color.parseColor("#007A99");
    static final int ICE_MID     = Color.parseColor("#00CCFF");
    static final int ICE_BRIGHT  = Color.parseColor("#00E5FF");
    static final int ICE_GLOW    = Color.parseColor("#80F0FF");
    static final int GOLD        = Color.parseColor("#FF7733");
    static final int TXT         = Color.WHITE;
    static final int TXT_DIM     = Color.parseColor("#8899BB");
    static final int TXT_ACCENT  = Color.parseColor("#00E5FF");

    static final String TCP_HOST = "127.0.0.1";
    static final int    TCP_PORT = 28900;

    private WindowManager wm;
    private View iconRoot;
    private View menuView;
    private WindowManager.LayoutParams iconParams, menuParams;
    private boolean menuOpen = false;

    private LinearLayout contentPanel;
    private LinearLayout tabVisual, tabAim, tabMisc;
    private String currentTab = "VISUAL";
    private Typeface fHeading, fBody, fMono;
    private Vibrator vibrator;
    private int lastMenuX = Integer.MIN_VALUE, lastMenuY = Integer.MIN_VALUE;

    private boolean stLine=false, stSkel=false, stHealth=false, stDist=false,
                    stName=false, stVehicle=false, stCount=false;
    private boolean stBtrack=false, stAimbot=false, stWide=false, stHideEsp=false;
    private boolean stFps120=false;
    private int     stDistance=120, stSmooth=10;
    private boolean stIgnBot=false, stIgnKnocked=false;
    private boolean stItems=false, stGrenade=false;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        cleanupOverlay();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    private void cleanupOverlay() {
        tcpRunning = false;
        try { cmdQueue.offer("ping"); } catch (Exception ignored) {}
        if (menuView != null) { try { wm.removeView(menuView); } catch (Exception ig) {} menuView = null; }
        if (iconRoot != null) { try { wm.removeView(iconRoot); } catch (Exception ig) {} iconRoot = null; }
    }

    @Override public void onCreate() {
        super.onCreate();
        loadFonts();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startTcpSender();
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        addFloatingIcon();
    }

    private void loadFonts() {
        try {
            int id = getResources().getIdentifier("rajdhani_bold", "font", getPackageName());
            fHeading = id != 0 ? ResourcesCompat.getFont(this, id) : Typeface.DEFAULT_BOLD;
        } catch (Exception e) { fHeading = Typeface.DEFAULT_BOLD; }
        try {
            int id = getResources().getIdentifier("poppins_medium", "font", getPackageName());
            fBody = id != 0 ? ResourcesCompat.getFont(this, id) : Typeface.DEFAULT;
        } catch (Exception e) { fBody = Typeface.DEFAULT; }
        try {
            int id = getResources().getIdentifier("en_regular", "font", getPackageName());
            fMono = id != 0 ? ResourcesCompat.getFont(this, id) : Typeface.MONOSPACE;
        } catch (Exception e) { fMono = Typeface.MONOSPACE; }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }
    private int res(String name, String type) {
        return getResources().getIdentifier(name, type, getPackageName());
    }

    // ── FLOATING ICON — Zero Kill logo with neon ring ─────────────────────
    private void addFloatingIcon() {
        FrameLayout iconWrap = new FrameLayout(this);

        // Outer glow ring
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(2), ICE_MID);

        // Inner circle bg
        GradientDrawable innerCircle = new GradientDrawable();
        innerCircle.setShape(GradientDrawable.OVAL);
        innerCircle.setColor(BG);
        innerCircle.setStroke(dp(1.5f), FIRE_MID);

        // Stack: outer ring frame → inner bg → logo image
        FrameLayout logoFrame = new FrameLayout(this);
        logoFrame.setBackground(innerCircle);

        ImageView img = new ImageView(this);
        img.setImageResource(res("icon", "drawable"));
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(40), dp(40));
        ilp.gravity = Gravity.CENTER;
        logoFrame.addView(img, ilp);

        // Ring wrapper
        FrameLayout outerRing = new FrameLayout(this);
        outerRing.setBackground(ring);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(54), dp(54));
        rlp.gravity = Gravity.CENTER;
        outerRing.addView(logoFrame, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));

        iconWrap.addView(outerRing, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER));

        iconParams = new WindowManager.LayoutParams(
                dp(58), dp(58), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        iconParams.gravity = Gravity.TOP | Gravity.START;
        iconParams.x = dp(10); iconParams.y = dp(90);

        iconWrap.setOnTouchListener(new View.OnTouchListener() {
            int initX, initY; float tx, ty; boolean dragging; long downTime;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX=iconParams.x; initY=iconParams.y;
                        tx=e.getRawX(); ty=e.getRawY();
                        dragging=false; downTime=System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx=e.getRawX()-tx, dy=e.getRawY()-ty;
                        if (!dragging && (Math.abs(dx)>dp(5)||Math.abs(dy)>dp(5))) dragging=true;
                        if (dragging) {
                            iconParams.x=initX+(int)dx; iconParams.y=initY+(int)dy;
                            try { wm.updateViewLayout(iconWrap,iconParams); } catch(Exception ig){}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging && System.currentTimeMillis()-downTime<300) {
                            vibrate();
                            toggleMenu();
                        }
                        return true;
                }
                return false;
            }
        });
        iconRoot = iconWrap;
        wm.addView(iconWrap, iconParams);
    }

    private void toggleMenu() {
        if (menuOpen) closeMenu();
        else { buildMenu(); menuOpen = true; }
    }

    private void vibrate() {
        try {
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createOneShot(22, VibrationEffect.DEFAULT_AMPLITUDE));
            else
                vibrator.vibrate(22);
        } catch (Exception ignored) {}
    }

    private void closeMenu() {
        if (menuView != null) {
            if (menuParams != null) { lastMenuX = menuParams.x; lastMenuY = menuParams.y; }
            try { wm.removeView(menuView); } catch (Exception ignored) {}
            menuView = null;
        }
        menuOpen = false;
    }

    // ── BUILD MENU — Zero Kill cyber dragon style ────────────────────────
    private void buildMenu() {
        // Outermost: fire gradient border (2dp) → inner dark
        FrameLayout outer = new FrameLayout(this);
        GradientDrawable outerBg = new GradientDrawable(
                GradientDrawable.Orientation.TR_BL,
                new int[]{FIRE_BRIGHT, SURFACE, ICE_DARK});
        outerBg.setCornerRadius(dp(18));
        outer.setBackground(outerBg);
        outer.setPadding(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f));

        // Inner body
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable innerBg = new GradientDrawable();
        innerBg.setCornerRadius(dp(17));
        innerBg.setColor(BG);
        inner.setBackground(innerBg);
        inner.setPadding(0, 0, 0, 0);

        // Drag handle on outer
        outer.setOnTouchListener(new View.OnTouchListener() {
            int initX, initY; float tx, ty; long lastMs=0;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX=menuParams.x; initY=menuParams.y;
                        tx=e.getRawX(); ty=e.getRawY(); return true;
                    case MotionEvent.ACTION_MOVE:
                        long now=System.currentTimeMillis();
                        if(now-lastMs<6) return true; lastMs=now;
                        menuParams.x=initX+(int)(e.getRawX()-tx);
                        menuParams.y=initY+(int)(e.getRawY()-ty);
                        try{ wm.updateViewLayout(menuView,menuParams); }catch(Exception ig){}
                        return true;
                }
                return false;
            }
        });

        // ── SIDEBAR ─────────────────────────────────────────────────────
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable sideBg = new GradientDrawable();
        // Left column: deep surface with subtle fire-red right border
        sideBg.setCornerRadius(dp(17));
        sideBg.setColor(SURFACE);
        sidebar.setBackground(sideBg);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(108),
                LinearLayout.LayoutParams.MATCH_PARENT);
        sidebar.setLayoutParams(slp);
        sidebar.setPadding(dp(6), dp(10), dp(6), dp(10));

        // Logo — Zero Kill icon with neon border ring
        FrameLayout logoBox = new FrameLayout(this);
        GradientDrawable logoBg = new GradientDrawable();
        logoBg.setShape(GradientDrawable.OVAL);
        logoBg.setColor(BG);
        logoBg.setStroke(dp(1.5f), ICE_MID);
        logoBox.setBackground(logoBg);
        ImageView logoImg = new ImageView(this);
        logoImg.setImageResource(res("icon", "drawable"));
        logoImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams lilp = new FrameLayout.LayoutParams(dp(36), dp(36));
        lilp.gravity = Gravity.CENTER;
        logoBox.addView(logoImg, lilp);
        LinearLayout.LayoutParams lblp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lblp.gravity = Gravity.CENTER_HORIZONTAL;
        lblp.bottomMargin = dp(12);
        sidebar.addView(logoBox, lblp);

        // App name under logo
        TextView appName = new TextView(this);
        appName.setText("ZERO\nKILL");
        appName.setTextColor(ICE_BRIGHT);
        appName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        appName.setTypeface(fHeading, Typeface.BOLD);
        appName.setLetterSpacing(0.18f);
        appName.setGravity(Gravity.CENTER);
        appName.setShadowLayer(8f, 0f, 0f, ICE_MID);
        LinearLayout.LayoutParams anlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        anlp.bottomMargin = dp(14);
        sidebar.addView(appName, anlp);

        // Horizontal divider
        View sideDivider = new View(this);
        sideDivider.setBackgroundColor(FIRE_MID);
        LinearLayout.LayoutParams sdlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        sdlp.setMargins(dp(8), 0, dp(8), dp(12));
        sideDivider.setLayoutParams(sdlp);
        sidebar.addView(sideDivider);

        tabVisual = buildSideTab("VISUALS", "VISUAL", res("ic_eye_open", "drawable"));
        tabAim    = buildSideTab("COMBAT",  "AIM",    res("ic_flame",    "drawable"));
        tabMisc   = buildSideTab("MISC",    "MISC",   res("ic_check",    "drawable"));
        sidebar.addView(tabVisual);
        sidebar.addView(tabAim);
        sidebar.addView(tabMisc);
        inner.addView(sidebar);

        // ── RIGHT PANEL ─────────────────────────────────────────────────
        LinearLayout rightPanel = new LinearLayout(this);
        rightPanel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setCornerRadius(dp(17));
        panelBg.setColor(BG);
        rightPanel.setBackground(panelBg);
        rightPanel.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        // ── HEADER BAR ──────────────────────────────────────────────────
        FrameLayout header = new FrameLayout(this);
        GradientDrawable headerBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{FIRE_DARK, BG, BG});
        headerBg.setCornerRadius(dp(17));
        header.setBackground(headerBg);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        header.setLayoutParams(hlp);

        // Title "ZERO KILL"
        TextView titleTv = new TextView(this);
        titleTv.setText("ZERO KILL");
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleTv.setTypeface(fHeading, Typeface.BOLD);
        titleTv.setLetterSpacing(0.22f);
        titleTv.setTextColor(TXT);
        titleTv.setShadowLayer(12f, 0f, 0f, FIRE_BRIGHT);
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        header.addView(titleTv);

        // ICE accent bar under title text (bottom border on header)
        View headLine = new View(this);
        GradientDrawable hlBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.TRANSPARENT, ICE_BRIGHT, Color.TRANSPARENT});
        headLine.setBackground(hlBg);
        FrameLayout.LayoutParams hllp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(1.5f));
        hllp.gravity = Gravity.BOTTOM;
        header.addView(headLine, hllp);

        // Close button — "✕" in fire red
        FrameLayout closeBtn = new FrameLayout(this);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setCornerRadius(dp(6));
        cbg.setColor(SURFACE);
        cbg.setStroke(dp(1), FIRE_MID);
        closeBtn.setBackground(cbg);
        TextView closeX = new TextView(this);
        closeX.setText("✕");
        closeX.setTextColor(FIRE_BRIGHT);
        closeX.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        closeX.setTypeface(fHeading, Typeface.BOLD);
        closeX.setGravity(Gravity.CENTER);
        closeBtn.addView(closeX, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        closeBtn.setOnClickListener(v -> closeMenu());
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(26), dp(26));
        clp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        clp.rightMargin = dp(10);
        header.addView(closeBtn, clp);
        rightPanel.addView(header);

        // Scroll content area
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        contentPanel = new LinearLayout(this);
        contentPanel.setOrientation(LinearLayout.VERTICAL);
        contentPanel.setPadding(dp(10), dp(8), dp(10), dp(10));
        scroll.addView(contentPanel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        rightPanel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        inner.addView(rightPanel);

        outer.addView(inner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        menuParams = new WindowManager.LayoutParams(
                dp(430), dp(268), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        menuParams.gravity = Gravity.TOP | Gravity.START;
        if (lastMenuX != Integer.MIN_VALUE) {
            menuParams.x = lastMenuX;
            menuParams.y = lastMenuY;
        } else {
            menuParams.x = iconParams.x + dp(64);
            menuParams.y = iconParams.y;
        }

        menuView = outer;
        wm.addView(menuView, menuParams);
        selectTab(currentTab);
        if (stHideEsp) menuView.post(() -> updateHideEspWindows(true));
    }

    // ── SIDEBAR TAB — neon-edge selector style ───────────────────────────
    private LinearLayout buildSideTab(String label, String key, int iconRes) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        rlp.bottomMargin = dp(6);
        row.setLayoutParams(rlp);

        // Icon
        FrameLayout iconWrap = new FrameLayout(this);
        iconWrap.setTag("iw");
        ImageView icon = new ImageView(this);
        if (iconRes != 0) {
            icon.setImageResource(iconRes);
            icon.setColorFilter(ICE_BRIGHT, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams iilp = new FrameLayout.LayoutParams(dp(16), dp(16));
        iilp.gravity = Gravity.CENTER;
        iconWrap.addView(icon, iilp);
        row.addView(iconWrap, new LinearLayout.LayoutParams(dp(20), dp(20)));

        // Label
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(TXT_DIM);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tv.setTypeface(fHeading, Typeface.BOLD);
        tv.setLetterSpacing(0.15f);
        LinearLayout.LayoutParams tvlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tvlp.leftMargin = dp(6);
        row.addView(tv, tvlp);
        row.setTag(tv);
        row.setOnClickListener(v -> { vibrate(); selectTab(key); });
        return row;
    }

    private void selectTab(String key) {
        currentTab = key;
        applyTabStyle(tabVisual, key.equals("VISUAL"));
        applyTabStyle(tabAim,    key.equals("AIM"));
        applyTabStyle(tabMisc,   key.equals("MISC"));
        contentPanel.removeAllViews();
        switch (key) {
            case "VISUAL": buildVisual(); break;
            case "AIM":    buildAim();    break;
            case "MISC":   buildMisc();   break;
        }
    }

    private void applyTabStyle(LinearLayout row, boolean active) {
        if (row == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        if (active) {
            // Active: gradient fire dark → fire mid with ice accent left edge
            bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{FIRE_DARK, CARD});
            bg.setCornerRadius(dp(8));
            bg.setStroke(dp(1), FIRE_BRIGHT);
            // Update label color to bright
            if (row.getTag() instanceof TextView) {
                ((TextView) row.getTag()).setTextColor(ICE_BRIGHT);
            }
            // Tint icon ice
            View iw = row.findViewWithTag("iw");
            if (iw instanceof FrameLayout) {
                View child = ((FrameLayout) iw).getChildAt(0);
                if (child instanceof ImageView)
                    ((ImageView) child).setColorFilter(ICE_BRIGHT, android.graphics.PorterDuff.Mode.SRC_IN);
            }
        } else {
            bg.setColor(Color.TRANSPARENT);
            if (row.getTag() instanceof TextView) {
                ((TextView) row.getTag()).setTextColor(TXT_DIM);
            }
            View iw = row.findViewWithTag("iw");
            if (iw instanceof FrameLayout) {
                View child = ((FrameLayout) iw).getChildAt(0);
                if (child instanceof ImageView)
                    ((ImageView) child).setColorFilter(TXT_DIM, android.graphics.PorterDuff.Mode.SRC_IN);
            }
        }
        row.setBackground(bg);
    }

    // ── SECTION HEADER — ice cyan bordered banner ────────────────────────
    private void sectionHeader(LinearLayout parent, String text) {
        addHeader(parent, text, dp(4), dp(10));
    }
    private void colHeader(LinearLayout col, String text) {
        addHeader(col, text, dp(2), dp(8));
    }
    private void addHeader(LinearLayout parent, String text, int topM, int botM) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(ICE_BRIGHT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        t.setTypeface(fHeading, Typeface.BOLD);
        t.setLetterSpacing(0.18f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(6), 0, dp(6));
        t.setShadowLayer(6f, 0f, 0f, ICE_MID);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(SURFACE_ALT);
        bg.setStroke(dp(1.5f), ICE_DARK);
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topM; lp.bottomMargin = botM;
        t.setLayoutParams(lp);
        parent.addView(t);
    }

    // ── CHECKBOX ROW — fire red checkbox with ice border ─────────────────
    private void checkRow(LinearLayout parent, String label, boolean initial, final CB cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(5), dp(2), dp(5));
        row.setClickable(true);
        row.setFocusable(true);

        final TextView box = new TextView(this);
        box.setGravity(Gravity.CENTER);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        box.setClickable(false);
        box.setFocusable(false);
        box.setDuplicateParentStateEnabled(true);
        applyCheck(box, initial);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(18), dp(18));
        blp.rightMargin = dp(8);
        row.addView(box, blp);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(TXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        name.setTypeface(fBody);
        name.setClickable(false);
        name.setFocusable(false);
        name.setDuplicateParentStateEnabled(true);
        row.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final boolean[] st = {initial};
        row.setOnClickListener(v -> {
            st[0] = !st[0];
            applyCheck(box, st[0]);
            vibrate();
            cb.onToggle(st[0]);
        });
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        parent.addView(row);
    }

    private TextView checkRowRet(LinearLayout parent, String label, boolean initial, final CB cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(5), dp(2), dp(5));
        row.setClickable(true);
        row.setFocusable(true);

        final TextView box = new TextView(this);
        box.setGravity(Gravity.CENTER);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        box.setClickable(false);
        box.setFocusable(false);
        applyCheck(box, initial);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(18), dp(18));
        blp.rightMargin = dp(8);
        row.addView(box, blp);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(TXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        name.setTypeface(fBody);
        name.setClickable(false);
        name.setFocusable(false);
        row.addView(name);

        final boolean[] st = {initial};
        row.setOnClickListener(v -> {
            st[0] = !st[0];
            applyCheck(box, st[0]);
            vibrate();
            cb.onToggle(st[0]);
        });
        parent.addView(row);
        return box;
    }

    // Checked: fire crimson fill + ice border; unchecked: dark surface + dim border
    private void applyCheck(TextView box, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(4));
        if (on) {
            bg.setColor(FIRE_MID);
            bg.setStroke(dp(1.5f), ICE_BRIGHT);
            box.setText("✔");
            box.setTextColor(Color.WHITE);
            box.setTypeface(null, Typeface.BOLD);
        } else {
            bg.setColor(SURFACE);
            bg.setStroke(dp(1.5f), BORDER);
            box.setText("");
        }
        box.setBackground(bg);
    }

    // ── SLIDER — fire red fill + ice thumb ───────────────────────────────
    private SeekBar makeSeekBar(int min, int max, int progress) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(progress - min);
        sb.setSplitTrack(false);
        sb.setBackground(null);
        sb.setPadding(dp(9), 0, dp(9), 0);

        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setShape(GradientDrawable.RECTANGLE);
        trackBg.setCornerRadius(dp(100));
        trackBg.setColor(SURFACE);
        GradientDrawable fillShape = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{FIRE_DARK, FIRE_BRIGHT});
        fillShape.setCornerRadius(dp(100));
        android.graphics.drawable.ClipDrawable clip =
                new android.graphics.drawable.ClipDrawable(fillShape,
                        Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable track =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{trackBg, clip});
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.progress);
        final int th = dp(5);
        track.setLayerHeight(0, th); track.setLayerGravity(0, Gravity.CENTER_VERTICAL);
        track.setLayerHeight(1, th); track.setLayerGravity(1, Gravity.CENTER_VERTICAL);
        sb.setProgressDrawable(track);

        GradientDrawable thumbNormal = new GradientDrawable();
        thumbNormal.setShape(GradientDrawable.OVAL);
        thumbNormal.setColor(ICE_MID);
        thumbNormal.setStroke(dp(2f), Color.WHITE);
        thumbNormal.setSize(dp(14), dp(14));

        GradientDrawable thumbPressed = new GradientDrawable();
        thumbPressed.setShape(GradientDrawable.OVAL);
        thumbPressed.setColor(ICE_BRIGHT);
        thumbPressed.setStroke(dp(2f), Color.WHITE);
        thumbPressed.setSize(dp(17), dp(17));

        android.graphics.drawable.StateListDrawable thumbSL =
                new android.graphics.drawable.StateListDrawable();
        thumbSL.addState(new int[]{android.R.attr.state_pressed}, thumbPressed);
        thumbSL.addState(new int[]{}, thumbNormal);

        sb.setThumb(thumbSL);
        sb.setThumbOffset(0);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24));
        lp.topMargin = dp(2); lp.bottomMargin = dp(10);
        sb.setLayoutParams(lp);
        return sb;
    }

    private TextView makeSliderLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TXT_DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(fMono);
        t.setPadding(dp(2), dp(4), 0, dp(2));
        t.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private TextView makeActionBtn(String label, boolean active) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(TXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(fHeading, Typeface.BOLD);
        t.setLetterSpacing(0.08f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(9), dp(8), dp(9));
        styleActionBtn(t, active);
        return t;
    }

    private void styleActionBtn(TextView t, boolean active) {
        GradientDrawable bg;
        if (active) {
            bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{FIRE_DARK, FIRE_MID});
            bg.setCornerRadius(dp(8));
            bg.setStroke(dp(1), FIRE_BRIGHT);
        } else {
            bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(SURFACE);
            bg.setStroke(dp(1), BORDER);
        }
        t.setBackground(bg);
    }

    interface CB { void onToggle(boolean on); }

    // ── VISUAL TAB ────────────────────────────────────────────────────────
    private void buildVisual() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout colL = newCol(dp(8), dp(6));
        LinearLayout colR = newCol(dp(6), dp(8));

        colHeader(colL, "ESP Visuals");
        checkRow(colL, "Line",        stLine,    on->{ stLine=on;    sendTcp("line="+(on?1:0)); });
        checkRow(colL, "Skeleton",    stSkel,    on->{ stSkel=on;    sendTcp("skeleton="+(on?1:0)); });
        checkRow(colL, "Health",      stHealth,  on->{ stHealth=on;  sendTcp("health="+(on?1:0)); });
        checkRow(colL, "Distance",    stDist,    on->{ stDist=on;    sendTcp("distance="+(on?1:0)); });
        checkRow(colL, "Player Name", stName,    on->{ stName=on;    sendTcp("name="+(on?1:0)); });

        colHeader(colR, "World Visuals");
        checkRow(colR, "Vehicles",      stVehicle, on->{ stVehicle=on; sendTcp("vehicle="+(on?1:0)); });
        checkRow(colR, "Enemy Counter", stCount,   on->{ stCount=on;   sendTcp("count="+(on?1:0)); });

        grid.addView(colL); grid.addView(colR);
        contentPanel.addView(grid);
    }

    // ── AIM TAB ───────────────────────────────────────────────────────────
    private void buildAim() {
        sectionHeader(contentPanel, "COMBAT FEATURES");

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView[] boxes = new TextView[2];
        boxes[0] = checkRowRet(col, "Bullet Track", stBtrack, on->{
            stBtrack=on; sendTcp("btrack="+(on?1:0));
            if(on){ stAimbot=false; applyCheck(boxes[1],false); }
        });
        boxes[1] = checkRowRet(col, "Aimbot", stAimbot, on->{
            stAimbot=on; sendTcp("aimbot="+(on?1:0));
            if(on){ stBtrack=false; applyCheck(boxes[0],false); }
        });

        TextView distLbl = makeSliderLabel("Distance: (" + stDistance + " m)");
        col.addView(distLbl);
        SeekBar distBar = makeSeekBar(5, 150, stDistance);
        distBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s, int p, boolean u){
                stDistance=p+5; distLbl.setText("Distance: ("+stDistance+" m)");
                sendTcp("btrange="+stDistance);
            }
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });
        col.addView(distBar);

        TextView smLbl = makeSliderLabel("Aimbot Smoothness: (" + stSmooth + " m)");
        col.addView(smLbl);
        SeekBar smBar = makeSeekBar(1, 50, stSmooth);
        smBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s, int p, boolean u){
                stSmooth=p+1; smLbl.setText("Aimbot Smoothness: ("+stSmooth+" m)");
                sendTcp("smooth="+stSmooth);
            }
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });
        col.addView(smBar);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = dp(8);
        btnRow.setLayoutParams(brlp);

        TextView btnBot     = makeActionBtn("Ignore Bot",     stIgnBot);
        TextView btnKnocked = makeActionBtn("Ignore Knocked", stIgnKnocked);
        LinearLayout.LayoutParams bl1 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bl1.rightMargin = dp(6);
        btnBot.setLayoutParams(bl1);
        btnKnocked.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnBot.setOnClickListener(v -> {
            stIgnBot=!stIgnBot; styleActionBtn(btnBot, stIgnBot);
            vibrate(); sendTcp("ignorebot="+(stIgnBot?1:0));
        });
        btnKnocked.setOnClickListener(v -> {
            stIgnKnocked=!stIgnKnocked; styleActionBtn(btnKnocked, stIgnKnocked);
            vibrate(); sendTcp("ignoreknocked="+(stIgnKnocked?1:0));
        });
        btnRow.addView(btnBot); btnRow.addView(btnKnocked);
        col.addView(btnRow);
        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(14)));
        contentPanel.addView(col);
    }

    // ── MISC TAB ──────────────────────────────────────────────────────────
    private void buildMisc() {
        sectionHeader(contentPanel, "STABILITY FEATURES");
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout colL = newCol(dp(8), dp(6));
        LinearLayout colR = newCol(dp(6), dp(8));

        checkRow(colL, "120 FPS",  stFps120, on->{ stFps120=on; sendTcp("fps120="+(on?1:0)); });
        checkRow(colR, "IPad View",stWide,   on->{ stWide=on;   sendTcp("wideview="+(on?1:0)); });
        checkRow(colL, "Hide ESP", stHideEsp,on->{ stHideEsp=on; sendTcp("hideesp="+(on?1:0)); updateHideEspWindows(on); });

        grid.addView(colL); grid.addView(colR);
        contentPanel.addView(grid);
    }

    // ── SCREENSHOT PROTECTION ─────────────────────────────────────────────
    private void updateHideEspWindows(boolean on) {
        applySkipScreenshot(iconRoot, iconParams, on);
        applySkipScreenshot(menuView, menuParams, on);
    }

    private void applySkipScreenshot(View v, WindowManager.LayoutParams p, boolean on) {
        if (v == null || p == null) return;
        boolean skipApplied = false;
        try {
            Object vri = View.class.getMethod("getViewRootImpl").invoke(v);
            if (vri != null) {
                Object sc = vri.getClass().getMethod("getSurfaceControl").invoke(vri);
                if (sc != null) {
                    Class<?> scClass = Class.forName("android.view.SurfaceControl");
                    Class<?> txClass = Class.forName("android.view.SurfaceControl$Transaction");
                    Object tx = txClass.getConstructor().newInstance();
                    java.lang.reflect.Method setSkip =
                            txClass.getMethod("setSkipScreenshot", scClass, boolean.class);
                    setSkip.invoke(tx, sc, on);
                    txClass.getMethod("apply").invoke(tx);
                    skipApplied = true;
                }
            }
        } catch (Throwable ignored) {}
        if (!skipApplied) {
            final int SECURE = WindowManager.LayoutParams.FLAG_SECURE;
            if (on) p.flags |= SECURE; else p.flags &= ~SECURE;
            try { wm.updateViewLayout(v, p); } catch (Exception ignored) {}
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────
    private LinearLayout newCol(int padStart, int padEnd) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(padStart, 0, padEnd, 0);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return col;
    }

    // ── TCP SENDER ────────────────────────────────────────────────────────
    private final java.util.concurrent.LinkedBlockingQueue<String> cmdQueue =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private volatile boolean tcpRunning = true;

    private void startTcpSender() {
        Thread t = new Thread(() -> {
            while (tcpRunning) {
                Socket s = null;
                try {
                    s = new Socket();
                    s.connect(new InetSocketAddress(TCP_HOST, TCP_PORT), 1500);
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    OutputStream out = s.getOutputStream();
                    while (tcpRunning) {
                        String cmd = cmdQueue.take();
                        try {
                            out.write((cmd + "\n").getBytes("UTF-8"));
                            out.flush();
                        } catch (Exception writeErr) {
                            cmdQueue.offer(cmd);
                            break;
                        }
                    }
                } catch (Exception connErr) {
                    try { Thread.sleep(500); } catch (Exception ignored) {}
                } finally {
                    if (s != null) try { s.close(); } catch (Exception ig) {}
                }
            }
        }, "ZkTcpSender");
        t.setDaemon(true);
        t.start();
    }

    private void sendTcp(final String cmd) {
        cmdQueue.offer(cmd);
    }

    @Override public void onDestroy() {
        super.onDestroy();
        cleanupOverlay();
    }
}
