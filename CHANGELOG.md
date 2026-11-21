# 更新日志

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
