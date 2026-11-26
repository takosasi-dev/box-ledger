package io.github.takosasidev.boxledger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.takosasidev.boxledger.core.model.AppSettings
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.data.LedgerRepository
import io.github.takosasidev.boxledger.data.backup.AutoBackup
import io.github.takosasidev.boxledger.notify.DailyCheck
import io.github.takosasidev.boxledger.notify.DailyCheckScheduler
import io.github.takosasidev.boxledger.ui.LedgerViewModel
import io.github.takosasidev.boxledger.ui.Routes
import io.github.takosasidev.boxledger.ui.collection.CollectionScreen
import io.github.takosasidev.boxledger.ui.game.GameDetailScreen
import io.github.takosasidev.boxledger.ui.game.GameEditScreen
import io.github.takosasidev.boxledger.ui.lending.LendingScreen
import io.github.takosasidev.boxledger.ui.lock.AppLockGate
import io.github.takosasidev.boxledger.ui.play.PlayEditScreen
import io.github.takosasidev.boxledger.ui.play.PlaysScreen
import io.github.takosasidev.boxledger.ui.players.PlayerDetailScreen
import io.github.takosasidev.boxledger.ui.players.PlayersScreen
import io.github.takosasidev.boxledger.ui.scan.ScanScreen
import io.github.takosasidev.boxledger.ui.settings.BackupScreen
import io.github.takosasidev.boxledger.ui.settings.SettingsScreen
import io.github.takosasidev.boxledger.ui.settings.TagsScreen
import io.github.takosasidev.boxledger.ui.stats.StatsScreen
import io.github.takosasidev.boxledger.ui.stats.YearReviewScreen
import io.github.takosasidev.boxledger.ui.theme.BoxLedgerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * ショートカット・通知から渡された Intent。`setIntent()` は再コンポーズを起こさないので、
     * State として持ち、処理したら null に戻す(起動中に来た分も飛べるように、二重に飛ばないように)。
     */
    private val pendingIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 画面の回転などで作り直されたときは積まない(同じ行き先へもう一度飛んでしまう)
        if (savedInstanceState == null) pendingIntent.value = intent

        // NFR-06: 起動時の自動バックアップの判定と返却の見回りは IO のスレッドで行い、画面を待たせない。
        // どれが失敗しても、アプリの起動は妨げない。
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { LedgerRepository.get(applicationContext).ensureDefaults() }
            runCatching { AutoBackup.runIfDue(applicationContext) }
            runCatching { DailyCheck.run(applicationContext) }
            // 再起動でアラームは消えるので、開くたびに積み直す
            runCatching { DailyCheckScheduler.schedule(applicationContext) }
        }

        setContent {
            val vm: LedgerViewModel = viewModel(factory = LedgerViewModel.factory(applicationContext))
            val library by vm.library.collectAsStateWithLifecycle()
            BoxLedgerTheme(library?.settings ?: AppSettings()) {
                val loaded = library
                if (loaded == null) {
                    // 設定(アプリロックの有無)が届くまで、中身を出さない
                    Surface(Modifier.fillMaxSize()) {}
                } else {
                    AppLockGate(enabled = loaded.settings.appLockEnabled) {
                        val navController = rememberNavController()
                        AppShell(vm, loaded, navController)
                        HandleLaunchIntent(navController, pendingIntent.value) { pendingIntent.value = null }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntent.value = intent
    }
}

/** 下のナビに出す行き先。ここに無い画面は掘り下げた先として、ナビを隠す。 */
private enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    COLLECTION(Routes.COLLECTION, "コレクション", Icons.Filled.Inventory2),
    PLAYS(Routes.PLAYS, "プレイ", Icons.Filled.EmojiEvents),
    PLAYERS(Routes.PLAYERS, "プレイヤー", Icons.Filled.Groups),
    STATS(Routes.STATS, "統計", Icons.Filled.BarChart),
    SETTINGS(Routes.SETTINGS, "設定", Icons.Filled.Settings),
}

private fun NavHostController.openTab(route: String) = navigate(route) {
    // タブを行き来しても戻る道を積み上げない
    popUpTo(Routes.COLLECTION) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun AppShell(vm: LedgerViewModel, library: Library, navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 貸出中の数を、コレクションのタブに出す
    val lentCount = library.lendingRecords.count { it.returnedAt == null }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = TopLevel.entries.any { it.route == currentRoute },
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                NavigationBar {
                    TopLevel.entries.forEach { dest ->
                        val badge = if (dest == TopLevel.COLLECTION) lentCount else 0
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                // 同じタブの連打と、遷移の途中に届いた 2 度目のタップを捨てる
                                if (currentRoute != dest.route && backStackEntry?.isFront() == true) navController.openTab(dest.route)
                            },
                            icon = {
                                BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) {
                                    Icon(dest.icon, contentDescription = if (badge > 0) "貸出中 $badge 件" else null)
                                }
                            },
                            label = { Text(dest.label, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // 外側で下のナビの分を空けたので、内側の Scaffold がもう一度同じ余白を足さないようにする
        AppNavHost(vm, library, navController, Modifier.padding(padding).consumeWindowInsets(padding))
    }
}

/** ショートカット(FR-19)と通知から開かれたときの飛び先。 */
@Composable
private fun HandleLaunchIntent(navController: NavHostController, intent: Intent?, onHandled: () -> Unit) {
    LaunchedEffect(intent) {
        if (intent == null) return@LaunchedEffect
        val gameUuid = intent.getStringExtra(Routes.EXTRA_GAME_UUID)
        when {
            intent.getStringExtra(Routes.EXTRA_DESTINATION) == Routes.DEST_NEW_PLAY -> navController.navigate(Routes.playEdit())
            // 外から来た文字なので、uuid の形(英数字と -)のときだけ行き先に使う
            gameUuid != null && gameUuid.all { it.isLetterOrDigit() || it == '-' } -> navController.navigate(Routes.game(gameUuid))
        }
        onHandled()
    }
}

/** その画面がまだ前面にいるか。遷移が始まった後に届いた 2 度目のタップを捨てるために使う。 */
private fun NavBackStackEntry.isFront(): Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

private fun NavHostController.navigateOnce(from: NavBackStackEntry, route: String) {
    if (from.isFront()) navigate(route)
}

/** [from] の画面を閉じて次へ進む(スキャンの画面を戻る道に残さない)。 */
private fun NavHostController.replaceOnce(from: NavBackStackEntry, route: String) {
    if (from.isFront()) navigate(route) { popUpTo(Routes.SCAN) { inclusive = true } }
}

/** 戻る。遷移の途中の 2 度目のタップを捨て、戻り先が無いときは何もしない(戻る道を空にしない)。 */
private fun NavHostController.backOnce(from: NavBackStackEntry) {
    if (from.isFront() && previousBackStackEntry != null) popBackStack()
}

private fun stringArg(name: String) = navArgument(name) {
    type = NavType.StringType
    defaultValue = ""
}

private fun NavBackStackEntry.arg(name: String): String = arguments?.getString(name).orEmpty()

@Composable
private fun AppNavHost(vm: LedgerViewModel, library: Library, navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = Routes.COLLECTION,
        modifier = modifier,
        enterTransition = { fadeIn(tween(200)) },
        exitTransition = { fadeOut(tween(150)) },
        popEnterTransition = { fadeIn(tween(200)) },
        popExitTransition = { fadeOut(tween(150)) },
    ) {
        composable(Routes.COLLECTION) { entry ->
            CollectionScreen(
                vm, library,
                onOpen = { uuid -> navController.navigateOnce(entry, Routes.game(uuid)) },
                onAdd = { wish -> navController.navigateOnce(entry, Routes.gameEdit(wish = wish)) },
                onScan = { navController.navigateOnce(entry, Routes.SCAN) },
                onRecordPlay = { uuid -> navController.navigateOnce(entry, Routes.playEdit(game = uuid)) },
                onLendings = { navController.navigateOnce(entry, Routes.LENDINGS) },
            )
        }
        composable(Routes.PLAYS) { entry ->
            PlaysScreen(
                library,
                onNew = { navController.navigateOnce(entry, Routes.playEdit()) },
                onOpen = { uuid -> navController.navigateOnce(entry, Routes.playEdit(uuid = uuid)) },
            )
        }
        composable(Routes.PLAYERS) { entry ->
            PlayersScreen(vm, library, onOpen = { uuid -> navController.navigateOnce(entry, Routes.player(uuid)) })
        }
        composable(Routes.STATS) { entry -> StatsScreen(library, onYearReview = { navController.navigateOnce(entry, Routes.YEAR) }) }
        composable(Routes.SETTINGS) { entry ->
            SettingsScreen(
                vm, library,
                onOpenTags = { navController.navigateOnce(entry, Routes.TAGS) },
                onOpenBackup = { navController.navigateOnce(entry, Routes.BACKUP) },
            )
        }
        composable(Routes.GAME, arguments = listOf(stringArg("uuid"))) { entry ->
            val uuid = entry.arg("uuid")
            GameDetailScreen(
                vm, library, uuid,
                onBack = { navController.backOnce(entry) },
                onEdit = { navController.navigateOnce(entry, Routes.gameEdit(uuid = uuid)) },
                onRecordPlay = { game -> navController.navigateOnce(entry, Routes.playEdit(game = game)) },
                onOpenGame = { other -> navController.navigateOnce(entry, Routes.game(other)) },
                onAddExpansion = { parent -> navController.navigateOnce(entry, Routes.gameEdit(parent = parent)) },
                onOpenSession = { session -> navController.navigateOnce(entry, Routes.playEdit(uuid = session)) },
            )
        }
        composable(Routes.GAME_EDIT, arguments = listOf(stringArg("uuid"), stringArg("barcode"), stringArg("parent"), stringArg("wish"))) { entry ->
            GameEditScreen(
                vm, library,
                uuid = entry.arg("uuid"),
                barcode = entry.arg("barcode"),
                parentUuid = entry.arg("parent"),
                wish = entry.arg("wish") == "true",
                onDone = { navController.backOnce(entry) },
                onBack = { navController.backOnce(entry) },
            )
        }
        composable(Routes.PLAY_EDIT, arguments = listOf(stringArg("uuid"), stringArg("game"))) { entry ->
            PlayEditScreen(
                vm, library,
                uuid = entry.arg("uuid"),
                initialGameUuid = entry.arg("game"),
                onDone = { navController.backOnce(entry) },
                onBack = { navController.backOnce(entry) },
            )
        }
        composable(Routes.PLAYER, arguments = listOf(stringArg("uuid"))) { entry ->
            PlayerDetailScreen(
                vm, library, entry.arg("uuid"),
                onBack = { navController.backOnce(entry) },
                onOpenSession = { session -> navController.navigateOnce(entry, Routes.playEdit(uuid = session)) },
            )
        }
        composable(Routes.SCAN) { entry ->
            ScanScreen(
                library.games,
                onBack = { navController.backOnce(entry) },
                onFound = { uuid -> navController.replaceOnce(entry, Routes.game(uuid)) },
                onNew = { code -> navController.replaceOnce(entry, Routes.gameEdit(barcode = code)) },
            )
        }
        composable(Routes.LENDINGS) { entry ->
            LendingScreen(vm, library, onBack = { navController.backOnce(entry) }, onOpenGame = { uuid -> navController.navigateOnce(entry, Routes.game(uuid)) })
        }
        composable(Routes.TAGS) { entry -> TagsScreen(vm, library, onBack = { navController.backOnce(entry) }) }
        composable(Routes.BACKUP) { entry -> BackupScreen(vm, library, onBack = { navController.backOnce(entry) }) }
        composable(Routes.YEAR) { entry -> YearReviewScreen(library, onBack = { navController.backOnce(entry) }) }
    }
}
