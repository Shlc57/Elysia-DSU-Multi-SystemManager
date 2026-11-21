package dev.multisystem.client;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

final class CommandRunner {
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

        long sent = 0;
        byte[] buffer = new byte[256 * 1024];
        java.io.IOException streamFailure = null;
        try (OutputStream output = process.getOutputStream()) {
            if (input != null) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    sent += count;
                    if (listener != null) listener.onProgress(sent, -1L);
                }
                output.flush();
            }
        } catch (java.io.IOException e) {
            streamFailure = e;
        } finally {
            if (input != null) input.close();
        }

        if (streamFailure != null) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            String output = reader.get(15, TimeUnit.SECONDS).trim();
            throw new IllegalStateException("镜像流传输失败：" + streamFailure.getMessage()
                    + (output.isEmpty() ? "" : "\n" + output), streamFailure);
        }

        if (!process.waitFor(timeout, timeoutUnit)) {
            process.destroyForcibly();
            throw new IllegalStateException("Root 操作超时，已终止等待。请检查设备状态后再试。");
        }
        String output = reader.get(15, TimeUnit.SECONDS).trim();
        return new Result(process.exitValue(), output);
    }

    private static String readLimited(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            boolean truncated = false;
            while ((count = in.read(buffer)) != -1) {
                total += count;
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
