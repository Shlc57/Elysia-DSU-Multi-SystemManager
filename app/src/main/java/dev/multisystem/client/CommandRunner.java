package dev.multisystem.client;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

class CommandRunner {
    static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    Result root(String... args) throws Exception {
        return execute(rootCommand(args), null, 2, TimeUnit.MINUTES, null);
    }

    Result rootWithStream(String command, InputStream input, long totalBytes,
                          ProgressListener listener) throws Exception {
        ProgressListener scaled = null;
        if (listener != null) {
            long interval = Math.max(1L, totalBytes / 100L);
            long[] lastReported = {0L};
            scaled = (sent, ignored) -> {
                if (sent == totalBytes || sent - lastReported[0] >= interval) {
                    lastReported[0] = sent;
                    listener.onProgress(sent, totalBytes);
                }
            };
        }
        return execute(command, input, 60, TimeUnit.MINUTES, scaled);
    }

    interface ProgressListener {
        void onProgress(long sentBytes, long totalBytes);
    }

    private static String rootCommand(String... args) {
        StringBuilder command = new StringBuilder();
        for (String arg : args) {
            if (command.length() > 0) command.append(' ');
            command.append(quote(arg));
        }
        return command.toString();
    }

    private static Result execute(String command, InputStream input, long timeout,
                                   TimeUnit timeoutUnit, ProgressListener listener) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true).start();
        FutureTask<String> reader = new FutureTask<>(() -> readLimited(process.getInputStream()));
        Thread thread = new Thread(reader, "root-command-output");
        thread.setDaemon(true);
        thread.start();
        FutureTask<Void> writer = new FutureTask<>(() -> {
            try (InputStream source = input; OutputStream output = process.getOutputStream()) {
                if (source != null) {
                    long sent = 0;
                    byte[] buffer = new byte[256 * 1024];
                    int count;
                    while ((count = source.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("镜像流传输已取消");
                        output.write(buffer, 0, count);
                        sent += count;
                        if (listener != null) listener.onProgress(sent, -1L);
                    }
                    output.flush();
                }
            }
            return null;
        });
        Thread writerThread = new Thread(writer, "root-command-input");
        writerThread.setDaemon(true);
        writerThread.start();
        long deadline = System.nanoTime() + timeoutUnit.toNanos(timeout);
        try {
            writer.get(timeout, timeoutUnit);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                throw new java.util.concurrent.TimeoutException();
            }
            return new Result(process.exitValue(), reader.get(15, TimeUnit.SECONDS).trim());
        } catch (Exception failure) {
            process.destroyForcibly();
            writer.cancel(true);
            String output = "";
            try { output = reader.get(3, TimeUnit.SECONDS).trim(); } catch (Exception ignored) { }
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Root 命令未完成：" + failure
                    + (output.isEmpty() ? "" : "\n" + output), failure);
        }
    }

    private static String readLimited(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            boolean truncated = false;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() < 2 * 1024 * 1024) {
                    int keep = Math.min(count, 2 * 1024 * 1024 - out.size());
                    out.write(buffer, 0, keep);
                    if (keep < count) truncated = true;
                } else {
                    truncated = true;
                }
            }
            return out.toString(StandardCharsets.UTF_8.name()) + (truncated ? "\n[输出已截断]" : "");
        }
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
