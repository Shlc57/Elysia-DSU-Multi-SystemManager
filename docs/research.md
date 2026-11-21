# DSU / 多系统开源项目调研

调研日期：2026-11-21。检查了上游仓库源码与固定 commit；候选 checkout 放在工作区外的临时目录，没有把它们的源码复制进本项目。更详细的上游集成结论见本文件“与 VegaBobo 上游的关系”。

## 结论先看

最贴近“多系统槽位管理器”的现成参考是 **[XZY-01/DSUManager](https://github.com/XZY-01/DSUManager)**：MIT、Java 原生 View、2026-11-14 仍有提交，代码中直接使用带 `--install-dir` 的 `gsi_tool` 创建命名槽位，并支持列出、切换和删除多个槽。README 声明在 Redmi K70 Pro / HyperOS 3 / Android 16 / KernelSU 上测试；这不等于已验证 MIX 4、ColorOS 17 或所有设备，也没有承诺固定 16 槽。

本项目第一版按其 **DSU 槽管理/Root gsi_tool 路线**实现自有客户端后端并保留 KSU 风格 UI；`SuperImage.java` 是基于上游同名 MIT 文件的派生实现，其他槽位/Root 代码独立编写。第三方来源、修改说明和完整 MIT 声明见 [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)。继续扩展时仍需保留槽名校验、覆盖保护、空间预检、失败状态保护与启动回退。

## 真正涉及多槽的项目

### XZY-01/DSUManager — 首选参考

- 仓库：[github.com/XZY-01/DSUManager](https://github.com/XZY-01/DSUManager)
- 本次检查 HEAD：`b1a787c580a302db1e29b8557e93701a20be7fbe`，2026-11-14；README/更新记录为 v2.2。
- 许可证：MIT。
- 实现：`gsi_tool status` 解析已安装 DSU；按 `/data/gsi/dsu/<slot>` 与 `/metadata/gsi/dsu/<slot>` 管理每槽数据/元数据；通过 `gsi_tool install --install-dir=...` 和 `gsi_tool enable -d <slot> -s` 安装并单次启动；也提供 sticky 启动、禁用、重命名、删除、多分区 `system/vendor/product/...` 安装及 userdata 重建。
- 安装器分成两路：多槽/多分区走 Root `gsi_tool`；Shizuku“原生安装”走系统 `VerificationActivity`，只适用于默认单 `dsu` system 镜像。它没有把这两条能力混为一谈。
- 工程特点：纯 Java + Android View，不依赖 Gradle/Compose；仓库有 PowerShell SDK 构建脚本、截图和逐版本日志。使用 Android Platform 37 / Build Tools 36，适合直接检查代码与界面，但本机仍需 Android SDK 才能生成 APK。
- 适配边界：项目自述主测试机为 Redmi K70 Pro、HyperOS 3、Android 16、KernelSU；“理论 Android 11–17”不是对其他机型的实测承诺。多槽总数会受动态分区、`/data` 空间、gsid/ROM 策略限制，不应直接承诺 16+。
- 审查重点：它通过 Root shell 触达 `gsi_tool`、`/data/gsi` 与 `/metadata/gsi`。在移植/改写前，应独立校验槽名与分区白名单、Shell 参数转义、失败回滚、活动槽保护、SELinux、元数据加密路径及设备启动回退。

### R15Hi/Dsu-Multi-sideloader — 多槽备份/恢复路线

- 仓库：[github.com/R15Hi/Dsu-Multi-sideloader](https://github.com/R15Hi/Dsu-Multi-sideloader)
- 本次检查 HEAD：`7e95c4c98f970374a40b839cb030e9bf237452db`，2026-10-15；Apache-2.0；基于 VegaBobo Sideloader。
- README 宣称多 DSU、GSI Hub、备份/切换和 Root 自动暂存。实际 `MultiDsuManager.kt` 采用另一种模型：把正在使用的 `/data/gsi/dsu` 与 `/metadata/gsi/dsu` 备份到 `dsu_backups`，wipe 当前活动 DSU，再将指定备份恢复回活动路径。
- 恢复过程还涉及 `/metadata/vold/metadata_encryption` 密钥、`restorecon` 标签和 gsid 状态，代码强调始终用 `gsi_tool enable -s`。这是高风险的备份/换入方案，不是简单的多个独立活动 install-dir；不能直接照搬到 MIX 4/ColorOS，必须审计并在可恢复设备上测试。

## 近期但不是多槽管理器

| 项目 | 本次检查 HEAD / 许可证 | 有价值的方向 | 与多槽目标的差距 |
|---|---|---|---|
| [ayzlol/bdsu-adb-dsu](https://github.com/ayzlol/bdsu-adb-dsu) | `580271c` · 2026-11-13 · GPL-3.0 | 每次通过系统 `VerificationActivity` 安装时传入唯一 `KEY_DSU_SLOT=bdsu<hex>`，减少复用 OEM `dsu` 槽造成的冲突；哈希校验、下载取消/清理和持久通知 | 可作为“新安装生成独立 DSU 名”的实现参考，但没有完整的已安装槽位列表、命名/删除/切换管理器 |
| [kerneldroid/Dsu-Extended](https://github.com/kerneldroid/Dsu-Extended) | `ea7357b` · 2026-11-08 · Apache-2.0 | Root/Shizuku/Dhizuku、镜像兼容性预检、Treble/ABI/VNDK/存储检查、GSI 专项诊断日志 | 现代化 DSU 安装器，不是多槽管理器；README 要求较新的 SDK/JDK/Gradle |
| [OhMyDitzzy/DSU-Next](https://github.com/OhMyDitzzy/DSU-Next) | `3fdb9ee` · 2026-10-04 · Apache-2.0 + NOTICE | DSU Sideloader 派生；GSI 编辑/重打包、Treble 检查、Rust core | 主要是单 DSU 安装/编辑；README 标示 JNI bridge 尚未完成 |
| [yangFenTuoZi/DSU-Sideloader-Plus](https://github.com/yangFenTuoZi/DSU-Sideloader-Plus) | `baa856f` · 2026-06-15 · Apache-2.0 | 延续 VegaBobo 工程、更新依赖和安装体验 | 主要仍是原 DSU Sideloader 路线；未发现多槽管理功能 |
| [Gong-Mi/DSU-Sideloader](https://github.com/Gong-Mi/DSU-Sideloader) | `7e93599` · 2026-07-27 | Rust CLI：状态、安装、移除、启动/回退、解压 | README 说 Android JNI 尚未完成；许可证有冲突：README 写 GPL-3.0，但 LICENSE 文件是 Apache-2.0，澄清前不建议复用其代码 |

## 仅 APK / 不适合作为源码基线

Appteka 上有名为 **Multi-Dsu** 的 APK 页面，标注 2026-10-15 更新，并宣称 Root auto-stashing、备份/恢复和补丁 gsid；本次没有找到可审计的公开源码仓库，页面摘要还注明部分内容由 AI 生成。它可当待核实的产品线索，不应作为 Root/启动链实现的可信来源或直接安装建议。

## 与 VegaBobo 上游的关系

[VegaBobo/DSU-Sideloader](https://github.com/VegaBobo/DSU-Sideloader) 的最新正式版为 v2.03（2023），许可证 Apache-2.0，适合作为标准 DSU 安装、权限模式和错误诊断的参考；其 Root `DSUInstaller` 当前固定 `DEFAULT_SLOT = "dsu"`。多槽能力应参考 XZY 的 `gsi_tool --install-dir` 路线，或 R15Hi 的备份/恢复实现，而不能只依赖上游 Sideloader 标准安装入口。

当前 Root shell 后端独立编写；只有 `SuperImage.java` 基于 XZY-01/DSUManager 的 MIT 文件修改。未复制 VegaBobo 代码。新增第三方文件时需保留上游声明并更新 NOTICE/许可证记录。

## 内核/系统侧资料

- [LineageOS `android_system_gsid`](https://github.com/LineageOS/android_system_gsid/tree/lineage-24.0) 有“支持多个 DSU 安装”的系统侧代码历史；因此多槽不是 UI 列表能独立实现的功能，仍由目标 Android/gsid/ROM 具体版本决定。
- [AOSP DSU 文档](https://developer.android.com/topic/dsu) 是标准安装/启动模式的依据；设备必须支持动态分区，常规安装要求解锁 bootloader。

## 推荐下一步

1. 从 XZY `DsuManager.java`、`RootShell.java`、安装工作流中抽取或重写 Root DSU 后端；以自己的名称校验、进程流式写入、事务回滚和数据安全策略替代简单复制。
2. 自有 UI 保持独立，先支持状态/槽列表/单次启动/回到主系统/安装/删除；多槽能力仅在 Root、`gsi_tool` 和目标 ROM 探测通过后开放。
3. MIX 4、ColorOS 17 Beta 的 16+ 槽、data 隔离、冷启动/失败回退都需要设备实测；目前没有从上述仓库获得该设备组合的公开验证。

## DSU 镜像安装范围

本应用可以把兼容的 GSI/ROM 分区镜像安装到新的命名 DSU 槽，不会把镜像刷写到设备的物理 system/vendor 分区。当前导入器支持单个 `system.img`（也识别发行版命名的 GSI 镜像）、按分区命名的多分区 ZIP/文件夹、可解析的 `super.img`，以及 sparse 与 `.gz` 输入；只写入白名单中的 `system`、`system_ext`、`product`、`vendor`、`odm`、`vendor_dlkm`、`system_dlkm`、`mi_ext`。`payload.bin`、整包 OTA、`boot`、`vendor_boot`、`vbmeta`、`recovery` 和 `userdata` 不是可安装输入。

AOSP/LineageOS `gsi_tool install` 从标准输入读取一个分区镜像，默认安装 `system` 并创建独立 userdata，也允许通过 `--partition-name` 写入其他 DSU 分区；`--install-dir` 选择命名槽目录，`--no-reboot` 禁止工具自动重启。工具会拒绝在正在运行的 GSI 内安装，因此本应用要求先回主系统。安装成功只证明镜像写入与槽元数据完成，不保证 GSI 与目标设备的 vendor、内核、ABI、AVB 或 SELinux 兼容；启动需单独确认。

- AOSP/LineageOS `gsi_tool.cpp`：<https://github.com/LineageOS/android_system_gsid/blob/lineage-24.0/gsi_tool.cpp>
- AOSP DSU 文档：<https://developer.android.com/topic/dsu>

## DSU 内预置管理器 APK

对任意导入的第三方 ROM，安装阶段不能直接把应用安装到将来启动的 DSU userdata：宿主当前的 PackageManager 和 `/data/app` 属于宿主系统，而 DSU 有自己的 userdata 与 PackageManager 状态。把 APK 放进 ROM ZIP 也不会生效；当前 importer 只提取分区镜像。

通用且风险较低的路径是在 DSU 首次启动后，通过该系统自己的 Package Installer 或已授权 ADB 安装管理器。应用会存入该槽的独立 userdata，切回其他系统后仍随原槽保留。首启前预置只适合自行构建并验证的 GSI：把普通应用放进 `/system/app` 并正确构建 system 镜像；不应默认放进 `/system/priv-app`，因为本应用没有 privileged permission allowlist，也不会因此获得 Root。任意第三方镜像被改包后，文件系统元数据、SELinux 标签和 AVB 校验都可能失效；重打包或写入成功不代表可以启动。Root provider 还必须在每个 DSU 内可用，并单独授权管理器，APK 预置本身不会带来 Root。

本项目当前采用开发证书签名，包名为 `dev.multisystem.client`。同一槽内后续覆盖更新需保持包名、签名身份兼容并使用递增的 `versionCode`。因此当前建议不自动注入 APK；先在 DSU 内安装并验证管理器及 Root 授权。设备内预置、启动和 A/B 管理器可用性仍需逐 ROM 实测。

- [AOSP DSU 安装器 userdata 处理](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/packages/DynamicSystemInstallationService/src/com/android/dynsystem/InstallationAsyncTask.java)
- [PackageManager 系统应用扫描](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/pm/InitAppsHelper.java)
- [Privileged permission allowlist](https://source.android.com/docs/core/permissions/perms-allowlist)
- [AOSP image builder](https://github.com/aosp-mirror/platform_build/blob/main/tools/releasetools/build_image.py)
- [AOSP AVB 校验](https://github.com/aosp-mirror/platform_system_core/blob/main/fs_mgr/libfs_avb/fs_avb.cpp)
