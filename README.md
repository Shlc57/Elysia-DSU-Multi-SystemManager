# Elysia 多系统工具箱

基于 Root + Android `gsi_tool` / DSU 的多槽客户端原型，软件名称为 **Elysia 多系统工具箱**，UI 参考 KernelSU Manager。项目已初始化 Git；当前实现只面向**已支持 DSU 多槽的设备**，不修改物理分区。

> **重要：**第一版不是 MIX 4 / ColorOS 17 的适配包。是否能启动某个完整 ROM，取决于设备的动态分区、gsid、启动镜像、vendor/驱动和 SELinux 策略。请备份数据并准备好官方线刷包；不要在主力机上盲刷。

## 第一版功能

- Root 授权与 `gsi_tool` 探测；读取已安装 DSU 槽、活动/运行槽以及 `/data` 可用空间。
- 支持独立分区镜像多选、镜像 ZIP、ROM 文件夹、raw/sparse/gzip 镜像和 `super.img` LP 解包；支持 system、system_ext、product、vendor、odm、vendor_dlkm、system_dlkm、mi_ext。
- 以新槽方式按固定顺序写入多个动态分区；设置 userdata 大小。拒绝覆盖同名槽，按 Root 临时副本 + DSU 分区 + userdata + 余量保守检查剩余 `/data` 空间和镜像传输长度。
- `gsi_tool enable -d <slot> -s` 单次启动，并提供禁用 DSU 后重启回主系统。
- 删除非运行槽位；正在运行 DSU 时禁止安装和删除，直切要求目标槽元数据核验并确认单次启动。
- Kotlin / Jetpack Compose 界面，固定使用 Miuix 组件风格；支持主题色、深色 / AMOLED、导航转场和操作日志。
- 伪回锁兼容模式：内置并校验 DSU-Permissive v0.7.0，检查环境后可生成补丁；刷入需在二次确认后执行，脚本先备份并回读校验，管理器不自动重启。模式切换与默认 DSU 流程分离，不改变 Bootloader 的真实锁定状态。详见 [`docs/compatibility-mode.md`](docs/compatibility-mode.md)。
- AndroidLiquidGlass Backdrop 提供顶部与底部导航的动态毛玻璃 / 折射效果；底栏避让系统导航区并为页面留出滚动空间。

## 当前不包含

- OTA `payload.bin`、boot/vbmeta/固件刷写、厂商驱动移植和镜像签名。
- Shizuku/无 Root 安装、自动内核适配、自动恢复引导或保证“16+”槽位。
- MIX 4 / ColorOS 17 的实机兼容验证。`gsi_tool` 能创建 DSU 槽，不代表厂商 ROM 一定能从该槽启动。

## 安装流程概览

```text
添加镜像 → 设置槽位名称和 userdata → 选择镜像/ZIP/ROM 文件夹 → 返回首页
→ 点击安装系统 → 前台服务解析镜像并写入同一个 DSU 槽
→ 清理暂存文件 → 在已安装系统列表中启动指定槽位
```

安装前会拒绝已有槽名，避免覆盖有效 DSU。Android 前台服务会在后台继续准备/刷写，并在写入前展示分区清单。每个分区临时写入 `/data/local/tmp` 后立即清理；安装失败会取消 gsid，但保留带 `.elysia-installing` 标记的槽位供恢复检查，不会盲目删除可能已写入的用户数据。`gsi_tool` 与厂商策略可能不同，尚未在目标设备实测的行为均需谨慎验证。

若当前运行的 DSU 没有可用 Root，应用可能无法调用 `gsi_tool` 回主系统；请使用系统 DSU 持久通知或厂商提供的恢复入口，不要强制删除活动槽。

## 构建

- JDK 21（包含 javac）
- Gradle 9.7.1（Wrapper）
- Android Gradle Plugin 9.4.1、Kotlin / Compose compiler 2.4.20
- Android SDK Platform 37.0、Build Tools 37.0.0

```bash
./gradlew assembleDebug
# Windows PowerShell: .\gradlew.bat assembleDebug
```

需要通过 Gradle 下载 Compose/Miuix 依赖；旧的纯 Java SDK 直编方式已停用。以下脚本调用 Gradle 构建和单元测试，验证签名/对齐并将结果放入被忽略的 `output/`。覆盖安装需要本地 `.sign/elysia-debug.jks` 与已安装版本保持同一证书，不提交签名文件。

```bash
python3 scripts/build_apk.py --sdk /home/elysia/elysia-dsu-tools/android-sdk
```

## 开源参考

- [XZY-01/DSUManager](https://github.com/XZY-01/DSUManager) — 多槽 Root `gsi_tool` 参考，MIT；槽位后端独立实现，`SuperImage.java` 的来源与许可证见 NOTICE。
- [VegaBobo/DSU-Sideloader](https://github.com/VegaBobo/DSU-Sideloader) — 标准 DSU 安装流程参考，Apache-2.0。
- [R15Hi/Dsu-Multi-sideloader](https://github.com/R15Hi/Dsu-Multi-sideloader) — Root 备份/恢复路线参考；危险操作需单独审计。
- 更多项目、版本和实现差异见 [`docs/research.md`](docs/research.md)。

## 许可证

本项目原创代码为 MIT，见 [`LICENSE`](LICENSE)。`SuperImage.java` 基于 XZY-01/DSUManager 的 MIT 文件修改，许可证和修改说明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
