# 多系统管理器（第一版）

基于 Root + Android `gsi_tool` / DSU 的多槽客户端原型，UI 参考 KernelSU Manager。项目已初始化 Git；当前实现只面向**已支持 DSU 多槽的设备**，不修改物理分区。

> **重要：**第一版不是 MIX 4 / ColorOS 17 的适配包。是否能启动某个完整 ROM，取决于设备的动态分区、gsid、启动镜像、vendor/驱动和 SELinux 策略。请备份数据并准备好官方线刷包；不要在主力机上盲刷。

## 第一版功能

- Root 授权与 `gsi_tool` 探测；读取已安装 DSU 槽、活动/运行槽以及 `/data` 可用空间。
- 以新槽方式安装 raw `system.img` 或 `.img.gz`；设置 userdata 大小。拒绝覆盖同名槽，按 Root 临时副本 + DSU system + userdata + 余量保守检查剩余 `/data` 空间和镜像传输长度。
- `gsi_tool enable -d <slot> -s` 单次启动，并提供禁用 DSU 后重启回主系统。
- 删除非运行槽位；正在运行 DSU 时禁止安装、切换和删除。
- KSU 风格首页、系统列表、设置、安全提示和操作日志。

## 当前不包含

- Super/OTA ZIP/多分区 ROM（vendor、boot、驱动等）安装、稀疏镜像转换、镜像签名/哈希目录。
- Shizuku/无 Root 安装、自动内核适配、自动恢复引导或保证“16+”槽位。
- MIX 4 / ColorOS 17 的实机兼容验证。`gsi_tool` 能创建 DSU 槽，不代表厂商 ROM 一定能从该槽启动。

## 安装流程概览

```text
选择镜像 → 校验格式/空间 → 暂存镜像 → root shell 将镜像流写入临时文件
→ gsi_tool install --install-dir=/data/gsi/dsu/<槽位> --no-reboot
→ 关闭安装命令设置的一次性自动启动 → 用户手动选择“启动一次”
```

安装前会拒绝已有槽名，避免覆盖有效 DSU。临时镜像写入 `/data/local/tmp`，操作结束清理。安装失败时会取消 gsid 操作并清理本次新建槽。`gsi_tool` 与厂商策略可能不同，尚未在目标设备实测的行为均需谨慎验证。

若当前运行的 DSU 没有可用 Root，应用可能无法调用 `gsi_tool` 回主系统；请使用系统 DSU 持久通知或厂商提供的恢复入口，不要强制删除活动槽。

## 构建

- JDK 17+
- Gradle 9.1+
- Android Gradle Plugin 9.0+
- Android SDK Platform 36、Build Tools 36.0.0

```bash
./gradlew assembleDebug
# Windows PowerShell: .\gradlew.bat assembleDebug
```

目前运行环境缺少 Android SDK Platform `android.jar`，且离线缓存中没有 AGP，尚未生成/验证 APK。安装 Android Studio SDK 并连接 Google Maven 后构建。

## 开源参考

- [XZY-01/DSUManager](https://github.com/XZY-01/DSUManager) — 多槽 Root `gsi_tool` 参考，MIT；我们是独立实现，未复制其源码。
- [VegaBobo/DSU-Sideloader](https://github.com/VegaBobo/DSU-Sideloader) — 标准 DSU 安装流程参考，Apache-2.0。
- [R15Hi/Dsu-Multi-sideloader](https://github.com/R15Hi/Dsu-Multi-sideloader) — Root 备份/恢复路线参考；危险操作需单独审计。
- 更多项目、版本和实现差异见 [`docs/research.md`](docs/research.md)。

## 许可证

本项目原创代码为 MIT，见 [`LICENSE`](LICENSE)。未复制上述上游源码；未来如引入第三方实现，需遵守其对应许可证并补齐 attribution/NOTICE。
