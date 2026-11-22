# 更新日志

## v0.2.0 — Compose 组件接入测试

- 页面迁移到 Kotlin / Jetpack Compose，直接使用 Miuix 0.9.4 和 Material 3。
- 新增真实 Miuix / Material 风格选择，保留主题色、深浅色、AMOLED 和日志功能。
- 使用 Miuix 导航运行时实现页面转场与返回栈，主导航使用 Compose 淡入淡出。
- 参考 KSU 的彩色状态卡、分组信息与偏好项布局，Material 使用完整种子色配色，主题页提供真实色板。
- Gradle 构建替代纯 Java SDK 直编，保留开发签名、版本递增和 output 忽略规则。
- DSU 后端保持 Java，保留安装、删除与切槽的校验/确认限制。
## v0.1.9 — 首页运行槽位信息

- 移除首页状态卡中重复的产品名称。
- 改为显示当前运行槽位名、应用版本号和设备信息。

## v0.1.8 — 关于页信息

- 补充 GitHub 仓库链接。
- 删除 Telegram 入口，改为 QQ 群 `811309060`，优先调用 QQ 客户端加群链接。

## v0.1.7 — 导航过渡与 DSU 镜像导入

- 移除首页底部兼容性说明文案。
- 增加 KSU 风格的页面淡入淡出与横向切换过渡。
- 增加 ROM 文件夹入口，并明确支持的 DSU 镜像格式与不支持的 OTA/启动镜像。
- 保留 Root、gsi_tool、命名槽位和多分区 DSU 安装安全检查。

## v0.1.0 — 第一版多分区原型

- 初始化 Git 仓库与 Android Gradle Wrapper。
- 新增 KSU 风格中文首页、槽位列表、操作日志和兼容性说明页面。
- 接入 Root `gsi_tool` 状态检测、多命名 DSU 槽解析、单次启动、回主系统和槽位删除。
- 支持 raw/sparse/gzip 分区镜像、独立分区多选、ROM 文件夹、分区 ZIP 和 `super.img` LP 解包。
- 同一个 DSU 槽按固定白名单顺序写入 system、system_ext、product、vendor、odm、vendor_dlkm、system_dlkm、mi_ext。
- 加入分区重复检查、A/B 分区冲突检查、路径安全校验、LP/sparse 校验、空间预检、传输校验、前台服务和失败状态保留。
- OTA `payload.bin`、boot/vbmeta、非 Root 安装和目标设备兼容性测试留待后续版本。
