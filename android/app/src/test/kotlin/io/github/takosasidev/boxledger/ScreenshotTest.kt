package io.github.takosasidev.boxledger

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.takosasidev.boxledger.core.calc.GameQuery
import io.github.takosasidev.boxledger.core.model.AppSettings
import io.github.takosasidev.boxledger.core.model.DarkModeMode
import io.github.takosasidev.boxledger.core.model.Game
import io.github.takosasidev.boxledger.core.model.GameStatus
import io.github.takosasidev.boxledger.core.model.GenreTag
import io.github.takosasidev.boxledger.core.model.LendingRecord
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.core.model.PlayResult
import io.github.takosasidev.boxledger.core.model.PlaySession
import io.github.takosasidev.boxledger.core.model.Player
import io.github.takosasidev.boxledger.core.model.ThemeColorMode
import io.github.takosasidev.boxledger.core.model.ViewMode
import io.github.takosasidev.boxledger.core.model.WinCondition
import io.github.takosasidev.boxledger.data.LedgerRepository
import io.github.takosasidev.boxledger.ui.LedgerViewModel
import io.github.takosasidev.boxledger.ui.collection.CollectionScreen
import io.github.takosasidev.boxledger.ui.game.GameDetailScreen
import io.github.takosasidev.boxledger.ui.game.GameEditScreen
import io.github.takosasidev.boxledger.ui.lending.LendingScreen
import io.github.takosasidev.boxledger.ui.play.PlayEditScreen
import io.github.takosasidev.boxledger.ui.play.PlaysScreen
import io.github.takosasidev.boxledger.ui.players.PlayerDetailScreen
import io.github.takosasidev.boxledger.ui.players.PlayersScreen
import io.github.takosasidev.boxledger.ui.settings.BackupScreen
import io.github.takosasidev.boxledger.ui.settings.SettingsScreen
import io.github.takosasidev.boxledger.ui.settings.TagsScreen
import io.github.takosasidev.boxledger.ui.stats.StatsScreen
import io.github.takosasidev.boxledger.ui.stats.YearReviewScreen
import io.github.takosasidev.boxledger.ui.theme.BoxLedgerTheme
import org.junit.Rule
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.NavigationBarItem
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 画面を PC の上で画像にする(端末が無くても見た目を確かめるため)。
 * `gradlew testDebugUnitTest -Pshots` のときだけ app/build/outputs/roborazzi/ に PNG を書く。
 * 画像は比べない(合否を見ない)。見るのは人。
 * 画面は Library を引数で受けるので、データは Room を通さずにここで組む(待ちが要らない)。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ScreenshotTest {

    // テスト用の空の Activity を、マニフェストに書かずに登録する(debug の APK に物を足さないため)
    @get:Rule(order = 0)
    val registerActivity = object : TestWatcher() {
        override fun starting(description: Description?) {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager)
                .addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.now()

    /** 見た目を確かめたい状態(長い名前・拡張・ウィッシュリスト・貸出の超過・記録 0 件の人・2 年分の記録)を一通り入れる。 */
    private fun seed(settings: AppSettings): Library {
        val created = today.minusYears(2).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val tags = listOf(
            GenreTag("t1", "戦略", 0xFF3F51B5.toInt()),
            GenreTag("t2", "協力", 0xFF00796B.toInt()),
            GenreTag("t3", "パーティー", 0xFFE07A2F.toInt()),
            GenreTag("t4", "2人向け", 0xFFB3325B.toInt()),
            GenreTag("t5", "重量級", 0xFF6D4C41.toInt()),
        )
        val players = listOf(
            Player("p1", "たかし", 0xFF3F51B5.toInt(), isSelf = true),
            Player("p2", "さくら", 0xFFB3325B.toInt(), memo = "協力ゲームが好き"),
            Player("p3", "けんた", 0xFF2E7D32.toInt()),
            Player("p4", "ゆうき", 0xFFE07A2F.toInt()),
            Player("p5", "はじめて来た人", 0xFF546E7A.toInt()),
        )
        fun game(
            uuid: String, title: String, min: Int?, max: Int?, tMin: Int?, tMax: Int?, tags: List<String>,
            rating: Int? = null, complexity: Double? = null, price: Int? = null, bought: LocalDate? = null,
            parent: String? = null, status: GameStatus = GameStatus.OWNED, archived: Boolean = false,
            place: String? = null, win: WinCondition = WinCondition.HIGH_SCORE,
        ) = Game(
            uuid = uuid, title = title, parentUuid = parent, minPlayers = min, maxPlayers = max,
            minPlayTimeMinutes = tMin, maxPlayTimeMinutes = tMax, complexityRating = complexity, purchasePrice = price,
            purchaseDate = bought, storageLocation = place, personalRating = rating, genreTagUuids = tags,
            winCondition = win, status = status, isArchived = archived, barcodeValue = if (parent == null && status == GameStatus.OWNED) "4900000000${uuid.length}12" else null,
            createdAt = created, updatedAt = created,
        )
        val games = listOf(
            game("g1", "港町の交易王", 3, 4, 60, 90, listOf("t1"), 5, 2.8, 5280, today.minusMonths(14), place = "リビングの棚・上段"),
            game("g2", "港町の交易王 拡張: 灯台と嵐の夜", 3, 6, 60, 120, listOf("t1"), 4, 3.0, 3300, today.minusMonths(8), parent = "g1"),
            game("g3", "七つの塔の建築家たち(デラックス版・日本語の第 2 版)", 1, 5, 90, 150, listOf("t1", "t5"), 4, 4.2, 12800, today.minusMonths(20), place = "押し入れ"),
            game("g4", "星降る森の探検隊", 1, 4, 30, 45, listOf("t2"), 5, 2.1, 4400, today.minusMonths(5), place = "リビングの棚・下段"),
            game("g5", "ことば当てクイズ", 4, 10, 15, 30, listOf("t3"), 3, 1.2, 1980, today.minusMonths(30), place = "リビングの棚・下段", win = WinCondition.MANUAL),
            game("g6", "二人の庭師", 2, 2, 20, 30, listOf("t4"), 4, 1.8, 2750, today.minusMonths(10), win = WinCondition.LOW_SCORE),
            game("g7", "雲上の庭園", 2, 4, 45, 60, listOf("t1", "t2", "t4"), null, 2.5, 6600, today.minusMonths(11)),
            game("g8", "深海探検隊 リメイク版", 2, 5, 60, 90, listOf("t2"), status = GameStatus.WISHLIST),
            game("g9", "砂漠の隊商", 2, 4, 40, 60, listOf("t1"), status = GameStatus.WISHLIST),
            game("g10", "古いすごろく", 2, 6, 30, 30, emptyList(), 2, archived = true),
        )
        // 2 年分のプレイ記録。今年と去年にまたがる
        val rota = listOf("g1", "g4", "g3", "g6", "g1", "g5", "g4", "g1", "g6", "g3", "g4", "g1")
        val sessions = rota.flatMapIndexed { i, g ->
            listOf(0L, 1L).map { y ->
                val n = i * 2 + y.toInt()
                val players4 = listOf("p1", "p2", "p3", "p4").take(if (g == "g6") 2 else 3 + n % 2)
                val scores = players4.mapIndexed { k, p -> p to ((n * 7 + k * 13) % 40 + 20).toDouble() }
                val best = if (g == "g6") scores.minOf { it.second } else scores.maxOf { it.second }
                PlaySession(
                    uuid = "s$n", gameUuid = g, playedAt = today.minusDays(3L + i * 23L + y * 365L),
                    durationMinutes = 45 + (n % 4) * 15, expansionUuids = if (g == "g1" && n % 2 == 0) listOf("g2") else emptyList(),
                    results = scores.map { (p, s) -> PlayResult(p, s, s == best, scores.count { o -> if (g == "g6") o.second < s else o.second > s } + 1) },
                )
            }
        }
        val lendings = listOf(
            LendingRecord("l1", "g4", "p2", today.minusDays(12), today.minusDays(2)),
            LendingRecord("l2", "g5", "p3", today.minusDays(6), today),
            LendingRecord("l3", "g7", "p4", today.minusDays(1), today.plusDays(6)),
            LendingRecord("l4", "g3", "p2", today.minusDays(60), today.minusDays(45), today.minusDays(47)),
        )
        return Library(genreTags = tags, players = players, games = games, playSessions = sessions, lendingRecords = lendings, settings = settings)
    }

    @Test
    fun 画面を撮る() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = LedgerViewModel(LedgerRepository.get(app))
        val indigo = 0xFF3F51B5.toInt()
        var dark by mutableStateOf(false)
        var name by mutableStateOf("")
        var seedColor by mutableStateOf<Int?>(indigo)
        var themeMode by mutableStateOf(ThemeColorMode.MANUAL)
        val firstSession = "s0"
        compose.setContent {
            val settings = AppSettings(
                themeColorMode = themeMode,
                manualThemeColor = seedColor,
                darkModeMode = if (dark) DarkModeMode.DARK else DarkModeMode.LIGHT,
                autoBackupEnabled = true,
                lastAutoBackupAt = Instant.now().minusSeconds(3 * 86_400),
            )
            val library = seed(settings)
            val screens = mapOf<String, @Composable () -> Unit>(
                "collection" to { CollectionScreen(vm, library, {}, {}, {}, {}, {}) },
                "collection-list" to { CollectionScreen(vm, library, {}, {}, {}, {}, {}) },
                "wishlist" to { CollectionScreen(vm, library, {}, {}, {}, {}, {}) },
                "detail" to { GameDetailScreen(vm, library, "g1", {}, {}, {}, {}, {}, {}) },
                "edit" to { GameEditScreen(vm, library, "g1", "", "", false, {}, {}) },
                "plays" to { PlaysScreen(library, {}, {}) },
                "play-edit" to { PlayEditScreen(vm, library, firstSession, "", {}, {}) },
                "players" to { PlayersScreen(vm, library, {}) },
                "player" to { PlayerDetailScreen(vm, library, "p2", {}, {}) },
                "stats" to { StatsScreen(library, {}) },
                "year" to { YearReviewScreen(library, {}) },
                "lending" to { LendingScreen(vm, library, {}, {}) },
                "settings" to { SettingsScreen(vm, library, {}, {}) },
                "tags" to { TagsScreen(vm, library, {}) },
                "backup" to { BackupScreen(vm, library, {}) },
                // 下のナビの名前が幅 411dp で切れないかを見る(MainActivity と同じ 5 つの名前と字の大きさ)
                "nav" to {
                    androidx.compose.material3.NavigationBar {
                        listOf("コレクション", "プレイ", "プレイヤー", "統計", "設定").forEach { label ->
                            NavigationBarItem(
                                selected = label == "コレクション",
                                onClick = {},
                                icon = { androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.requiredSize(24.dp)) },
                                label = {
                                    androidx.compose.material3.Text(
                                        label, maxLines = 1, softWrap = false,
                                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                        }
                    }
                },
            )
            BoxLedgerTheme(settings) {
                // key で画面ごとに状態を作り直す(前の画面の remember を持ち越さない)
                key(name, seedColor, themeMode, dark) { screens[name]?.invoke() }
            }
        }
        fun prepare(n: String) {
            vm.viewMode.value = if (n == "collection-list") ViewMode.LIST else ViewMode.GRID
            vm.query.value = GameQuery(status = if (n == "wishlist") GameStatus.WISHLIST else GameStatus.OWNED)
        }
        fun shoot(file: String) {
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/$file.png")
        }
        val long = setOf("collection", "collection-list", "detail", "edit", "play-edit", "player", "stats", "settings", "backup")
        for (n in listOf("collection", "collection-list", "wishlist", "detail", "edit", "plays", "play-edit", "players", "player", "stats", "year", "lending", "settings", "tags", "backup", "nav")) {
            for (d in listOf(false, true)) {
                prepare(n)
                dark = d
                name = n
                val mode = if (d) "dark" else "light"
                shoot("$n-$mode")
                // 縦に長い画面は、下まで送った所も撮る
                if (n in long) {
                    repeat(3) { compose.onRoot().performTouchInput { swipeUp() } }
                    shoot("$n-$mode-bottom")
                }
            }
        }
        // 既定の設定(壁紙の色に合わせる)。依頼者の端末(Android 12 以上)ではこちらの見た目になる
        themeMode = ThemeColorMode.DYNAMIC
        for (n in listOf("collection", "stats")) for (d in listOf(false, true)) {
            prepare(n)
            dark = d
            name = n
            shoot("$n-dynamic-${if (d) "dark" else "light"}")
        }
        themeMode = ThemeColorMode.MANUAL
        // 色の候補ごとの見え方(注意の色とぶつからないか)。null は「壁紙の色が無い端末で、色を選んでいない」とき
        dark = false
        for ((label, c) in listOf("orange" to 0xFFE07A2F.toInt(), "rose" to 0xFFB3325B.toInt(), "fallback" to null)) {
            seedColor = c
            for (n in listOf("collection-list", "players")) {
                prepare(n)
                name = n
                shoot("$n-$label-light")
            }
        }
    }
}
