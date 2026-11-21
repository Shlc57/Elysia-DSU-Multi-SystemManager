package dev.multisystem.client;

final class SystemSlot {
    final String id;
    final String name;
    final String version;
    final String state;
    final String partitions;
    final long sizeBytes;
    final boolean active;
    final boolean selected;
    final boolean bootable;
    final boolean dataIsolated;

    SystemSlot(String id, String name, String version, String state, String partitions, long sizeBytes,
               boolean active, boolean selected, boolean bootable, boolean dataIsolated) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.state = state;
        this.partitions = partitions;
        this.sizeBytes = sizeBytes;
        this.active = active;
        this.selected = selected;
        this.bootable = bootable;
        this.dataIsolated = dataIsolated;
    }

    String sizeLabel() {
        if (sizeBytes <= 0) return "大小由设备端提供";
        return String.format(java.util.Locale.getDefault(), "%.2f GiB", sizeBytes / 1073741824.0);
    }
}
