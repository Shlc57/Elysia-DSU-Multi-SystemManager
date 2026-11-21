# 更新日志

## v0.1.0 — 第一版原型

- 初始化 Git 仓库与 Android Gradle Wrapper。
- 新增 KSU 风格中文首页、槽位列表、操作日志和兼容性说明页面。
- 接入 Root `gsi_tool` 状态检测、多命名 DSU 槽解析、单次启动、回主系统和槽位删除。
- 支持 raw `system.img` / `.img.gz` 安装与 userdata 大小配置；加入槽名校验、拒绝覆盖、空间预检、传输校验和失败清理。
- 完整 ROM、多分区镜像、Sparse 转换、非 Root 安装和目标设备兼容性测试留待后续版本。
