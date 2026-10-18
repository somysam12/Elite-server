package com.bgmi;

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
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
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

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;


@SuppressWarnings({"unused","deprecation"})
public class OverlayService extends Service {

    static final int ORANGE        = Color.parseColor("#FFC021");
    static final int ORANGE_BRIGHT = Color.parseColor("#FFD700");
    static final int ORANGE_DEEP   = Color.parseColor("#3A2000");
    static final int GREEN         = Color.parseColor("#FFC021");
    static final int PANEL_BG      = Color.parseColor("#0A0600");
    static final int SIDE_BG       = Color.parseColor("#0D0800");
    static final int TAB_BG        = Color.parseColor("#140A00");
    static final int TAB_SEL_S     = Color.parseColor("#3A2000");
    static final int TAB_SEL_E     = Color.parseColor("#FFC021");
    static final int CHECK_OFF     = Color.parseColor("#120800");
    static final int TXT           = Color.WHITE;
    static final int TXT_DIM       = Color.parseColor("#A08040");
    static final int CLOSE_BG      = Color.parseColor("#120800");
    static final int INNER_STROKE  = Color.parseColor("#FFC021");
    static final int DIVIDER_COL   = Color.parseColor("#FFC021");
    static final int SECTION_BG    = Color.parseColor("#0E0800");

    static final String TCP_HOST  = "127.0.0.1";
    static final int    TCP_PORT  = 28900;

    private WindowManager wm;
    private View iconRoot;
    private View menuView;
    private WindowManager.LayoutParams iconParams, menuParams;
    private boolean menuOpen = false;  // simple boolean, NO animation complexity

    private LinearLayout contentPanel;
    private LinearLayout tabVisual, tabAim, tabMisc;
    private String currentTab = "VISUAL";
    private Typeface fPoppins, fRegular, fPacifico;
    private Vibrator vibrator;
    // Remember last menu position + tab so reopening restores them
    private int lastMenuX = Integer.MIN_VALUE, lastMenuY = Integer.MIN_VALUE;

    // States — exact same as original working version
    private boolean stLine=false, stSkel=false, stHealth=false, stDist=false,
                    stName=false, stVehicle=false, stCount=false;
    private boolean stBtrack=false, stAimbot=false, stWide=false, stHideEsp=false;
    private boolean stFps120=false;
    private int     stDistance=120, stSmooth=10;
    private boolean stIgnBot=false, stIgnKnocked=false;
    private boolean stItems=false, stGrenade=false;

    @Override public IBinder onBind(Intent i) { return null; }

    // Do NOT let the system auto-restart this service after the app is closed.
    // Default behaviour (START_STICKY) re-creates the service → the floating
    // logo reappears even though the user swiped the app away. START_NOT_STICKY
    // means: once killed, stay killed until the user opens the app again.
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    // Fired when the user swipes the app out of Recents. Tear everything down
    // (logo + menu + ESP canvas) and stop the service so nothing lingers.
    // Some OEMs (Oppo/ColorOS, etc.) otherwise keep the service alive.
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
        startTcpSender();   // open the single persistent command connection
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        addFloatingIcon();
    }

    private void loadFonts() {
        try { fPoppins = Typeface.createFromAsset(getAssets(), "fonts/poppins_medium.ttf"); }
        catch (Exception e) { fPoppins = Typeface.DEFAULT_BOLD; }
        try { fRegular = Typeface.createFromAsset(getAssets(), "fonts/font.ttf"); }
        catch (Exception e) { fRegular = Typeface.DEFAULT; }
        try { fPacifico = Typeface.createFromAsset(getAssets(), "fonts/pacifico.ttf"); }
        catch (Exception e) { fPacifico = Typeface.DEFAULT_BOLD; }
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

    // ── FLOATING ICON — bare A logo only ─────────────────────────────────
    private void addFloatingIcon() {
        ImageView img = new ImageView(this);
        img.setImageResource(res("bc_logo", "drawable"));
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);

        iconParams = new WindowManager.LayoutParams(
                dp(52), dp(52), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        iconParams.gravity = Gravity.TOP | Gravity.START;
        iconParams.x = dp(10); iconParams.y = dp(90);

        img.setOnTouchListener(new View.OnTouchListener() {
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
                            try { wm.updateViewLayout(img,iconParams); } catch(Exception ig){}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging && System.currentTimeMillis()-downTime<300) toggleMenu();
                        return true;
                }
                return false;
            }
        });
        iconRoot = img;
        wm.addView(img, iconParams);
    }

    // ── TOGGLE — simplest possible, NO animation = NO flicker ────────────
    private void toggleMenu() {
        if (menuOpen) {
            closeMenu();
        } else {
            buildMenu();
            menuOpen = true;
        }
    }

    // Short haptic tick on every toggle/tap
    private void vibrate() {
        try {
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE));
            else
                vibrator.vibrate(28);
        } catch (Exception ignored) {}
    }

    private void closeMenu() {
        if (menuView != null) {
            // Remember where the menu was, so it reopens in the same place
            if (menuParams != null) { lastMenuX = menuParams.x; lastMenuY = menuParams.y; }
            try { wm.removeView(menuView); } catch (Exception ignored) {}
            menuView = null;
        }
        menuOpen = false;
    }

    // ── BUILD MENU ───────────────────────────────────────────────────────
    private void buildMenu() {
        // Outer: black, curved r20
        FrameLayout outer = new FrameLayout(this);
        GradientDrawable outerBg = new GradientDrawable();
        outerBg.setCornerRadius(dp(20));
        outerBg.setColor(Color.BLACK);
        outer.setBackground(outerBg);
        outer.setPadding(dp(2), dp(2), dp(2), dp(2));

        // Inner: warm dark + golden outline r18
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable innerBg = new GradientDrawable();
        innerBg.setCornerRadius(dp(18));
        innerBg.setColor(PANEL_BG);
        innerBg.setStroke(dp(1.5f), INNER_STROKE);
        inner.setBackground(innerBg);
        inner.setPadding(dp(8), dp(8), dp(8), dp(8));

        // Drag from anywhere
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

        // ── SIDEBAR ──
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable sideBg = new GradientDrawable();
        sideBg.setCornerRadius(dp(14));
        sideBg.setColor(SIDE_BG);
        sideBg.setStroke(dp(1), INNER_STROKE);
        sidebar.setBackground(sideBg);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(115),
                LinearLayout.LayoutParams.MATCH_PARENT);
        slp.rightMargin = dp(8);
        sidebar.setLayoutParams(slp);
        sidebar.setPadding(dp(8), 0, dp(8), 0);

        // Logo — bare A
        ImageView logoImg = new ImageView(this);
        logoImg.setImageResource(res("bc_logo","drawable"));
        logoImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lwlp = new LinearLayout.LayoutParams(dp(52),dp(52));
        lwlp.gravity = Gravity.CENTER_HORIZONTAL;
        lwlp.topMargin=dp(14); lwlp.bottomMargin=dp(16);
        sidebar.addView(logoImg, lwlp);

        tabVisual = buildSideTab("VISUALS","VISUAL", res("ic_eye_open","drawable"));
        tabAim    = buildSideTab("COMBAT", "AIM",    res("ic_flame","drawable"));
        tabMisc   = buildSideTab("MISC",   "MISC",   res("ic_check","drawable"));
        sidebar.addView(tabVisual);
        sidebar.addView(tabAim);
        sidebar.addView(tabMisc);
        inner.addView(sidebar);

        // ── RIGHT PANEL ──
        LinearLayout rightPanel = new LinearLayout(this);
        rightPanel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setCornerRadius(dp(14));
        panelBg.setColor(PANEL_BG);
        panelBg.setStroke(dp(1), INNER_STROKE);
        rightPanel.setBackground(panelBg);
        rightPanel.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        // Header — GameZone title centered
        FrameLayout header = new FrameLayout(this);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(45)));

        TextView titleTv = new TextView(this);
        SpannableString ss = new SpannableString("BEASTCROWN");
        ss.setSpan(new ForegroundColorSpan(0xFFFFC021), 0, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new ForegroundColorSpan(0xFFFFD700), 5, 10, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        titleTv.setText(ss);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        titleTv.setTypeface(fPacifico);
        titleTv.setShadowLayer(14f, 0f, 0f, 0xFFC02180);   // gold glow
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setPadding(0, 0, dp(8), 0);
        titleTv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        header.addView(titleTv);

        // Close
        FrameLayout closeBtn = new FrameLayout(this);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setCornerRadius(dp(6)); cbg.setColor(CLOSE_BG);
        closeBtn.setBackground(cbg);
        TextView closeX = new TextView(this);
        closeX.setText("✕"); closeX.setTextColor(TXT_DIM);
        closeX.setTextSize(TypedValue.COMPLEX_UNIT_SP,13);
        closeX.setGravity(Gravity.CENTER);
        closeBtn.addView(closeX, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        closeBtn.setOnClickListener(v -> closeMenu());
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(30),dp(30));
        clp.gravity = Gravity.CENTER_VERTICAL|Gravity.END;
        clp.rightMargin = dp(12);
        header.addView(closeBtn, clp);
        rightPanel.addView(header);

        // Divider
        View div = new View(this);
        div.setBackgroundColor(DIVIDER_COL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dlp.setMargins(dp(14),0,dp(14),0);
        div.setLayoutParams(dlp);
        rightPanel.addView(div);

        // Scroll
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        contentPanel = new LinearLayout(this);
        contentPanel.setOrientation(LinearLayout.VERTICAL);
        contentPanel.setPadding(dp(12),dp(10),dp(12),dp(12));
        scroll.addView(contentPanel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        rightPanel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        inner.addView(rightPanel);

        outer.addView(inner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        menuParams = new WindowManager.LayoutParams(
                dp(433), dp(272), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        menuParams.gravity = Gravity.TOP|Gravity.START;
        // Restore last position if menu was moved+closed before; else place near icon
        if (lastMenuX != Integer.MIN_VALUE) {
            menuParams.x = lastMenuX;
            menuParams.y = lastMenuY;
        } else {
            menuParams.x = iconParams.x+dp(58);
            menuParams.y = iconParams.y;
        }

        menuView = outer;
        wm.addView(menuView, menuParams);
        selectTab(currentTab);

        // If HideESP was already ON, re-apply skip-screenshot to this freshly
        // created menu surface (posted so the window's SurfaceControl exists).
        if (stHideEsp) menuView.post(() -> updateHideEspWindows(true));
    }

    // ── SIDEBAR TAB ──────────────────────────────────────────────────────
    private LinearLayout buildSideTab(String label, String key, int iconRes) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10),0,dp(10),0);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        rlp.bottomMargin = dp(8);
        row.setLayoutParams(rlp);

        FrameLayout iconWrap = new FrameLayout(this);
        iconWrap.setTag("iw");
        ImageView icon = new ImageView(this);
        if (iconRes != 0) icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setColorFilter(TXT, android.graphics.PorterDuff.Mode.SRC_IN);
        FrameLayout.LayoutParams iilp = new FrameLayout.LayoutParams(dp(18),dp(18));
        iilp.gravity = Gravity.CENTER;
        iconWrap.addView(icon, iilp);
        row.addView(iconWrap, new LinearLayout.LayoutParams(dp(24),dp(24)));

        TextView tv = new TextView(this);
        tv.setText(label); tv.setTextColor(TXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP,9);
        tv.setTypeface(fPoppins,Typeface.BOLD); tv.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams tvlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvlp.leftMargin = dp(8);
        row.addView(tv,tvlp);
        row.setOnClickListener(v -> selectTab(key));
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
        if (row==null) return;
        GradientDrawable bg;
        if (active) {
            bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{TAB_SEL_S,TAB_SEL_E});
            bg.setCornerRadius(dp(8));
            View iw = row.findViewWithTag("iw");
            if (iw!=null) {
                GradientDrawable iwBg = new GradientDrawable();
                iwBg.setCornerRadius(dp(4));
                iwBg.setColor(Color.parseColor("#332E7CFF"));
                iw.setBackground(iwBg);
            }
        } else {
            bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(TAB_BG);
            View iw = row.findViewWithTag("iw");
            if (iw!=null) iw.setBackground(null);
        }
        row.setBackground(bg);
    }

    // ── SECTION / COL HEADER ─────────────────────────────────────────────
    private void sectionHeader(LinearLayout parent, String text) {
        addHeader(parent, text, dp(4), dp(12));
    }
    private void colHeader(LinearLayout col, String text) {
        addHeader(col, text, dp(4), dp(8));
    }
    private void addHeader(LinearLayout parent, String text, int topM, int botM) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextColor(ORANGE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP,11);
        t.setTypeface(fPoppins,Typeface.BOLD); t.setLetterSpacing(0.12f);
        t.setGravity(Gravity.CENTER); t.setPadding(0,dp(8),0,dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));                    // all corners rounded
        bg.setColor(SECTION_BG);
        bg.setStroke(dp(1.5f), ORANGE);                // visible orange outline
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin=topM; lp.bottomMargin=botM;
        t.setLayoutParams(lp);
        parent.addView(t);
    }

    // ── CHECKBOX ROW — original style: box LEFT, label RIGHT ─────────────
    // Exact same as original working checkRow
    private void checkRow(LinearLayout parent, String label, boolean initial, final CB cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(6), dp(2), dp(6));
        row.setClickable(true);
        row.setFocusable(true);

        // Orange checkbox box
        final TextView box = new TextView(this);
        box.setGravity(Gravity.CENTER);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        box.setClickable(false);
        box.setFocusable(false);
        box.setDuplicateParentStateEnabled(true);
        applyCheck(box, initial);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(18), dp(18));
        blp.rightMargin = dp(10);
        row.addView(box, blp);

        TextView name = new TextView(this);
        name.setText(label); name.setTextColor(TXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        name.setTypeface(fRegular);
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

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rlp);
        parent.addView(row);
    }

    // Returns box TextView for exclusive toggles (Aim tab)
    private TextView checkRowRet(LinearLayout parent, String label, boolean initial, final CB cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(6), dp(2), dp(6));
        row.setClickable(true);
        row.setFocusable(true);

        final TextView box = new TextView(this);
        box.setGravity(Gravity.CENTER);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        box.setClickable(false);
        box.setFocusable(false);
        applyCheck(box, initial);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(18), dp(18));
        blp.rightMargin = dp(10);
        row.addView(box, blp);

        TextView name = new TextView(this);
        name.setText(label); name.setTextColor(TXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        name.setTypeface(fRegular);
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

    // Red filled when checked, dark bg when unchecked
    private void applyCheck(TextView box, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(5));
        if (on) {
            bg.setColor(ORANGE);
            bg.setStroke(dp(1.5f), Color.parseColor("#7FB0FF"));
            box.setText("✔");
            box.setTextColor(Color.WHITE);
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            box.setTypeface(null, Typeface.BOLD);
        } else {
            bg.setColor(CHECK_OFF);
            bg.setStroke(dp(1.5f), Color.parseColor("#1A2848"));
            box.setText("");
        }
        box.setBackground(bg);
    }

    // ── SLIDER — slim modern pill track + clean circular thumb ────────────
    private SeekBar makeSeekBar(int min, int max, int progress) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max-min); sb.setProgress(progress-min);
        sb.setSplitTrack(false);                 // no notch/square carved around the thumb
        sb.setBackground(null);                   // drop default ripple/selector square
        // inset so the round thumb never clips at the two ends
        sb.setPadding(dp(9), 0, dp(9), 0);

        // ── Track: slim rounded pill (unfilled) + bright red→orange fill ──
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setShape(GradientDrawable.RECTANGLE);
        trackBg.setCornerRadius(dp(100));                         // full pill
        trackBg.setColor(Color.parseColor("#132038"));           // unfilled groove
        GradientDrawable fillShape = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ Color.parseColor("#2E7CFF"), Color.parseColor("#5AA0FF") });
        fillShape.setCornerRadius(dp(100));
        android.graphics.drawable.ClipDrawable clip =
            new android.graphics.drawable.ClipDrawable(fillShape,
                Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable track =
            new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{ trackBg, clip });
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.progress);
        // Keep the visible bar SLIM (6dp) and vertically centred inside the
        // taller touch area — this is what makes it "patla" instead of a fat block.
        final int th = dp(6);
        track.setLayerHeight(0, th); track.setLayerGravity(0, Gravity.CENTER_VERTICAL);
        track.setLayerHeight(1, th); track.setLayerGravity(1, Gravity.CENTER_VERTICAL);
        sb.setProgressDrawable(track);

        // ── Thumb: solid red circle with a white ring (perfectly round) ──
        GradientDrawable thumbNormal = new GradientDrawable();
        thumbNormal.setShape(GradientDrawable.OVAL);
        thumbNormal.setColor(Color.parseColor("#2E7CFF"));
        thumbNormal.setStroke(dp(2.5f), Color.WHITE);
        thumbNormal.setSize(dp(15), dp(15));

        GradientDrawable thumbPressed = new GradientDrawable();
        thumbPressed.setShape(GradientDrawable.OVAL);
        thumbPressed.setColor(Color.parseColor("#5AA0FF"));
        thumbPressed.setStroke(dp(2.5f), Color.WHITE);
        thumbPressed.setSize(dp(18), dp(18));    // subtle grow on press

        android.graphics.drawable.StateListDrawable thumbSL =
                new android.graphics.drawable.StateListDrawable();
        thumbSL.addState(new int[]{android.R.attr.state_pressed}, thumbPressed);
        thumbSL.addState(new int[]{}, thumbNormal);

        sb.setThumb(thumbSL);
        sb.setThumbOffset(0);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24));   // touch area (track stays 6dp)
        lp.topMargin=dp(2); lp.bottomMargin=dp(12);
        sb.setLayoutParams(lp);
        return sb;
    }

    private TextView makeSliderLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextColor(TXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP,12);
        t.setTypeface(fRegular);
        t.setPadding(dp(2),dp(6),0,dp(2)); t.setAlpha(0.85f);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    // Action button (Ignore Bot / Ignore Knocked)
    private TextView makeActionBtn(String label, boolean active) {
        TextView t = new TextView(this);
        t.setText(label); t.setTextColor(TXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP,12);
        t.setTypeface(fRegular); t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10),dp(10),dp(10),dp(10));
        styleActionBtn(t,active); return t;
    }
    private void styleActionBtn(TextView t, boolean active) {
        GradientDrawable bg;
        if (active) {
            bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{TAB_SEL_S,TAB_SEL_E});
            bg.setCornerRadius(dp(10));
        } else {
            bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(TAB_BG);
            bg.setStroke(dp(1), DIVIDER_COL);
        }
        t.setBackground(bg);
    }

    interface CB { void onToggle(boolean on); }

    // ── VISUAL TAB — exact original TCP commands ──────────────────────────
    private void buildVisual() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout colL = newCol(dp(10),dp(7));
        LinearLayout colR = newCol(dp(7),dp(10));

        colHeader(colL,"ESP Visuals");
        checkRow(colL,"Line",     stLine,    on->{ stLine=on;    sendTcp("line="+(on?1:0)); });
        checkRow(colL,"Skeleton", stSkel,    on->{ stSkel=on;    sendTcp("skeleton="+(on?1:0)); });
        checkRow(colL,"Health",   stHealth,  on->{ stHealth=on;  sendTcp("health="+(on?1:0)); });
        checkRow(colL,"Distance", stDist,    on->{ stDist=on;    sendTcp("distance="+(on?1:0)); });
        checkRow(colL,"Player Name",stName,  on->{ stName=on;    sendTcp("name="+(on?1:0)); });

        colHeader(colR,"World Visuals");
        checkRow(colR,"Vehicles",      stVehicle, on->{ stVehicle=on; sendTcp("vehicle="+(on?1:0)); });
        checkRow(colR,"Enemy Counter", stCount,   on->{ stCount=on;   sendTcp("count="+(on?1:0)); });
        
        

        grid.addView(colL); grid.addView(colR);
        contentPanel.addView(grid);
    }

    // ── AIM TAB ───────────────────────────────────────────────────────────
    private void buildAim() {
        sectionHeader(contentPanel,"COMBAT FEATURES");

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView[] boxes = new TextView[2];
        boxes[0] = checkRowRet(col,"Bullet Track", stBtrack, on->{
            stBtrack=on; sendTcp("btrack="+(on?1:0));
            if(on){ stAimbot=false; applyCheck(boxes[1],false); }
        });
        boxes[1] = checkRowRet(col,"Aimbot", stAimbot, on->{
            stAimbot=on; sendTcp("aimbot="+(on?1:0));
            if(on){ stBtrack=false; applyCheck(boxes[0],false); }
        });

        // Distance slider
        TextView distLbl = makeSliderLabel("Distance: ("+stDistance+" m)");
        col.addView(distLbl);
        SeekBar distBar = makeSeekBar(5,150,stDistance);
        distBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean u){
                stDistance=p+5; distLbl.setText("Distance: ("+stDistance+" m)");
                sendTcp("btrange="+stDistance);
            }
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });
        col.addView(distBar);

        // Smoothness slider
        TextView smLbl = makeSliderLabel("Aimbot Smoothness: ("+stSmooth+" m)");
        col.addView(smLbl);
        SeekBar smBar = makeSeekBar(1,50,stSmooth);
        smBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean u){
                stSmooth=p+1; smLbl.setText("Aimbot Smoothness: ("+stSmooth+" m)");
                sendTcp("smooth="+stSmooth);
            }
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });
        col.addView(smBar);

        // Ignore buttons
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brlp.topMargin=dp(10); btnRow.setLayoutParams(brlp);

        TextView btnBot     = makeActionBtn("Ignore Bot",     stIgnBot);
        TextView btnKnocked = makeActionBtn("Ignore Knocked", stIgnKnocked);
        LinearLayout.LayoutParams bl1 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT,1f);
        bl1.rightMargin=dp(8); btnBot.setLayoutParams(bl1);
        btnKnocked.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT,1f));

        btnBot.setOnClickListener(v->{
            stIgnBot=!stIgnBot; styleActionBtn(btnBot,stIgnBot);
            vibrate();
            sendTcp("ignorebot="+(stIgnBot?1:0));
        });
        btnKnocked.setOnClickListener(v->{
            stIgnKnocked=!stIgnKnocked; styleActionBtn(btnKnocked,stIgnKnocked);
            vibrate();
            sendTcp("ignoreknocked="+(stIgnKnocked?1:0));
        });
        btnRow.addView(btnBot); btnRow.addView(btnKnocked);
        col.addView(btnRow);
        // extra bottom space so buttons never clip against the panel edge
        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(16)));
        contentPanel.addView(col);
    }

    // ── MISC TAB ──────────────────────────────────────────────────────────
    private void buildMisc() {
        sectionHeader(contentPanel,"STABILITY FEATURES");
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout colL = newCol(dp(10),dp(7));
        LinearLayout colR = newCol(dp(7),dp(10));

        checkRow(colL,"120 FPS",  stFps120, on->{ stFps120=on; sendTcp("fps120="+(on?1:0)); });
        checkRow(colR,"IPad View",stWide,   on->{ stWide=on;   sendTcp("wideview="+(on?1:0)); });
        checkRow(colL,"Hide ESP", stHideEsp,on->{ stHideEsp=on; sendTcp("hideesp="+(on?1:0)); updateHideEspWindows(on); });

        grid.addView(colL); grid.addView(colR);
        contentPanel.addView(grid);
    }

    // ── SCREENSHOT PROTECTION for the logo + menu ────────────────────────────
    // Goal (same idea as the native ESP's eSkipScreenshot surface): when HideESP
    // is ON, the logo + menu stay fully visible to the player but are SKIPPED by
    // screenshots / screen-recording / casting — the capture shows the game behind
    // them, NOT a black box.
    //
    // Preferred path: set SurfaceControl.setSkipScreenshot(true) on each overlay
    // window's own surface (this is the "show-behind" behaviour, layer flag 0x40).
    // If that hidden API is unavailable on the device, we fall back to FLAG_SECURE
    // (still hidden from capture, just rendered black) so protection never breaks.
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
        } catch (Throwable ignored) {
            // hidden API blocked on this OS/OEM — fall through to FLAG_SECURE
        }

        // Fallback (only if show-behind wasn't available): FLAG_SECURE hides it as
        // a black region. Applying both would force black even where skip worked,
        // so we only touch the flag when skipScreenshot could not be applied.
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
        col.setPadding(padStart,0,padEnd,0);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT,1f));
        return col;
    }

    // ── TCP — same as original working sendTcp ────────────────────────────
    // ── Persistent TCP: ONE long-lived connection + an ordered command queue ─
    // Old approach opened a fresh socket+thread per toggle. When you tapped fast
    // (2-3 times), those threads raced and commands arrived out of order, so a
    // feature looked like it needed multiple taps. Now a single writer thread
    // drains an ordered queue over one kept-open connection: every tap is
    // delivered exactly once, in order, with almost no overhead.
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
                    s.setTcpNoDelay(true);      // no Nagle delay — send immediately
                    s.setKeepAlive(true);
                    OutputStream out = s.getOutputStream();
                    while (tcpRunning) {
                        String cmd = cmdQueue.take();          // wait for next command
                        try {
                            out.write((cmd + "\n").getBytes("UTF-8"));
                            out.flush();
                        } catch (Exception writeErr) {
                            cmdQueue.offer(cmd);               // reconnect & resend
                            break;
                        }
                    }
                } catch (Exception connErr) {
                    try { Thread.sleep(500); } catch (Exception ignored) {}
                } finally {
                    if (s != null) try { s.close(); } catch (Exception ig) {}
                }
            }
        }, "ArkTcpSender");
        t.setDaemon(true);
        t.start();
    }

    private void sendTcp(final String cmd) {
        cmdQueue.offer(cmd);   // non-blocking enqueue; writer thread delivers it
    }

    @Override public void onDestroy() {
        super.onDestroy();
        cleanupOverlay();
    }
}
