package dev.multisystem.client;

/*
 * Derived from XZY-01/DSUManager SuperImage.java at commit
 * b1a787c580a302db1e29b8557e93701a20be7fbe. Modified for the Elysia package,
 * importer, and partition allowlist. Licensed under the MIT License; the
 * upstream copyright and full license are reproduced in THIRD_PARTY_NOTICES.md.
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.security.MessageDigest;
import java.util.zip.CRC32;

/**
 * super.img（动态分区镜像）解析、Android sparse 转换、分区流式读取。
 * 只依赖 java.io / java.nio，方便在 PC 上单测。
 */
public final class SuperImage {

    public interface Progress {
        void onProgress(long done, long total);
    }

    public static final class Partition {
        public String name = "";
        public long size = 0;
        public final ArrayList<long[]> extents = new ArrayList<>(); // {offset, length, isZero}
    }

    public static final class Info {
        public final ArrayList<Partition> partitions = new ArrayList<>();
        public String error = "";
        public long sourceSize = 0;
    }

    private static final int LP_SECTOR = 512;
    private static final long SPARSE_MAGIC = 0xED26FF3AL;
    private static final int GEOM_MAGIC = 0x616C4467;
    private static final int META_MAGIC = 0x414C5030;

    // ---------------- sparse -> raw ----------------

    public static boolean isSparse(byte[] head4) {
        if (head4 == null || head4.length < 4) return false;
        long v = (head4[0] & 0xFFL) | ((head4[1] & 0xFFL) << 8)
                | ((head4[2] & 0xFFL) << 16) | ((head4[3] & 0xFFL) << 24);
        return v == SPARSE_MAGIC;
    }

    public static void copyToRaw(InputStream in, OutputStream out, long sourceSize,
                                 Progress cb) throws IOException {
        byte[] head = new byte[4];
        if (readFully(in, head, 4) < 4) throw new IOException("镜像为空");
        if (!isSparse(head)) {
            out.write(head);
            long done = 4;
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (cb != null) cb.onProgress(done, sourceSize);
            }
            return;
        }
        byte[] rest = new byte[24];
        if (readFully(in, rest, 24) < 24) throw new IOException("sparse 头不完整");
        byte[] hdr = new byte[28];
        System.arraycopy(head, 0, hdr, 0, 4);
        System.arraycopy(rest, 0, hdr, 4, 24);
        ByteBuffer hb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        if (hb.getShort(4) != 1) throw new IOException("不支持的 sparse 主版本");
        int fileHdrSize = hb.getShort(8) & 0xFFFF;
        int chunkHdrSize = hb.getShort(10) & 0xFFFF;
        int blkSize = hb.getInt(12);
        long totalBlks = hb.getInt(16) & 0xFFFFFFFFL;
        long totalChunks = hb.getInt(20) & 0xFFFFFFFFL;
        long expectedChecksum = Integer.toUnsignedLong(hb.getInt(24));
        if (fileHdrSize < 28 || chunkHdrSize < 12 || blkSize <= 0 || blkSize % 4 != 0
                || totalBlks == 0 || totalBlks > (100L * 1024 * 1024 * 1024) / blkSize
                || totalChunks > 1000000) throw new IOException("sparse 头或输出大小异常");
        if (fileHdrSize > 28) skipFully(in, fileHdrSize - 28);

        byte[] chunkHdr = new byte[12];
        byte[] buf = new byte[1024 * 1024];
        long outBlocks = 0;
        CRC32 crc = new CRC32();
        for (long c = 0; c < totalChunks; c++) {
            if (readFully(in, chunkHdr, 12) < 12) throw new IOException("sparse chunk 不完整");
            ByteBuffer cb2 = ByteBuffer.wrap(chunkHdr).order(ByteOrder.LITTLE_ENDIAN);
            int type = cb2.getShort(0) & 0xFFFF;
            long chunkBlocks = cb2.getInt(4) & 0xFFFFFFFFL;
            long totalSize = cb2.getInt(8) & 0xFFFFFFFFL;
            if (totalSize < chunkHdrSize || chunkBlocks > totalBlks - outBlocks) {
                throw new IOException("sparse chunk 越界");
            }
            skipFully(in, chunkHdrSize - 12L);
            long dataLen = totalSize - chunkHdrSize;
            long outputBytes = chunkBlocks * blkSize;

            if (type == 0xCAC1) { // RAW
                if (dataLen != outputBytes) throw new IOException("sparse RAW 长度不符");
                long left = dataLen;
                while (left > 0) {
                    int n = (int) Math.min((long) buf.length, left);
                    int r = in.read(buf, 0, n);
                    if (r <= 0) throw new IOException("sparse RAW 数据不完整");
                    out.write(buf, 0, r);
                    crc.update(buf, 0, r);
                    left -= r;
                }
            } else if (type == 0xCAC2) { // FILL
                if (dataLen != 4) throw new IOException("sparse FILL 长度不符");
                byte[] pat = new byte[4];
                if (readFully(in, pat, 4) != 4) throw new IOException("sparse FILL 被截断");
                long bytes = chunkBlocks * blkSize;
                long left = bytes;
                while (left > 0) {
                    int n = (int) Math.min((long) buf.length, left);
                    for (int i = 0; i < n; i += 4) {
                        for (int k = 0; k < 4 && i + k < n; k++) buf[i + k] = pat[k];
                    }
                    out.write(buf, 0, n);
                    crc.update(buf, 0, n);
                    left -= n;
                }
            } else if (type == 0xCAC3) { // DONT_CARE
                if (dataLen != 0) throw new IOException("sparse DONT_CARE 长度不符");
                long left = chunkBlocks * blkSize;
                while (left > 0) {
                    int n = (int) Math.min((long) buf.length, left);
                    Arrays.fill(buf, 0, n, (byte) 0);
                    out.write(buf, 0, n);
                    crc.update(buf, 0, n);
                    left -= n;
                }
            } else if (type == 0xCAC4) { // CRC32
                if (dataLen != 4 || chunkBlocks != 0) throw new IOException("sparse CRC32 chunk 异常");
                byte[] checksum = new byte[4];
                if (readFully(in, checksum, 4) != 4
                        || Integer.toUnsignedLong(le32(checksum, 0)) != crc.getValue()) {
                    throw new IOException("sparse CRC32 校验失败");
                }
            } else {
                throw new IOException("未知 sparse chunk 类型：" + type);
            }
            outBlocks += chunkBlocks;
            if (cb != null) cb.onProgress(outBlocks * blkSize, totalBlks * blkSize);
        }
        if (outBlocks != totalBlks) throw new IOException("sparse 总输出长度不符");
        if (expectedChecksum != 0 && expectedChecksum != crc.getValue()) throw new IOException("sparse 整体 CRC32 校验失败");
    }

    // ---------------- LP 解析 ----------------

    public static Info parse(File file) {
        Info info = new Info();
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long fileLen = f.length();
            info.sourceSize = fileLen;
            if (fileLen < 12288 + 128) {
                info.error = "文件太小，不是 super.img";
                return info;
            }
            f.seek(4096);
            byte[] geom = new byte[52];
            f.readFully(geom);
            if (le32(geom, 0) != GEOM_MAGIC) {
                info.error = "不是 super.img（geometry magic 不符）";
                return info;
            }
            if (le32(geom, 4) != 52) throw new IOException("LP geometry 结构大小异常");
            byte[] geometryChecksum = Arrays.copyOfRange(geom, 8, 40);
            byte[] geometryForHash = geom.clone();
            Arrays.fill(geometryForHash, 8, 40, (byte) 0);
            if (!Arrays.equals(geometryChecksum, sha256(geometryForHash))) throw new IOException("LP geometry 校验失败");
            int maxSize = le32(geom, 40);
            int slotCount = le32(geom, 44);
            if (maxSize < 128 || maxSize > 4 * 1024 * 1024 || maxSize % 512 != 0
                    || slotCount <= 0 || slotCount > 8) throw new IOException("LP geometry 参数异常");

            // Full super images keep primary metadata after the geometry copies and
            // backup metadata immediately before the reserved backup geometry area.
            long[] metadataOffsets = new long[slotCount * 2];
            for (int slot = 0; slot < slotCount; slot++) {
                metadataOffsets[slot] = 12288L + (long) slot * maxSize;
                metadataOffsets[slotCount + slot] = fileLen - 8192L - (long) (slot + 1) * maxSize;
            }
            for (long off : metadataOffsets) {
                if (off + 128 > fileLen) continue;
                f.seek(off);
                byte[] hdr = new byte[128];
                f.readFully(hdr);
                if (le32(hdr, 0) != META_MAGIC) continue;
                int headerSize = le32(hdr, 8);
                int tablesSize = le32(hdr, 44);
                if (headerSize < 128 || headerSize > 4096 || tablesSize <= 0
                        || tablesSize > maxSize - headerSize || off + headerSize + tablesSize > fileLen) continue;
                if ((hdr[4] & 0xff) != 10 || hdr[5] != 0) continue;
                f.seek(off);
                hdr = new byte[headerSize];
                f.readFully(hdr);
                byte[] headerChecksum = Arrays.copyOfRange(hdr, 12, 44);
                byte[] headerForHash = hdr.clone();
                Arrays.fill(headerForHash, 12, 44, (byte) 0);
                if (!Arrays.equals(headerChecksum, sha256(headerForHash))) continue;
                int partOff = le32(hdr, 80), partNum = le32(hdr, 84), partEsz = le32(hdr, 88);
                int extOff = le32(hdr, 92), extNum = le32(hdr, 96), extEsz = le32(hdr, 100);
                if (partEsz < 52 || extEsz < 24 || partNum < 0 || extNum < 0
                        || !tableFits(partOff, partNum, partEsz, tablesSize)
                        || !tableFits(extOff, extNum, extEsz, tablesSize)) continue;
                f.seek(off + headerSize);
                byte[] tables = new byte[tablesSize];
                f.readFully(tables);
                if (!Arrays.equals(Arrays.copyOfRange(hdr, 48, 80), sha256(tables))) continue;

                ArrayList<String> names = new ArrayList<>();
                ArrayList<Integer> firstExt = new ArrayList<>();
                ArrayList<Integer> numExt = new ArrayList<>();
                for (int i = 0; i < partNum; i++) {
                    int o = partOff + i * partEsz;
                    if (o + 52 > tables.length) break;
                    names.add(cstr(tables, o, 36));
                    firstExt.add(le32(tables, o + 40));
                    numExt.add(le32(tables, o + 44));
                }
                ArrayList<long[]> exts = new ArrayList<>();
                for (int i = 0; i < extNum; i++) {
                    int o = extOff + i * extEsz;
                    if (o + 24 > tables.length) break;
                    long sectors = le64(tables, o);
                    int type = le32(tables, o + 8);
                    long dataSector = le64(tables, o + 12);
                    long source = Integer.toUnsignedLong(le32(tables, o + 20));
                    exts.add(new long[]{sectors, type, dataSector, source});
                }
                ArrayList<Partition> candidatePartitions = new ArrayList<>();
                HashSet<String> usedNames = new HashSet<>();
                for (int i = 0; i < names.size(); i++) {
                    String name = stripSlot(names.get(i));
                    if (!isKnown(name)) continue;
                    Partition p = new Partition();
                    p.name = name;
                    int fe = firstExt.get(i);
                    int ne = numExt.get(i);
                    if (fe < 0 || ne < 0 || (long) fe + ne > exts.size()) throw new IOException("LP 分区 extent 索引越界");
                    long total = 0;
                    for (int j = 0; j < ne; j++) {
                        int idx = fe + j;
                        long[] e = exts.get(idx);
                        if (e[0] < 0 || e[0] > fileLen / LP_SECTOR) throw new IOException("LP extent 大小异常");
                        long len = e[0] * LP_SECTOR;
                        long[] ex = new long[3];
                        if (e[1] == 0) {
                            if (e[3] != 0 || e[2] < 0 || e[2] > fileLen / LP_SECTOR
                                    || e[2] * LP_SECTOR > fileLen - len) {
                                throw new IOException("LP extent 不属于当前 super 文件或超出文件范围");
                            }
                            ex[0] = e[2] * LP_SECTOR;
                            ex[1] = len;
                            ex[2] = 0;
                        } else if (e[1] == 1) {
                            ex[0] = 0;
                            ex[1] = len;
                            ex[2] = 1;
                        } else throw new IOException("未知 LP extent 类型");
                        p.extents.add(ex);
                        total = Math.addExact(total, len);
                    }
                    p.size = total;
                    if (p.size <= 0) continue;
                    if (!usedNames.add(p.name)) throw new IOException("super 同时包含多个非空 A/B 分区：" + p.name + "；请手动选择一套分区镜像");
                    candidatePartitions.add(p);
                }
                if (!candidatePartitions.isEmpty()) {
                    info.partitions.addAll(candidatePartitions);
                    return info;
                }
            }
            info.error = "没有解析到分区（可能不是标准 super.img）";
        } catch (Exception e) {
            info.partitions.clear();
            info.error = "解析失败：" + e.getMessage();
        }
        return info;
    }

    public static InputStream openPartition(File file, Partition p) throws IOException {
        return new ExtentInputStream(file, p);
    }

    private static final class ExtentInputStream extends InputStream {
        private final ArrayList<long[]> exts;
        private RandomAccessFile raf;
        private int idx = 0;
        private long pos = 0;
        private boolean needSeek = true;

        ExtentInputStream(File file, Partition p) throws IOException {
            this.exts = p.extents;
            this.raf = new RandomAccessFile(file, "r");
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (idx < exts.size()) {
                long[] e = exts.get(idx);
                if (pos >= e[1]) {
                    idx++;
                    pos = 0;
                    needSeek = true;
                    continue;
                }
                int n = (int) Math.min((long) len, e[1] - pos);
                if (e[2] == 1) {
                    Arrays.fill(b, off, off + n, (byte) 0);
                } else {
                    if (needSeek) {
                        raf.seek(e[0] + pos);
                        needSeek = false;
                    }
                    raf.readFully(b, off, n);
                }
                pos += n;
                return n;
            }
            return -1;
        }

        @Override
        public void close() throws IOException {
            try { raf.close(); } catch (Exception ignored) { }
        }
    }

    // ---------------- helpers ----------------

    private static String stripSlot(String name) {
        if (name.endsWith("_a") || name.endsWith("_b")) return name.substring(0, name.length() - 2);
        return name;
    }

    private static boolean isKnown(String name) {
        return MultiSystemBackend.isSupportedPartition(name);
    }

    private static boolean tableFits(int offset, int count, int size, int tableBytes) {
        return offset >= 0 && (long) offset + (long) count * size <= tableBytes;
    }

    private static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private static String cstr(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.US_ASCII);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static long le64(byte[] b, int off) {
        return (le32(b, off) & 0xFFFFFFFFL) | ((long) le32(b, off + 4) << 32);
    }

    private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new IOException("镜像头或 chunk 被截断");
                s = 1;
            }
            n -= s;
        }
    }

    private SuperImage() {}
}
