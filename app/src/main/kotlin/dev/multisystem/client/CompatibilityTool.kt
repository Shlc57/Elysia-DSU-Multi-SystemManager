package dev.multisystem.client

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

internal data class CompatibilityOptions(val selinux: Boolean = true, val avb: Boolean = true, val verity: Boolean = false)
internal data class CompatibilityPlan(val kernel: String, val target: String, val partition: String,
    val partitionBytes: Long, val freeBytes: Long, val runningDsu: Boolean, val avbEnforce: Boolean, val lockedState: String) {
    val canPrepare get() = target.isNotEmpty() && partition.isNotEmpty() && partitionBytes in 1..MAX_BOOT_BYTES
        && freeBytes > partitionBytes * 6 + 128L * 1024 * 1024 && !runningDsu
    companion object { const val MAX_BOOT_BYTES = 1024L * 1024 * 1024 }
}

/** Runs the pinned bundled CLI; flashing is a separate explicit-confirmation path. */
internal class CompatibilityTool(private val context: Context, private val runner: CommandRunner = CommandRunner()) {
    private val imported = File(context.filesDir, "compatibility/dsu-permissive-v0.7.0.sh")

    fun hasVerifiedBundle(): Boolean {
        if (!validBundle(imported)) {
            imported.parentFile!!.mkdirs()
            val staged = File(imported.parentFile, UUID.randomUUID().toString() + ".tmp")
            try {
                context.assets.open(BUNDLE_ASSET).use { input ->
                    staged.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= BUNDLE_BYTES) { "内置脚本大小异常" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                check(validBundle(staged)) { "内置脚本 SHA-256 不匹配，拒绝执行" }
                java.nio.file.Files.move(staged.toPath(), imported.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { staged.delete() }
        }
        return validBundle(imported)
    }

    fun inspect(): CompatibilityPlan {
        val identity = runner.root("id")
        check(identity.exitCode == 0 && identity.output.contains("uid=0")) { "需先授予管理器 Root 权限" }
        val script = "printf 'ARCH='; uname -m; printf 'KERNEL='; uname -r; " +
            "printf 'RUNNING='; getprop ro.gsid.image_running; printf 'RUNNING_ALT='; getprop ro.gsi.dsu; " +
            "printf 'DSU_SLOT='; getprop ro.gsid.dsu_slot; " +
            "printf 'BOOTED='; if [ -e /metadata/gsi/dsu/booted ]; then echo 1; else echo 0; fi; " +
            "printf 'LOCKED='; getprop ro.boot.flash.locked; " +
            "printf 'AVB_ENFORCE='; if [ -e /metadata/gsi/dsu/avb_enforce ]; then echo 1; else echo 0; fi; " +
            "suffix=\$(getprop ro.boot.slot_suffix); " +
            "if [ -z \"\$suffix\" ]; then slot=\$(getprop ro.boot.slot); case \"\$slot\" in a|b) suffix=_\$slot;; esac; fi; " +
            "case \"\$suffix\" in _a|_b|'') ;; *) echo ERR_SLOT; exit 9;; esac; " +
            "found=; for root in /dev/block/by-name /dev/block/bootdevice/by-name /dev/block/platform/*/by-name; do " +
            "[ -d \"\$root\" ] || continue; for name in init_boot boot; do p=\"\$root/\$name\$suffix\"; " +
            "if [ -b \"\$p\" ]; then found=\$p; break; fi; done; [ -n \"\$found\" ] && break; done; " +
            "printf 'PARTITION=%s\\n' \"\$found\"; " +
            "if [ -n \"\$found\" ]; then printf 'SIZE='; blockdev --getsize64 \"\$found\"; fi; " +
            "printf 'FREE='; df -k /data/local/tmp | awk 'END {print \$4}'"
        val result = runner.root("sh", "-c", script)
        check(result.exitCode == 0) { "环境检查失败：${result.output}" }
        val values = result.output.lineSequence().filter { '=' in it }.associate {
            it.substringBefore('=') to it.substringAfter('=').trim()
        }
        check(values["ARCH"] in listOf("aarch64", "arm64")) { "仅支持 arm64 GKI" }
        val kernel = values["KERNEL"].orEmpty()
        val partition = values["PARTITION"].orEmpty().takeIf(::validPartition) ?: ""
        return CompatibilityPlan(kernel, kmiTarget(kernel).orEmpty(), partition,
            values["SIZE"]?.toLongOrNull() ?: 0, (values["FREE"]?.toLongOrNull() ?: 0) * 1024,
            values["RUNNING"] in listOf("1", "true") || values["RUNNING_ALT"] in listOf("1", "true")
                || values["BOOTED"] == "1" || values["DSU_SLOT"].orEmpty().isNotEmpty(),
            values["AVB_ENFORCE"] == "1", values["LOCKED"].orEmpty())
    }

    fun prepare(options: CompatibilityOptions, flash: Boolean): String {
        check(hasVerifiedBundle()) { "内置脚本校验失败" }
        val plan = inspect()
        check(plan.canPrepare) { "需在主系统、匹配的 arm64 GKI、已识别引导槽位且备份空间足够时生成补丁" }
        check(!options.avb || !plan.avbEnforce) { "存在 avb_enforce 标记，不能确认 AVB 辅助路径有效；不会自动删除该标记" }
        check(!options.verity || options.avb) { "dm-verity 表辅助依赖 AVB 拦截" }
        val directory = "/data/local/tmp/elysia-dsu-permissive/" + UUID.randomUUID().toString().replace("-", "")
        val bundle = "$directory/bundle.sh"
        val backups = "$directory/backups"
        val command = patchCommand(bundle, backups, plan.partition, options, flash)
        val script = "set -e; umask 077; base=/data/local/tmp/elysia-dsu-permissive; " +
            "test ! -L \"\$base\"; mkdir -p \"\$base\"; chmod 700 \"\$base\"; mkdir ${quote(directory)}; " +
            "BUNDLE=${quote(bundle)}; trap 'rm -f \"\$BUNDLE\"' EXIT; cat > \"\$BUNDLE\"; " +
            "actual=\$(sha256sum \"\$BUNDLE\" | cut -d ' ' -f 1); " +
            "test \"\$actual\" = ${quote(BUNDLE_SHA256)} || { echo ERR_BUNDLE_HASH; exit 8; }; " +
            "TMPDIR=${quote(directory)} $command </dev/null; echo ELYSIA_BACKUPS=${quote(backups)}"
        val result = FileInputStream(imported).use { runner.rootWithStream(script, it, imported.length(), null) }
        check(result.exitCode == 0) {
            if (flash) "刷写或回读校验未完成；不要重启。请检查备份目录：$backups\n${result.output}"
            else "补丁生成未完成；未请求写入分区。可能的备份目录：$backups\n${result.output}"
        }
        return result.output + if (flash)
            "\n脚本已按其流程完成刷入和回读校验；管理器未执行重启，请先确认备份与设备恢复方式。"
        else "\n本次未请求刷写。生成补丁不代表已启用或与厂商 KMI/签名兼容。"
    }

    companion object {
        const val BUNDLE_BYTES = 11999539L
        const val BUNDLE_SHA256 = "86b7c4eaaed8d11211c15c71302f0ecc620de1b0647c510299895ed6ec01c04d"
        const val BUNDLE_ASSET = "dsu-permissive-v0.7.0.sh"
        const val SOURCE = "https://github.com/yangFenTuoZi/DSU-Permissive"
        private fun quote(value: String) = CommandRunner.quote(value)
        fun validBundle(file: File): Boolean = file.isFile && file.length() == BUNDLE_BYTES && sha256(file) == BUNDLE_SHA256
        fun sha256(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun kmiTarget(kernel: String): String? {
            val matrix = listOf("5.10" to 12, "5.10" to 13, "5.15" to 13, "5.15" to 14,
                "6.1" to 14, "6.6" to 15, "6.12" to 16)
            return matrix.firstOrNull { (branch, android) ->
                Regex("^" + Regex.escape(branch) + "[.-].*android$android(?:[-.]|$)").containsMatchIn(kernel)
            }?.let { "android${it.second}-${it.first}" }
        }
        fun validPartition(value: String): Boolean = Regex("^/dev/block/(?:by-name|bootdevice/by-name|platform/[A-Za-z0-9_./-]+/by-name)/(?:init_boot|boot)(?:_[ab])?$").matches(value)
                && ".." !in value.split('/')
        fun patchCommand(bundle: String, backups: String, partition: String, options: CompatibilityOptions,
                         flash: Boolean = false): String {
            require(validPartition(partition))
            require(Regex("^/data/local/tmp/elysia-dsu-permissive/[a-f0-9]{32}/bundle\\.sh$").matches(bundle))
            require(Regex("^/data/local/tmp/elysia-dsu-permissive/[a-f0-9]{32}/backups$").matches(backups))
            require(!options.verity || options.avb)
            return (listOf("sh", bundle) + (if (flash) listOf("--flash") else emptyList()) +
                listOf("--partition", partition, "--slot", "current", "--backup-dir", backups,
                "--selinux", if (options.selinux) "1" else "0", "--avb", if (options.avb) "1" else "0",
                "--verity-table-spoof", if (options.verity) "1" else "0")).joinToString(" ", transform = ::quote)
        }
    }
}
