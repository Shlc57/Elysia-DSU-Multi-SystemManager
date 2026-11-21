package dev.multisystem.client;

import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** First-build DSU backend based on Android's gsi_tool slot support. */
final class MultiSystemBackend {
    private static final Pattern SLOT_ID = Pattern.compile("[A-Za-z0-9_]{1,48}");
    private static final Pattern SLOT_LINE = Pattern.compile("^\\[\\d+\\]\\s+(.+)$");
    private static final String DATA_ROOT = "/data/gsi/dsu";
    private static final String META_ROOT = "/metadata/gsi/dsu";
    private static final long MIN_USERDATA_BYTES = 1024L * 1024L * 1024L;
    private static final long MAX_USERDATA_BYTES = 64L * 1024L * 1024L * 1024L;

    private final CommandRunner runner = new CommandRunner();

    static final class Snapshot {
        final boolean rootGranted;
        final boolean gsiToolAvailable;
        final String gsiToolPath;
        final String activeSlot;
        final String runningSlot;
        final boolean runningDsu;
        final long freeBytes;
        final List<SystemSlot> slots;

        Snapshot(boolean rootGranted, boolean gsiToolAvailable, String gsiToolPath,
                 String activeSlot, String runningSlot, boolean runningDsu,
                 long freeBytes, List<SystemSlot> slots) {
            this.rootGranted = rootGranted;
            this.gsiToolAvailable = gsiToolAvailable;
            this.gsiToolPath = gsiToolPath;
            this.activeSlot = activeSlot;
            this.runningSlot = runningSlot;
            this.runningDsu = runningDsu;
            this.freeBytes = freeBytes;
            this.slots = slots;
        }

        boolean ready() { return rootGranted && gsiToolAvailable; }
    }

    Snapshot load() throws Exception {
        CommandRunner.Result identity = runner.root("id");
        boolean root = identity.exitCode == 0 && identity.output.contains("uid=0");
        if (!root) return new Snapshot(false, false, "", "", "", false, 0L, Collections.emptyList());

        CommandRunner.Result tool = runner.root("sh", "-c", "command -v gsi_tool");
        String path = tool.exitCode == 0 ? tool.output.trim() : "";
        if (path.isEmpty()) return new Snapshot(true, false, "", "", "", false, 0L, Collections.emptyList());

        CommandRunner.Result status = runner.root("gsi_tool", "status");
        if (status.exitCode != 0) {
            throw new IllegalStateException(status.output.isEmpty()
                    ? "gsi_tool status 执行失败（退出码 " + status.exitCode + "）。"
                    : status.output);
        }

        String metadataScript = "printf '__DSU_SELECTED__'; cat /metadata/gsi/dsu/active 2>/dev/null; "
                + "printf '\\n__DSU_RUNNING_SLOT__'; getprop ro.gsid.dsu_slot; "
                + "printf '\\n__DSU_RUNNING_FLAG__'; getprop ro.gsid.image_running; "
                + "printf '\\n__DSU_RUNNING_FLAG2__'; getprop ro.gsi.dsu; "
                + "printf '\\n__DSU_SIZES__\\n'; "
                + "for d in /data/gsi/dsu/*/; do [ -d \"$d\" ] || continue; "
                + "printf 'SIZE:%s:%s\\n' \"$(basename \"$d\")\" \"$(du -sk \"$d\" 2>/dev/null | cut -f1)\"; done; "
                + "printf '__DSU_SIZES_END__\\n__DSU_FREE__'; "
                + "df -k /data 2>/dev/null | awk 'END {print $4}'; printf '__DSU_FREE_END__'";
        CommandRunner.Result metadata = runner.root("sh", "-c", metadataScript);
        if (metadata.exitCode != 0) throw new IllegalStateException(metadata.output);

        String selected = section(metadata.output, "__DSU_SELECTED__", "__DSU_RUNNING_SLOT__").trim();
        String runningSlot = section(metadata.output, "__DSU_RUNNING_SLOT__", "__DSU_RUNNING_FLAG__").trim();
        String runningFlag = section(metadata.output, "__DSU_RUNNING_FLAG__", "__DSU_RUNNING_FLAG2__").trim();
        String runningFlag2 = section(metadata.output, "__DSU_RUNNING_FLAG2__", "__DSU_SIZES__").trim();
        boolean running = !runningSlot.isEmpty() || "1".equals(runningFlag) || "1".equals(runningFlag2);
        Map<String, Long> sizes = parseSizes(metadata.output);
        long freeBytes = parseLong(section(metadata.output, "__DSU_FREE__", "__DSU_FREE_END__").trim()) * 1024L;
        List<SystemSlot> slots = parseSlots(status.output, selected, running ? runningSlot : "", sizes);
        return new Snapshot(true, true, path, selected, runningSlot, running, freeBytes, slots);
    }

    String install(String slot, File image, long userdataBytes,
                   CommandRunner.ProgressListener progress) throws Exception {
        requireSlotId(slot);
        if (image == null || !image.isFile() || image.length() <= 0) {
            throw new IllegalArgumentException("system.img 不存在或为空。");
        }
        if (userdataBytes < MIN_USERDATA_BYTES || userdataBytes > MAX_USERDATA_BYTES) {
            throw new IllegalArgumentException("userdata 大小需在 1–64 GiB 之间。");
        }
        rejectCompressedOrSparse(image);

        Snapshot current = load();
        ensureReady(current);
        if (current.runningDsu) throw new IllegalStateException("不能在 DSU 副系统中安装；请先重启回主系统，再安装新槽位。");
        long imageBytes = image.length();
        long safetyMargin = 256L * 1024L * 1024L;
        if (imageBytes > (Long.MAX_VALUE - userdataBytes - safetyMargin) / 2L) {
            throw new IllegalArgumentException("镜像过大，空间需求计算溢出。");
        }
        // The app-staged raw image already occupies /data when this check runs.
        // Reserve another source copy under /data/local/tmp plus the DSU image itself.
        long estimatedPeakBytes = imageBytes * 2L + userdataBytes + safetyMargin;
        if (current.freeBytes <= 0) throw new IllegalStateException("无法读取 /data 可用空间，为安全起见取消安装。");
        if (estimatedPeakBytes > current.freeBytes) {
            throw new IllegalStateException("剩余 /data 空间不足以创建 DSU（保守估算含 Root 临时镜像副本、DSU system、userdata 和 256 MiB 余量）。");
        }
        for (SystemSlot item : current.slots) {
            if (item.id.equals(slot)) throw new IllegalStateException("该槽位已存在，请换一个槽位名称。");
        }

        String imageSize = Long.toString(image.length());
        String installPath = DATA_ROOT + "/" + slot;
        String metaPath = META_ROOT + "/" + slot;
        String tempPath = "/data/local/tmp/dsu_install_" + slot + ".img";
        String script = "S=" + CommandRunner.quote(slot) + "; IMG=" + CommandRunner.quote(tempPath) + "; "
                + "if [ -e " + CommandRunner.quote(installPath) + " ] || [ -e " + CommandRunner.quote(metaPath) + " ]; then "
                + "echo ERR_SLOT_EXISTS; exit 9; fi; "
                + "command -v gsi_tool >/dev/null 2>&1 || { echo ERR_NO_GSI_TOOL; exit 8; }; "
                + "rm -f \"$IMG\"; "
                + "cat > \"$IMG\" || { echo ERR_STAGE_IMAGE; rm -f \"$IMG\"; exit 7; }; "
                + "ACTUAL=$(wc -c < \"$IMG\"); "
                + "if [ \"$ACTUAL\" -ne " + imageSize + " ]; then echo ERR_SIZE_MISMATCH; rm -f \"$IMG\"; exit 6; fi; "
                + "cat \"$IMG\" | gsi_tool install --install-dir=" + CommandRunner.quote(installPath)
                + " --gsi-size=" + imageSize
                + " --userdata-size=" + userdataBytes
                + " --no-reboot; RC=$?; rm -f \"$IMG\"; "
                + "if [ \"$RC\" -ne 0 ]; then gsi_tool cancel >/dev/null 2>&1; "
                + "rm -rf " + CommandRunner.quote(installPath) + " " + CommandRunner.quote(metaPath) + "; "
                + "echo INSTALL_FAILED_RC=$RC; exit \"$RC\"; fi; "
                + "if ! gsi_tool disable >/dev/null 2>&1; then echo INSTALL_OK_DISABLE_FAILED; exit 5; fi; "
                + "echo INSTALL_OK";

        try (InputStream input = new FileInputStream(image)) {
            CommandRunner.Result result = runner.rootWithStream(script, input, image.length(), progress);
            if (result.exitCode != 0) {
                if (result.output.contains("ERR_SLOT_EXISTS")) throw new IllegalStateException("该槽位已存在；没有覆盖旧系统。");
                if (result.output.contains("ERR_NO_GSI_TOOL")) throw new IllegalStateException("设备缺少 gsi_tool。");
                if (result.output.contains("ERR_SIZE_MISMATCH")) throw new IllegalStateException("镜像传输长度不一致，安装已中止。");
                if (result.output.contains("INSTALL_OK_DISABLE_FAILED")) {
                    throw new IllegalStateException("系统已安装，但无法取消安装器设置的一次性启动状态。请勿直接重启；先检查 gsi_tool status。\n" + result.output);
                }
                throw new IllegalStateException(result.output.isEmpty()
                        ? "gsi_tool 安装失败（退出码 " + result.exitCode + "）。"
                        : result.output);
            }
            return result.output;
        }
    }

    String enableOnce(String slot) throws Exception {
        Snapshot current = load();
        ensureReady(current);
        if (current.runningDsu) throw new IllegalStateException("请先重启回主系统，再选择另一个 DSU 槽位。");
        SystemSlot target = findSlot(current, slot);
        if (target == null || !target.bootable) throw new IllegalStateException("槽位不存在，或没有完整的 system/userdata 镜像。");
        return requireSuccess(runner.root("gsi_tool", "enable", "-d", slot, "-s"));
    }

    String disableAndReboot() throws Exception {
        Snapshot current = load();
        ensureReady(current);
        requireSuccess(runner.root("gsi_tool", "disable"));
        return requireSuccess(runner.root("reboot"));
    }

    String reboot() throws Exception {
        return requireSuccess(runner.root("reboot"));
    }

    String delete(String slot) throws Exception {
        requireSlotId(slot);
        Snapshot current = load();
        ensureReady(current);
        if (current.runningDsu) throw new IllegalStateException("请先回到主系统，再删除 DSU 槽位。");
        SystemSlot target = findSlot(current, slot);
        if (target == null) throw new IllegalStateException("该 DSU 槽位不存在。");
        if (target.active || slot.equals(current.runningSlot)) {
            throw new IllegalStateException("不能删除当前正在运行的系统。请先回到主系统后删除。");
        }

        String dataPath = DATA_ROOT + "/" + slot;
        String metaPath = META_ROOT + "/" + slot;
        if (slot.equals(current.activeSlot)) requireSuccess(runner.root("gsi_tool", "disable"));
        String script = "set -e; test -d " + CommandRunner.quote(dataPath) + "; "
                + "rm -rf " + CommandRunner.quote(dataPath) + " " + CommandRunner.quote(metaPath) + "; "
                + "if [ \"$(cat " + CommandRunner.quote(META_ROOT + "/active") + " 2>/dev/null)\" = "
                + CommandRunner.quote(slot) + " ]; then : > " + CommandRunner.quote(META_ROOT + "/active")
                + "; rm -f " + CommandRunner.quote(META_ROOT + "/one_shot_boot") + "; fi; echo DELETE_OK";
        CommandRunner.Result result = runner.root("sh", "-c", script);
        return requireSuccess(result);
    }

    String hostLabel() {
        String display = Build.DISPLAY == null ? "Android 主系统" : Build.DISPLAY.trim();
        return display.isEmpty() ? "Android 主系统" : display;
    }

    String deviceLabel() { return Build.MODEL + " · " + Build.DEVICE; }

    static String validateSlotId(String value) {
        if (value == null) return null;
        String id = value.trim();
        return SLOT_ID.matcher(id).matches() ? id : null;
    }

    private static void requireSlotId(String slot) {
        if (validateSlotId(slot) == null) {
            throw new IllegalArgumentException("槽位名称只允许 1–48 位英文字母、数字和下划线。");
        }
    }

    private static void ensureReady(Snapshot current) {
        if (!current.rootGranted) throw new IllegalStateException("未获得 Root 授权。请在 KernelSU/Magisk/APatch 中允许本应用。");
        if (!current.gsiToolAvailable) throw new IllegalStateException("未找到 gsi_tool；当前系统可能没有启用 DSU/gsid。");
    }

    private static SystemSlot findSlot(Snapshot snapshot, String id) {
        for (SystemSlot slot : snapshot.slots) if (slot.id.equals(id)) return slot;
        return null;
    }

    private static void rejectCompressedOrSparse(File image) throws Exception {
        String name = image.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".gz") || name.endsWith(".xz") || name.endsWith(".zip")) {
            throw new IllegalArgumentException("第一版仅支持 raw system.img；压缩包和完整 ROM 包暂不支持。");
        }
        try (InputStream input = new FileInputStream(image)) {
            byte[] magic = new byte[4];
            if (input.read(magic) == 4 && (magic[0] & 0xff) == 0x3a
                    && (magic[1] & 0xff) == 0xff && (magic[2] & 0xff) == 0x26
                    && (magic[3] & 0xff) == 0xed) {
                throw new IllegalArgumentException("检测到 Android sparse 镜像；第一版请先转换为 raw system.img。");
            }
        }
    }

    private static List<SystemSlot> parseSlots(String output, String selected,
                                                String running, Map<String, Long> sizes) {
        List<SystemSlot> slots = new ArrayList<>();
        String slotName = null;
        boolean hasSystem = false;
        boolean hasUserdata = false;
        for (String sourceLine : output.split("\\r?\\n")) {
            String line = sourceLine.trim();
            Matcher matcher = SLOT_LINE.matcher(line);
            if (matcher.matches()) {
                if (slotName != null) addSlot(slots, slotName, hasSystem, hasUserdata, selected, running, sizes);
                slotName = matcher.group(1).trim();
                hasSystem = false;
                hasUserdata = false;
            } else if (slotName != null && line.startsWith("installed: ")) {
                String partition = line.substring("installed: ".length()).trim();
                if (partition.startsWith("system_gsi")) hasSystem = true;
                if (partition.startsWith("userdata_gsi")) hasUserdata = true;
            }
        }
        if (slotName != null) addSlot(slots, slotName, hasSystem, hasUserdata, selected, running, sizes);
        return slots;
    }

    private static void addSlot(List<SystemSlot> slots, String id, boolean hasSystem,
                                boolean hasUserdata, String selected, String running,
                                Map<String, Long> sizes) {
        if (validateSlotId(id) == null) return;
        boolean active = id.equals(running);
        String state = hasSystem && hasUserdata ? "READY" : hasSystem ? "USERDATA_MISSING" : "INCOMPLETE";
        slots.add(new SystemSlot(id, id, "DSU", state, sizes.containsKey(id) ? sizes.get(id) : 0L,
                active, id.equals(selected), hasSystem && hasUserdata, hasUserdata));
    }

    private static Map<String, Long> parseSizes(String output) {
        Map<String, Long> sizes = new HashMap<>();
        for (String line : output.split("\\r?\\n")) {
            if (!line.startsWith("SIZE:")) continue;
            String[] fields = line.split(":");
            if (fields.length != 3 || validateSlotId(fields[1]) == null) continue;
            try { sizes.put(fields[1], Long.parseLong(fields[2]) * 1024L); }
            catch (NumberFormatException ignored) { }
        }
        return sizes;
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value.trim()); }
        catch (Exception ignored) { return 0L; }
    }

    private static String section(String text, String start, String end) {
        int from = text.indexOf(start);
        if (from < 0) return "";
        from += start.length();
        int to = text.indexOf(end, from);
        return to < 0 ? text.substring(from) : text.substring(from, to);
    }

    private static String requireSuccess(CommandRunner.Result result) {
        if (result.exitCode != 0) throw new IllegalStateException(result.output.isEmpty()
                ? "设备命令失败（退出码 " + result.exitCode + "）。" : result.output);
        return result.output;
    }
}
