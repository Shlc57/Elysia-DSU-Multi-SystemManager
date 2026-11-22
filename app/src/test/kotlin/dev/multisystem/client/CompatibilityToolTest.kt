package dev.multisystem.client

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CompatibilityToolTest {
    private val bundle = "/data/local/tmp/elysia-dsu-permissive/0123456789abcdef0123456789abcdef/bundle.sh"
    private val backups = "/data/local/tmp/elysia-dsu-permissive/0123456789abcdef0123456789abcdef/backups"
    @Test fun recognizesOnlySupportedKmiGenerations() {
        assertEquals("android15-6.6", CompatibilityTool.kmiTarget("6.6.89-android15-8-g12345"))
        assertEquals("android14-6.1", CompatibilityTool.kmiTarget("6.1.75-android14-11"))
        assertNull(CompatibilityTool.kmiTarget("6.6.75-custom"))
        assertNull(CompatibilityTool.kmiTarget("6.6.75-android14-8"))
        assertNull(CompatibilityTool.kmiTarget("5.4.75-android12-8"))
    }
    @Test fun neverBuildsAFlashOrRebootCommand() {
        val command = CompatibilityTool.patchCommand(bundle, backups, "/dev/block/by-name/init_boot_a", CompatibilityOptions())
        assertFalse(command.contains("--flash"))
        assertFalse(command.contains("reboot"))
        assertFalse(command.contains("dd "))
        assertTrue(command.contains("'--selinux' '1'"))
        assertTrue(command.contains("'--verity-table-spoof' '0'"))
    }
    @Test fun flashRequiresAnExplicitCommandFlag() {
        val command = CompatibilityTool.patchCommand(bundle, backups, "/dev/block/by-name/init_boot_a",
            CompatibilityOptions(), flash = true)
        assertTrue(command.contains("'--flash'"))
    }
    @Test fun blocksLiveDsuAndUncertainPartitions() {
        assertFalse(CompatibilityTool.validPartition("/dev/block/by-name/vbmeta_a"))
        assertFalse(CompatibilityTool.validPartition("/dev/block/by-name/init_boot_a;reboot"))
        assertFalse(CompatibilityTool.validPartition("/dev/block/platform/../by-name/boot"))
        val plan = CompatibilityPlan("6.6-android15", "android15-6.6", "/dev/block/by-name/init_boot_a",
            32L * 1024 * 1024, 8L * 1024 * 1024 * 1024, true, false, "1")
        assertFalse(plan.canPrepare)
        assertTrue(plan.copy(runningDsu = false).canPrepare)
        assertFalse(plan.copy(runningDsu = false, freeBytes = 1).canPrepare)
    }
    @Test(expected = IllegalArgumentException::class)
    fun verityRequiresAvb() {
        CompatibilityTool.patchCommand(bundle, backups, "/dev/block/by-name/init_boot_a",
            CompatibilityOptions(avb = false, verity = true))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsEscapingStagingPath() {
        CompatibilityTool.patchCommand("/data/local/tmp/elysia-dsu-permissive/../boot/bundle.sh", backups,
            "/dev/block/by-name/init_boot_a", CompatibilityOptions())
    }
    @Test fun rejectsChangedBundleWithoutExecutingIt() {
        val file = File.createTempFile("invalid-bundle", ".sh")
        try {
            file.writeText("#!/system/bin/sh\necho not-the-reviewed-bundle\n")
            assertFalse(CompatibilityTool.validBundle(file))
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CompatibilityTool.sha256(file))
        } finally { file.delete() }
    }
}
