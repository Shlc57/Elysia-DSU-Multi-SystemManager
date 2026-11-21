package dev.multisystem.client;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Import only an explicit set of DSU partition images; never use archive paths as output paths. */
final class RomImageImporter {
    static final long MAX_IMAGE_BYTES = 100L * 1024L * 1024L * 1024L;
    private static final long DISK_RESERVE = 64L * 1024L * 1024L;
    private final Context context;

    RomImageImporter(Context context) { this.context = context.getApplicationContext(); }

    static final class PreparedRom {
        final File directory;
        final List<MultiSystemBackend.PartitionImage> partitions;
        final List<String> ignoredFiles;
        final long totalBytes;

        PreparedRom(File directory, List<MultiSystemBackend.PartitionImage> partitions,
                    List<String> ignoredFiles, long totalBytes) {
            this.directory = directory;
            this.partitions = partitions;
            this.ignoredFiles = ignoredFiles;
            this.totalBytes = totalBytes;
        }

        String summary() {
            StringBuilder result = new StringBuilder();
            for (MultiSystemBackend.PartitionImage part : partitions) {
                result.append("• ").append(part.partition).append("  ·  ")
                        .append(formatBytes(part.image.length())).append('\n');
            }
            result.append("合计：").append(formatBytes(totalBytes));
            if (!ignoredFiles.isEmpty()) result.append("\n\n未安装：").append(String.join("、", ignoredFiles));
            return result.toString();
        }
    }

    static final class NamedUri {
        final String name;
        final Uri uri;
        NamedUri(String name, Uri uri) { this.name = name; this.uri = uri; }
    }

    PreparedRom fromFile(Uri uri, String displayName, File directory) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法打开所选文件。");
            return fromStream(input, displayName, directory);
        }
    }

    static PreparedRom fromStream(InputStream input, String filename, File directory) throws Exception {
        prepareDirectory(directory);
        List<String> ignored = new ArrayList<>();
        if (filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            File archive = new File(directory, "rom-source.zip");
            try (OutputStream out = boundedOutput(archive, directory)) { copy(input, out); }
            try { return extractZip(archive, directory); }
            finally { archive.delete(); }
        }
        if (isSuperImage(filename)) {
            File source = new File(directory, "super-raw.img");
            saveNormalized(input, filename, source, directory);
            return extractSuper(source, directory, ignored);
        }
        String partition = MultiSystemBackend.partitionFromFilename(filename);
        if (partition == null) {
            // A single GSI commonly has a release-specific name. Never reinterpret known
            // firmware/container names as system images.
            String lower = filename.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".img") || lower.endsWith(".img.gz"))
                    || lower.matches("(?:boot|init_boot|vendor_boot|recovery|dtbo|vbmeta.*|userdata|persist|super_empty)(?:_[ab])?\\.img(?:\\.gz)?")) {
                throw new IOException("无法识别可安装镜像；请选择 system.img、多分区镜像 ZIP 或 super.img。");
            }
            partition = "system";
        }
        File output = new File(directory, partition + ".img");
        saveNormalized(input, filename, output, directory);
        List<MultiSystemBackend.PartitionImage> parts = new ArrayList<>();
        parts.add(new MultiSystemBackend.PartitionImage(partition, output));
        return result(directory, parts, ignored);
    }

    private static PreparedRom extractZip(File archive, File directory) throws Exception {
        List<MultiSystemBackend.PartitionImage> parts = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ArrayList<ZipEntry> entries = new ArrayList<>();
        ZipEntry superEntry = null;
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            int count = 0;
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (++count > 10000) throw new IOException("ZIP 条目过多。");
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains(":")) throw new IOException("ZIP 包含不安全路径。");
                for (String component : name.split("/")) {
                    if (component.equals("..")) throw new IOException("ZIP 包含父目录路径。");
                }
                String base = name.substring(name.lastIndexOf('/') + 1);
                String partition = MultiSystemBackend.partitionFromFilename(base);
                if (isSuperImage(base)) {
                    if (superEntry != null) throw new IOException("ZIP 包含多个 super 镜像。");
                    superEntry = entry;
                } else if (partition != null) {
                    if (!seen.add(partition)) throw new IOException("ZIP 包含重复分区（含 A/B）：" + partition);
                    entries.add(entry);
                } else if ((base.endsWith(".img") || base.equals("payload.bin")) && ignored.size() < 24) {
                    ignored.add(base);
                }
            }
            if (superEntry != null) {
                if (!entries.isEmpty()) throw new IOException("ZIP 同时包含 super 和独立分区；请只保留一种镜像来源。");
                File source = new File(directory, "super-raw.img");
                try (InputStream input = zip.getInputStream(superEntry)) {
                    saveNormalized(input, superEntry.getName(), source, directory);
                }
                return extractSuper(source, directory, ignored);
            }
            for (ZipEntry entry : entries) {
                String name = entry.getName().replace('\\', '/');
                String partition = MultiSystemBackend.partitionFromFilename(name.substring(name.lastIndexOf('/') + 1));
                File output = new File(directory, partition + ".img");
                try (InputStream input = zip.getInputStream(entry)) {
                    saveNormalized(input, name, output, directory);
                }
                parts.add(new MultiSystemBackend.PartitionImage(partition, output));
            }
        }
        return result(directory, parts, ignored);
    }

    PreparedRom fromFolder(List<NamedUri> files, File directory) throws Exception {
        prepareDirectory(directory);
        List<MultiSystemBackend.PartitionImage> parts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // Validate the complete input set before creating or overwriting any staged file.
        boolean hasSuper = false;
        for (NamedUri file : files) {
            if (isSuperImage(file.name)) {
                if (hasSuper) throw new IOException("选择了多个 super 镜像。");
                hasSuper = true;
            } else {
                String partition = MultiSystemBackend.partitionFromFilename(file.name);
                if (partition == null) throw new IOException("多选时文件名必须明确对应分区：" + file.name);
                if (!seen.add(partition)) throw new IOException("选择了重复分区（含 A/B）：" + partition);
            }
        }
        if (hasSuper && files.size() != 1) throw new IOException("super 与独立分区不能混合安装。");
        if (hasSuper) return fromFile(files.get(0).uri, files.get(0).name, directory);
        for (NamedUri file : files) {
            String partition = MultiSystemBackend.partitionFromFilename(file.name);
            File output = new File(directory, partition + ".img");
            try (InputStream input = context.getContentResolver().openInputStream(file.uri)) {
                if (input == null) throw new IOException("无法读取 " + file.name);
                saveNormalized(input, file.name, output, directory);
            }
            parts.add(new MultiSystemBackend.PartitionImage(partition, output));
        }
        return result(directory, parts, new ArrayList<>());
    }

    private static PreparedRom extractSuper(File source, File directory, List<String> ignored) throws Exception {
        SuperImage.Info info = SuperImage.parse(source);
        if (!info.error.isEmpty()) throw new IOException("解析 super.img 失败：" + info.error);
        List<MultiSystemBackend.PartitionImage> parts = new ArrayList<>();
        for (SuperImage.Partition part : info.partitions) {
            File output = new File(directory, part.name + ".img");
            try (InputStream input = SuperImage.openPartition(source, part);
                 OutputStream out = boundedOutput(output, directory)) {
                long copied = copy(input, out);
                if (copied != part.size) throw new IOException("super 分区提取长度不符：" + part.name);
            }
            parts.add(new MultiSystemBackend.PartitionImage(part.name, output));
        }
        source.delete();
        return result(directory, parts, ignored);
    }

    static boolean isSuperImage(String name) {
        return "super.img".equalsIgnoreCase(name) || "super.img.gz".equalsIgnoreCase(name);
    }

    private static PreparedRom result(File directory, List<MultiSystemBackend.PartitionImage> parts,
                                      List<String> ignored) throws Exception {
        MultiSystemBackend.validatePartitionImages(parts);
        long total = 0L;
        for (MultiSystemBackend.PartitionImage part : parts) total = Math.addExact(total, part.image.length());
        return new PreparedRom(directory, parts, ignored, total);
    }

    private static void saveNormalized(InputStream input, String filename, File output, File directory) throws Exception {
        InputStream source = filename.toLowerCase(Locale.ROOT).endsWith(".gz") ? new GZIPInputStream(input) : input;
        try (BufferedInputStream buffered = new BufferedInputStream(source);
             OutputStream target = boundedOutput(output, directory)) {
            SuperImage.copyToRaw(buffered, target, -1L, null);
        }
        if (output.length() == 0 || output.length() % 512L != 0) throw new IOException("分区镜像长度不符合 512 字节对齐。");
    }

    private static void prepareDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法创建镜像暂存目录。");
    }

    private static OutputStream boundedOutput(File file, File directory) throws IOException {
        return new FilterOutputStream(new FileOutputStream(file)) {
            long written;
            @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
            @Override public void write(byte[] buffer, int offset, int count) throws IOException {
                if (Thread.currentThread().isInterrupted()) throw new IOException("导入已取消。");
                if (count > MAX_IMAGE_BYTES - written) throw new IOException("单个镜像超过 100 GiB 上限。");
                if (directory.getUsableSpace() < DISK_RESERVE + count) throw new IOException("暂存空间不足（预留 64 MiB）。");
                out.write(buffer, offset, count);
                written += count;
            }
        };
    }

    private static long copy(InputStream input, OutputStream output) throws IOException {
        long copied = 0L;
        byte[] buffer = new byte[1024 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); copied += count; }
        return copied;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024d);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576d);
        return String.format(Locale.ROOT, "%.2f GiB", bytes / 1073741824d);
    }
}
