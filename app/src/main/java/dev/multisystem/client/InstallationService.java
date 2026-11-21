package dev.multisystem.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps image preparation and partition writes alive independently of the Activity. */
public final class InstallationService extends Service {
    static final String PREPARE = "dev.multisystem.PREPARE";
    static final String INSTALL = "dev.multisystem.INSTALL";
    static final String CANCEL = "dev.multisystem.CANCEL";
    private static final String CHANNEL = "elysia-installation";
    private static final int NOTIFICATION = 11;
    enum Phase { IDLE, PREPARING, REVIEW, WRITING, SUCCEEDED, FAILED }
    static volatile Phase phase = Phase.IDLE;
    static volatile String message = "";
    static volatile String installSummary = "";
    static volatile String slotName = "";
    static volatile String currentPartition = "";
    static volatile int currentPartitionIndex;
    static volatile int partitionCount;
    static volatile int progressPercent;
    static volatile int partitionProgressPercent;
    private static final ArrayList<String> OPERATION_EVENTS = new ArrayList<>();
    private static int operationGeneration;
    static volatile long partitionSent;
    static volatile long partitionBytes;
    static volatile long totalSent;
    static volatile long totalBytes;
    private static Runnable listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private RomImageImporter.PreparedRom prepared;
    private long userdata;
    private File operationDirectory;
    private volatile boolean cancelled;

    static boolean isBusy() { return phase == Phase.PREPARING || phase == Phase.WRITING; }
    static void listen(Runnable callback) { listener = callback; }
    static void unlisten(Runnable callback) { if (listener == callback) listener = null; }

    static synchronized void beginOperationLog() {
        operationGeneration++;
        OPERATION_EVENTS.clear();
    }

    static synchronized void recordOperation(String line) { OPERATION_EVENTS.add(line); }

    static synchronized int operationGeneration() { return operationGeneration; }

    static synchronized ArrayList<String> operationEventsFrom(int index) {
        int start = Math.max(0, Math.min(index, OPERATION_EVENTS.size()));
        return new ArrayList<>(OPERATION_EVENTS.subList(start, OPERATION_EVENTS.size()));
    }

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "系统安装", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (PREPARE.equals(action)) {
            if (isBusy()) return START_NOT_STICKY;
            slotName = intent.getStringExtra("slot");
            userdata = intent.getLongExtra("userdata", 0L);
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra("uris");
            ArrayList<String> names = intent.getStringArrayListExtra("names");
            phase = Phase.PREPARING;
            message = "正在校验和解包 ROM 镜像…";
            currentPartition = "";
            currentPartitionIndex = 0;
            partitionCount = 0;
            progressPercent = 0;
            partitionProgressPercent = 0;
            installSummary = "";
            beginOperationLog();
            recordOperation("开始安装槽位：" + slotName + "，userdata "
                    + String.format(java.util.Locale.ROOT, "%.2f GiB", userdata / 1073741824d));
            progressPercent = 0;
            currentPartition = "";
            totalSent = 0;
            totalBytes = 0;
            recordOperation("开始准备镜像：" + slotName);
            startForeground(NOTIFICATION, notification(message, -1));
            publish();
            worker.execute(() -> prepare(uris, names));
        } else if (INSTALL.equals(action) && phase == Phase.REVIEW && prepared != null) {
            phase = Phase.WRITING;
            message = "正在创建 DSU 槽并写入分区…";
            updateNotification(message, -1);
            publish();
            worker.execute(this::install);
        } else if (CANCEL.equals(action) && phase == Phase.REVIEW) {
            worker.execute(() -> {
                deleteTemporary(operationDirectory);
                finish(Phase.IDLE, "已取消，未写入设备分区。");
            });
        }
        return START_NOT_STICKY;
    }

    private void prepare(List<Uri> uris, List<String> names) {
        try {
            if (uris == null || names == null || uris.isEmpty() || uris.size() != names.size()) {
                throw new IllegalArgumentException("镜像选择信息缺失。");
            }
            File external = getExternalFilesDir(null);
            if (external == null) throw new IllegalStateException("外部存储不可用。");
            operationDirectory = new File(external, "imports/rom-" + Long.toHexString(System.nanoTime()));
            RomImageImporter importer = new RomImageImporter(this);
            if (uris.size() == 1) {
                prepared = importer.fromFile(uris.get(0), names.get(0), operationDirectory);
            } else {
                ArrayList<RomImageImporter.NamedUri> inputs = new ArrayList<>();
                for (int i = 0; i < uris.size(); i++) inputs.add(new RomImageImporter.NamedUri(names.get(i), uris.get(i)));
                prepared = importer.fromFolder(inputs, operationDirectory);
            }
            if (cancelled) throw new IllegalStateException("任务已取消。");
            installSummary = "目标槽：" + slotName + "\nuserdata："
                    + String.format(java.util.Locale.ROOT, "%.2f GiB", userdata / 1073741824d)
                    + "\n\n" + prepared.summary();
            partitionCount = prepared.partitions.size();
            phase = Phase.WRITING;
            message = "镜像已准备好，正在安装到 DSU 槽…";
            recordOperation("镜像解析完成：" + prepared.summary().replace('\n', '；'));
            updateNotification(message, -1);
            publish();
            install();
        } catch (Exception error) {
            deleteTemporary(operationDirectory);
            recordOperation("镜像准备错误：" + error.getMessage());
            finish(Phase.FAILED, "镜像准备失败：" + error.getMessage());
        }
    }

    private void install() {
        try {
            new MultiSystemBackend().install(slotName, prepared.partitions, userdata,
                    new MultiSystemBackend.InstallProgressListener() {
                @Override public void onPartitionStarted(String partition, int index, int count,
                                                          long totalBytesValue) {
                    currentPartition = partition;
                    currentPartitionIndex = index;
                    partitionCount = count;
                    partitionSent = 0;
                    partitionBytes = 0;
                    totalBytes = totalBytesValue;
                    partitionProgressPercent = 0;
                    message = "正在安装 " + partition + "（" + index + "/" + count + "）";
                    recordOperation("开始写入分区：" + partition + "（" + index + "/" + count + "）");
                    updateNotification(message, progressPercent);
                    publish();
                }

                @Override public void onPartitionProgress(String partition, long sent, long partBytes,
                                                          long aggregate, long total) {
                    currentPartition = partition;
                    partitionSent = sent;
                    partitionBytes = partBytes;
                    totalSent = aggregate;
                    totalBytes = total;
                    partitionProgressPercent = partBytes > 0
                            ? Math.min(99, (int) (sent * 100 / partBytes)) : 0;
                    progressPercent = total > 0
                            ? Math.min(99, (int) (aggregate * 100 / total)) : 0;
                    message = String.format(java.util.Locale.ROOT,
                            "当前分区：%s（%d/%d）  ·  分区 %d%%  ·  总进度 %d%%",
                            partition, currentPartitionIndex, partitionCount,
                            partitionProgressPercent, progressPercent);
                    updateNotification(message, progressPercent);
                    publish();
                }

                @Override public void onPartitionCompleted(String partition, int index, int count,
                                                            long aggregate, long total) {
                    totalSent = aggregate;
                    totalBytes = total;
                    progressPercent = total > 0 ? (int) (aggregate * 100 / total) : 100;
                    partitionProgressPercent = 100;
                    message = String.format(java.util.Locale.ROOT,
                            "当前分区：%s（%d/%d）  ·  分区 100%%  ·  总进度 %d%%",
                            partition, index, count, progressPercent);
                    recordOperation("分区安装完成：" + partition + "（" + index + "/" + count + "）");
                    updateNotification(message, progressPercent);
                    publish();
                }
            });
            progressPercent = 100;
            recordOperation("所有分区写入完成：" + slotName);
            finish(Phase.SUCCEEDED, "多分区安装完成：" + slotName + "。可选择单次启动。");
        } catch (Exception error) {
            recordOperation("安装错误：" + error.getMessage());
            finish(Phase.FAILED, "安装未完成：" + error.getMessage()
                    + "\n若已创建槽位，将保留未完成标记，请回主系统检查日志。");
        } finally {
            deleteTemporary(operationDirectory);
            prepared = null;
        }
    }

    private void finish(Phase next, String text) {
        main.post(() -> {
            phase = next;
            message = text;
            stopForeground(STOP_FOREGROUND_REMOVE);
            publish();
            stopSelf();
        });
    }

    private void publish() {
        main.post(() -> { Runnable callback = listener; if (callback != null) callback.run(); });
    }

    private Notification notification(String text, int progress) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Elysia 多系统工具箱")
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(100, Math.max(0, progress), progress < 0).build();
    }

    private void updateNotification(String text, int progress) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text, progress));
    }

    static void deleteTemporary(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTemporary(child);
        }
        file.delete();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { cancelled = true; worker.shutdown(); super.onDestroy(); }
}
