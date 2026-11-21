package dev.multisystem.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.ClipData;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;

public final class MainActivity extends Activity {
    private static final int PICK_IMAGE = 41;
    private static final int PICK_FOLDER = 42;
    private static int lastTerminalLogGeneration = -1;
    private static final int[] ACCENT_COLORS = {
            0xfff44336, 0xffe91e63, 0xff9c27b0, 0xff3f51b5,
            0xff2196f3, 0xff00a6c7, 0xff009688, 0xff4caf50,
            0xffff9800, 0xffffc107, 0xff795548, 0xff607d8b
    };
    private static final String[] ACCENT_LABELS = {
            "红", "粉", "紫", "靛蓝", "蓝", "青", "青绿", "绿",
            "橙", "琥珀", "棕", "蓝灰"
    };
    private int BLUE;
    private int BUTTON_PRIMARY;
    private int INK;
    private int MUTED;
    private int SURFACE;
    private int CARD;
    private int SOFT_SURFACE;
    private int SECONDARY;
    private int NAV_SELECTED;
    private int STATUS_START;
    private int STATUS_END;
    private int GOOD;
    private int WARNING;
    private int themeMode;
    private int accentColor;
    private boolean darkTheme;
    private boolean themeSettingsMode;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final MultiSystemBackend backend = new MultiSystemBackend();
    private final ArrayList<String> logEntries = new ArrayList<>();
    private LinearLayout root;
    private LinearLayout content;
    private LinearLayout bottomBar;
    private TextView rootValue;
    private TextView dsuValue;
    private TextView deviceValue;
    private TextView toolValue;
    private TextView feedback;
    private TextView operationStatus;
    private TextView storageValue;
    private ProgressBar installProgress;
    private Button rootAction;
    private Button installButton;
    private Button addMirrorButton;
    private EditText setupSlotInput;
    private EditText setupUserdataInput;
    private TextView slotValidationMessage;
    private String pendingName;
    private long pendingUserdataBytes;
    private final ArrayList<Uri> pendingUris = new ArrayList<>();
    private final ArrayList<String> pendingNames = new ArrayList<>();
    private boolean busy;
    private boolean setupMode;
    private boolean aboutMode;
    private int selectedTab;
    private String currentPage;
    private String pendingPage;
    private int pageTransitionToken;
    private MultiSystemBackend.Snapshot snapshot;
    private Exception lastStatusError;
    private boolean statusLoaded;
    private int serviceLogGeneration = -1;
    private int serviceLogIndex;
    private InstallationService.Phase lastInstallPhase = InstallationService.Phase.IDLE;
    private final Runnable installListener = this::onInstallChanged;

    @Override public void onCreate(Bundle state) {
        android.content.SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        int storedThemeMode;
        if (prefs.getInt("theme_schema", 0) == 0) {
            int oldTheme = prefs.getInt("theme", -1);
            storedThemeMode = oldTheme == 0 ? 2 : oldTheme == 1 ? 1 : 0;
            prefs.edit().putInt("theme", storedThemeMode).putInt("theme_schema", 1).apply();
        } else {
            storedThemeMode = prefs.getInt("theme", 0);
        }
        themeMode = storedThemeMode;
        accentColor = prefs.getInt("accent_color", ACCENT_COLORS[4]);
        boolean systemDark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        boolean nativeDark = themeMode == 2 || themeMode == 3 || (themeMode == 0 && systemDark);
        setTheme(nativeDark ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        serviceLogGeneration = prefs.getInt("service_log_generation", -1);
        serviceLogIndex = prefs.getInt("service_log_index", 0);
        loadLogs();
        applyThemeMode();
        buildShell();
        if (state != null && state.getBoolean("theme_settings_mode")) showThemeSettingsPage(null);
        else if (state != null && state.getBoolean("about_mode")) showAboutPage(null);
        else showHome();
        InstallationService.listen(installListener);
        onInstallChanged();
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        InstallationService.listen(installListener);
        onInstallChanged();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("theme_settings_mode", themeSettingsMode);
        state.putBoolean("about_mode", aboutMode);
        super.onSaveInstanceState(state);
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SURFACE);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(10), dp(5), dp(10), dp(5));
        bottomBar.setBackground(round(CARD, 16));
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(MATCH, dp(68));
        navParams.setMargins(dp(14), dp(4), dp(14), dp(10));
        root.addView(bottomBar, navParams);
        setContentView(root);
        root.requestApplyInsets();
    }

    private void showHome() {
        int direction = "setup".equals(currentPage) || "theme".equals(currentPage)
                || "about".equals(currentPage) ? -1 : 0;
        transitionTo("home", this::renderHome, direction);
    }

    private void transitionTo(String destination, Runnable render, int direction) {
        int token = ++pageTransitionToken;
        pendingPage = destination;
        content.animate().cancel();
        if (currentPage == null || currentPage.equals(destination) || content.getWidth() == 0) {
            render.run();
            currentPage = destination;
            pendingPage = null;
            content.setAlpha(1f);
            content.setTranslationX(0f);
            return;
        }

        PathInterpolator easing = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
        content.setAlpha(1f);
        content.setTranslationX(0f);
        content.animate().alpha(0f)
                .translationX(direction == 0 ? 0f : -direction * dp(18))
                .setDuration(100)
                .setInterpolator(easing)
                .withEndAction(() -> {
                    if (token != pageTransitionToken) return;
                    render.run();
                    currentPage = destination;
                    pendingPage = null;
                    content.setAlpha(0f);
                    content.setTranslationX(direction == 0 ? 0f : direction * dp(18));
                    content.animate().alpha(1f).translationX(0f)
                            .setDuration(direction == 0 ? 220 : 240)
                            .setInterpolator(easing)
                            .withEndAction(() -> {
                                if (token == pageTransitionToken) {
                                    content.setAlpha(1f);
                                    content.setTranslationX(0f);
                                }
                            }).start();
                }).start();
    }

    private void renderHome() {
        selectedTab = 0;
        setupMode = false;
        aboutMode = false;
        themeSettingsMode = false;
        bottomBar.setVisibility(View.VISIBLE);
        content.removeAllViews();
        int inset = 16;
        content.setPadding(dp(inset), dp(inset), dp(inset), dp(inset));
        content.addView(text("Elysia 多系统工具箱", 23, INK, true),
                params(WRAP, WRAP, 0, 0, 0, 12));

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        int statusInset = 16;
        status.setPadding(dp(statusInset), dp(14), dp(statusInset), dp(14));
        GradientDrawable statusBackground = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{STATUS_START, STATUS_END});
        statusBackground.setCornerRadius(dp(20));
        status.setBackground(statusBackground);
        status.setElevation(dp(3));
        content.addView(status, params(MATCH, WRAP, 0, 0, 0, 16));
        int statusInk = Color.WHITE;
        int statusMuted = 0xffe6edf4;
        TextView header = text("◉  " + currentSlotLabel(), 20, statusInk, true);
        status.addView(header);
        deviceValue = text("v" + appVersion() + "\n" + backend.deviceLabel(), 13, statusMuted, false);
        status.addView(deviceValue, params(MATCH, WRAP, 0, 8, 0, 18));
        View divider = new View(this);
        divider.setBackgroundColor(0x55ffffff);
        status.addView(divider, new LinearLayout.LayoutParams(MATCH, dp(1)));
        LinearLayout indicators = new LinearLayout(this);
        indicators.setOrientation(LinearLayout.HORIZONTAL);
        indicators.setPadding(0, dp(13), 0, 0);
        status.addView(indicators, params(MATCH, WRAP, 0, 0, 0, 0));
        rootValue = text("检查中", 14, statusInk, true);
        dsuValue = text("检查中", 14, statusInk, true);
        addMetric(indicators, "Root 授权", rootValue, statusInk, statusMuted);
        addMetric(indicators, "DSU / gsi_tool", dsuValue, statusInk, statusMuted);
        status.addView(rootActionButton(), params(MATCH, dp(44), 0, 14, 0, 0));
        toolValue = text("gsi_tool：检测中", 12, statusMuted, false);
        status.addView(toolValue, params(MATCH, WRAP, 0, 10, 0, 0));
        storageValue = text("/data 可用空间：检测中", 12, statusMuted, false);
        status.addView(storageValue, params(MATCH, WRAP, 0, 4, 0, 0));

        addMirrorButton = button("添加镜像", true);
        addMirrorButton.setOnClickListener(v -> showImportConfig());
        content.addView(addMirrorButton, params(MATCH, dp(48), 0, 0, 0, 8));
        installButton = button("安装系统", true);
        installButton.setOnClickListener(v -> startInstallation(new ArrayList<>(pendingUris), new ArrayList<>(pendingNames)));
        content.addView(installButton, params(MATCH, dp(48), 0, 0, 0, 16));
        if (snapshot != null && snapshot.runningDsu) {
            Button backMain = button("回主系统", false);
            backMain.setOnClickListener(v -> confirmReturnMain());
            content.addView(backMain, params(MATCH, dp(44), 0, 0, 0, 16));
        }
        if (!pendingUris.isEmpty()) renderPendingImages();
        installProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        installProgress.setMax(100);
        installProgress.setProgress(0);
        installProgress.setProgressDrawable(getDrawable(R.drawable.progress_install));
        installProgress.setVisibility(View.GONE);
        content.addView(installProgress, params(MATCH, dp(8), 2, 0, 2, 6));
        operationStatus = text("", 13, MUTED, false);
        operationStatus.setVisibility(View.GONE);
        content.addView(operationStatus, params(MATCH, WRAP, 2, 0, 0, 10));

        TextView slotsHeading = text("已安装系统", 19, INK, true);
        content.addView(slotsHeading, params(MATCH, WRAP, 2, 0, 0, 9));
        LinearLayout slots = new LinearLayout(this);
        slots.setOrientation(LinearLayout.VERTICAL);
        content.addView(slots, params(MATCH, WRAP, 0, 0, 0, 12));
        renderSlots(slots);

        buildNav();
        if (statusLoaded) updateStatus(lastStatusError);
        updateInstallControls();
        renderInstallStatus();
    }

    private void showSettings() {
        int direction = "theme".equals(currentPage) || "about".equals(currentPage) ? -1 : 0;
        transitionTo("settings", this::renderSettings, direction);
    }

    private void renderSettings() {
        selectedTab = 1;
        setupMode = false;
        aboutMode = false;
        themeSettingsMode = false;
        bottomBar.setVisibility(View.VISIBLE);
        content.removeAllViews();
        int inset = 16;
        content.setPadding(dp(inset), dp(inset), dp(inset), dp(inset));
        content.addView(text("设置", 23, INK, true), params(MATCH, WRAP, 0, 0, 0, 14));

        LinearLayout appearance = settingsGroup();
        addSettingsRow(appearance, "主题设置", themeSummary(), "颜色模式与自定义主题色",
                this::showThemeSettingsPage, false);
        card(content, appearance, 14, 16, 14, 16, 16, CARD);

        LinearLayout logs = settingsGroup();
        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        logHeader.addView(text("安装日志", 17, INK, true),
                new LinearLayout.LayoutParams(0, WRAP, 1));
        Button clear = button("清空", false);
        clear.setTextSize(12);
        clear.setMinHeight(0);
        clear.setMinWidth(0);
        clear.setPadding(dp(8), 0, dp(8), 0);
        clear.setOnClickListener(v -> confirmClearLogs());
        logHeader.addView(clear, new LinearLayout.LayoutParams(dp(68), dp(36)));
        Button print = button("打印", false);
        print.setTextSize(12);
        print.setMinHeight(0);
        print.setMinWidth(0);
        print.setPadding(dp(8), 0, dp(8), 0);
        print.setOnClickListener(v -> exportLogs());
        LinearLayout.LayoutParams printParams = new LinearLayout.LayoutParams(dp(68), dp(36));
        printParams.leftMargin = dp(6);
        logHeader.addView(print, printParams);
        logs.addView(logHeader);
        ScrollView logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setNestedScrollingEnabled(true);
        LinearLayout logBody = new LinearLayout(this);
        logBody.setOrientation(LinearLayout.VERTICAL);
        if (logEntries.isEmpty()) {
            logBody.addView(text("暂无操作记录", 13, MUTED, false),
                    params(MATCH, WRAP, 0, 10, 0, 2));
        } else {
            int shown = Math.min(logEntries.size(), 100);
            for (int i = 0; i < shown; i++) {
                TextView entry = text(logEntries.get(i), 13, INK, false);
                entry.setTextIsSelectable(true);
                logBody.addView(entry, params(MATCH, WRAP, 0, 10, 0, 0));
                if (i + 1 < shown) {
                    View divider = new View(this);
                    divider.setBackgroundColor(SECONDARY);
                    logBody.addView(divider, params(MATCH, dp(1), 0, 8, 0, 0));
                }
            }
        }
        logScroll.addView(logBody);
        logs.addView(logScroll, params(MATCH, dp(206), 0, 4, 0, 0));
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        card(content, logs, 14, 16, 14, 16, 16, CARD);

        LinearLayout about = settingsGroup();
        addSettingsRow(about, "关于 Elysia", appVersion(), "多系统工具箱",
                this::showAboutPage, false);
        addSettingsRow(about, "安全策略", "查看说明", "镜像写入与槽位保护",
                this::showSafetyInfo, true);
        card(content, about, 14, 16, 16, 16, 16, CARD);
        buildNav();
    }

    private LinearLayout settingsGroup() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        return group;
    }

    private void addSettingsRow(LinearLayout group, String title, String value, String summary,
                                View.OnClickListener listener, boolean dividerBefore) {
        if (dividerBefore) {
            View divider = new View(this);
            divider.setBackgroundColor(SECONDARY);
            group.addView(divider, params(MATCH, dp(1), 0, 0, 0, 0));
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(11), dp(2), dp(11));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(title, 15, INK, true));
        copy.addView(text(summary, 12, MUTED, false), params(MATCH, WRAP, 0, 4, 0, 0));
        row.addView(copy, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView selected = text(value + "  ›", 13, BLUE, false);
        selected.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        row.addView(selected, new LinearLayout.LayoutParams(WRAP, WRAP));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setOnClickListener(listener);
        group.addView(row, params(MATCH, WRAP, 0, 0, 0, 0));
    }

    private void showThemeSettingsPage(View ignored) {
        transitionTo("theme", this::renderThemeSettingsPage, 1);
    }

    private void renderThemeSettingsPage() {
        selectedTab = 1;
        setupMode = false;
        aboutMode = false;
        themeSettingsMode = true;
        bottomBar.setVisibility(View.GONE);
        content.removeAllViews();
        int inset = 16;
        content.setPadding(dp(inset), dp(18), dp(inset), dp(28));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = backButton();
        back.setOnClickListener(v -> showSettings());
        heading.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        content.addView(heading, params(MATCH, dp(56), 0, 0, 0, 14));
        content.addView(text("主题设置", 30, INK, true), params(MATCH, WRAP, 0, 0, 0, 16));

        LinearLayout modePanel = settingsGroup();
        modePanel.addView(text("颜色模式", 16, INK, true));
        modePanel.addView(text("选择系统外观", 12, MUTED, false),
                params(MATCH, WRAP, 0, 4, 0, 12));
        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER_VERTICAL);
        String[] names = {"跟随系统", "浅色", "深色", "AMOLED"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView option = text(names[i], 12, themeMode == i
                    ? foregroundFor(BUTTON_PRIMARY) : INK, themeMode == i);
            option.setGravity(Gravity.CENTER);
            option.setPadding(dp(3), 0, dp(3), 0);
            option.setBackground(round(themeMode == i ? BUTTON_PRIMARY : SOFT_SURFACE, 12));
            option.setOnClickListener(v -> setThemeMode(index));
            LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(0, dp(44), 1);
            if (i > 0) optionParams.leftMargin = dp(4);
            modes.addView(option, optionParams);
        }
        modePanel.addView(modes);
        card(content, modePanel, 14, 16, 16, 16, 16, CARD);

        LinearLayout palettePanel = settingsGroup();
        palettePanel.addView(text("主题色", 16, INK, true));
        palettePanel.addView(text("自定义界面强调色", 12, MUTED, false),
                params(MATCH, WRAP, 0, 4, 0, 12));
        GridLayout palette = new GridLayout(this);
        palette.setColumnCount(4);
        palette.setUseDefaultMargins(false);
        for (int i = 0; i < ACCENT_COLORS.length; i++) {
            final int index = i;
            LinearLayout swatch = new LinearLayout(this);
            swatch.setOrientation(LinearLayout.VERTICAL);
            swatch.setGravity(Gravity.CENTER);
            GradientDrawable swatchBackground = round(ACCENT_COLORS[i], 50);
            swatchBackground.setStroke(dp(accentColor == ACCENT_COLORS[i] ? 3 : 1),
                    accentColor == ACCENT_COLORS[i] ? INK : SECONDARY);
            View color = new View(this);
            color.setBackground(swatchBackground);
            swatch.addView(color, new LinearLayout.LayoutParams(dp(32), dp(32)));
            swatch.addView(text(ACCENT_LABELS[i], 10, MUTED, false),
                    params(MATCH, dp(18), 0, 4, 0, 0));
            swatch.setContentDescription("主题色：" + ACCENT_LABELS[i]);
            swatch.setOnClickListener(v -> setAccentColor(index));
            GridLayout.LayoutParams tileParams = new GridLayout.LayoutParams(
                    GridLayout.spec(i / 4), GridLayout.spec(i % 4, 1f));
            tileParams.width = 0;
            tileParams.height = dp(62);
            tileParams.setMargins(dp(2), dp(2), dp(2), dp(4));
            palette.addView(swatch, tileParams);
        }
        palettePanel.addView(palette, params(MATCH, WRAP, 0, 0, 0, 0));
        Button customColor = button("自定义 HEX 色值", false);
        customColor.setOnClickListener(v -> showCustomAccentDialog());
        palettePanel.addView(customColor, params(MATCH, dp(44), 0, 12, 0, 0));
        card(content, palettePanel, 14, 16, 16, 16, 16, CARD);
    }

    private void setThemeMode(int mode) {
        themeMode = mode;
        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("theme", themeMode).apply();
        recreate();
    }

    private void setAccentColor(int index) {
        accentColor = ACCENT_COLORS[index];
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putInt("accent_color", accentColor).apply();
        applyThemeMode();
        showThemeSettingsPage(null);
    }

    private void showCustomAccentDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setText(String.format(java.util.Locale.ROOT, "#%06X", 0xFFFFFF & accentColor));
        LinearLayout field = new LinearLayout(this);
        field.setPadding(dp(20), dp(8), dp(20), 0);
        field.addView(input, new LinearLayout.LayoutParams(MATCH, WRAP));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("自定义主题色")
                .setView(field)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", (dialogInterface, which) -> {
                    String value = input.getText().toString().trim();
                    if (!value.matches("#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) {
                        toast("请输入 #RRGGBB 或 #AARRGGBB 格式的色值。");
                        return;
                    }
                    try {
                        accentColor = Color.parseColor(value);
                        getSharedPreferences("settings", MODE_PRIVATE).edit()
                                .putInt("accent_color", accentColor).apply();
                        applyThemeMode();
                        showThemeSettingsPage(null);
                    } catch (IllegalArgumentException error) {
                        toast("主题色色值无效。");
                    }
                }).create();
        showStyledConfirmation(dialog);
    }

    private void showAboutPage(View ignored) {
        transitionTo("about", this::renderAboutPage, 1);
    }

    private void renderAboutPage() {
        selectedTab = 1;
        setupMode = false;
        aboutMode = true;
        bottomBar.setVisibility(View.GONE);
        content.removeAllViews();
        int inset = 16;
        content.setPadding(dp(inset), dp(18), dp(inset), dp(28));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = backButton();
        back.setOnClickListener(v -> showSettings());
        heading.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        content.addView(heading, params(MATCH, dp(56), 0, 0, 0, 14));
        content.addView(text("关于", 42, INK, false),
                params(MATCH, WRAP, 0, 0, 0, 28));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.elysia_icon);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setPadding(dp(16), dp(16), dp(16), dp(16));
        icon.setBackground(round(CARD, 22));
        hero.addView(icon, new LinearLayout.LayoutParams(dp(108), dp(108)));
        hero.addView(text("Elysia 多系统工具箱", 28, INK, false),
                params(WRAP, WRAP, 0, 24, 0, 0));
        hero.addView(text("v" + appVersion(), 16, MUTED, false),
                params(WRAP, WRAP, 0, 8, 0, 0));
        content.addView(hero, params(MATCH, WRAP, 0, 26, 0, 52));

        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.VERTICAL);
        addAboutLink(links, "在 GitHub 查看源码",
                v -> openUrl("https://github.com/Shlc57/Elysia-DSU-Multi-SystemManager"), false);
        addAboutLink(links, "加入 QQ 群（811309060）",
                v -> joinQqGroup(), true);
        card(content, links, 18, 0, 0, 0, 0, CARD);
    }

    private void addAboutLink(LinearLayout parent, String label,
                              View.OnClickListener listener, boolean dividerBefore) {
        if (dividerBefore) {
            View divider = new View(this);
            divider.setBackgroundColor(SECONDARY);
            parent.addView(divider, params(MATCH, dp(1), 0, 0, 0, 0));
        }
        TextView row = text(label, 17, INK, false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), 0, dp(18), 0);
        row.setOnClickListener(listener);
        parent.addView(row, new LinearLayout.LayoutParams(MATCH, dp(68)));
    }

    private TextView backButton() {
        TextView back = text("←", 32, INK, false);
        back.setGravity(Gravity.CENTER);
        back.setBackground(round(NAV_SELECTED, 50));
        return back;
    }

    private void showSafetyInfo(View ignored) {
        AlertDialog safety = new AlertDialog.Builder(this).setTitle("安全策略")
                .setMessage("安装始终创建新的命名 DSU 槽，不覆盖已有槽；安装失败时保留未完成标记供检查。删除当前运行中的槽位会被拒绝。重要数据请先备份。")
                .setPositiveButton("知道了", null).create();
        showStyledConfirmation(safety);
    }

    private void confirmClearLogs() {
        if (logEntries.isEmpty()) return;
        AlertDialog clearDialog = new AlertDialog.Builder(this).setTitle("清空安装日志？")
                .setMessage("此操作只清除应用内的操作记录，不会修改 DSU 槽位。")
                .setNegativeButton("取消", null).setPositiveButton("清空", (dialog, which) -> {
                    logEntries.clear();
                    persistLogs();
                    showSettings();
                }).create();
        showStyledConfirmation(clearDialog);
    }

    private void exportLogs() {
        if (logEntries.isEmpty()) {
            showTransientMessage("暂无可打印的安装日志");
            return;
        }
        final String content = String.join("\n", logEntries) + "\n";
        final String filename = "elysia-install-log-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date())
                + ".txt";
        worker.execute(() -> {
            Uri savedUri = null;
            Exception failure = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                savedUri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (savedUri == null) throw new IllegalStateException("无法创建下载文件。");
                try (OutputStream output = getContentResolver().openOutputStream(savedUri)) {
                    if (output == null) throw new IllegalStateException("无法写入下载文件。");
                    output.write(content.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(savedUri, ready, null, null);
            } catch (Exception error) {
                failure = error;
                if (savedUri != null) getContentResolver().delete(savedUri, null, null);
            }
            Exception error = failure;
            runOnUiThread(() -> {
                if (error != null) {
                    toast("打印日志失败：" + error.getMessage());
                } else {
                    showTransientMessage("日志已保存到 Download/" + filename);
                }
            });
        });
    }

    private void showTransientMessage(String message) {
        Toast notice = Toast.makeText(this, message, Toast.LENGTH_SHORT);
        notice.show();
        new Handler(Looper.getMainLooper()).postDelayed(notice::cancel, 1500L);
    }

    private void showStyledConfirmation(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(round(CARD, 20));
            int width = Math.min(dp(480), getResources().getDisplayMetrics().widthPixels - dp(48));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setOnShowListener(ignored -> {
            int titleId = getResources().getIdentifier("alertTitle", "id", "android");
            TextView title = titleId == 0 ? null : dialog.findViewById(titleId);
            if (title != null) {
                title.setTextColor(INK);
                title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            }
            TextView message = dialog.findViewById(android.R.id.message);
            if (message != null) message.setTextColor(MUTED);
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setAllCaps(false);
                boolean destructive = title != null && title.getText().toString().contains("删除");
                positive.setTextColor(Color.WHITE);
                positive.setBackground(round(destructive
                        ? (darkTheme ? 0xffa84942 : 0xffa6433b) : BUTTON_PRIMARY, 12));
            }
            Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (negative != null) {
                negative.setAllCaps(false);
                negative.setTextColor(INK);
                negative.setBackground(round(SOFT_SURFACE, 12));
            }
            Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (neutral != null) {
                neutral.setAllCaps(false);
                neutral.setTextColor(BLUE);
                neutral.setBackground(round(SOFT_SURFACE, 12));
            }
        });
        dialog.show();
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception error) { toast("无法打开链接。"); }
    }

    private void joinQqGroup() {
        String[] urls = {
                "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=811309060&card_type=group&source=qrcode",
                "mqqwpa://im/chat?chat_type=group&uin=811309060"
        };
        for (String url : urls) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return;
            } catch (Exception ignored) { }
        }
        toast("未检测到 QQ 客户端，群号：811309060");
    }

    private String themeLabel() {
        return themeMode == 1 ? "浅色" : themeMode == 2 ? "深色"
                : themeMode == 3 ? "AMOLED" : "跟随系统";
    }

    private String themeSummary() {
        return themeLabel() + " · " + accentLabel();
    }

    private String accentLabel() {
        for (int i = 0; i < ACCENT_COLORS.length; i++) {
            if (ACCENT_COLORS[i] == accentColor) return ACCENT_LABELS[i];
        }
        return "自定义";
    }

    private int foregroundFor(int background) {
        int red = Color.red(background);
        int green = Color.green(background);
        int blue = Color.blue(background);
        return red * 299 + green * 587 + blue * 114 > 150000 ? INK : Color.WHITE;
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { return "0.1.9"; }
    }

    private void applyThemeMode() {
        boolean systemDark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        darkTheme = themeMode == 2 || themeMode == 3 || (themeMode == 0 && systemDark);
        boolean amoled = themeMode == 3;
        if (darkTheme) {
            BLUE = lightenColor(accentColor, 0.42f);
            BUTTON_PRIMARY = mixColor(accentColor, 0xff000000, 0.30f);
            INK = 0xffe7edf2;
            MUTED = 0xffa9b4bd;
            SURFACE = amoled ? 0xff000000 : 0xff12191f;
            CARD = amoled ? 0xff101010 : 0xff1d282f;
            SOFT_SURFACE = amoled ? 0xff1d1d1d : 0xff26343d;
            SECONDARY = 0xff35434c;
            NAV_SELECTED = mixColor(BLUE, SURFACE, 0.66f);
            STATUS_START = mixColor(accentColor, SURFACE, 0.50f);
            STATUS_END = mixColor(accentColor, SURFACE, 0.30f);
            GOOD = 0xff92c7a6;
            WARNING = 0xffe4bd7e;
        } else {
            BLUE = accentColor;
            BUTTON_PRIMARY = BLUE;
            INK = 0xff1d2733;
            MUTED = 0xff697785;
            SURFACE = 0xfff4f6f8;
            CARD = 0xffffffff;
            SOFT_SURFACE = 0xffe9eef2;
            SECONDARY = 0xffdce3e8;
            NAV_SELECTED = mixColor(BLUE, SURFACE, 0.84f);
            STATUS_START = mixColor(BLUE, 0xff466b91, 0.35f);
            STATUS_END = mixColor(BLUE, 0xff6685a4, 0.25f);
            GOOD = 0xff47775e;
            WARNING = 0xff946b32;
        }
        getWindow().setStatusBarColor(SURFACE);
        getWindow().setNavigationBarColor(SURFACE);
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        flags = darkTheme ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                : flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        if (root != null) root.setBackgroundColor(SURFACE);
        if (bottomBar != null) bottomBar.setBackground(round(CARD, 16));
    }

    private int mixColor(int first, int second, float secondWeight) {
        float weight = Math.max(0f, Math.min(1f, secondWeight));
        int red = Math.round(Color.red(first) * (1f - weight) + Color.red(second) * weight);
        int green = Math.round(Color.green(first) * (1f - weight) + Color.green(second) * weight);
        int blue = Math.round(Color.blue(first) * (1f - weight) + Color.blue(second) * weight);
        return Color.rgb(red, green, blue);
    }

    private int lightenColor(int color, float amount) {
        return mixColor(color, Color.WHITE, amount);
    }

    private void buildNav() {
        bottomBar.removeAllViews();
        addNav(R.drawable.ic_nav_home, "首页", 0);
        addNav(R.drawable.ic_nav_settings, "设置", 1);
    }

    private void addNav(int iconRes, String label, int index) {
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(0, dp(6), 0, dp(4));
        item.setBackground(round(selectedTab == index ? NAV_SELECTED : Color.TRANSPARENT, 14));
        ImageView glyph = new ImageView(this);
        glyph.setImageResource(iconRes);
        glyph.setColorFilter(selectedTab == index ? BLUE : (darkTheme ? 0xffa9b4bd : 0xff69727b), PorterDuff.Mode.SRC_IN);
        item.addView(glyph, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView name = text(label, 11, selectedTab == index ? BLUE
                : (darkTheme ? 0xffa9b4bd : 0xff69727b), selectedTab == index);
        name.setGravity(Gravity.CENTER);
        item.addView(name, new LinearLayout.LayoutParams(MATCH, dp(22)));
        item.setOnClickListener(v -> {
            if (index == 0) showHome();
            else showSettings();
        });
        bottomBar.addView(item, new LinearLayout.LayoutParams(0, MATCH, 1));
    }

    private void addMetric(LinearLayout parent, String label, TextView value,
                           int primaryTextColor, int secondaryTextColor) {
        LinearLayout metric = new LinearLayout(this);
        metric.setOrientation(LinearLayout.VERTICAL);
        parent.addView(metric, new LinearLayout.LayoutParams(0, WRAP, 1));
        metric.addView(text(label, 12, secondaryTextColor, false));
        value.setTextColor(primaryTextColor);
        metric.addView(value, params(MATCH, WRAP, 0, 5, 0, 0));
    }

    private void renderPendingImages() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(text("待安装镜像", 17, INK, true));
        StringBuilder names = new StringBuilder("目标槽：").append(pendingName)
                .append("  ·  userdata：").append(String.format(java.util.Locale.ROOT, "%.1f GiB",
                        pendingUserdataBytes / 1073741824d)).append('\n');
        for (String name : pendingNames) names.append("• ").append(name).append('\n');
        panel.addView(text(names.toString().trim(), 13, MUTED, false),
                params(MATCH, WRAP, 0, 8, 0, 0));
        Button clear = button("清除镜像", false);
        clear.setOnClickListener(v -> {
            pendingUris.clear();
            pendingNames.clear();
            pendingName = null;
            pendingUserdataBytes = 0L;
            showHome();
        });
        panel.addView(clear, params(MATCH, dp(42), 0, 12, 0, 0));
        card(content, panel, 16, 16, 16, 16, 16, CARD);
    }

    private void renderSlots(LinearLayout slots) {
        if (snapshot == null || !snapshot.ready() || snapshot.slots.isEmpty()) {
            String message = snapshot == null ? "正在检测设备…"
                    : !snapshot.rootGranted ? "尚未获得 Root 授权。请在 Root 管理器中允许本应用后重新进入应用。"
                    : !snapshot.gsiToolAvailable ? "未找到 gsi_tool 或 DSU 不可用。请确认系统支持动态分区，并检查 ROM/Root 环境。"
                    : snapshot.runningDsu && snapshot.runningSlot.isEmpty()
                    ? "当前正在运行 DSU，但系统未报告活动槽 ID。为安全起见，安装和删除操作已被限制。"
                    : "暂无已安装系统。";
            TextView empty = text(message,
                    14, MUTED, false);
            card(slots, empty, 16, 18, 18, 18, 18, SOFT_SURFACE);
            return;
        }
        for (SystemSlot slot : snapshot.slots) {
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(18), dp(15), dp(18), dp(15));
            tile.setBackground(round(SOFT_SURFACE, 16));
            slots.addView(tile, params(MATCH, WRAP, 0, 0, 0, 6));
            tile.addView(text((slot.active ? "▣  " : "◆  ") + slot.name,
                    17, slot.active ? BLUE : INK, true));
            tile.addView(text(slot.version + "  ·  " + slot.sizeLabel(), 13, MUTED, false),
                    params(MATCH, WRAP, 26, 5, 0, 0));
            if (!slot.partitions.isEmpty()) {
                tile.addView(text("分区：" + slot.partitions, 12, MUTED, false),
                        params(MATCH, WRAP, 26, 5, 0, 0));
            }
            String slotState = "INSTALL_INCOMPLETE".equals(slot.state) ? "安装未完成"
                    : slot.active ? (snapshot.oneShotBoot ? "正在运行 · 单次启动" : "正在运行")
                    : slot.selected ? "下次启动目标"
                    : "READY".equals(slot.state) ? "可启动"
                    : "DETAILS_UNAVAILABLE".equals(slot.state) ? "镜像详情暂不可读" : slot.state;
            String userdataState = slot.dataIsolated ? "独立 userdata 镜像"
                    : "DETAILS_UNAVAILABLE".equals(slot.state) ? "userdata 状态暂不可读" : "未检测到 userdata";
            tile.addView(text(slotState + "  ·  " + userdataState,
                    12, slot.dataIsolated ? GOOD : WARNING, false),
                    params(MATCH, WRAP, 26, 5, 0, 0));
            if (!slot.active) {
                boolean directSwitch = snapshot != null && snapshot.runningDsu;
                boolean runningSlotKnown = directSwitch && !snapshot.runningSlot.isEmpty();
                Button action = button(slot.bootable
                        ? (directSwitch ? "切换到此系统并重启" : "启动此系统并重启")
                        : (directSwitch ? "不可切换（完整性未核验）" : "不可启动（设备端未确认）"), true);
                boolean enabled = slot.bootable && !busy && snapshot != null && snapshot.ready()
                        && (!directSwitch || runningSlotKnown);
                action.setEnabled(enabled);
                action.setAlpha(action.isEnabled() ? 1f : 0.55f);
                action.setOnClickListener(v -> confirmSwitch(slot));
                tile.addView(action, params(MATCH, dp(42), 26, 10, 0, 0));
                if (snapshot == null || !snapshot.runningDsu) {
                    TextView delete = text("删除该槽位", 12, darkTheme ? 0xffe39b92 : 0xff8b514a, false);
                    delete.setGravity(Gravity.RIGHT);
                    delete.setPadding(0, dp(12), 0, 0);
                    delete.setOnClickListener(v -> confirmDelete(slot));
                    tile.addView(delete, params(MATCH, WRAP, 0, 0, 0, 0));
                }
            }
        }
    }

    private void refresh() {
        if (busy) return;
        busy = true;
        worker.execute(() -> {
            MultiSystemBackend.Snapshot loaded = null;
            Exception failure = null;
            try { loaded = backend.load(); } catch (Exception e) { failure = e; }
            MultiSystemBackend.Snapshot result = loaded;
            Exception error = failure;
            runOnUiThread(() -> {
                snapshot = result;
                lastStatusError = error;
                statusLoaded = true;
                busy = false;
                if (pendingPage == null && "home".equals(currentPage)) renderHome();
                else if (pendingPage == null && selectedTab == 0) showHome();
                updateStatus(error);
            });
        });
    }

    private void updateStatus(Exception error) {
        String root = error != null ? "检测失败" : snapshot != null && snapshot.rootGranted ? "已授权" : "未授权";
        String dsu = error != null ? "检测失败" : snapshot != null && snapshot.gsiToolAvailable ? "已就绪" : "不可用";
        if (rootValue != null) rootValue.setText(root);
        if (dsuValue != null) dsuValue.setText(dsu);
        if (toolValue != null) toolValue.setText("gsi_tool："
                + (snapshot == null || !snapshot.gsiToolAvailable ? "未检测到" : snapshot.gsiToolPath));
        if (storageValue != null) storageValue.setText(snapshot == null || snapshot.freeBytes <= 0
                ? "/data 可用空间：未知"
                : "/data 可用空间：" + formatBytes(snapshot.freeBytes));
        if (feedback != null) feedback.setText(error != null ? "检测失败：" + error.getMessage()
                : snapshot != null && snapshot.ready() ? "Root 与 gsi_tool 已就绪，可导入多分区 ROM 镜像。"
                : "需要 Root 和设备端 gsi_tool；未满足时不会执行安装。");
        if (rootAction != null) {
            boolean needsRoot = error != null || snapshot == null || !snapshot.rootGranted;
            rootAction.setVisibility(needsRoot ? View.VISIBLE : View.GONE);
            rootAction.setEnabled(!busy);
            rootAction.setText(needsRoot ? "请求 Root 授权" : "已获得 Root 授权");
        }
        updateInstallControls();
    }

    private Button rootActionButton() {
        rootAction = button("请求 Root 授权", false);
        rootAction.setVisibility(View.GONE);
        rootAction.setOnClickListener(v -> requestRootAuthorization());
        return rootAction;
    }

    private void requestRootAuthorization() {
        if (busy) return;
        busy = true;
        if (rootAction != null) {
            rootAction.setEnabled(false);
            rootAction.setText("等待 Root 授权…");
        }
        worker.execute(() -> {
            Exception failure = null;
            try {
                CommandRunner.Result result = new CommandRunner().root("id");
                if (result.exitCode != 0 || !result.output.contains("uid=0")) {
                    throw new IllegalStateException("Root 管理器未授予本应用权限。");
                }
            } catch (Exception e) {
                failure = e;
            }
            Exception error = failure;
            runOnUiThread(() -> {
                busy = false;
                if (error != null) {
                    toast("Root 授权未完成：" + error.getMessage());
                } else {
                    toast("Root 已授权，正在重新检测设备。");
                }
                refresh();
            });
        });
    }

    private void showImportConfig() {
        transitionTo("setup", this::renderImportConfig, 1);
    }

    private void renderImportConfig() {
        if (InstallationService.isBusy() || snapshot != null && snapshot.runningDsu) return;
        setupMode = true;
        aboutMode = false;
        themeSettingsMode = false;
        selectedTab = 0;
        bottomBar.setVisibility(View.GONE);
        content.removeAllViews();
        int importInset = 16;
        content.setPadding(dp(importInset), dp(18), dp(importInset), dp(24));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(heading, params(MATCH, dp(52), 0, 0, 0, 12));
        TextView back = backButton();
        back.setOnClickListener(v -> showHome());
        heading.addView(back, new LinearLayout.LayoutParams(dp(52), MATCH));
        heading.addView(text("添加镜像", 22, INK, true), params(WRAP, WRAP, 12, 0, 0, 0));

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(18), dp(18), dp(18));
        form.setBackground(round(CARD, 14));
        content.addView(form, params(MATCH, WRAP, 0, 0, 0, 14));
        form.addView(text("DSU 槽位命名", 16, INK, true));
        setupSlotInput = new EditText(this);
        setupSlotInput.setSingleLine(true);
        setupSlotInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setupSlotInput.setKeyListener(android.text.method.DigitsKeyListener.getInstance(
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_"));
        setupSlotInput.setFilters(new InputFilter[]{asciiSlotFilter(), new InputFilter.LengthFilter(48)});
        if (pendingName != null) setupSlotInput.setText(pendingName);
        form.addView(setupSlotInput, params(MATCH, WRAP, 0, 6, 0, 14));
        slotValidationMessage = text("仅允许英文字母、数字和下划线", 12,
                darkTheme ? 0xffffaaa3 : 0xffa6433b, false);
        slotValidationMessage.setVisibility(View.GONE);
        form.addView(slotValidationMessage, params(MATCH, WRAP, 0, -8, 0, 12));
        form.addView(text("DSU 用户空间（GiB）", 16, INK, true));
        setupUserdataInput = new EditText(this);
        setupUserdataInput.setSingleLine(true);
        setupUserdataInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (pendingUserdataBytes > 0) {
            setupUserdataInput.setText(String.format(java.util.Locale.ROOT, "%.1f",
                    pendingUserdataBytes / 1073741824d));
        }
        form.addView(setupUserdataInput, params(MATCH, WRAP, 0, 6, 0, 0));

        content.addView(text("镜像来源", 17, INK, true), params(MATCH, WRAP, 2, 8, 0, 8));
        Button files = button("选择镜像或 ZIP", true);
        files.setOnClickListener(v -> launchFilePicker());
        content.addView(files, params(MATCH, dp(48), 0, 0, 0, 8));
        Button folder = button("选择 ROM 文件夹", false);
        folder.setOnClickListener(v -> launchFolderPicker());
        content.addView(folder, params(MATCH, dp(44), 0, 0, 0, 8));
        content.addView(text("支持 GSI system.img、分区 ZIP/文件夹和 super.img（含 sparse、.gz）。不支持 payload.bin、boot/vbmeta 或整包 OTA。",
                12, MUTED, false), params(MATCH, WRAP, 0, 0, 0, 12));
    }

    private boolean commitSetupValues() {
        if (setupSlotInput == null || setupUserdataInput == null) return false;
        String entered = setupSlotInput.getText().toString().trim();
        String slot = MultiSystemBackend.validateSlotId(entered);
        if (slot == null) {
            toast("槽位 ID 仅允许 1–48 位英文字母、数字和下划线。");
            return false;
        }
        double userdataGiB;
        try { userdataGiB = Double.parseDouble(setupUserdataInput.getText().toString().trim()); }
        catch (Exception e) { toast("请输入有效的 DSU 用户空间大小。"); return false; }
        if (Double.isNaN(userdataGiB) || Double.isInfinite(userdataGiB)
                || userdataGiB < 1 || userdataGiB > 64) {
            toast("DSU 用户空间需在 1–64 GiB 之间。");
            return false;
        }
        if (snapshot != null) {
            for (SystemSlot existing : snapshot.slots) {
                if (existing.id.equals(slot)) { toast("该槽位已存在，请换一个名称。"); return false; }
            }
        }
        pendingName = slot;
        pendingUserdataBytes = (long) (userdataGiB * 1073741824d);
        return true;
    }

    private InputFilter asciiSlotFilter() {
        return (source, start, end, dest, dstart, dend) -> {
            StringBuilder accepted = new StringBuilder();
            for (int i = start; i < end; i++) {
                char character = source.charAt(i);
                if ((character >= 'A' && character <= 'Z')
                        || (character >= 'a' && character <= 'z')
                        || (character >= '0' && character <= '9')
                        || character == '_') {
                    accepted.append(character);
                }
            }
            String original = source.subSequence(start, end).toString();
            boolean invalid = !accepted.toString().equals(original);
            if (slotValidationMessage != null) {
                slotValidationMessage.post(() -> slotValidationMessage.setVisibility(
                        invalid ? View.VISIBLE : View.GONE));
            }
            return invalid ? accepted.toString() : null;
        };
    }

    private void launchFilePicker() {
        if (!commitSetupValues()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_IMAGE);
    }

    private void launchFolderPicker() {
        if (!commitSetupValues()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_FOLDER);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_IMAGE && requestCode != PICK_FOLDER) return;
        if (resultCode != RESULT_OK || data == null) {
            if (setupMode && setupSlotInput == null) showImportConfig();
            return;
        }
        if (requestCode == PICK_FOLDER) {
            Uri tree = data.getData();
            if (tree == null) return;
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            scanRomFolder(tree);
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData clip = data.getClipData();
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) { toast("没有选择镜像文件。"); return; }
        for (Uri uri : uris) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
        }
        ArrayList<String> names = new ArrayList<>();
        for (Uri uri : uris) names.add(displayName(uri));
        finishSelection(uris, names);
    }

    private void scanRomFolder(Uri tree) {
        if (busy) return;
        setBusy(true);
        worker.execute(() -> {
            ArrayList<RomImageImporter.NamedUri> files = new ArrayList<>();
            Exception failure = null;
            try {
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
                try (Cursor cursor = getContentResolver().query(children,
                        new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null) {
                        while (cursor.moveToNext()) {
                            String name = cursor.getString(1);
                            if (MultiSystemBackend.partitionFromFilename(name) == null
                                    && !RomImageImporter.isSuperImage(name)) continue;
                            Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                            files.add(new RomImageImporter.NamedUri(name, child));
                        }
                    }
                }
                if (files.isEmpty()) throw new IllegalArgumentException("文件夹内没有找到支持的分区镜像。");
            } catch (Exception e) { failure = e; }
            Exception error = failure;
            runOnUiThread(() -> {
                busy = false;
                if (error != null) {
                    toast("读取 ROM 文件夹失败：" + error.getMessage());
                    return;
                }
                ArrayList<Uri> uris = new ArrayList<>();
                ArrayList<String> names = new ArrayList<>();
                for (RomImageImporter.NamedUri file : files) {
                    uris.add(file.uri);
                    names.add(file.name);
                }
                finishSelection(uris, names);
            });
        });
    }

    private void finishSelection(List<Uri> uris, List<String> names) {
        pendingUris.clear();
        pendingNames.clear();
        pendingUris.addAll(uris);
        pendingNames.addAll(names);
        setupMode = false;
        showHome();
        toast("已添加 " + pendingUris.size() + " 个镜像，点击“安装系统”继续。");
    }

    private void startInstallation(List<Uri> uris, List<String> names) {
        if (busy || InstallationService.isBusy()) { toast("已有镜像安装任务正在运行。"); return; }
        if (pendingName == null || uris == null || uris.isEmpty()) {
            toast("请先添加镜像并配置槽位。");
            return;
        }
        if (snapshot == null || !snapshot.ready()) {
            toast("需要 Root 与设备端 gsi_tool 才能安装。");
            return;
        }
        if (snapshot.runningDsu) {
            toast("请先重启回主系统，再安装新的 DSU 槽位。");
            return;
        }
        importAndInstall(uris, names);
    }

    private void importAndInstall(List<Uri> uris, List<String> names) {
        if (uris == null || uris.isEmpty() || names == null || uris.size() != names.size()) {
            toast("镜像文件列表无效。"); return;
        }
        Intent intent = new Intent(this, InstallationService.class)
                .setAction(InstallationService.PREPARE)
                .putExtra("slot", pendingName)
                .putExtra("userdata", pendingUserdataBytes)
                .putParcelableArrayListExtra("uris", new ArrayList<>(uris))
                .putStringArrayListExtra("names", new ArrayList<>(names));
        startInstallService(intent);
        busy = true;
        updateInstallControls();
        onInstallChanged();
    }

    private void updateInstallControls() {
        boolean hasSelection = !pendingUris.isEmpty() && pendingName != null;
        boolean allowed = snapshot != null && snapshot.ready() && !snapshot.runningDsu;
        if (installButton != null) {
            boolean enabled = hasSelection && allowed && !busy;
            installButton.setEnabled(enabled);
            installButton.setAlpha(enabled ? 1f : 0.48f);
        }
        if (addMirrorButton != null) {
            boolean canAdd = pendingUris.isEmpty() && !InstallationService.isBusy()
                    && (snapshot == null || !snapshot.runningDsu);
            addMirrorButton.setEnabled(canAdd);
            addMirrorButton.setAlpha(canAdd ? 1f : 0.48f);
        }
    }

    @Override public void onBackPressed() {
        if (setupMode) {
            showHome();
            return;
        }
        if (aboutMode) {
            showSettings();
            return;
        }
        if (themeSettingsMode) {
            showSettings();
            return;
        }
        super.onBackPressed();
    }

    private void startInstallService(Intent intent) {
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    private void onInstallChanged() {
        syncServiceEvents();
        InstallationService.Phase phase = InstallationService.phase;
        busy = phase == InstallationService.Phase.PREPARING || phase == InstallationService.Phase.WRITING;
        updateInstallControls();
        renderInstallStatus();
        if (phase != lastInstallPhase && (phase == InstallationService.Phase.SUCCEEDED
                || phase == InstallationService.Phase.FAILED)) {
            int generation = InstallationService.operationGeneration();
            if (generation != lastTerminalLogGeneration) {
                appendLog(InstallationService.message);
                lastTerminalLogGeneration = generation;
            }
            toast(InstallationService.message);
            if (phase == InstallationService.Phase.SUCCEEDED) {
                pendingUris.clear();
                pendingNames.clear();
                pendingName = null;
                pendingUserdataBytes = 0L;
                updateInstallControls();
                refresh();
            }
        }
        lastInstallPhase = phase;
    }

    private void renderInstallStatus() {
        InstallationService.Phase phase = InstallationService.phase;
        boolean active = phase == InstallationService.Phase.PREPARING
                || phase == InstallationService.Phase.WRITING;
        if (operationStatus != null) {
            if (active || phase == InstallationService.Phase.SUCCEEDED
                    || phase == InstallationService.Phase.FAILED) {
                String status = InstallationService.message;
                if (active && !InstallationService.installSummary.isEmpty()) {
                    status += "\n\n" + InstallationService.installSummary;
                }
                operationStatus.setText(status);
                operationStatus.setVisibility(View.VISIBLE);
            } else {
                operationStatus.setVisibility(View.GONE);
            }
        }
        if (installProgress != null) {
            boolean visible = active || phase == InstallationService.Phase.SUCCEEDED
                    || phase == InstallationService.Phase.FAILED;
            installProgress.setVisibility(visible ? View.VISIBLE : View.GONE);
            installProgress.setProgress(Math.max(0, Math.min(100, InstallationService.progressPercent)));
        }
    }

    private void confirmSwitch(SystemSlot slot) {
        if (busy) return;
        boolean directSwitch = snapshot != null && snapshot.runningDsu;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("切换启动系统？")
                .setMessage((directSwitch
                        ? "当前正在 DSU 内运行。设备会把下一次启动目标改为“"
                        : "下一次启动将进入“") + slot.name
                        + "”，仅设置单次启动目标，不改写分区，然后重启设备。若目标无法启动，请使用设备既有的 DSU 回退或 Recovery 流程。")
                .setNegativeButton("取消", null).setPositiveButton("切换并重启", (dialogInterface, which) -> {
                    setBusy(true);
                    worker.execute(() -> {
                        Exception failure = null;
                        try {
                            if (directSwitch) backend.enableOnceFromDsu(slot.id);
                            else backend.enableOnce(slot.id);
                            backend.reboot();
                        } catch (Exception e) { failure = e; }
                        Exception error = failure;
                        runOnUiThread(() -> { busy = false; if (error != null) { appendLog("切换失败：" + error.getMessage()); toast("切换失败"); if (feedback != null) feedback.setText(error.getMessage()); } else appendLog("切换启动目标：" + slot.name); });
                    });
                }).create();
        showStyledConfirmation(dialog);
    }

    private void confirmReturnMain() {
        if (busy || snapshot == null || !snapshot.runningDsu) return;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("重启回主系统？")
                .setMessage("将禁用当前 DSU 的启动状态并重启设备。副系统槽位及其数据会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("回主系统", (dialogInterface, which) -> {
                    setBusy(true);
                    worker.execute(() -> {
                        Exception failure = null;
                        try { backend.disableAndReboot(); } catch (Exception e) { failure = e; }
                        Exception error = failure;
                        runOnUiThread(() -> {
                            busy = false;
                            if (error != null) {
                                appendLog("回主系统失败：" + error.getMessage());
                                toast("回主系统失败");
                                if (feedback != null) feedback.setText(error.getMessage());
                            } else {
                                appendLog("已禁用 DSU 并请求重启回主系统");
                            }
                        });
                    });
                }).create();
        showStyledConfirmation(dialog);
    }

    private void confirmDelete(SystemSlot slot) {
        if (busy) return;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("删除副系统？")
                .setMessage("将删除“" + slot.name + "”及其数据，不可撤销。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialogInterface, which) -> {
                    setBusy(true);
                    worker.execute(() -> {
                        Exception failure = null;
                        try { backend.delete(slot.id); } catch (Exception e) { failure = e; }
                        Exception error = failure;
                        runOnUiThread(() -> { busy = false; if (error != null) { appendLog("删除失败：" + error.getMessage()); toast("删除失败"); if (feedback != null) feedback.setText(error.getMessage()); } else { appendLog("删除槽位：" + slot.name); refresh(); } });
                    });
                }).create();
        showStyledConfirmation(dialog);
    }

    private void setBusy(boolean value) { busy = value; updateInstallControls(); }

    private void appendLog(String line) {
        addLogLine(line);
        if (selectedTab == 1 && !busy) showSettings();
    }

    private void addLogLine(String line) {
        if (line == null || line.trim().isEmpty()) return;
        if (line.length() > 6000) line = line.substring(0, 6000) + "…[日志已截断]";
        logEntries.add(java.text.DateFormat.getTimeInstance().format(new java.util.Date()) + "  " + line);
        while (logEntries.size() > 100) logEntries.remove(0);
        persistLogs();
    }

    private void syncServiceEvents() {
        int generation = InstallationService.operationGeneration();
        if (generation == 0) return;
        if (generation != serviceLogGeneration) {
            serviceLogGeneration = generation;
            serviceLogIndex = 0;
        }
        ArrayList<String> events = InstallationService.operationEventsFrom(serviceLogIndex);
        for (String line : events) addLogLine(line);
        serviceLogIndex += events.size();
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putInt("service_log_generation", serviceLogGeneration)
                .putInt("service_log_index", serviceLogIndex).apply();
    }

    private void loadLogs() {
        android.content.SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        String encoded = prefs.getString("logs", "");
        if (encoded.isEmpty()) return;
        try {
            JSONArray values = new JSONArray(encoded);
            for (int i = 0; i < values.length() && logEntries.size() < 100; i++) {
                String value = values.optString(i, "");
                if (!value.isEmpty()) logEntries.add(value);
            }
            if (!prefs.getBoolean("logs_ascending", false)) {
                java.util.Collections.reverse(logEntries);
                persistLogs();
                prefs.edit().putBoolean("logs_ascending", true).apply();
            }
        } catch (Exception ignored) { }
    }

    private void persistLogs() {
        JSONArray values = new JSONArray();
        for (String line : logEntries) values.put(line);
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString("logs", values.toString())
                .putBoolean("logs_ascending", true).apply();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024d);
        if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576d);
        return String.format(java.util.Locale.ROOT, "%.2f GiB", bytes / 1073741824d);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private String runningSystemLabel() {
        if (snapshot != null && snapshot.runningDsu) {
            return snapshot.runningSlot.isEmpty() ? "DSU（运行中）" : "DSU · " + snapshot.runningSlot;
        }
        return backend.hostLabel();
    }

    private String currentSlotLabel() {
        if (snapshot != null && snapshot.runningDsu && !snapshot.runningSlot.isEmpty()) {
            return snapshot.runningSlot;
        }
        return "主系统";
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment(); return last == null ? "system.img" : last;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label); button.setTextSize(14); button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setBackground(round(primary ? BUTTON_PRIMARY : SOFT_SURFACE, 14));
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void card(ViewGroup parent, View child, int radius, int left, int top, int right, int bottom, int color) {
        LinearLayout wrapper = new LinearLayout(this); wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(left), dp(top), dp(right), dp(bottom)); wrapper.setBackground(round(color, radius));
        wrapper.addView(child, new LinearLayout.LayoutParams(MATCH, WRAP));
        parent.addView(wrapper, params(MATCH, WRAP, 0, 0, 0, 10));
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private LinearLayout.LayoutParams params(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height); p.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return p;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy() {
        InstallationService.unlisten(installListener);
        worker.shutdownNow();
        super.onDestroy();
    }
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
}
