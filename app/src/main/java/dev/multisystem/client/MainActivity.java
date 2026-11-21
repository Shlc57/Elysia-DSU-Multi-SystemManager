package dev.multisystem.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.GZIPInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_IMAGE = 41;
    private static final int BLUE = 0xff52769a;
    private static final int INK = 0xff1d2733;
    private static final int MUTED = 0xff697785;
    private static final int SURFACE = 0xfff6f7fb;

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
    private String pendingName;
    private long pendingUserdataBytes = 8L * 1024L * 1024L * 1024L;
    private boolean busy;
    private int selectedTab;
    private MultiSystemBackend.Snapshot snapshot;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(SURFACE);
        getWindow().setNavigationBarColor(SURFACE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        buildShell();
        showHome();
        refresh();
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SURFACE);
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
        bottomBar.setBackground(round(Color.WHITE, 20));
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(MATCH, dp(68));
        navParams.setMargins(dp(14), dp(4), dp(14), dp(10));
        root.addView(bottomBar, navParams);
        setContentView(root);
    }

    private void showHome() {
        selectedTab = 0;
        content.removeAllViews();
        content.setPadding(dp(20), dp(20), dp(20), dp(20));
        content.addView(text("多系统", 26, INK, true), params(WRAP, WRAP, 0, 0, 0, 16));

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setPadding(dp(20), dp(18), dp(20), dp(18));
        status.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff466b91, 0xff6685a4}));
        status.setElevation(dp(3));
        content.addView(status, params(MATCH, WRAP, 0, 0, 0, 16));
        TextView header = text("◉  多系统管理器", 20, Color.WHITE, true);
        status.addView(header);
        deviceValue = text(runningSystemLabel(), 13, 0xffe6edf4, false);
        status.addView(deviceValue, params(MATCH, WRAP, 0, 8, 0, 18));
        View divider = new View(this);
        divider.setBackgroundColor(0x55ffffff);
        status.addView(divider, new LinearLayout.LayoutParams(MATCH, dp(1)));
        LinearLayout indicators = new LinearLayout(this);
        indicators.setOrientation(LinearLayout.HORIZONTAL);
        indicators.setPadding(0, dp(13), 0, 0);
        status.addView(indicators, params(MATCH, WRAP, 0, 0, 0, 0));
        rootValue = text("检查中", 14, Color.WHITE, true);
        dsuValue = text("检查中", 14, Color.WHITE, true);
        addMetric(indicators, "Root 授权", rootValue);
        addMetric(indicators, "DSU / gsi_tool", dsuValue);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(buttons, params(MATCH, WRAP, 0, 0, 0, 18));
        Button install = button("＋ 安装系统", true);
        install.setEnabled(snapshot != null && snapshot.ready() && !snapshot.runningDsu && !busy);
        install.setAlpha(install.isEnabled() ? 1f : 0.55f);
        install.setOnClickListener(v -> askInstallName());
        buttons.addView(install, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button reboot = button("刷新", false);
        reboot.setOnClickListener(v -> refresh());
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(dp(92), dp(48));
        refreshParams.leftMargin = dp(10);
        buttons.addView(reboot, refreshParams);
        if (snapshot != null && snapshot.runningDsu) {
            Button backMain = button("回主系统", false);
            backMain.setOnClickListener(v -> confirmReturnMain());
            LinearLayout.LayoutParams mainParams = new LinearLayout.LayoutParams(dp(96), dp(48));
            mainParams.leftMargin = dp(8);
            buttons.addView(backMain, mainParams);
        }
        operationStatus = text("", 13, MUTED, false);
        operationStatus.setVisibility(View.GONE);
        content.addView(operationStatus, params(MATCH, WRAP, 2, 0, 0, 10));

        TextView slotsHeading = text("系统", 19, INK, true);
        content.addView(slotsHeading, params(MATCH, WRAP, 2, 0, 0, 9));
        LinearLayout slots = new LinearLayout(this);
        slots.setOrientation(LinearLayout.VERTICAL);
        content.addView(slots, params(MATCH, WRAP, 0, 0, 0, 12));
        renderSlots(slots);

        TextView safety = text("第一版基于设备现有 DSU/gsid 槽位能力管理系统。未在你的机型与 ROM 上验证前，不保证多槽启动、驱动兼容或数据完整；操作前请备份。", 13, MUTED, false);
        card(content, safety, 16, 16, 16, 16, 16, 0xffe9eef5);
        buildNav();
    }

    private void showSystems() {
        selectedTab = 1;
        content.removeAllViews();
        content.setPadding(dp(20), dp(22), dp(20), dp(24));
        content.addView(text("系统", 26, INK, true), params(MATCH, WRAP, 0, 0, 0, 18));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(17), dp(18), dp(17));
        card.setBackground(round(Color.WHITE, 18));
        content.addView(card, params(MATCH, WRAP, 0, 0, 0, 12));
        card.addView(text("设备与权限", 18, INK, true));
        deviceValue = text(runningSystemLabel() + "\n" + backend.deviceLabel(), 14, MUTED, false);
        card.addView(deviceValue, params(MATCH, WRAP, 0, 10, 0, 0));
        rootValue = text("检测中", 14, MUTED, false);
        card.addView(rootValue, params(MATCH, WRAP, 0, 8, 0, 0));
        dsuValue = text("检测中", 14, MUTED, false);
        card.addView(dsuValue, params(MATCH, WRAP, 0, 6, 0, 0));
        toolValue = text("gsi_tool：检测中", 14, MUTED, false);
        card.addView(toolValue, params(MATCH, WRAP, 0, 6, 0, 0));
        TextView storage = text(snapshot == null || snapshot.freeBytes <= 0
                ? "/data 可用空间：未知"
                : "/data 可用空间：" + formatBytes(snapshot.freeBytes), 13, MUTED, false);
        card.addView(storage, params(MATCH, WRAP, 0, 6, 0, 0));
        Button refresh = button("重新检测", true);
        refresh.setOnClickListener(v -> refresh());
        card.addView(refresh, params(MATCH, dp(46), 0, 14, 0, 0));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(actions, params(MATCH, WRAP, 0, 0, 0, 12));
        Button install = button("＋ 安装系统", true);
        install.setEnabled(snapshot != null && snapshot.ready() && !snapshot.runningDsu && !busy);
        install.setAlpha(install.isEnabled() ? 1f : 0.55f);
        install.setOnClickListener(v -> askInstallName());
        actions.addView(install, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button reload = button("刷新", false);
        reload.setOnClickListener(v -> refresh());
        LinearLayout.LayoutParams reloadParams = new LinearLayout.LayoutParams(dp(88), dp(48));
        reloadParams.leftMargin = dp(8);
        actions.addView(reload, reloadParams);
        content.addView(text("已安装槽位", 18, INK, true), params(MATCH, WRAP, 2, 8, 0, 8));
        LinearLayout slots = new LinearLayout(this);
        slots.setOrientation(LinearLayout.VERTICAL);
        content.addView(slots, params(MATCH, WRAP, 0, 0, 0, 12));
        renderSlots(slots);
        feedback = text("设备适配状态将在检测后显示。", 14, MUTED, false);
        card(content, feedback, 16, 16, 16, 16, 16, 0xffe9eef5);
        buildNav();
    }

    private void showLogs() {
        selectedTab = 2;
        content.removeAllViews();
        content.setPadding(dp(20), dp(22), dp(20), dp(24));
        content.addView(text("日志", 26, INK, true), params(MATCH, WRAP, 0, 0, 0, 18));
        TextView intro = text("仅显示本次运行期间的管理器操作结果。", 13, MUTED, false);
        content.addView(intro, params(MATCH, WRAP, 0, 0, 0, 12));
        if (logEntries.isEmpty()) {
            TextView empty = text("暂无操作记录。", 14, MUTED, false);
            card(content, empty, 16, 16, 16, 16, 16, 0xffe9eef5);
        } else {
            for (String line : logEntries) {
                TextView entry = text(line, 13, INK, false);
                card(content, entry, 14, 14, 13, 14, 13, Color.WHITE);
            }
        }
        buildNav();
    }

    private void showSettings() {
        selectedTab = 3;
        content.removeAllViews();
        content.setPadding(dp(20), dp(22), dp(20), dp(24));
        content.addView(text("设置", 26, INK, true), params(MATCH, WRAP, 0, 0, 0, 18));
        TextView compatibility = text("第一版范围\n\n需要已解锁 Bootloader、Root、设备 DSU/动态分区支持和 gsi_tool。安装方式为 Root 下创建命名 DSU 槽；不直接刷写物理分区。先支持 raw system.img 与 gzip 压缩的 system.img.gz。完整 OTA ZIP、super.img、多分区 ROM、驱动移植和任意机型适配暂不包含。", 15, INK, false);
        card(content, compatibility, 18, 18, 18, 18, 18, 0xffffffff);
        TextView operations = text("安全策略\n\n安装使用 gsi_tool --install-dir 创建新槽，不覆盖已有槽；启动优先使用单次启动，失败后可回主系统。删除当前正在运行的槽位会被拒绝。空间、SELinux、gsid/内核兼容性仍由设备端决定；重要数据请先备份。", 13, MUTED, false);
        card(content, operations, 18, 18, 18, 18, 18, 0xffe9eef5);
        buildNav();
    }

    private void buildNav() {
        bottomBar.removeAllViews();
        addNav("⌂", "首页", 0);
        addNav("▣", "系统", 1);
        addNav("≡", "日志", 2);
        addNav("⚙", "设置", 3);
    }

    private void addNav(String icon, String label, int index) {
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(12), dp(5), dp(12), dp(3));
        item.setBackground(round(selectedTab == index ? 0xffdce8f8 : Color.TRANSPARENT, 22));
        TextView glyph = text(icon, 22, selectedTab == index ? BLUE : 0xff69727b, true);
        glyph.setGravity(Gravity.CENTER);
        item.addView(glyph);
        TextView name = text(label, 11, selectedTab == index ? BLUE : 0xff69727b, selectedTab == index);
        item.addView(name);
        item.setOnClickListener(v -> {
            if (index == 0) showHome();
            else if (index == 1) showSystems();
            else if (index == 2) showLogs();
            else showSettings();
        });
        bottomBar.addView(item, new LinearLayout.LayoutParams(0, MATCH, 1));
    }

    private void addMetric(LinearLayout parent, String label, TextView value) {
        LinearLayout metric = new LinearLayout(this);
        metric.setOrientation(LinearLayout.VERTICAL);
        parent.addView(metric, new LinearLayout.LayoutParams(0, WRAP, 1));
        metric.addView(text(label, 12, 0xffe0e8f0, false));
        metric.addView(value, params(MATCH, WRAP, 0, 5, 0, 0));
    }

    private void renderSlots(LinearLayout slots) {
        if (snapshot == null || !snapshot.ready() || snapshot.slots.isEmpty()) {
            String message = snapshot == null ? "正在检测设备…"
                    : !snapshot.rootGranted ? "尚未获得 Root 授权。请在 Root 管理器中允许本应用后刷新。"
                    : !snapshot.gsiToolAvailable ? "未找到 gsi_tool 或 DSU 不可用。请确认系统支持动态分区，并检查 ROM/Root 环境。"
                    : snapshot.runningDsu && snapshot.runningSlot.isEmpty()
                    ? "当前正在运行 DSU，但系统未报告活动槽 ID。为安全起见，安装和删除操作已被限制。"
                    : "暂无已安装 DSU 槽。点击“安装系统”导入 system.img。";
            TextView empty = text(message,
                    14, MUTED, false);
            card(slots, empty, 16, 18, 18, 18, 18, 0xffe9eef5);
            return;
        }
        for (SystemSlot slot : snapshot.slots) {
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(18), dp(15), dp(18), dp(15));
            tile.setBackground(round(0xffe9eef5, 16));
            slots.addView(tile, params(MATCH, WRAP, 0, 0, 0, 6));
            tile.addView(text((slot.active ? "▣  " : "◆  ") + slot.name,
                    17, slot.active ? BLUE : INK, true));
            tile.addView(text(slot.version + "  ·  " + slot.sizeLabel(), 13, MUTED, false),
                    params(MATCH, WRAP, 26, 5, 0, 0));
            String slotState = slot.active ? "正在运行" : slot.selected ? "下次启动目标" : slot.state;
            tile.addView(text(slotState + "  ·  "
                            + (slot.dataIsolated ? "数据独立" : "隔离未确认"),
                    12, slot.dataIsolated ? 0xff47775e : 0xff946b32, false),
                    params(MATCH, WRAP, 26, 5, 0, 0));
            if (!slot.active) {
                Button action = button(slot.bootable ? "切换并重启" : "不可启动（设备端未确认）", true);
                action.setEnabled(slot.bootable && !busy && snapshot != null && !snapshot.runningDsu);
                action.setAlpha(action.isEnabled() ? 1f : 0.55f);
                action.setOnClickListener(v -> confirmSwitch(slot));
                tile.addView(action, params(MATCH, dp(42), 26, 10, 0, 0));
                if (snapshot == null || !snapshot.runningDsu) {
                    TextView delete = text("删除该槽位", 12, 0xff8b514a, false);
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
                busy = false;
                if (selectedTab == 0) showHome();
                else if (selectedTab == 1) showSystems();
                updateStatus(error);
            });
        });
    }

    private void updateStatus(Exception error) {
        String root = error != null ? "检测失败" : snapshot != null && snapshot.rootGranted ? "已授权" : "未授权";
        String dsu = error != null ? "检测失败" : snapshot != null && snapshot.gsiToolAvailable ? "已就绪" : "不可用";
        if (rootValue != null) rootValue.setText(root);
        if (dsuValue != null) dsuValue.setText(dsu);
        if (toolValue != null) toolValue.setText("gsi_tool：" + (snapshot == null || !snapshot.gsiToolAvailable ? "未检测到" : snapshot.gsiToolPath)
                + (snapshot != null && snapshot.freeBytes > 0 ? "\n/data 可用：" + formatBytes(snapshot.freeBytes) : ""));
        if (feedback != null) feedback.setText(error != null ? "检测失败：" + error.getMessage()
                : snapshot != null && snapshot.ready() ? "Root 与 gsi_tool 已就绪。当前安装只支持 system.img / system.img.gz。"
                : "需要 Root 和设备端 gsi_tool；未满足时不会执行安装。");
    }

    private void askInstallName() {
        if (busy) return;
        if (snapshot == null || !snapshot.ready()) {
            toast("Root 与 gsi_tool 就绪后才能安装。");
            return;
        }
        if (snapshot.runningDsu) {
            toast("请先重启回主系统，再安装新的 DSU 槽位。");
            return;
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("槽位 ID，例如 coloros17");
        EditText userdataInput = new EditText(this);
        userdataInput.setSingleLine(true);
        userdataInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        userdataInput.setText("8");
        userdataInput.setHint("userdata 大小（GiB）");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.addView(input, params(MATCH, WRAP, 0, 0, 0, 6));
        form.addView(userdataInput, params(MATCH, WRAP, 0, 0, 0, 0));
        new AlertDialog.Builder(this).setTitle("安装副系统")
                .setMessage("第一版仅安装一个 system 镜像分区；请确认镜像适配当前机型。")
                .setView(form).setNegativeButton("取消", null)
                .setPositiveButton("选择镜像", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    String slot = MultiSystemBackend.validateSlotId(name);
                    if (slot == null) { toast("槽位 ID 仅允许 1–48 位英文字母、数字和下划线。"); return; }
                    double userdataGiB;
                    try { userdataGiB = Double.parseDouble(userdataInput.getText().toString().trim()); }
                    catch (Exception e) { toast("请输入有效的 userdata 大小。"); return; }
                    if (Double.isNaN(userdataGiB) || Double.isInfinite(userdataGiB)
                            || userdataGiB < 1 || userdataGiB > 64) {
                        toast("userdata 大小需在 1–64 GiB 之间。"); return;
                    }
                    for (SystemSlot slot : snapshot.slots) {
                        if (slot.id.equals(name)) { toast("该槽位已存在，请换一个名称。"); return; }
                    }
                    pendingName = slot;
                    pendingUserdataBytes = (long) (userdataGiB * 1024d * 1024d * 1024d);
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    startActivityForResult(intent, PICK_IMAGE);
                }).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String displayName = displayName(uri);
        String lower = displayName.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".img") && !lower.endsWith(".img.gz")) { toast("第一版仅接受 raw .img 或 gzip 压缩的 .img.gz。"); return; }
        new AlertDialog.Builder(this).setTitle("确认安装")
                .setMessage("镜像：" + displayName + "\n槽位：" + pendingName + "\nuserdata：" + (pendingUserdataBytes / 1073741824L) + " GiB\n\n操作会创建 DSU 分区并占用存储。请确认镜像适配当前机型、Bootloader 已解锁，并已备份数据。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始安装", (dialog, which) -> importAndInstall(uri, displayName)).show();
    }

    private void importAndInstall(Uri uri, String displayName) {
        setBusy(true);
        if (selectedTab == 1 && feedback != null) feedback.setText("正在暂存镜像…请保持设备供电。");
        else if (selectedTab == 0 && operationStatus != null) {
            operationStatus.setText("正在暂存镜像…请保持设备供电。");
            operationStatus.setVisibility(View.VISIBLE);
        }
        worker.execute(() -> {
            File staged = null;
            File rawImage = null;
            Exception failure = null;
            try {
                File externalFiles = getExternalFilesDir(null);
                if (externalFiles == null) throw new IllegalStateException("外部存储当前不可用。");
                File directory = new File(externalFiles, "imports");
                if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("无法创建镜像暂存目录。");
                String suffix = displayName.toLowerCase().endsWith(".gz") ? ".img.gz" : ".img";
                String token = Long.toHexString(System.currentTimeMillis());
                staged = new File(directory, "system-import-" + token + suffix);
                long available = new StatFs(directory.getAbsolutePath()).getAvailableBytes();
                try (InputStream input = getContentResolver().openInputStream(uri);
                     OutputStream output = new java.io.FileOutputStream(staged)) {
                    if (input == null) throw new IllegalStateException("无法读取所选镜像。");
                    byte[] buffer = new byte[1024 * 1024]; long copied = 0; int count;
                    while ((count = input.read(buffer)) != -1) {
                        copied += count;
                        if (copied > available) throw new IllegalStateException("暂存空间不足。");
                        output.write(buffer, 0, count);
                    }
                    if (copied == 0) throw new IllegalStateException("镜像文件为空。");
                }
                if (suffix.endsWith(".gz")) {
                    rawImage = new File(directory, "system-ready-" + token + ".img");
                    long decompressAvailable = new StatFs(directory.getAbsolutePath()).getAvailableBytes();
                    try (InputStream input = new GZIPInputStream(new FileInputStream(staged));
                         OutputStream output = new FileOutputStream(rawImage)) {
                        byte[] buffer = new byte[1024 * 1024];
                        long written = 0; int count;
                        while ((count = input.read(buffer)) != -1) {
                            written += count;
                            if (written > decompressAvailable) throw new IllegalStateException("解压 system.img 所需暂存空间不足。");
                            output.write(buffer, 0, count);
                        }
                        if (written == 0) throw new IllegalStateException("压缩镜像解压后为空。");
                    }
                } else {
                    rawImage = staged;
                }
                File installImage = rawImage;
                backend.install(pendingName, installImage, pendingUserdataBytes,
                        (sent, total) -> runOnUiThread(() -> {
                            String message = "正在写入 DSU 槽：" + formatBytes(sent)
                                    + (total > 0 ? " / " + formatBytes(total) : "");
                            if (selectedTab == 1 && feedback != null) feedback.setText(message);
                            else if (selectedTab == 0 && operationStatus != null) {
                                operationStatus.setText(message);
                                operationStatus.setVisibility(View.VISIBLE);
                            }
                        }));
            } catch (Exception e) { failure = e; }
            finally {
                if (rawImage != null && rawImage != staged && rawImage.exists()) rawImage.delete();
                if (staged != null && staged.exists()) staged.delete();
            }
            Exception error = failure;
            runOnUiThread(() -> {
                busy = false;
                if (operationStatus != null) operationStatus.setVisibility(View.GONE);
                if (error != null) {
                    appendLog("安装失败：" + error.getMessage());
                    if (selectedTab == 1 && feedback != null) feedback.setText("安装未完成：" + error.getMessage());
                    toast("安装失败");
                } else {
                    appendLog("安装完成：" + pendingName);
                    toast("安装完成；可在槽位卡片中选择单次启动");
                    refresh();
                }
            });
        });
    }

    private void confirmSwitch(SystemSlot slot) {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("切换启动系统？")
                .setMessage("下一次启动将进入“" + slot.name + "”，设备将立即重启。请先确认主系统可以恢复并已备份数据。")
                .setNegativeButton("取消", null).setPositiveButton("切换并重启", (dialog, which) -> {
                    setBusy(true);
                    worker.execute(() -> {
                        Exception failure = null;
                        try { backend.setNext(slot.id); backend.reboot(); } catch (Exception e) { failure = e; }
                        Exception error = failure;
                        runOnUiThread(() -> { busy = false; if (error != null) { appendLog("切换失败：" + error.getMessage()); toast("切换失败"); if (feedback != null) feedback.setText(error.getMessage()); } else appendLog("切换启动目标：" + slot.name); });
                    });
                }).show();
    }

    private void confirmReturnMain() {
        if (busy || snapshot == null || !snapshot.runningDsu) return;
        new AlertDialog.Builder(this).setTitle("重启回主系统？")
                .setMessage("将禁用当前 DSU 的启动状态并重启设备。副系统槽位及其数据会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("回主系统", (dialog, which) -> {
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
                }).show();
    }

    private void confirmDelete(SystemSlot slot) {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("删除副系统？")
                .setMessage("将删除“" + slot.name + "”及其数据，不可撤销。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> {
                    setBusy(true);
                    worker.execute(() -> {
                        Exception failure = null;
                        try { backend.delete(slot.id); } catch (Exception e) { failure = e; }
                        Exception error = failure;
                        runOnUiThread(() -> { busy = false; if (error != null) { appendLog("删除失败：" + error.getMessage()); toast("删除失败"); if (feedback != null) feedback.setText(error.getMessage()); } else { appendLog("删除槽位：" + slot.name); refresh(); } });
                    });
                }).show();
    }

    private void setBusy(boolean value) { busy = value; }

    private void appendLog(String line) {
        logEntries.add(0, java.text.DateFormat.getTimeInstance().format(new java.util.Date()) + "  " + line);
        while (logEntries.size() > 100) logEntries.remove(logEntries.size() - 1);
        if (selectedTab == 2) showLogs();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024d);
        if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576d);
        return String.format(java.util.Locale.ROOT, "%.2f GiB", bytes / 1073741824d);
    }

    private String runningSystemLabel() {
        if (snapshot != null && snapshot.runningDsu) {
            return snapshot.runningSlot.isEmpty() ? "DSU（运行中）" : "DSU · " + snapshot.runningSlot;
        }
        return backend.hostLabel();
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
        button.setTextColor(primary ? Color.WHITE : 0xff41566d);
        button.setBackground(round(primary ? BLUE : 0xffdce5ef, 15));
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
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
}
