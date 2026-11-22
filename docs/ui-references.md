# UI 源码参考

此前提到的图片在当前工作区中没有可读取的原文件，因此不能据此保证穷尽图片里的每一行。本表整理了此前可辨认/已讨论的项目并核对官方仓库；如果图片还列有其他项目，请重新上传原图，我会继续逐项核对。

| 项目 | 官方源码 | 本应用中的情况 |
| --- | --- | --- |
| Jetpack Compose / AndroidX | https://github.com/androidx/androidx | UI 框架依赖 |
| Material 3 | https://github.com/androidx/androidx | MaterialKolor 的配色桥接依赖；不提供 Material 风格界面 |
| Miuix | https://github.com/compose-miuix-ui/miuix | 实际 UI 组件库 |
| Capsule | https://github.com/Kyant0/Capsule | 实际依赖 2.1.3；连续圆角 |
| AndroidLiquidGlass / Backdrop | https://github.com/Kyant0/AndroidLiquidGlass | 实际依赖 1.0.6；玻璃顶栏、底栏的 blur、vibrancy 和 lens 折射 |
| Kotlin Coroutines | https://github.com/Kotlin/kotlinx.coroutines | 实际依赖；异步任务 |
| Kotlin Serialization | https://github.com/Kotlin/kotlinx.serialization | 实际依赖；导航状态序列化 |
| MaterialKolor | https://github.com/jordond/MaterialKolor | 实际依赖；动态主题色 |
| Material Icons / Symbols | https://github.com/google/material-design-icons | 源码参考；当前图标由项目自带 VectorDrawable 绘制 |
| OkHttp | https://github.com/square/okhttp | 源码已核对，但此应用未直接依赖网络请求库 |
| KernelSU Manager | https://github.com/KOWX712/KernelSU | 仅参考首页信息层级，未复制 GPL 页面源码 |
| DSUManager | https://github.com/XZY-01/DSUManager | `SuperImage.java` 使用的镜像解析源码，MIT，见第三方声明 |

Backdrop 没有提供整套高层页面。本应用保留 Miuix 组件，以 Backdrop 的图层采样、模糊、vibrancy 和 Android 13+ lens API 绘制液态玻璃效果；Android 12 及以下降级到模糊与半透明高光。采样来自应用自身 Compose 图层，不申请截图权限。
