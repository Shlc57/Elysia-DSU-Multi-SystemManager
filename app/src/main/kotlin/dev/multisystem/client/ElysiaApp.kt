package dev.multisystem.client

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import android.os.Build
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.serialization.Serializable
import com.materialkolor.rememberDynamicColorScheme
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.basic.Button as MButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon as MIcon
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MDivider
import top.yukonga.miuix.kmp.basic.Card as MCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MCardDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator as MProgress
import top.yukonga.miuix.kmp.basic.Text as MText
import top.yukonga.miuix.kmp.basic.TextField as MTextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
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
    @Serializable data object Sources : Page
    @Serializable data object CompatibilityGuide : Page
}

private val LocalInk = staticCompositionLocalOf { Color.Black }
private val LocalMuted = staticCompositionLocalOf { Color.Gray }
private val LocalNight = staticCompositionLocalOf { false }
private val LocalBottomBarVisible = staticCompositionLocalOf { false }
private val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }
private enum class CardTone { Normal, Ready, Warning }

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
        Page.CompatibilityGuide -> "使用说明"
        Page.Theme -> "主题设置"
        Page.About -> "关于"
        Page.Import -> "添加镜像"
        Page.Sources -> "开源项目"
        else -> "Elysia 多系统工具箱"
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
                        AnimatedContent(targetState = tab to model.mode, transitionSpec = {
                            fadeIn(tween(340)) togetherWith fadeOut(tween(340))
                        }, label = "MainTabs") { selected ->
                            if (selected.first == 0 && selected.second == ManagerMode.Compatibility) {
                                CompatibilityPage(model, onOpenUrl) { push(Page.CompatibilityGuide) }
                            }
                            else if (selected.first == 0) HomePage(model) { push(Page.Import) }
                            else SettingsPage(model, { push(Page.Theme) }, { push(Page.About) })
                        }
                    }
                    entry<Page.Theme>(swipeDismiss = swipe) { ThemePage(model) }
                    entry<Page.About>(swipeDismiss = swipe) { AboutPage(onOpenUrl, onJoinGroup) { push(Page.Sources) } }
                    entry<Page.Import>(swipeDismiss = swipe) { ImportPage(model, onFiles, onFolder) }
                    entry<Page.Sources>(swipeDismiss = swipe) { SourcePage(onOpenUrl) }
                    entry<Page.CompatibilityGuide>(swipeDismiss = swipe) {
                        CompatibilityGuidePage(onOpenUrl)
                    }
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
    CompositionLocalProvider(LocalNight provides night,
        LocalInk provides if (night) Color(0xffeeeeee) else Color(0xff202020),
        LocalMuted provides if (night) Color(0xffb0b0b0) else Color(0xff6a6a6a)) {
        MaterialExpressiveTheme(colorScheme = materialColors) { MiuixTheme(colors = miuix) { content() } }
    }
}

@Composable
private fun AppScaffold(title: String, showBack: Boolean, onBack: () -> Unit, tab: Int?, onTab: (Int) -> Unit,
                        content: @Composable (PaddingValues) -> Unit) {
    val surface = MiuixTheme.colorScheme.surface
    val primary = MiuixTheme.colorScheme.primary
    val tertiary = MiuixTheme.colorScheme.tertiaryContainer
    val night = LocalNight.current
    val backdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    val pagePadding = PaddingValues()
    CompositionLocalProvider(LocalBottomBarVisible provides (tab != null), LocalBackdrop provides backdrop) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(surface)
                val radius = size.minDimension * 0.44f
                drawCircle(brush = Brush.radialGradient(
                    colors = listOf(primary.copy(alpha = if (night) 0.32f else 0.24f),
                        primary.copy(alpha = if (night) 0.11f else 0.08f), Color.Transparent),
                    center = Offset(size.width * 0.94f, size.height * 0.10f), radius = radius),
                    radius = radius, center = Offset(size.width * 0.94f, size.height * 0.10f))
                drawCircle(brush = Brush.radialGradient(
                    colors = listOf(tertiary.copy(alpha = if (night) 0.34f else 0.26f),
                        tertiary.copy(alpha = if (night) 0.12f else 0.09f), Color.Transparent),
                    center = Offset(size.width * 0.02f, size.height * 0.78f), radius = radius * 0.86f),
                    radius = radius * 0.86f, center = Offset(size.width * 0.02f, size.height * 0.78f))
            }
            content(pagePadding)
        }
        GlassHeader(backdrop, title, showBack, onBack, Modifier.align(Alignment.TopCenter))
        if (tab != null) GlassNavigationBar(backdrop, tab, onTab, Modifier.align(Alignment.BottomCenter))
    }
    }
}

@Composable
private fun Modifier.glassSurface(backdrop: Backdrop, shape: Shape, tint: Color): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        blur(22f.dp.toPx())
        if (Build.VERSION.SDK_INT >= 33) lens(14f.dp.toPx(), 28f.dp.toPx())
    },
    onDrawSurface = {
        drawRect(tint)
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f),
            Color.White.copy(alpha = 0.04f), tint.copy(alpha = 0.16f))))
        drawRect(Color.White.copy(alpha = 0.56f), style = Stroke(width = 1.dp.toPx()))
        drawLine(Color.White.copy(alpha = 0.40f), Offset(24.dp.toPx(), 1.dp.toPx()),
            Offset(size.width - 24.dp.toPx(), 1.dp.toPx()), strokeWidth = 1.dp.toPx())
    }
)

@Composable
private fun GlassHeader(backdrop: Backdrop, title: String, showBack: Boolean, onBack: () -> Unit,
                        modifier: Modifier = Modifier) {
    val surface = MiuixTheme.colorScheme.surface
    val night = LocalNight.current
    Row(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp)
        .height(54.dp).glassSurface(backdrop, ContinuousRoundedRectangle(24.dp),
            surface.copy(alpha = if (night) 0.48f else 0.38f))
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showBack) {
            MButton(onClick = onBack, modifier = Modifier.size(40.dp).semantics { contentDescription = "返回" },
                minWidth = 40.dp, minHeight = 40.dp, cornerRadius = 20.dp, insideMargin = PaddingValues(0.dp),
                colors = MButtonDefaults.buttonColors(color = surface.copy(alpha = 0.28f),
                    contentColor = MiuixTheme.colorScheme.onSurface)) { MText("←", fontSize = 24.sp) }
            Spacer(Modifier.width(8.dp))
        }
        AppText(title, 18, bold = true)
    }
}

@Composable
private fun GlassNavigationBar(backdrop: Backdrop, tab: Int, onTab: (Int) -> Unit,
                               modifier: Modifier = Modifier) {
    val night = LocalNight.current
    Row(modifier.navigationBarsPadding().padding(bottom = 8.dp).widthIn(max = 232.dp).fillMaxWidth()
        .height(52.dp).glassSurface(backdrop, ContinuousCapsule,
            MiuixTheme.colorScheme.surface.copy(alpha = if (night) 0.50f else 0.40f))
        .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("首页" to R.drawable.ic_nav_home, "设置" to R.drawable.ic_nav_settings)
            .forEachIndexed { index, (label, drawable) ->
                val selected = tab == index
                val tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceContainer
                Column(Modifier.weight(1f).fillMaxHeight().clip(ContinuousCapsule)
                    .background(if (selected) MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.66f) else Color.Transparent)
                    .clickable { onTab(index) }, verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    MIcon(imageVector = ImageVector.vectorResource(drawable), contentDescription = label,
                        modifier = Modifier.size(19.dp), tint = tint)
                    MText(label, fontSize = 10.sp, color = tint)
                }
            }
    }
}

@Composable
private fun AppText(value: String, size: Int = 14, muted: Boolean = false, bold: Boolean = false,
                    modifier: Modifier = Modifier, color: Color = if (muted) LocalMuted.current else LocalInk.current) {
    MText(value, modifier = modifier, fontSize = size.sp,
        color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
}

@Composable
private fun AppCard(tone: CardTone = CardTone.Normal, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    val miuix = MiuixTheme.colorScheme
    val night = LocalNight.current
    val backdrop = LocalBackdrop.current
    val shape = ContinuousRoundedRectangle(20.dp)
    val container = when (tone) {
        CardTone.Normal -> miuix.surfaceContainer
        CardTone.Ready -> if (LocalNight.current) Color(0xff193d2b) else Color(0xffdff7e7)
        CardTone.Warning -> miuix.errorContainer
    }
    val ink = when (tone) {
        CardTone.Ready -> if (LocalNight.current) Color(0xffb8e9c7) else Color(0xff18482b)
        CardTone.Warning -> miuix.onErrorContainer
        else -> miuix.onSurfaceContainer
    }
    CompositionLocalProvider(LocalInk provides ink, LocalMuted provides ink.copy(alpha = 0.7f)) {
        val tint = when (tone) {
            CardTone.Normal -> miuix.surfaceContainer.copy(alpha = if (night) 0.48f else 0.38f)
            CardTone.Ready -> (if (night) Color(0xff193d2b) else Color(0xffdff7e7)).copy(alpha = 0.74f)
            CardTone.Warning -> miuix.errorContainer.copy(alpha = 0.76f)
        }
        val glass = if (backdrop != null) Modifier.glassSurface(backdrop, shape, tint) else Modifier
        MCard(Modifier.fillMaxWidth().then(glass)
            .shadow(if (tone == CardTone.Normal) 1.dp else 0.dp, shape).clip(shape),
            cornerRadius = 20.dp, insideMargin = PaddingValues(padding.dp),
            colors = MCardDefaults.defaultColors(color = if (backdrop != null) Color.Transparent else container,
                contentColor = ink)) { content() }
    }
}

@Composable
private fun AppButton(label: String, onClick: () -> Unit, enabled: Boolean = true,
                      primary: Boolean = true, fullWidth: Boolean = true, modifier: Modifier = Modifier) {
    val width = if (fullWidth) Modifier.fillMaxWidth() else Modifier
    MButton(onClick = onClick, modifier = modifier.then(width), enabled = enabled,
            colors = if (primary) MButtonDefaults.buttonColorsPrimary() else MButtonDefaults.buttonColors()) {
            MText(label)
        }
}

@Composable
private fun AppField(value: String, label: String, onValueChange: (String) -> Unit, number: Boolean = false) {
    val keyboard = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Ascii, autoCorrectEnabled = false)
    MTextField(value = value, onValueChange = onValueChange,
        label = label, singleLine = true, keyboardOptions = keyboard, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 66.dp
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        if (LocalBottomBarVisible.current) 68.dp else 20.dp
    Column(Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState()).imePadding()
        .padding(start = 12.dp, top = topInset, end = 12.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun HomePage(model: ManagerViewModel, onImport: () -> Unit) {
    val status = model.snapshot
    ScreenColumn {
        HomeModeCard(model)
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
        AppCard {
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
            MProgress(progress = model.installProgress / 100f)
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
private fun HomeModeCard(model: ManagerViewModel) {
    val status = model.snapshot
    val tone = when { status == null -> CardTone.Normal; status.ready() -> CardTone.Ready; else -> CardTone.Warning }
    AppCard(tone, padding = 12) {
        Row(Modifier.fillMaxWidth().heightIn(min = 112.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.08f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText("当前系统", 12, muted = true)
                AppText(model.currentSlot, 21, bold = true)
                AppText("${model.deviceLabel} · v${BuildConfig.VERSION_NAME}", 11, muted = true)
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusChip(if (status?.rootGranted == true) "Root 已授权" else "Root 未授权")
                    StatusChip(if (status?.gsiToolAvailable == true) "DSU 就绪" else "DSU 未就绪")
                }
            }
            Box(Modifier.width(1.dp).height(88.dp).background(LocalInk.current.copy(alpha = 0.16f)))
            Column(Modifier.weight(0.92f).padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AppText("模式", 12, muted = true)
                ModeButton(model, ManagerMode.Standard)
                ModeButton(model, ManagerMode.Compatibility)
            }
        }
    }
}

@Composable
private fun ModeButton(model: ManagerViewModel, mode: ManagerMode) {
    val selected = model.mode == mode
    val scheme = MiuixTheme.colorScheme
    MButton(onClick = { model.updateMode(mode) }, modifier = Modifier.fillMaxWidth().height(34.dp),
        enabled = !model.busy, minWidth = 0.dp, minHeight = 34.dp, cornerRadius = 17.dp,
        insideMargin = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        colors = if (selected) MButtonDefaults.buttonColors(color = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer) else MButtonDefaults.buttonColors(
            color = Color.Transparent, contentColor = scheme.onSurfaceContainer)) {
        AppText(mode.title, 11, bold = selected,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceContainer)
    }
}

@Composable
private fun StatusChip(label: String) {
    val container = LocalInk.current.copy(alpha = 0.08f)
    MCard(insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        cornerRadius = 6.dp, colors = MCardDefaults.defaultColors(container, LocalInk.current)) { AppText(label, 11, bold = true) }
}

@Composable
private fun InfoRow(label: String, value: String, icon: Int) {
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        MIcon(imageVector = ImageVector.vectorResource(icon), contentDescription = null, modifier = Modifier.size(23.dp), tint = MiuixTheme.colorScheme.primary)
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
        MIcon(imageVector = ImageVector.vectorResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary)
    }
    ArrowPreference(title = title, summary = summary.takeIf { it.isNotEmpty() },
        startAction = leading, onClick = onClick)
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
            listOf("跟随系统", "浅色", "深色", "AMOLED").forEachIndexed { i, name ->
                RadioButtonPreference(title = name, selected = model.themeMode == i, onClick = { model.setTheme(i) })
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
                            MButton(onClick = choose, modifier = Modifier.size(40.dp),
                                minWidth = 40.dp, minHeight = 40.dp, cornerRadius = 20.dp, insideMargin = PaddingValues(0.dp),
                                colors = MButtonDefaults.buttonColors(color = color, contentColor = foreground)) {
                                if (selected) MText("✓", color = foreground)
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
private fun AboutPage(onOpenUrl: (String) -> Unit, onJoinGroup: () -> Unit, onSources: () -> Unit) {
    ScreenColumn {
        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.elysia_icon), "Elysia 图标", Modifier.size(108.dp))
            Spacer(Modifier.height(24.dp)); AppText("Elysia 多系统工具箱", 25, bold = true)
            AppText("v${BuildConfig.VERSION_NAME}", 16, muted = true)
        }
        AppCard(padding = 0) {
            PreferenceItem("在 GitHub 查看源码", "", { onOpenUrl("https://github.com/Shlc57/Elysia-DSU-Multi-SystemManager") })
            MDivider(Modifier.padding(horizontal = 16.dp))
            PreferenceItem("加入 QQ 群", "", onJoinGroup)
            MDivider(Modifier.padding(horizontal = 16.dp))
            PreferenceItem("开源项目", "", onSources)
        }
    }
}

@Composable
private fun ConfirmationDialog(model: ManagerViewModel) {
    val request = model.confirmation
    val dismiss = { model.confirmation = null }
    val label = when (request?.action) { "switch" -> "切换并重启"; "return" -> "回主系统"; "delete" -> "删除"; "install" -> "安装"; "compatibility-flash" -> "确认刷入"; "compatibility-prepare" -> "仅生成补丁"; "info" -> "知道了"; else -> "确认" }
    OverlayDialog(show = request != null, title = request?.title, summary = request?.message, onDismissRequest = dismiss) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("取消", dismiss, primary = false, fullWidth = false, modifier = Modifier.weight(1f))
                AppButton(label, model::executeConfirmation, fullWidth = false, modifier = Modifier.weight(1f))
            }
    }
}

@Composable
private fun CompatibilityPage(model: ManagerViewModel, onOpenUrl: (String) -> Unit, onGuide: () -> Unit) {
    LaunchedEffect(Unit) {
        snapshotFlow { model.busy }.first { !it }
        model.inspectCompatibility()
    }
    val plan = model.compatibilityPlan
    val options = model.compatibilityOptions
    ScreenColumn {
        HomeModeCard(model)
        AppCard(CardTone.Warning) {
            AppText("DSU 启动兼容辅助", 17, bold = true)
            AppText("脚本修补宿主 boot/init_boot，使 DSU 启动时加载模块。它不会改变 Bootloader 的真实锁定状态；不同设备仍需匹配 KMI、模块签名和启动链。", 13)
        }
        AppCard {
            AppText("环境检查", 17, bold = true)
            AppText("内核：${plan?.kernel ?: "待检测"}", 12, muted = true)
            AppText("KMI 世代：${plan?.target?.ifEmpty { "不支持或无法确认" } ?: "待检测"}", 12, muted = true)
            AppText("引导分区：${plan?.partition?.ifEmpty { "未识别" } ?: "待检测"}", 12, muted = true)
            AppText("外部启动链与模块签名不能由上述信息自动确认。", 12, muted = true)
            Spacer(Modifier.height(10.dp))
            AppButton("重新检查环境", model::inspectCompatibility, !model.busy, false)
        }
        PreferenceRow("新手使用教程", "从检查环境到备份、生成补丁与刷入", onGuide)
        AppCard(padding = 0) {
            SwitchPreference(title = "SELinux 启动期辅助", summary = "仅用于 DSU 启动期，不持续拦截 setenforce",
                checked = options.selinux, enabled = !model.busy,
                onCheckedChange = { model.updateCompatibilityOptions(options.copy(selinux = it)) })
            SwitchPreference(title = "AVB 启动期辅助", summary = "临时调整 first-stage PID 1 读取视图，不签名 vbmeta",
                checked = options.avb, enabled = !model.busy,
                onCheckedChange = { model.updateCompatibilityOptions(options.copy(avb = it)) })
            SwitchPreference(title = "dm-verity 表辅助", summary = "高级选项，默认关闭，依赖 AVB 辅助",
                checked = options.verity, enabled = !model.busy && options.avb,
                onCheckedChange = { model.updateCompatibilityOptions(options.copy(verity = it)) })
        }
        AppText("已内置脚本：DSU-Permissive v0.7.0 · ${if (model.busy) "校验/操作中" else if (model.verifiedBundle) "SHA-256 校验通过" else "不可用"}", 12, muted = true)
        AppButton("仅备份并生成补丁", { model.confirmCompatibilityPreparation(false) },
            !model.busy && model.verifiedBundle && plan?.canPrepare == true)
        AppButton("备份、生成并刷入引导分区…", { model.confirmCompatibilityPreparation(true) },
            !model.busy && model.verifiedBundle && plan?.canPrepare == true, false)
        if (model.compatibilityMessage.isNotEmpty()) AppCard {
            SelectionContainer { AppText(model.compatibilityMessage, 12) }
        }
        PreferenceRow("DSU-Permissive 源码与说明", "", { onOpenUrl(CompatibilityTool.SOURCE) })
    }
}

@Composable
private fun CompatibilityGuidePage(onOpenUrl: (String) -> Unit) {
    ScreenColumn {
        AppCard(CardTone.Warning) {
            AppText("先看这段：它不是真回锁", 17, bold = true)
            AppText("此模式不会解锁或重新锁定 Bootloader。只有设备启动链本来就允许启动对应的修改后 boot/init_boot，并满足上游要求时，脚本才可能帮助 DSU 启动。它不能替代解锁、签名或设备专属适配。", 13)
        }
        GuideStepCard("1", "确认设备条件", "先授予本管理器 Root。上游当前目标是 arm64 GKI，且待修补的宿主引导镜像已由外部启动链允许启动、已按上游要求由 KernelSU LKM 处理；Android 12 通常是 boot.img，Android 13 及以上通常是 init_boot.img。请在主系统操作，不要在 DSU 已运行时修补。出现“不支持 / 未识别 / 正在运行 DSU”就先停止，不要尝试刷入。")
        GuideStepCard("2", "先准备好恢复办法", "确认你有原始 boot/init_boot 的完整备份，并知道如何通过自己熟悉的 Recovery 或 fastboot 恢复。刷错引导分区可能导致无法启动；不要只依赖本应用里的备份，也不要在没有恢复途径时继续。")
        GuideStepCard("3", "回到环境检查", "返回上一页点“重新检查环境”。看清内核、KMI 世代和引导分区名称；确认当前不是 DSU、目标分区确实属于本机。若提示 avb_enforce，或你不确定这些信息，取消操作并先查设备/上游说明。")
        GuideStepCard("4", "保持保守选项", "SELinux 启动期辅助和 AVB 启动期辅助默认开启；dm-verity 表辅助默认关闭，普通情况下先保持关闭。不要为了“兼容更多机型”而随意开关；修改选项后需要重新生成并刷入对应补丁。")
        GuideStepCard("5", "先只生成，不写分区", "点“仅备份并生成补丁”。这一步会读取目标引导分区、制作完整备份、生成并验证补丁镜像，但不会写回分区。等操作结果显示成功后，保存结果里给出的备份路径和补丁路径；若失败就停在这里。")
        GuideStepCard("6", "确认后才刷入", "只有在确认设备、槽位、分区名和恢复方法都正确后，才点“备份、生成并刷入引导分区…”。弹窗会再次显示目标分区和风险；逐项核对，不确定就点取消。确认后脚本会再次备份、生成补丁、刷入并回读校验。")
        GuideStepCard("7", "根据结果处理", "只有看到“刷入并回读校验成功”才算刷写校验通过。管理器不会自动重启；请先保存备份路径和恢复命令，再按设备自己的 DSU 流程决定下一步。若刷写/回读报错或提示“请勿重启”，不要重启，保留备份并先恢复或寻求设备维护帮助。")
        AppCard {
            AppText("当前内置版本", 17, bold = true)
            AppText("DSU-Permissive 0.7.0，与上游最新稳定 Release 的脚本资产 SHA-256 一致。上游支持范围和设备前提可能变化；本管理器不会自动更新脚本。", 13)
            Spacer(Modifier.height(8.dp))
            AppButton("打开上游项目与最新说明", { onOpenUrl(CompatibilityTool.SOURCE) }, primary = false)
        }
    }
}

@Composable
private fun GuideStepCard(number: String, title: String, description: String) {
    AppCard {
        AppText("第 $number 步：$title", 16, bold = true, color = MiuixTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        AppText(description, 13)
    }
}

@Composable
private fun SourcePage(onOpenUrl: (String) -> Unit) {
    val projects = listOf(
        Triple("Jetpack Compose", "Apache-2.0", "https://github.com/androidx/androidx"),
        Triple("Material 3", "Apache-2.0", "https://github.com/androidx/androidx"),
        Triple("Miuix", "Apache-2.0", "https://github.com/compose-miuix-ui/miuix"),
        Triple("Capsule", "Apache-2.0", "https://github.com/Kyant0/Capsule"),
        Triple("AndroidLiquidGlass / Backdrop", "Apache-2.0", "https://github.com/Kyant0/AndroidLiquidGlass"),
        Triple("KernelSU Manager（界面参考）", "GPL-3.0", "https://github.com/KOWX712/KernelSU"),
        Triple("Material Icons（源码参考）", "Apache-2.0", "https://github.com/google/material-design-icons"),
        Triple("KernelSU（UI 参考，未复制源码）", "GPL-3.0", "https://github.com/KOWX712/KernelSU"),
        Triple("Kotlin Coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
        Triple("Kotlin Serialization", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
        Triple("MaterialKolor", "Apache-2.0", "https://github.com/jordond/MaterialKolor"),
        Triple("OkHttp（本应用未直接依赖）", "Apache-2.0", "https://github.com/square/okhttp"),
        Triple("DSUManager 镜像解析", "MIT", "https://github.com/XZY-01/DSUManager")
    )
    ScreenColumn {
        AppText("应用依赖与源码参考。标注“未直接依赖”的项目仅提供源码入口。", 13, muted = true)
        AppCard(padding = 0) {
            projects.forEachIndexed { index, (name, license, url) ->
                if (index > 0) MDivider(Modifier.padding(horizontal = 16.dp))
                PreferenceItem(name, license, { onOpenUrl(url) })
            }
        }
    }
}
