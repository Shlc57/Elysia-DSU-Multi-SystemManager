package dev.multisystem.client;

import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Root DSU backend using gsi_tool's named install directories. */
final class MultiSystemBackend {
    private static final Pattern SLOT_ID = Pattern.compile("[A-Za-z0-9_]{1,48}");
    private static final Pattern SLOT_LINE = Pattern.compile("^\\[\\d+\\]\\s+(.+)$");
    private static final Pattern PARTITION_NAME = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    private static final String DATA_ROOT = "/data/gsi/dsu";
    private static final String META_ROOT = "/metadata/gsi/dsu";
    private static final long MIN_USERDATA_BYTES = 1024L * 1024L * 1024L;
    private static final long MAX_USERDATA_BYTES = 64L * 1024L * 1024L * 1024L;
    private static final String[] PARTITION_ORDER = {
            "system", "system_ext", "product", "vendor", "odm",
            "vendor_dlkm", "system_dlkm", "mi_ext"
    };

    private final CommandRunner runner;

    MultiSystemBackend() { this(new CommandRunner()); }
    MultiSystemBackend(CommandRunner runner) { this.runner = runner; }

    static final class Snapshot {
        final boolean rootGranted;
        final boolean gsiToolAvailable;
        final String gsiToolPath;
        final String activeSlot;
        final String runningSlot;
        final boolean runningDsu;
        final boolean oneShotBoot;
        final long freeBytes;
        final List<SystemSlot> slots;

        Snapshot(boolean rootGranted, boolean gsiToolAvailable, String gsiToolPath,
                 String activeSlot, String runningSlot, boolean runningDsu,
                 boolean oneShotBoot, long freeBytes, List<SystemSlot> slots) {
            this.rootGranted = rootGranted;
            this.gsiToolAvailable = gsiToolAvailable;
            this.gsiToolPath = gsiToolPath;
            this.activeSlot = activeSlot;
            this.runningSlot = runningSlot;
            this.runningDsu = runningDsu;
            this.oneShotBoot = oneShotBoot;
            this.freeBytes = freeBytes;
            this.slots = slots;
        }

        boolean ready() { return rootGranted && gsiToolAvailable; }
    }

    static final class PartitionImage {
        final String partition;
        final File image;

        PartitionImage(String partition, File image) {
            this.partition = partition;
            this.image = image;
        }
    }

    interface InstallProgressListener {
        void onPartitionStarted(String partition, int index, int count, long totalBytes);
        void onPartitionProgress(String partition, long sentBytes, long partitionBytes,
                                 long aggregateBytes, long totalBytes);
        void onPartitionCompleted(String partition, int index, int count,
                                  long aggregateBytes, long totalBytes);
    }

    Snapshot load() throws Exception {
        CommandRunner.Result identity = runner.root("id");
        boolean root = identity.exitCode == 0 && identity.output.contains("uid=0");
        if (!root) return new Snapshot(false, false, "", "", "", false, false,
                0L, Collections.emptyList());

        CommandRunner.Result tool = runner.root("sh", "-c", "command -v gsi_tool");
        String path = tool.exitCode == 0 ? tool.output.trim() : "";
        if (path.isEmpty()) return new Snapshot(true, false, "", "", "", false, false,
                0L, Collections.emptyList());

        CommandRunner.Result status = runner.root("gsi_tool", "status");
        if (status.exitCode != 0) {
            throw new IllegalStateException(status.output.isEmpty()
                    ? "gsi_tool status 执行失败（退出码 " + status.exitCode + "）。" : status.output);
        }

        String script = "printf '__DSU_SELECTED__'; cat /metadata/gsi/dsu/active 2>/dev/null; "
                + "printf '\\n__DSU_RUNNING_SLOT__'; getprop ro.gsid.dsu_slot; "
                + "printf '\\n__DSU_RUNNING_FLAG__'; getprop ro.gsid.image_running; "
                + "printf '\\n__DSU_RUNNING_FLAG2__'; getprop ro.gsi.dsu; "
                + "printf '\\n__DSU_ONE_SHOT__'; [ -e /metadata/gsi/dsu/one_shot_boot ] && printf '1' || printf '0'; "
                + "printf '\\n__DSU_SIZES__\\n'; "
                + "for d in /data/gsi/dsu/*/; do [ -d \"$d\" ] || continue; "
                + "printf 'SIZE:%s:%s\\n' \"$(basename \"$d\")\" \"$(du -sk \"$d\" 2>/dev/null | cut -f1)\"; "
                + "if [ -e \"$d/.elysia-installing\" ]; then printf 'PENDING:%s\\n' \"$(basename \"$d\")\"; fi; done; "
                + "printf '__DSU_SIZES_END__\\n__DSU_SLOT_CHECKS__\\n'; "
                + "for m in /metadata/gsi/dsu/*/; do [ -d \"$m\" ] || continue; "
                + "id=$(basename \"$m\"); d=$(cat \"$m/install_dir\" 2>/dev/null); d=${d%/}; "
                + "[ \"$d\" = \"/data/gsi/dsu/$id\" ] || continue; "
                + "[ \"$(cat \"$m/complete\" 2>/dev/null)\" = OK ] && printf 'COMPLETE:%s\\n' \"$id\"; "
                + "for f in \"$d\"/system_gsi.img*; do [ -e \"$f\" ] && { printf 'SYSTEM_IMAGE:%s\\n' \"$id\"; break; }; done; "
                + "for f in \"$d\"/userdata_gsi.img*; do [ -e \"$f\" ] && { printf 'USERDATA_IMAGE:%s\\n' \"$id\"; break; }; done; "
                + "[ -r \"$m/lp_metadata\" ] && grep -a -q system_gsi \"$m/lp_metadata\" "
                + "&& printf 'SYSTEM_IMAGE:%s\\n' \"$id\"; "
                + "[ -r \"$m/lp_metadata\" ] && grep -a -q userdata_gsi \"$m/lp_metadata\" "
                + "&& printf 'USERDATA_IMAGE:%s\\n' \"$id\"; done; "
                + "printf '__DSU_SLOT_CHECKS_END__\\n__DSU_FREE__'; "
                + "df -k /data 2>/dev/null | awk 'END {print $4}'; printf '__DSU_FREE_END__'";
        CommandRunner.Result metadata = runner.root("sh", "-c", script);
        if (metadata.exitCode != 0) throw new IllegalStateException(metadata.output);

        String selected = section(metadata.output, "__DSU_SELECTED__", "__DSU_RUNNING_SLOT__").trim();
        String runningSlot = section(metadata.output, "__DSU_RUNNING_SLOT__", "__DSU_RUNNING_FLAG__").trim();
        String runningFlag = section(metadata.output, "__DSU_RUNNING_FLAG__", "__DSU_RUNNING_FLAG2__").trim();
        String runningFlag2 = section(metadata.output, "__DSU_RUNNING_FLAG2__", "__DSU_ONE_SHOT__").trim();
        boolean oneShotBoot = "1".equals(section(metadata.output,
                "__DSU_ONE_SHOT__", "__DSU_SIZES__").trim());
        String slotChecks = section(metadata.output, "__DSU_SLOT_CHECKS__", "__DSU_SLOT_CHECKS_END__");
        boolean running = !runningSlot.isEmpty() || "1".equals(runningFlag) || "1".equals(runningFlag2)
                || java.util.Arrays.asList(status.output.split("\\r?\\n")).contains("running");
        Map<String, Long> sizes = parseSizes(metadata.output);
        long freeBytes = parseLong(section(metadata.output, "__DSU_FREE__", "__DSU_FREE_END__").trim()) * 1024L;
        Set<String> complete = parseSlotChecks(slotChecks, "COMPLETE:");
        Set<String> systemImages = parseSlotChecks(slotChecks, "SYSTEM_IMAGE:");
        Set<String> userdataImages = parseSlotChecks(slotChecks, "USERDATA_IMAGE:");
        List<SystemSlot> slots = parseSlots(status.output, selected, running ? runningSlot : "", sizes,
                complete, systemImages, userdataImages, running);
        Set<String> pending = new HashSet<>();
        for (String line : metadata.output.split("\\r?\\n")) {
            if (line.startsWith("PENDING:")) pending.add(line.substring(8).trim());
        }
        for (int i = 0; i < slots.size(); i++) {
            SystemSlot item = slots.get(i);
            if (pending.contains(item.id)) slots.set(i, new SystemSlot(item.id, item.name, item.version,
                    "INSTALL_INCOMPLETE", item.partitions, item.sizeBytes, item.active,
                    item.selected, false, item.dataIsolated));
        }
        return new Snapshot(true, true, path, selected, runningSlot, running, oneShotBoot,
                freeBytes, slots);
    }

    String install(String slot, List<PartitionImage> images, long userdataBytes,
                   InstallProgressListener progress) throws Exception {
        requireSlotId(slot);
        images = new ArrayList<>(images);
        validatePartitionImages(images);
        if (userdataBytes < MIN_USERDATA_BYTES || userdataBytes > MAX_USERDATA_BYTES) {
            throw new IllegalArgumentException("userdata 大小需在 1–64 GiB 之间。");
        }
        Snapshot current = load();
        ensureReady(current);
        if (current.runningDsu) throw new IllegalStateException("不能在 DSU 副系统中安装；请先重启回主系统，再安装新槽位。");
        long imageBytes = 0L;
        for (PartitionImage item : images) imageBytes = Math.addExact(imageBytes, item.image.length());
        long safetyMargin = 256L * 1024L * 1024L;
        if (imageBytes > (Long.MAX_VALUE - userdataBytes - safetyMargin) / 2L) {
            throw new IllegalArgumentException("镜像过大，空间需求计算溢出。");
        }
        long largestImage = 0;
        for (PartitionImage image : images) largestImage = Math.max(largestImage, image.image.length());
        // Already staged in app storage. Only one root-side temp image exists at a time.
        long estimatedPeakBytes = imageBytes + largestImage + userdataBytes + safetyMargin;
        if (current.freeBytes <= 0) throw new IllegalStateException("无法读取 /data 可用空间，为安全起见取消安装。");
        if (estimatedPeakBytes > current.freeBytes) {
            throw new IllegalStateException("剩余 /data 空间不足以创建多分区 DSU（含临时副本、分区镜像、userdata 和余量）。");
        }
        for (SystemSlot item : current.slots) {
            if (item.id.equals(slot)) throw new IllegalStateException("该槽位已存在，请换一个槽位名称。");
        }

        String installPath = DATA_ROOT + "/" + slot;
        String metaPath = META_ROOT + "/" + slot;
        CommandRunner.Result preflight = runner.root("sh", "-c",
                "if [ -e " + CommandRunner.quote(installPath) + " ] || [ -e " + CommandRunner.quote(metaPath) + " ]; then echo ERR_SLOT_EXISTS; exit 9; fi; "
                        + "command -v gsi_tool >/dev/null 2>&1 || { echo ERR_NO_GSI_TOOL; exit 8; }; "
                        + "mkdir -p " + CommandRunner.quote(DATA_ROOT) + "; "
                        + "mkdir " + CommandRunner.quote(installPath) + " || exit 9; "
                        + "touch " + CommandRunner.quote(installPath + "/.elysia-installing") + " || exit 7; echo PREFLIGHT_OK");
        if (preflight.exitCode != 0) {
            if (preflight.output.contains("ERR_SLOT_EXISTS")) throw new IllegalStateException("该槽位已存在；没有覆盖旧系统。");
            throw new IllegalStateException(preflight.output.isEmpty() ? "设备缺少 gsi_tool。" : preflight.output);
        }

        long completedBytes = 0L;
        StringBuilder operationLog = new StringBuilder();
        try {
            int partitionIndex = 0;
            for (PartitionImage item : images) {
                partitionIndex++;
                final long aggregateBefore = completedBytes;
                final long totalImageBytes = imageBytes;
                if (progress != null) progress.onPartitionStarted(item.partition,
                        partitionIndex, images.size(), imageBytes);
                CommandRunner.ProgressListener transfer = progress == null ? null
                        : (sent, total) -> progress.onPartitionProgress(item.partition, sent,
                                item.image.length(), aggregateBefore + sent, totalImageBytes);
                CommandRunner.Result result = installPartition(slot, item, userdataBytes,
                        completedBytes, imageBytes, transfer);
                if (result.exitCode != 0) throw installationError(item.partition, result);
                completedBytes += item.image.length();
                operationLog.append(item.partition).append(": ").append(result.output).append('\n');
                if (progress != null) progress.onPartitionCompleted(item.partition,
                        partitionIndex, images.size(), completedBytes, imageBytes);
            }
        } catch (Exception failure) {
            try {
                requireSuccess(runner.root("gsi_tool", "cancel"));
                requireSuccess(runner.root("gsi_tool", "disable"));
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            // Keep the named slot marked incomplete; never remove user data on a failed write.
            throw failure;
        }

        CommandRunner.Result disabled = runner.root("gsi_tool", "disable");
        if (disabled.exitCode != 0) {
            throw new IllegalStateException("分区已写入，但无法清除安装启动标记。请先检查 gsi_tool status。\n" + disabled.output);
        }
        Snapshot installed = load();
        SystemSlot target = findSlot(installed, slot);
        if (target == null || !target.dataIsolated) throw new IllegalStateException("写入结束但未检测到槽位的 userdata，保留未完成标记。");
        Set<String> found = new HashSet<>(java.util.Arrays.asList(target.partitions.split(", ")));
        for (PartitionImage image : images) {
            if (!found.contains(image.partition)) throw new IllegalStateException("写入结束但缺少分区：" + image.partition + "，保留未完成标记。");
        }
        requireSuccess(runner.root("rm", "-f", installPath + "/.elysia-installing"));
        return operationLog.toString();
    }

    private CommandRunner.Result installPartition(String slot, PartitionImage part, long userdataBytes,
                                                   long completedBytes,
                                                   long totalBytes, CommandRunner.ProgressListener progress) throws Exception {
        String installPath = DATA_ROOT + "/" + slot;
        String tempPath = "/data/local/tmp/dsu_install_" + slot + "_" + part.partition + ".img";
        StringBuilder command = new StringBuilder("cat ").append(CommandRunner.quote(tempPath))
                .append(" | gsi_tool install --install-dir=").append(CommandRunner.quote(installPath))
                .append(" --gsi-size=").append(part.image.length())
                .append(" --userdata-size=").append(userdataBytes);
        command.append(" --partition-name ").append(CommandRunner.quote(part.partition));
        command.append(" --no-reboot");

        String script = "IMG=" + CommandRunner.quote(tempPath) + "; umask 077; "
                + "if [ -e \"$IMG\" ] || [ -L \"$IMG\" ]; then echo ERR_TEMP_EXISTS; exit 10; fi; "
                + "trap 'rm -f \"$IMG\"' EXIT; "
                + "cat > \"$IMG\" || { echo ERR_STAGE_IMAGE; rm -f \"$IMG\"; exit 7; }; "
                + "ACTUAL=$(wc -c < \"$IMG\"); if [ \"$ACTUAL\" -ne " + part.image.length() + " ]; then echo ERR_SIZE_MISMATCH; rm -f \"$IMG\"; exit 6; fi; "
                + command + "; RC=$?; "
                + "if [ \"$RC\" -ne 0 ]; then echo INSTALL_FAILED_RC=$RC; exit \"$RC\"; fi; echo PARTITION_OK";
        CommandRunner.ProgressListener aggregate = progress == null ? null
                : (sent, ignored) -> progress.onProgress(completedBytes + sent, totalBytes);
        try (InputStream input = new FileInputStream(part.image)) {
            return runner.rootWithStream(script, input, part.image.length(), aggregate);
        }
    }

    private IllegalStateException installationError(String partition, CommandRunner.Result result) {
        return new IllegalStateException(partition + " 分区安装失败（退出码 " + result.exitCode + "）。\n" + result.output);
    }

    static void validatePartitionImages(List<PartitionImage> images) throws Exception {
        if (images == null || images.isEmpty()) throw new IllegalArgumentException("未选择任何分区镜像。");
        Set<String> seen = new HashSet<>();
        boolean hasSystem = false;
        for (PartitionImage item : images) {
            if (item == null || item.image == null || !item.image.isFile() || item.image.length() <= 0) {
                throw new IllegalArgumentException("分区镜像缺失或为空。");
            }
            if (item.partition == null || !PARTITION_NAME.matcher(item.partition).matches() || !isSupportedPartition(item.partition)) {
                throw new IllegalArgumentException("不支持的 DSU 分区名称：" + item.partition);
            }
            if (!seen.add(item.partition)) throw new IllegalArgumentException("镜像中包含重复分区：" + item.partition);
            rejectCompressedOrSparse(item.image);
            if ("system".equals(item.partition)) hasSystem = true;
        }
        if (!hasSystem) throw new IllegalArgumentException("多分区 ROM 必须包含 system.img。");
        images.sort((a, b) -> partitionOrder(a.partition) - partitionOrder(b.partition));
    }

    static String partitionFromFilename(String filename) {
        if (filename == null) return null;
        String name = filename.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".gz")) name = name.substring(0, name.length() - 3);
        if (!name.endsWith(".img")) return null;
        name = name.substring(0, name.length() - 4);
        if (name.endsWith("_a") || name.endsWith("_b")) name = name.substring(0, name.length() - 2);
        return isSupportedPartition(name) ? name : null;
    }

    static boolean isSupportedPartition(String name) {
        for (String supported : PARTITION_ORDER) if (supported.equals(name)) return true;
        return false;
    }

    static int partitionOrder(String name) {
        for (int i = 0; i < PARTITION_ORDER.length; i++) if (PARTITION_ORDER[i].equals(name)) return i;
        return PARTITION_ORDER.length;
    }

    String enableOnce(String slot) throws Exception {
        requireSlotId(slot);
        Snapshot current = load();
        ensureReady(current);
        if (current.runningDsu) throw new IllegalStateException("请先重启回主系统，再选择另一个 DSU 槽位。");
        SystemSlot target = findSlot(current, slot);
        if (target == null || !target.bootable) throw new IllegalStateException("槽位不存在，或完整镜像未通过设备端核验。");
        return requireSuccess(runner.root("gsi_tool", "enable", "-d", slot, "-s"));
    }

    String enableOnceFromDsu(String slot) throws Exception {
        requireSlotId(slot);
        Snapshot current = load();
        ensureReady(current);
        if (!current.runningDsu || current.runningSlot.isEmpty()) {
            throw new IllegalStateException("无法确认当前运行的 DSU 槽位，已取消直切。");
        }
        if (!current.runningSlot.equals(current.activeSlot)) {
            throw new IllegalStateException("DSU 活动槽与运行槽不一致，已取消直切。");
        }
        if (slot.equals(current.runningSlot)) throw new IllegalStateException("目标就是当前运行槽位。");
        SystemSlot target = findSlot(current, slot);
        if (target == null || !target.bootable || !target.dataIsolated) {
            throw new IllegalStateException("目标槽缺少已核验的完整标记、system 或 userdata 镜像，已取消直切。");
        }
        return requireSuccess(runner.root("gsi_tool", "enable", "-d", slot, "-s"));
    }

    String disableAndReboot() throws Exception {
        Snapshot current = load();
        ensureReady(current);
        requireSuccess(runner.root("gsi_tool", "disable"));
        return requireSuccess(runner.root("reboot"));
    }

    String reboot() throws Exception { return requireSuccess(runner.root("reboot")); }

    String delete(String slot) throws Exception {
        requireSlotId(slot);
        Snapshot current = load();
        ensureReady(current);
        if (current.runningDsu) throw new IllegalStateException("请先回到主系统，再删除 DSU 槽位。");
        SystemSlot target = findSlot(current, slot);
        if (target == null) throw new IllegalStateException("该 DSU 槽位不存在。");
        if (target.active || slot.equals(current.runningSlot)) {
            throw new IllegalStateException("不能删除当前活动或正在运行的系统。");
        }

        String dataPath = DATA_ROOT + "/" + slot;
        String metaPath = META_ROOT + "/" + slot;
        if (slot.equals(current.activeSlot)) requireSuccess(runner.root("gsi_tool", "disable"));
        String script = "set -e; test -d " + CommandRunner.quote(dataPath) + "; "
                + "test ! -L " + CommandRunner.quote(dataPath) + "; test ! -L " + CommandRunner.quote(metaPath) + "; "
                + "rm -rf " + CommandRunner.quote(dataPath) + " " + CommandRunner.quote(metaPath) + "; "
                + "if [ \"$(cat " + CommandRunner.quote(META_ROOT + "/active") + " 2>/dev/null)\" = "
                + CommandRunner.quote(slot) + " ]; then : > " + CommandRunner.quote(META_ROOT + "/active")
                + "; rm -f " + CommandRunner.quote(META_ROOT + "/one_shot_boot") + "; fi; echo DELETE_OK";
        return requireSuccess(runner.root("sh", "-c", script));
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
        if (validateSlotId(slot) == null || !slot.equals(validateSlotId(slot))) {
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
            throw new IllegalArgumentException("应先解压分区镜像，再传入 raw .img。");
        }
        if (image.length() % 512L != 0) throw new IllegalArgumentException("镜像大小不是 512 字节对齐，疑似损坏或格式错误。");
        try (InputStream input = new FileInputStream(image)) {
            byte[] magic = new byte[4];
            if (input.read(magic) == 4 && (magic[0] & 0xff) == 0x3a
                    && (magic[1] & 0xff) == 0xff && (magic[2] & 0xff) == 0x26
                    && (magic[3] & 0xff) == 0xed) {
                throw new IllegalArgumentException("检测到 Android sparse 镜像；请先转换为 raw 分区镜像。");
            }
        }
    }

    private static List<SystemSlot> parseSlots(String output, String selected,
                                                String running, Map<String, Long> sizes,
                                                Set<String> complete, Set<String> systemImages,
                                                Set<String> userdataImages, boolean runningDsu) {
        List<SystemSlot> slots = new ArrayList<>();
        String slotName = null;
        boolean hasSystem = false;
        boolean hasUserdata = false;
        LinkedHashSet<String> partitions = new LinkedHashSet<>();
        for (String sourceLine : output.split("\\r?\\n")) {
            String line = sourceLine.trim();
            Matcher matcher = SLOT_LINE.matcher(line);
            if (matcher.matches()) {
                if (slotName != null) addSlot(slots, slotName, hasSystem, hasUserdata,
                        partitions, selected, running, sizes, complete, systemImages, userdataImages,
                        runningDsu);
                slotName = matcher.group(1).trim();
                hasSystem = false;
                hasUserdata = false;
                partitions.clear();
            } else if (slotName != null && line.startsWith("installed: ")) {
                String partition = line.substring("installed: ".length()).trim();
                if (partition.startsWith("system_gsi")) hasSystem = true;
                if (partition.startsWith("userdata_gsi")) hasUserdata = true;
                for (String supported : PARTITION_ORDER) {
                    if (partition.startsWith(supported + "_gsi")) partitions.add(supported);
                }
            }
        }
        if (slotName != null) addSlot(slots, slotName, hasSystem, hasUserdata,
                partitions, selected, running, sizes, complete, systemImages, userdataImages,
                runningDsu);
        return slots;
    }

    private static void addSlot(List<SystemSlot> slots, String id, boolean hasSystem,
                                boolean hasUserdata, Set<String> partitions,
                                String selected, String running, Map<String, Long> sizes,
                                Set<String> complete, Set<String> systemImages,
                                Set<String> userdataImages, boolean runningDsu) {
        if (validateSlotId(id) == null) return;
        boolean active = id.equals(running);
        boolean hasSystemImage = hasSystem || systemImages.contains(id);
        boolean hasUserdataImage = hasUserdata || userdataImages.contains(id);
        boolean bootable = complete.contains(id) && systemImages.contains(id) && userdataImages.contains(id);
        String state = bootable ? "READY"
                : runningDsu ? "DETAILS_UNAVAILABLE"
                : hasSystemImage && !hasUserdataImage ? "USERDATA_MISSING"
                : "INCOMPLETE";
        String partitionSummary = partitions.isEmpty() ? "" : String.join(", ", partitions);
        slots.add(new SystemSlot(id, id, "DSU", state, partitionSummary,
                sizes.containsKey(id) ? sizes.get(id) : 0L,
                active, id.equals(selected), bootable, hasUserdataImage));
    }

    private static Set<String> parseSlotChecks(String output, String prefix) {
        Set<String> slots = new HashSet<>();
        for (String line : output.split("\\r?\\n")) {
            if (!line.startsWith(prefix)) continue;
            String id = line.substring(prefix.length()).trim();
            if (validateSlotId(id) != null) slots.add(id);
        }
        return slots;
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
