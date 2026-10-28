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

    // ── XDCheat Elite Palette ────────────────────────────────────────────────
    static final int C_PURPLE      = Color.parseColor("#8E2DE2");
    static final int C_INDIGO      = Color.parseColor("#4A00E0");
    static final int C_PANEL       = Color.parseColor("#0e0e0e");
    static final int C_PANEL_BRD   = Color.parseColor("#1f1f1f");
    static final int C_RAISED      = Color.parseColor("#262626");
    static final int C_SECTION     = Color.parseColor("#1c1c1c");
    static final int C_BTN         = Color.parseColor("#3d3d3d");
    static final int C_TRACK       = Color.parseColor("#3d3d3d");
    static final int C_LABEL       = Color.parseColor("#ececec");
    static final int C_HEAD_TEXT   = Color.parseColor("#9232E0");
    static final int C_WHITE       = Color.WHITE;
    static final int C_DIMMED      = Color.parseColor("#888888");

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

    // State
    private boolean stLine=false, stSkel=false, stHealth=false, stBox=false,
                    stDist=false, stCount=false;
    private boolean stVehicle=false, stItems=false, stGrenade=false;
    private boolean stBtrack=false;
    private boolean stTimeFixer=false, stFps120=false, stWide=false, stHideEsp=false;
    private int     stDistance=120;
    private boolean stIgnBot=false, stIgnKnocked=false;

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
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int res(String name, String type) {
        return getResources().getIdentifier(name, type, getPackageName());
    }

    private void vibrate() {
        if (vibrator == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(18);
        } catch (Exception ignored) {}
    }

    private int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    // ── FLOATING ICON ────────────────────────────────────────────────────────
    private void addFloatingIcon() {
        FrameLayout wrap = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(C_RAISED);
        bg.setStroke(dp(1.5f), C_PURPLE);
        wrap.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageResource(res("icon", "drawable"));
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(36), dp(36));
        ilp.gravity = Gravity.CENTER;
        wrap.addView(img, ilp);

        iconParams = new WindowManager.LayoutParams(
                dp(48), dp(48), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        iconParams.gravity = Gravity.TOP | Gravity.START;
        iconParams.x = dp(20); iconParams.y = dp(200);
        iconRoot = wrap;
        wm.addView(iconRoot, iconParams);

        wrap.setOnTouchListener(new View.OnTouchListener() {
            int initX, initY; float tx, ty; long downMs;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX=iconParams.x; initY=iconParams.y;
                        tx=e.getRawX(); ty=e.getRawY(); downMs=System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        iconParams.x=initX+(int)(e.getRawX()-tx);
                        iconParams.y=initY+(int)(e.getRawY()-ty);
                        try{ wm.updateViewLayout(iconRoot,iconParams); }catch(Exception ig){}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (System.currentTimeMillis()-downMs < 200
                                && Math.abs(e.getRawX()-tx)<10 && Math.abs(e.getRawY()-ty)<10) {
                            vibrate();
                            if (menuOpen) closeMenu(); else { buildMenu(); menuOpen=true; }
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void closeMenu() {
        if (menuView != null) {
            if (menuParams != null) { lastMenuX=menuParams.x; lastMenuY=menuParams.y; }
            try { wm.removeView(menuView); } catch (Exception ignored) {}
            menuView = null;
        }
        menuOpen = false;
    }

    // ── BUILD MENU — XDCheat Elite style ────────────────────────────────────
    private void buildMenu() {
        // Root: panel bg + border radius 17dp (matches 34px / 2)
        FrameLayout outer = new FrameLayout(this);
        GradientDrawable outerBg = new GradientDrawable();
        outerBg.setCornerRadius(dp(17));
        outerBg.setColor(C_PANEL);
        outerBg.setStroke(dp(1.5f), C_PANEL_BRD);
        outer.setBackground(outerBg);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setPadding(dp(9), dp(9), dp(9), dp(9));

        // Drag from outer (non-interactive area)
        outer.setOnTouchListener(new View.OnTouchListener() {
            int initX, initY; float tx, ty;
            boolean dragging = false;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX=menuParams.x; initY=menuParams.y;
                        tx=e.getRawX(); ty=e.getRawY(); dragging=false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx=e.getRawX()-tx, dy=e.getRawY()-ty;
                        if (!dragging && (Math.abs(dx)>4||Math.abs(dy)>4)) dragging=true;
                        if (dragging) {
                            menuParams.x=initX+(int)(e.getRawX()-tx);
                            menuParams.y=initY+(int)(e.getRawY()-ty);
                            try { wm.updateViewLayout(menuView, menuParams); } catch (Exception ig) {}
                        }
                        return dragging;
                }
                return false;
            }
        });

        // ── SIDEBAR (110dp wide, radius 17dp) ────────────────────────────────
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable sideBg = new GradientDrawable();
        sideBg.setCornerRadius(dp(17));
        sideBg.setColor(C_PANEL);
        sideBg.setStroke(dp(1.5f), C_PANEL_BRD);
        sidebar.setBackground(sideBg);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(110),
                LinearLayout.LayoutParams.MATCH_PARENT);
        slp.rightMargin = dp(8);
        sidebar.setLayoutParams(slp);
        sidebar.setPadding(dp(7), dp(7), dp(7), dp(7));

        // Logo box (50dp tall)
        FrameLayout logoBox = new FrameLayout(this);
        GradientDrawable logoBg = new GradientDrawable();
        logoBg.setShape(GradientDrawable.OVAL);
        logoBg.setColor(C_RAISED);
        logoBox.setBackground(logoBg);
        ImageView logoImg = new ImageView(this);
        logoImg.setImageResource(res("icon", "drawable"));
        logoImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams lilp = new FrameLayout.LayoutParams(dp(38), dp(38));
        lilp.gravity = Gravity.CENTER;
        logoBox.addView(logoImg, lilp);
        LinearLayout.LayoutParams lblp = new LinearLayout.LayoutParams(dp(50), dp(50));
        lblp.gravity = Gravity.CENTER_HORIZONTAL;
        lblp.bottomMargin = dp(10);
        lblp.topMargin = dp(6);
        sidebar.addView(logoBox, lblp);

        // Nav items: VISUALS, COMBAT, MISC — Vision_Tw icons
        tabVisual = buildNavItem("VISUALS", "VISUAL", res("esp",      "drawable"));
        tabAim    = buildNavItem("COMBAT",  "AIM",    res("aimbot",   "drawable"));
        tabMisc   = buildNavItem("MISC",    "MISC",   res("security", "drawable"));
        sidebar.addView(tabVisual);
        sidebar.addView(tabAim);
        sidebar.addView(tabMisc);
        inner.addView(sidebar);

        // ── RIGHT PANEL ──────────────────────────────────────────────────────
        LinearLayout rightPanel = new LinearLayout(this);
        rightPanel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setCornerRadius(dp(17));
        panelBg.setColor(C_PANEL);
        panelBg.setStroke(dp(1.5f), C_PANEL_BRD);
        rightPanel.setBackground(panelBg);
        rightPanel.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        // ── HEADER BAR (42dp tall) ───────────────────────────────────────────
        FrameLayout header = new FrameLayout(this);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        header.setLayoutParams(hlp);
        header.setPadding(dp(14), 0, dp(10), 0);

        // Bottom border on header
        View headLine = new View(this);
        headLine.setBackgroundColor(Color.parseColor("#1affffff"));
        FrameLayout.LayoutParams hllp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(1));
        hllp.gravity = Gravity.BOTTOM;
        header.addView(headLine, hllp);

        // Title
        TextView titleTv = new TextView(this);
        titleTv.setText("Vision");
        titleTv.setTextColor(C_WHITE);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        titleTv.setTypeface(fHeading, Typeface.BOLD);
        titleTv.setLetterSpacing(0.21f);
        FrameLayout.LayoutParams ttlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        ttlp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        header.addView(titleTv, ttlp);

        // Close button (30×30dp, radius 5dp)
        FrameLayout closeBtn = new FrameLayout(this);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setCornerRadius(dp(5));
        cbg.setColor(Color.parseColor("#33ffffff"));
        closeBtn.setBackground(cbg);
        TextView closeX = new TextView(this);
        closeX.setText("✕");
        closeX.setTextColor(C_WHITE);
        closeX.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        closeX.setGravity(Gravity.CENTER);
        closeBtn.addView(closeX, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        closeBtn.setOnClickListener(v -> { vibrate(); closeMenu(); });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(30), dp(30));
        clp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        header.addView(closeBtn, clp);
        rightPanel.addView(header);

        // Content scroll area
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        contentPanel = new LinearLayout(this);
        contentPanel.setOrientation(LinearLayout.VERTICAL);
        contentPanel.setPadding(dp(5), dp(10), dp(5), dp(8));
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
                dp(430), dp(280), overlayType(),
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

    // Nav item: 34dp tall, radius 8dp, purple→indigo gradient when active
    private LinearLayout buildNavItem(String label, String key, int iconRes) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(11), 0, dp(8), 0);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        rlp.bottomMargin = dp(7);
        row.setLayoutParams(rlp);
        row.setClickable(true);
        row.setFocusable(true);

        // Icon wrapper (17×17dp, radius 5dp)
        FrameLayout iconWrap = new FrameLayout(this);
        iconWrap.setTag("iw");
        GradientDrawable iwBg = new GradientDrawable();
        iwBg.setCornerRadius(dp(5));
        iwBg.setColor(Color.TRANSPARENT);
        iconWrap.setBackground(iwBg);
        ImageView icon = new ImageView(this);
        if (iconRes != 0) {
            icon.setImageResource(iconRes);
            icon.setColorFilter(C_WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams iilp = new FrameLayout.LayoutParams(dp(11), dp(11));
        iilp.gravity = Gravity.CENTER;
        iconWrap.addView(icon, iilp);
        row.addView(iconWrap, new LinearLayout.LayoutParams(dp(17), dp(17)));

        // Label
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(C_WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7.5f);
        tv.setTypeface(fHeading, Typeface.BOLD);
        tv.setLetterSpacing(0.09f);
        LinearLayout.LayoutParams tvlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tvlp.leftMargin = dp(9);
        row.addView(tv, tvlp);
        row.setTag(tv);
        row.setOnClickListener(v -> { vibrate(); selectTab(key); });
        return row;
    }

    private void selectTab(String key) {
        currentTab = key;
        applyNavStyle(tabVisual, key.equals("VISUAL"));
        applyNavStyle(tabAim,    key.equals("AIM"));
        applyNavStyle(tabMisc,   key.equals("MISC"));
        contentPanel.removeAllViews();
        switch (key) {
            case "VISUAL": buildVisual(); break;
            case "AIM":    buildAim();    break;
            case "MISC":   buildMisc();   break;
        }
    }

    // Active: purple→indigo gradient; inactive: raised dark bg
    private void applyNavStyle(LinearLayout row, boolean active) {
        if (row == null) return;
        GradientDrawable bg;
        if (active) {
            bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{C_PURPLE, C_INDIGO});
            bg.setCornerRadius(dp(8));
            if (row.getTag() instanceof TextView)
                ((TextView) row.getTag()).setTextColor(C_WHITE);
            View iw = row.findViewWithTag("iw");
            if (iw instanceof FrameLayout) {
                GradientDrawable iwBg = new GradientDrawable();
                iwBg.setCornerRadius(dp(5));
                iwBg.setColor(Color.parseColor("#38ffffff"));
                iw.setBackground(iwBg);
                View child = ((FrameLayout) iw).getChildAt(0);
                if (child instanceof ImageView)
                    ((ImageView) child).setColorFilter(C_WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
            }
        } else {
            bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(C_RAISED);
            if (row.getTag() instanceof TextView)
                ((TextView) row.getTag()).setTextColor(C_WHITE);
            View iw = row.findViewWithTag("iw");
            if (iw instanceof FrameLayout) {
                iw.setBackground(null);
                View child = ((FrameLayout) iw).getChildAt(0);
                if (child instanceof ImageView)
                    ((ImageView) child).setColorFilter(C_WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
            }
        }
        row.setBackground(bg);
    }

    // Section header: #1c1c1c bg, #9232E0 text, top radius 7dp
    private void sectionHeader(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_HEAD_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        t.setTypeface(fHeading, Typeface.BOLD);
        t.setLetterSpacing(0.1f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(5), 0, dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(7));
        bg.setColor(C_SECTION);
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        t.setLayoutParams(lp);
        parent.addView(t);
    }

    // Column header same as section header
    private void colHeader(LinearLayout col, String text) {
        sectionHeader(col, text);
    }

    // Checkbox row: 17dp box, purple when checked, label 11sp
    private void checkRow(LinearLayout parent, String label, boolean initial, final CB cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(4), dp(2), dp(4));
        row.setClickable(true);
        row.setFocusable(true);

        final TextView box = new TextView(this);
        box.setGravity(Gravity.CENTER);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        box.setClickable(false);
        box.setFocusable(false);
        box.setDuplicateParentStateEnabled(true);
        applyCheck(box, initial);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(17), dp(17));
        blp.rightMargin = dp(7);
        row.addView(box, blp);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(C_LABEL);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
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
        row.setPadding(dp(2), dp(4), dp(2), dp(4));
        row.setClickable(true);
        row.setFocusable(true);

        final TextView box = new TextView(this);
        box.setGravity(Gravity.CENTER);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        box.setClickable(false);
        box.setFocusable(false);
        applyCheck(box, initial);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(17), dp(17));
        blp.rightMargin = dp(7);
        row.addView(box, blp);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(C_LABEL);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
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

    // Checked: Vision_Tw check.xml; unchecked: Vision_Tw uncheck.xml
    private void applyCheck(TextView box, boolean on) {
        int drawRes = on ? res("check", "drawable") : res("uncheck", "drawable");
        if (drawRes != 0) {
            box.setBackground(getResources().getDrawable(drawRes, null));
            box.setText(on ? "✔" : "");
            box.setTextColor(on ? C_WHITE : Color.TRANSPARENT);
        } else {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(4));
            if (on) {
                bg.setColor(C_PURPLE);
                bg.setStroke(dp(1.5f), C_PURPLE);
                box.setText("✔");
                box.setTextColor(C_WHITE);
                box.setTypeface(null, Typeface.BOLD);
            } else {
                bg.setColor(Color.TRANSPARENT);
                bg.setStroke(dp(1.6f), Color.parseColor("#8e8e9a"));
                box.setText("");
            }
            box.setBackground(bg);
        }
    }

    // Slider: purple fill track, purple thumb with white border
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
        trackBg.setColor(C_TRACK);
        GradientDrawable fillShape = new GradientDrawable();
        fillShape.setCornerRadius(dp(100));
        fillShape.setColor(C_PURPLE);
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
        thumbNormal.setColor(C_PURPLE);
        thumbNormal.setStroke(dp(2f), C_WHITE);
        thumbNormal.setSize(dp(14), dp(14));
        sb.setThumb(thumbNormal);
        sb.setThumbOffset(0);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24));
        lp.topMargin = dp(2); lp.bottomMargin = dp(6);
        sb.setLayoutParams(lp);
        return sb;
    }

    // Action button: dark gray inactive, purple active
    private TextView makeActionBtn(String label, boolean active) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(C_WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(fBody);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(9), dp(6), dp(9));
        styleActionBtn(t, active);
        return t;
    }

    private void styleActionBtn(TextView t, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(3));
        bg.setColor(active ? C_PURPLE : C_BTN);
        t.setBackground(bg);
    }

    interface CB { void onToggle(boolean on); }

    // ── VISUAL TAB ────────────────────────────────────────────────────────────
    private void buildVisual() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout colL = newCol(dp(4), dp(5));
        LinearLayout colR = newCol(dp(5), dp(4));

        colHeader(colL, "ESP Visuals");
        checkRow(colL, "Line",        stLine,    on->{ stLine=on;    sendTcp("line="+(on?1:0)); });
        checkRow(colL, "Skeleton",    stSkel,    on->{ stSkel=on;    sendTcp("skeleton="+(on?1:0)); });
        checkRow(colL, "Health",      stHealth,  on->{ stHealth=on;  sendTcp("health="+(on?1:0)); });
        checkRow(colL, "Box",         stBox,     on->{ stBox=on;     sendTcp("box="+(on?1:0)); });
        checkRow(colL, "Distance",    stDist,    on->{ stDist=on;    sendTcp("distance="+(on?1:0)); });
        checkRow(colL, "Enemy Count", stCount,   on->{ stCount=on;   sendTcp("count="+(on?1:0)); });

        colHeader(colR, "World Visuals");
        checkRow(colR, "Vehicles",          stVehicle, on->{ stVehicle=on; sendTcp("vehicle="+(on?1:0)); });
        checkRow(colR, "Items",             stItems,   on->{ stItems=on;   sendTcp("items="+(on?1:0)); });
        checkRow(colR, "Throwable Warning", stGrenade, on->{ stGrenade=on; sendTcp("grenade="+(on?1:0)); });

        grid.addView(colL); grid.addView(colR);
        contentPanel.addView(grid);
    }

    // ── AIM TAB ───────────────────────────────────────────────────────────────
    private void buildAim() {
        sectionHeader(contentPanel, "COMBAT FEATURES");

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        checkRowRet(col, "Bullet Track", stBtrack, on->{
            stBtrack=on; sendTcp("btrack="+(on?1:0));
        });

        // Distance slider
        TextView distLbl = new TextView(this);
        distLbl.setText("Distance: (" + stDistance + " m)");
        distLbl.setTextColor(Color.parseColor("#d2d2d2"));
        distLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        distLbl.setTypeface(fMono);
        distLbl.setPadding(dp(2), dp(8), 0, dp(4));
        distLbl.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        col.addView(distLbl);

        SeekBar distBar = makeSeekBar(10, 150, stDistance);
        distBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s, int p, boolean u){
                stDistance=p+10;
                distLbl.setText("Distance: ("+stDistance+" m)");
                sendTcp("btrange="+stDistance);
            }
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });
        col.addView(distBar);

        // Ignore Bot / Ignore Knocked buttons
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = dp(6);
        btnRow.setLayoutParams(brlp);

        TextView btnBot     = makeActionBtn("Ignore Bot",     stIgnBot);
        TextView btnKnocked = makeActionBtn("Ignore Knocked", stIgnKnocked);
        LinearLayout.LayoutParams bl1 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bl1.rightMargin = dp(9);
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
        contentPanel.addView(col);
    }

    // ── MISC TAB ──────────────────────────────────────────────────────────────
    private void buildMisc() {
        sectionHeader(contentPanel, "STABILITY FEATURES");

        // 2×2 grid via horizontal pairs
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout c1 = newCol(dp(4), dp(5));
        LinearLayout c2 = newCol(dp(5), dp(4));
        LinearLayout c3 = newCol(dp(4), dp(5));
        LinearLayout c4 = newCol(dp(5), dp(4));

        checkRow(c1, "6H Time Fixer", stTimeFixer, on->{ stTimeFixer=on; sendTcp("timefixer="+(on?1:0)); });
        checkRow(c2, "120 FPS",       stFps120,    on->{ stFps120=on;    sendTcp("fps120="+(on?1:0)); });
        checkRow(c3, "IPad View",     stWide,      on->{ stWide=on;      sendTcp("wideview="+(on?1:0)); });
        checkRow(c4, "Hide Recording",stHideEsp,   on->{ stHideEsp=on;   sendTcp("hideesp="+(on?1:0)); updateHideEspWindows(on); });

        row1.addView(c1); row1.addView(c2);
        row2.addView(c3); row2.addView(c4);
        contentPanel.addView(row1);
        contentPanel.addView(row2);
    }

    // ── SCREENSHOT PROTECTION ──────────────────────────────────────────────────
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

    // ── HELPERS ───────────────────────────────────────────────────────────────
    private LinearLayout newCol(int padStart, int padEnd) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(padStart, 0, padEnd, 0);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return col;
    }

    // ── TCP SENDER ─────────────────────────────────────────────────────────────
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
