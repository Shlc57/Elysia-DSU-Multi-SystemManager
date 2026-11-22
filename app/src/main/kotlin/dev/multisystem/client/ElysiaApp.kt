package dev.multisystem.client

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import com.materialkolor.rememberDynamicColorScheme
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import top.yukonga.miuix.kmp.basic.Button as MButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MCardDefaults
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar as MFloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem as MFloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.TopAppBar as MTopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold as MScaffold
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator as MProgress
import top.yukonga.miuix.kmp.basic.Text as MText
import top.yukonga.miuix.kmp.basic.TextField as MTextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColors
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColors
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions

@Serializable
internal sealed interface Page : NavKey {
    @Serializable data object Main : Page
    @Serializable data object Theme : Page
    @Serializable data object About : Page
    @Serializable data object Import : Page
}

private val LocalStyle = staticCompositionLocalOf { UiStyle.Miuix }
private val LocalInk = staticCompositionLocalOf { Color.Black }
private val LocalMuted = staticCompositionLocalOf { Color.Gray }
private val LocalNight = staticCompositionLocalOf { false }
private val LocalPageScroll = staticCompositionLocalOf<NestedScrollConnection?> { null }
private enum class CardTone { Normal, Ready, Warning, Accent }

@Composable
internal fun ElysiaApp(model: ManagerViewModel, onFiles: () -> Unit, onFolder: () -> Unit,
                       onSelectionReady: (() -> Unit) -> Unit, onOpenUrl: (String) -> Unit, onJoinGroup: () -> Unit) {
    val stack = rememberNavBackStack<Page>(Page.Main)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    fun push(page: Page) { if (page !in stack) stack.add(page) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    SideEffect { onSelectionReady { while (stack.size > 1) stack.removeAt(stack.lastIndex); tab = 0 } }
    val page = stack.last()
    val title = when (page) {
        Page.Theme -> "主题设置"
        Page.About -> "关于"
        Page.Import -> "添加镜像"
        else -> if (tab == 0) "Elysia 多系统工具箱" else "设置"
    }
    ElysiaTheme(model) {
        val swipe = if (LocalLayoutDirection.current == LayoutDirection.Rtl) NavSwipeDirection.RightToLeft else NavSwipeDirection.LeftToRight
        AppScaffold(title, page != Page.Main, ::back, if (page == Page.Main) tab else null, { tab = it }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                NavDisplay(backStack = stack, modifier = Modifier.fillMaxSize(), onBack = ::back,
                    transition = NavTransitions.MiuixDefault,
                    effects = NavDisplayEffects(blockInputDuringTransition = true, backdropColor = MaterialTheme.colorScheme.background)) {
                    entry<Page.Main> {
                        BackHandler(enabled = tab != 0) { tab = 0 }
                        AnimatedContent(targetState = tab, transitionSpec = {
                            fadeIn(tween(340)) togetherWith fadeOut(tween(340))
                        }, label = "MainTabs") { selected ->
                            if (selected == 0) HomePage(model) { push(Page.Import) }
                            else SettingsPage(model, { push(Page.Theme) }, { push(Page.About) })
                        }
                    }
                    entry<Page.Theme>(swipeDismiss = swipe) { ThemePage(model) }
                    entry<Page.About>(swipeDismiss = swipe) { AboutPage(onOpenUrl, onJoinGroup) }
                    entry<Page.Import>(swipeDismiss = swipe) { ImportPage(model, onFiles, onFolder) }
                }
                ConfirmationDialog(model)
            }
        }
    }
}

@Composable
private fun ElysiaTheme(model: ManagerViewModel, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val night = model.themeMode == 2 || model.themeMode == 3 || (model.themeMode == 0 && systemDark)
    val amoled = model.themeMode == 3
    val seed = Color(model.accent)
    val primary = if (night) lerp(seed, Color.White, 0.3f) else seed
    val onPrimary = if (primary.luminance() > 0.45f) Color.Black else Color.White
    val context = LocalContext.current
    SideEffect {
        (context as? android.app.Activity)?.window?.let { window ->
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !night
                isAppearanceLightNavigationBars = !night
            }
        }
    }
    val materialColors = rememberDynamicColorScheme(seedColor = seed, isDark = night, isAmoled = amoled,
        style = PaletteStyle.Vibrant, specVersion = ColorSpec.SpecVersion.SPEC_2025)
    val miuix = if (night) miuixDarkColors(primary = primary, onPrimary = onPrimary,
        primaryVariant = primary, disabledPrimaryButton = lerp(primary, Color.Black, 0.65f),
        surface = if (amoled) Color.Black else Color(0xff121212),
        surfaceContainer = if (amoled) Color(0xff101010) else Color(0xff242424),
        tertiaryContainer = materialColors.tertiaryContainer, onTertiaryContainer = materialColors.onTertiaryContainer,
        primaryContainer = materialColors.primaryContainer, onPrimaryContainer = materialColors.onPrimaryContainer)
    else miuixLightColors(primary = primary, onPrimary = onPrimary,
        primaryVariant = primary, disabledPrimaryButton = lerp(primary, Color.White, 0.7f),
        primaryContainer = materialColors.primaryContainer, onPrimaryContainer = materialColors.onPrimaryContainer,
        tertiaryContainer = materialColors.tertiaryContainer, onTertiaryContainer = materialColors.onTertiaryContainer)
    CompositionLocalProvider(LocalStyle provides model.style,
        LocalNight provides night,
        LocalInk provides if (night) Color(0xffeeeeee) else Color(0xff202020),
        LocalMuted provides if (night) Color(0xffb0b0b0) else Color(0xff6a6a6a)) {
        MaterialExpressiveTheme(colorScheme = materialColors) { MiuixTheme(colors = miuix) { content() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(title: String, showBack: Boolean, onBack: () -> Unit, tab: Int?, onTab: (Int) -> Unit,
                        content: @Composable (PaddingValues) -> Unit) {
    val miuixScroll = MiuixScrollBehavior()
    val materialScroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    LaunchedEffect(title) {
        miuixScroll.state.heightOffset = 0f
        miuixScroll.state.contentOffset = 0f
        materialScroll.state.heightOffset = 0f
        materialScroll.state.contentOffset = 0f
    }
    val heading: @Composable () -> Unit = {
        val back: @Composable () -> Unit = {
            if (showBack) IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回" }) { AppText("←", 28) }
        }
        if (LocalStyle.current == UiStyle.Miuix) MTopAppBar(title = title, navigationIcon = back, scrollBehavior = miuixScroll)
        else LargeFlexibleTopAppBar(title = { Text(title) }, navigationIcon = back, scrollBehavior = materialScroll,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer))
    }
    val navigation: @Composable () -> Unit = {
        if (tab != null) {
            val home = ImageVector.vectorResource(R.drawable.ic_nav_home)
            val settings = ImageVector.vectorResource(R.drawable.ic_nav_settings)
            val entries = listOf("首页" to home, "设置" to settings)
            if (LocalStyle.current == UiStyle.Miuix) {
                MFloatingNavigationBar { entries.forEachIndexed { i, (label, icon) ->
                    Column(Modifier.clickable { onTab(i) }.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        MFloatingNavigationBarItem(selected = tab == i, onClick = { onTab(i) }, icon = icon, label = label)
                        MText(label, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
                    }
                } }
            } else {
                NavigationBar { entries.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { onTab(i) },
                        icon = { Icon(icon, null) }, label = { Text(label) })
                } }
            }
        }
    }
    CompositionLocalProvider(LocalPageScroll provides if (LocalStyle.current == UiStyle.Miuix)
        miuixScroll.nestedScrollConnection else materialScroll.nestedScrollConnection) {
        if (LocalStyle.current == UiStyle.Miuix) MScaffold(topBar = heading, bottomBar = navigation, content = content)
        else Scaffold(topBar = heading, bottomBar = navigation, containerColor = MaterialTheme.colorScheme.surface, content = content)
    }
}

@Composable
private fun AppText(value: String, size: Int = 14, muted: Boolean = false, bold: Boolean = false,
                    modifier: Modifier = Modifier, color: Color = if (muted) LocalMuted.current else LocalInk.current) {
    if (LocalStyle.current == UiStyle.Miuix) MText(value, modifier = modifier, fontSize = size.sp,
        color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    else Text(value, modifier = modifier, fontSize = size.sp, color = color,
        style = when { size <= 13 -> MaterialTheme.typography.bodySmall; size <= 16 -> MaterialTheme.typography.bodyMedium
            else -> MaterialTheme.typography.titleMedium },
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
}

@Composable
private fun AppCard(tone: CardTone = CardTone.Normal, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    val material = MaterialTheme.colorScheme
    val miuix = MiuixTheme.colorScheme
    val container = if (LocalStyle.current == UiStyle.Miuix) when (tone) {
        CardTone.Normal -> miuix.surfaceContainer
        CardTone.Ready -> if (LocalNight.current) Color(0xff193d2b) else Color(0xffdff7e7)
        CardTone.Warning -> miuix.errorContainer
        CardTone.Accent -> miuix.primaryContainer
    } else when (tone) {
        CardTone.Normal -> material.surfaceContainerLow
        CardTone.Ready -> material.secondaryContainer
        CardTone.Warning -> material.errorContainer
        CardTone.Accent -> material.primaryContainer
    }
    val ink = if (LocalStyle.current == UiStyle.Miuix) when (tone) {
        CardTone.Ready -> if (LocalNight.current) Color(0xffb8e9c7) else Color(0xff18482b)
        CardTone.Warning -> miuix.onErrorContainer
        CardTone.Accent -> miuix.onPrimaryContainer
        else -> miuix.onSurfaceContainer
    } else material.contentColorFor(container)
    CompositionLocalProvider(LocalInk provides ink, LocalMuted provides ink.copy(alpha = 0.7f)) {
        if (LocalStyle.current == UiStyle.Miuix) MCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(padding.dp),
            colors = MCardDefaults.defaultColors(color = container, contentColor = ink)) { content() }
        else Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container, contentColor = ink)) {
            Column(Modifier.padding(padding.dp), content = content)
        }
    }
}

@Composable
private fun AppButton(label: String, onClick: () -> Unit, enabled: Boolean = true,
                      primary: Boolean = true, fullWidth: Boolean = true, modifier: Modifier = Modifier,
                      swatch: Color? = null) {
    val width = if (fullWidth) Modifier.fillMaxWidth() else Modifier
    val foreground = if (swatch != null && swatch.luminance() > 0.45f) Color.Black else Color.White
    if (LocalStyle.current == UiStyle.Miuix) {
        MButton(onClick = onClick, modifier = modifier.then(width), enabled = enabled,
            colors = if (swatch != null) MButtonDefaults.buttonColors(color = swatch, contentColor = foreground)
            else if (primary) MButtonDefaults.buttonColorsPrimary() else MButtonDefaults.buttonColors()) {
            MText(label)
        }
    } else if (swatch != null) Button(onClick, modifier.then(width), enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = swatch, contentColor = foreground)) { Text(label) }
    else if (primary) Button(onClick, modifier.then(width), enabled = enabled) { Text(label) }
    else OutlinedButton(onClick, modifier.then(width), enabled = enabled) { Text(label) }
}

@Composable
private fun AppField(value: String, label: String, onValueChange: (String) -> Unit, number: Boolean = false) {
    val keyboard = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Ascii, autoCorrectEnabled = false)
    if (LocalStyle.current == UiStyle.Miuix) MTextField(value = value, onValueChange = onValueChange,
        label = label, singleLine = true, keyboardOptions = keyboard, modifier = Modifier.fillMaxWidth())
    else OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = keyboard, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    val scroll = LocalPageScroll.current
    Column(Modifier.fillMaxSize().background(if (LocalStyle.current == UiStyle.Miuix) MiuixTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surface).then(if (scroll != null) Modifier.nestedScroll(scroll) else Modifier)
        .verticalScroll(rememberScrollState()).imePadding()
        .padding(if (LocalStyle.current == UiStyle.Miuix) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (LocalStyle.current == UiStyle.Miuix) 12.dp else 16.dp), content = content)
}

@Composable
private fun HomePage(model: ManagerViewModel, onImport: () -> Unit) {
    val status = model.snapshot
    ScreenColumn {
        AppCard(when { status == null -> CardTone.Normal; status.ready() -> CardTone.Ready
            else -> CardTone.Warning }, padding = 20) {
            Box(Modifier.fillMaxWidth().heightIn(min = 132.dp)) {
                Icon(ImageVector.vectorResource(R.drawable.ic_status_ready), null,
                    modifier = Modifier.size(110.dp).align(Alignment.CenterEnd), tint = LocalInk.current.copy(alpha = 0.12f))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppText(model.currentSlot, 27, bold = true)
                    AppText("v${BuildConfig.VERSION_NAME}", 15, muted = true)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(if (status?.rootGranted == true) "Root 已授权" else "Root 未授权")
                        StatusChip(if (status?.gsiToolAvailable == true) "DSU 就绪" else "DSU 未就绪")
                    }
                }
            }
        }
        if (model.error.isNotBlank()) AppCard(CardTone.Warning) { AppText(model.error, 13) }
        if (status?.rootGranted != true) AppButton("请求 Root 授权", model::requestRoot, !model.busy)
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText("设备信息", 17, bold = true, modifier = Modifier.weight(1f))
                AppButton(if (model.loading) "检测中…" else "刷新", model::refresh, !model.busy, false, false)
            }
            InfoRow("设备", model.deviceLabel, R.drawable.ic_nav_home)
            InfoRow("gsi_tool", status?.gsiToolPath?.ifEmpty { "未检测到" } ?: "检测中", R.drawable.ic_nav_settings)
            InfoRow("可用空间", status?.freeBytes?.takeIf { it > 0 }?.let(::formatBytes) ?: "未知", R.drawable.ic_nav_log)
        }
        AppCard(CardTone.Accent) {
            AppText("镜像安装", 17, bold = true)
            AppText("创建新的命名槽位与独立 userdata", 12, muted = true)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton("添加镜像", onImport, model.canAdd, modifier = Modifier.weight(1f), fullWidth = false)
                AppButton("安装系统", model::confirmInstall, model.canInstall, modifier = Modifier.weight(1f), fullWidth = false)
            }
        }
        if (status?.runningDsu == true) {
            AppText("当前运行 DSU；安装新槽需先回主系统。", 12, muted = true)
            AppButton("回主系统", model::confirmReturn, !model.busy, false)
        }
        if (model.pendingNames.isNotEmpty()) AppCard {
            AppText("待安装镜像", 17, bold = true)
            model.pendingNames.forEach { AppText(it, 13, muted = true) }
            Spacer(Modifier.height(8.dp)); AppButton("清除镜像", model::clearSelection, !model.busy, false)
        }
        if (model.installMessage.isNotEmpty()) {
            AppText(model.installMessage, 13)
            if (LocalStyle.current == UiStyle.Miuix) MProgress(progress = model.installProgress / 100f)
            else LinearProgressIndicator(progress = { model.installProgress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText("已安装系统", 19, bold = true, modifier = Modifier.weight(1f))
            AppText("${status?.slots?.size ?: 0} 个槽位", 12, muted = true)
        }
        if (status?.slots.isNullOrEmpty()) AppText(if (model.loading) "正在检测设备…" else "暂无已安装系统或尚未授权", muted = true)
        status?.slots?.forEach { slot -> AppCard(if (slot.active) CardTone.Ready else CardTone.Normal) {
            AppText(slot.name, 19, bold = true)
            AppText("DSU · ${slot.sizeLabel()}", 12, muted = true)
            if (slot.partitions.isNotEmpty()) AppText("分区：${slot.partitions}", 12, muted = true)
            val state = when {
                slot.state == "INSTALL_INCOMPLETE" -> "安装未完成"
                slot.active -> "正在运行"
                slot.selected -> "下次启动目标"
                slot.bootable -> "可启动"
                slot.state == "DETAILS_UNAVAILABLE" -> "详情暂不可读"
                else -> "完整性未核验"
            }
            AppText("$state · ${if (slot.dataIsolated) "独立 userdata" else "userdata 未核验"}", 12, muted = true)
            if (!slot.active) {
                Spacer(Modifier.height(10.dp))
                AppButton(if (status.runningDsu) "切换到此系统并重启" else "启动此系统并重启",
                    { model.confirmSwitch(slot) }, model.canSwitch(slot))
                if (!status.runningDsu) AppButton("删除该槽位", { model.confirmDelete(slot) }, !model.busy, false)
            }
        } }
    }
}

@Composable
private fun StatusChip(label: String) {
    val container = LocalInk.current.copy(alpha = 0.08f)
    if (LocalStyle.current == UiStyle.Miuix) MCard(insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        cornerRadius = 6.dp, colors = MCardDefaults.defaultColors(container, LocalInk.current)) { AppText(label, 11, bold = true) }
    else Surface(color = container, shape = MaterialTheme.shapes.small) { AppText(label, 11, bold = true,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
}

@Composable
private fun InfoRow(label: String, value: String, icon: Int) {
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ImageVector.vectorResource(icon), null, Modifier.size(23.dp), tint = if (LocalStyle.current == UiStyle.Miuix)
            MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column {
            AppText(label, 14, bold = true)
            AppText(value, 13, muted = true)
        }
    }
}

@Composable
private fun SettingsPage(model: ManagerViewModel, onTheme: () -> Unit, onAbout: () -> Unit) {
    ScreenColumn {
        AppText("外观", 13, muted = true, modifier = Modifier.padding(start = 8.dp))
        AppCard(padding = 0) {
            if (LocalStyle.current == UiStyle.Miuix) OverlayDropdownPreference(title = "界面风格",
                summary = "当前：Miuix", items = UiStyle.entries.map { it.name }, selectedIndex = model.style.ordinal,
                onSelectedIndexChange = { model.updateStyle(UiStyle.entries[it]) })
            else {
                Column(Modifier.padding(16.dp)) {
                    Text("界面风格", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        UiStyle.entries.forEachIndexed { index, style ->
                            SegmentedButton(selected = model.style == style, onClick = { model.updateStyle(style) },
                                shape = SegmentedButtonDefaults.itemShape(index, UiStyle.entries.size)) { Text(style.name) }
                        }
                    }
                }
            }
            PreferenceItem("主题设置", "颜色模式与自定义主题色", onTheme, R.drawable.ic_nav_settings)
        }
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppText("安装日志", 17, bold = true, modifier = Modifier.weight(1f))
                AppButton("清空", model::confirmClearLogs, !model.busy, false, false)
                AppButton("导出", model::exportLogs, !model.busy, false, false)
            }
            LazyColumn(Modifier.fillMaxWidth().height(206.dp)) {
                if (model.logs.isEmpty()) item { AppText("暂无操作记录", 13, muted = true) }
                items(model.logs) { line -> SelectionContainer { AppText(line, 13, modifier = Modifier.padding(vertical = 8.dp)) } }
            }
        }
        AppCard(padding = 0) {
            PreferenceItem("关于 Elysia", "v${BuildConfig.VERSION_NAME}", onAbout, R.drawable.ic_nav_home)
            PreferenceItem("安全策略", "镜像写入与槽位保护", model::showSafety, R.drawable.ic_nav_log)
        }
        if (model.error.isNotBlank()) AppText(model.error, 13, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun PreferenceRow(title: String, summary: String, onClick: () -> Unit) {
    AppCard(padding = 0) { PreferenceItem(title, summary, onClick) }
}

@Composable
private fun PreferenceItem(title: String, summary: String, onClick: () -> Unit, icon: Int = R.drawable.ic_nav_settings) {
    val leading: @Composable () -> Unit = {
        Icon(ImageVector.vectorResource(icon), null, Modifier.size(24.dp), tint = if (LocalStyle.current == UiStyle.Miuix)
            MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary)
    }
    if (LocalStyle.current == UiStyle.Miuix) ArrowPreference(title = title, summary = summary,
        startAction = leading, onClick = onClick)
    else ListItem(supportingContent = { Text(summary) }, leadingContent = leading,
        trailingContent = { Text("›") }, modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)) { Text(title) }
}

private val accents = listOf(0xfff44336, 0xffe91e63, 0xff9c27b0, 0xff3f51b5, 0xff2196f3,
    0xff00a6c7, 0xff009688, 0xff4caf50, 0xffff9800, 0xffffc107, 0xff795548, 0xff607d8b)
private val accentNames = listOf("红", "粉", "紫", "靛蓝", "蓝", "青", "青绿", "绿", "橙", "琥珀", "棕", "蓝灰")

@Composable
private fun ThemePage(model: ManagerViewModel) {
    var hex by rememberSaveable(model.accent) { mutableStateOf("#%06X".format(model.accent and 0xffffff)) }
    var invalid by remember { mutableStateOf(false) }
    ScreenColumn {
        AppCard {
            AppText("颜色模式", 17, bold = true)
            if (LocalStyle.current == UiStyle.Miuix) listOf("跟随系统", "浅色", "深色", "AMOLED").forEachIndexed { i, name ->
                RadioButtonPreference(title = name, selected = model.themeMode == i, onClick = { model.setTheme(i) })
            } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("跟随系统", "浅色", "深色", "AMOLED").forEachIndexed { i, name ->
                    FilterChip(selected = model.themeMode == i, onClick = { model.setTheme(i) }, label = { Text(name) })
                }
            }
        }
        AppCard {
            AppText("主题色", 17, bold = true)
            accents.chunked(4).forEachIndexed { row, colors ->
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    colors.forEachIndexed { col, value ->
                        val i = row * 4 + col
                        val color = Color(value)
                        val selected = model.accent == value.toInt()
                        val choose = { model.updateAccent(value.toInt()) }
                        Column(Modifier.weight(1f).clickable(onClick = choose).padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            val foreground = if (color.luminance() > 0.45f) Color.Black else Color.White
                            if (LocalStyle.current == UiStyle.Miuix) MButton(onClick = choose, modifier = Modifier.size(40.dp),
                                minWidth = 40.dp, minHeight = 40.dp, cornerRadius = 20.dp, insideMargin = PaddingValues(0.dp),
                                colors = MButtonDefaults.buttonColors(color = color, contentColor = foreground)) {
                                if (selected) MText("✓", color = foreground)
                            }
                            else FilledIconButton(onClick = choose, modifier = Modifier.size(40.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = foreground)) {
                                if (selected) Text("✓", color = foreground)
                            }
                            AppText(accentNames[i], 11, muted = true, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            AppField(hex, "自定义 HEX：#RRGGBB", { hex = it; invalid = false })
            if (invalid) AppText("请输入 #RRGGBB 或 #AARRGGBB", 12, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            AppButton("应用主题色", {
                if (hex.matches(Regex("#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})"))) {
                    model.updateAccent(android.graphics.Color.parseColor(hex)); invalid = false
                } else invalid = true
            })
        }
    }
}

@Composable
private fun ImportPage(model: ManagerViewModel, onFiles: () -> Unit, onFolder: () -> Unit) {
    ScreenColumn {
        AppCard {
            AppField(model.slotInput, "DSU 槽位命名", model::setSlot)
            AppText(model.slotError.ifEmpty { "仅允许英文字母、数字和下划线，最多 48 位" }, 12,
                muted = model.slotError.isEmpty(), color = if (model.slotError.isEmpty()) LocalMuted.current else MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            AppField(model.userdataInput, "DSU 用户空间（GiB）", model::setUserdata, number = true)
        }
        AppButton("选择镜像或 ZIP", onFiles, !model.busy && model.snapshot?.runningDsu != true)
        AppButton("选择 ROM 文件夹", onFolder, !model.busy && model.snapshot?.runningDsu != true, false)
        AppText("支持 system.img、分区 ZIP/文件夹、super.img（含 sparse、.gz）。不支持 payload.bin、boot/vbmeta 或整包 OTA。", 12, muted = true)
        if (model.error.isNotBlank()) AppText(model.error, 13, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun AboutPage(onOpenUrl: (String) -> Unit, onJoinGroup: () -> Unit) {
    ScreenColumn {
        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.elysia_icon), "Elysia 图标", Modifier.size(108.dp))
            Spacer(Modifier.height(24.dp)); AppText("Elysia 多系统工具箱", 25, bold = true)
            AppText("v${BuildConfig.VERSION_NAME}", 16, muted = true)
            AppText("Kotlin + Compose · Miuix / Material 3", 12, muted = true)
        }
        PreferenceRow("在 GitHub 查看源码", "Shlc57/Elysia-DSU-Multi-SystemManager") {
            onOpenUrl("https://github.com/Shlc57/Elysia-DSU-Multi-SystemManager")
        }
        PreferenceRow("加入 QQ 群", "811309060", onJoinGroup)
    }
}

@Composable
private fun ConfirmationDialog(model: ManagerViewModel) {
    val request = model.confirmation
    val dismiss = { model.confirmation = null }
    val label = when (request?.action) { "switch" -> "切换并重启"; "return" -> "回主系统"; "delete" -> "删除"; "install" -> "安装"; "info" -> "知道了"; else -> "确认" }
    if (LocalStyle.current == UiStyle.Miuix) {
        OverlayDialog(show = request != null, title = request?.title, summary = request?.message, onDismissRequest = dismiss) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("取消", dismiss, primary = false, fullWidth = false, modifier = Modifier.weight(1f))
                AppButton(label, model::executeConfirmation, fullWidth = false, modifier = Modifier.weight(1f))
            }
        }
    } else if (request != null) AlertDialog(onDismissRequest = dismiss,
        title = { Text(request.title) }, text = { Text(request.message) },
        confirmButton = { TextButton(model::executeConfirmation) { Text(label) } },
        dismissButton = { TextButton(dismiss) { Text("取消") } })
}
