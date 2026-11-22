# 伪回锁模式与 DSU 启动兼容脚本

## 模式隔离

- 首页系统信息卡分为两栏：左侧展示设备、当前系统及 Root/DSU 状态，右侧提供“默认模式”和“伪回锁模式”切换。
- 默认模式保留 Elysia 的镜像导入、DSU 槽位管理、切槽与回主系统流程。
- 伪回锁模式的首页只显示辅助脚本、环境检查和启动配置；不会显示默认模式的 DSU 安装、切槽或删除入口。
- 模式切换仅改变管理器首页和入口，不卸载已安装模块，不还原引导镜像，也不改变 Bootloader 的真实锁定状态。

## 内置脚本

`app/src/main/assets/dsu-permissive-v0.7.0.sh` 是用户提供的 DSU-Permissive Android flasher 原文件，未修改地随 APK 发布。其 SHA-256 为：

```text
86b7c4eaaed8d11211c15c71302f0ecc620de1b0647c510299895ed6ec01c04d
```

管理器在使用前校验脚本长度和 SHA-256；再将副本送入 Root 暂存目录，并在执行前再次校验。任何哈希不匹配都会中止。未执行过脚本的开发环境构建或测试不会触碰设备。

应用先做只读环境检查：Root、CPU 架构、内核/KMI、当前 DSU 状态、当前槽位 boot/init_boot 路径、分区大小和可用空间。脚本修补流程会完整备份目标分区、校验备份大小并生成/验证副本。管理器提供两种明确分开的操作：

1. **仅备份并生成补丁**：不写 boot/init_boot。
2. **备份、生成并刷入**：弹出独立确认框，显示目标分区、SELinux/AVB/表选项和写入风险。确认后才传入脚本的 `--flash`。脚本会刷后回读并做 SHA-256 校验；失败信息要求不要重启并保留备份。管理器不会自动重启。

刷写可能造成无法启动。使用者应事先确认设备/分区映射、原始备份和可用的 Recovery/fastboot 恢复方法。不能仅凭 KMI 名称推断模块签名和厂商内核符号兼容。开发过程未执行刷写、切槽或重启；当前没有连接测试设备。

## 静态原理审查

此单文件脚本内嵌 Magisk `magiskboot`、`dsuinit`、七种 KMI 模块和 ramdisk patcher。核心流程是将启动加载器、配置和内核模块放入 boot/init_boot ramdisk；启动时加载模块，再接续原 init 链。模块将其特殊行为限制在 DSU first-stage 窗口，按选项对 PID 1 读取的启动状态和验证视图作临时处理，并在 second-stage/超时后撤销 hooks。dm-verity 表辅助为可选项，默认关闭。该机制不签名 vbmeta，不改变真实 Bootloader 锁定状态，也不保证绕过 Bootloader 对引导镜像本身的验证。

用户报告该脚本在其设备上已成功进入 DSU。此结果是该设备/该软件组合的验证，不等同于所有设备或 ROM 的兼容保证。

对应上游源码与设计：

- [DSU-Permissive 仓库](https://github.com/yangFenTuoZi/DSU-Permissive)
- [设计说明](https://github.com/yangFenTuoZi/DSU-Permissive/blob/853bd9badee38b998c3c0cf2c33336fc44aa1476/docs/design.md)
- [引导镜像布局](https://github.com/yangFenTuoZi/DSU-Permissive/blob/853bd9badee38b998c3c0cf2c33336fc44aa1476/docs/image-layout.md)
- [loader 源码](https://github.com/yangFenTuoZi/DSU-Permissive/blob/853bd9badee38b998c3c0cf2c33336fc44aa1476/loader/dsuinit.c)
- [内核模块源码](https://github.com/yangFenTuoZi/DSU-Permissive/tree/853bd9badee38b998c3c0cf2c33336fc44aa1476/module)
- [测试与验证说明](https://github.com/yangFenTuoZi/DSU-Permissive/blob/853bd9badee38b998c3c0cf2c33336fc44aa1476/docs/testing.md)
- [Magisk 源码](https://github.com/topjohnwu/Magisk)

## 许可

该 bundle 是单独运行的脚本/工具资产，不与 Elysia 的 Kotlin 代码链接。内嵌模块标注 GPL-2.0-only，Magisk 工具遵循其 GPL-3.0 许可；相关许可文本在仓库 `LICENSE-GPL-2.0` 和 `LICENSE-GPL-3.0` 中。第三方源码和组件来源另见 [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)。
