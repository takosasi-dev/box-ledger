// 勝率・集計・絞り込み・提案・順位の自動の提案。ゲームの名前は全部作り物。
package io.github.takosasidev.boxledger.core

import io.github.takosasidev.boxledger.core.calc.Format
import io.github.takosasidev.boxledger.core.calc.GameFilter
import io.github.takosasidev.boxledger.core.calc.GameQuery
import io.github.takosasidev.boxledger.core.calc.Money
import io.github.takosasidev.boxledger.core.calc.Ranking
import io.github.takosasidev.boxledger.core.calc.Record
import io.github.takosasidev.boxledger.core.calc.Reminders
import io.github.takosasidev.boxledger.core.calc.SortKey
import io.github.takosasidev.boxledger.core.calc.Stats
import io.github.takosasidev.boxledger.core.calc.Suggestions
import io.github.takosasidev.boxledger.core.model.Game
import io.github.takosasidev.boxledger.core.model.GameStatus
import io.github.takosasidev.boxledger.core.model.LendingRecord
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.core.model.PlayResult
import io.github.takosasidev.boxledger.core.model.PlaySession
import io.github.takosasidev.boxledger.core.model.Player
import io.github.takosasidev.boxledger.core.model.WinCondition
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalcTest {

    private val today = LocalDate.parse("2026-11-26")

    private fun play(n: Int, game: String, date: String, minutes: Int? = null, expansions: List<String> = emptyList(), vararg results: PlayResult) =
        PlaySession("s$n", game, LocalDate.parse(date), minutes, expansionUuids = expansions, results = results.toList())

    private fun win(p: String) = PlayResult(p, isWinner = true)
    private fun lose(p: String) = PlayResult(p)

    // ---- 勝率 ----

    @Test
    fun 十回のうち七回勝てば勝率_70_パーセント() {
        val sessions = (1..10).map { n -> play(n, "g1", "2026-01-01", results = arrayOf(if (n <= 7) win("a") else lose("a"), lose("b"))) }
        val stats = Stats.playerStats(Library(playSessions = sessions), "a")
        assertEquals(Record(10, 7), stats.total)
        assertEquals(0.7, stats.total.winRate)
        assertEquals("70%", stats.total.winRateText)
    }

    @Test
    fun 複数の勝者はそれぞれ_1_勝_全員が負ければ全員_0_勝_出ていないプレイは数えない() {
        val lib = Library(
            playSessions = listOf(
                play(1, "g1", "2026-01-01", results = arrayOf(win("a"), win("b"), lose("c"))),
                play(2, "g2", "2026-01-02", results = arrayOf(lose("a"), lose("b"))),
                play(3, "g2", "2026-01-03", results = arrayOf(win("b"), lose("c"))),
            ),
        )
        assertEquals(Record(2, 1), Stats.playerStats(lib, "a").total)
        assertEquals(Record(3, 2), Stats.playerStats(lib, "b").total)
        assertEquals(Record(2, 0), Stats.playerStats(lib, "c").total)
        assertEquals(listOf("g2" to Record(2, 1), "g1" to Record(1, 1)), Stats.playerStats(lib, "b").byGame)
        // 1 回も遊んでいなければ勝率は出さない(0% と区別する)
        assertNull(Stats.playerStats(lib, "z").total.winRate)
        assertEquals("-", Stats.playerStats(lib, "z").total.winRateText)
        assertEquals("0%", Stats.playerStats(lib, "c").total.winRateText)
    }

    // ---- ゲーム別 ----

    @Test
    fun 本体は拡張を使ったプレイも数え_拡張は使った回数を数える() {
        val lib = Library(
            playSessions = listOf(
                play(1, "base", "2026-01-01", 60),
                play(2, "base", "2026-03-01", 91, listOf("ex")),
                play(3, "base", "2026-02-01"),
            ),
        )
        val base = Stats.gameStats(lib, "base")
        assertEquals(3, base.playCount)
        assertEquals(151, base.totalMinutes)
        // 時間を書いていない記録は平均に入れない
        assertEquals(76, base.averageMinutes)
        assertEquals(LocalDate.parse("2026-03-01"), base.lastPlayed)
        val ex = Stats.gameStats(lib, "ex")
        assertEquals(1, ex.playCount)
        assertEquals(LocalDate.parse("2026-03-01"), ex.lastPlayed)
        assertEquals(0, Stats.gameStats(lib, "unused").playCount)
        assertNull(Stats.gameStats(lib, "unused").averageMinutes)
        assertEquals(base, Stats.allGameStats(lib)["base"])
        assertEquals(ex, Stats.allGameStats(lib)["ex"])
    }

    @Test
    fun ダッシュボードと年間の振り返り() {
        val lib = Library(
            players = listOf(Player("me", "自分", 0, isSelf = true), Player("f", "友人A", 0)),
            games = listOf(
                Game("a", "架空のゲームA", purchasePrice = 4000, complexityRating = 2.4, purchaseDate = LocalDate.parse("2026-02-01")),
                Game("b", "架空のゲームB", purchasePrice = 3000, complexityRating = 2.5, purchaseDate = LocalDate.parse("2025-02-01")),
                Game("ex", "A の拡張", parentUuid = "a", purchasePrice = 1500),
                Game("w", "ほしい物", status = GameStatus.WISHLIST, purchasePrice = 9999, purchaseDate = LocalDate.parse("2026-03-01")),
                Game("sold", "手放した物", isArchived = true, purchasePrice = 9999),
            ),
            playSessions = listOf(
                play(1, "a", "2026-01-01", 30, results = arrayOf(win("me"), lose("f"))),
                play(2, "a", "2026-05-01", 40, results = arrayOf(lose("me"), win("f"))),
                play(3, "b", "2026-06-01", results = arrayOf(win("me"))),
                play(4, "b", "2025-06-01", 500, results = arrayOf(win("me"))),
            ),
        )
        val d = Stats.dashboard(lib)
        assertEquals(2, d.ownedGames)
        assertEquals(1, d.ownedExpansions)
        assertEquals(1, d.wishlist)
        assertEquals(8500L, d.totalPrice)
        assertEquals(listOf(0, 1, 1, 0, 0), d.complexity)
        assertEquals(listOf("架空のゲームA" to 2, "架空のゲームB" to 2), d.mostPlayed.map { it.first.title to it.second })

        val y = Stats.yearReview(lib, 2026)
        assertEquals(3, y.plays)
        assertEquals(70, y.totalMinutes)
        assertEquals(2, y.distinctGames)
        assertEquals("架空のゲームA", y.topGames.first().first.title)
        assertEquals(1, y.newPurchases)
        assertEquals(4000L, y.purchaseTotal)
        assertEquals(Record(3, 2), y.self)
        assertEquals("友人A" to 2, y.topPartner)
        assertEquals(listOf(2026, 2025), Stats.years(lib, today))
        // 記録の無い年でも落ちない
        assertEquals(0, Stats.yearReview(lib, 2020).plays)
        assertNull(Stats.yearReview(Library(), 2026).self)
    }

    // ---- 順位と勝者 ----

    @Test
    fun スコアから順位と勝者を埋める() {
        val results = listOf(PlayResult("a", 10.0), PlayResult("b", 10.0), PlayResult("c", 7.0), PlayResult("d", null, isWinner = true, rank = 1))
        val high = Ranking.autoFill(results, WinCondition.HIGH_SCORE)
        // 同点は同じ順位、1 位が複数なら全員が勝者。スコアの無い人は順位なし
        assertEquals(listOf(1, 1, 3, null), high.map { it.rank })
        assertEquals(listOf(true, true, false, false), high.map { it.isWinner })
        val low = Ranking.autoFill(results, WinCondition.LOW_SCORE)
        assertEquals(listOf(2, 2, 1, null), low.map { it.rank })
        assertEquals(listOf(false, false, true, false), low.map { it.isWinner })
        // 手動のゲームと、誰もスコアを入れていないときは、手で入れた物を変えない
        assertEquals(results, Ranking.autoFill(results, WinCondition.MANUAL))
        val manual = listOf(PlayResult("a", isWinner = true, rank = 1))
        assertEquals(manual, Ranking.autoFill(manual, WinCondition.HIGH_SCORE))
    }

    @Test
    fun スコアを全部消したら_自動で入った順位と勝者も消える() {
        // 「10」と打って a が 1 位・勝ちになった後で、その「10」を消した所
        val cleared = listOf(PlayResult("a", null, isWinner = true, rank = 1), PlayResult("b"))
        assertEquals(listOf(PlayResult("a"), PlayResult("b")), Ranking.afterScoreEdit(cleared, WinCondition.HIGH_SCORE))
        // 手動のゲームは、スコアを消しても手で入れた勝者を変えない
        assertEquals(cleared, Ranking.afterScoreEdit(cleared, WinCondition.MANUAL))
        // スコアが残っていれば、いつもの埋め方(スコアなしの人が混ざっても、ある人だけで順位を付ける)
        val mixed = listOf(PlayResult("a", 3.0), PlayResult("b", null, isWinner = true, rank = 1), PlayResult("c", 5.0))
        val low = Ranking.afterScoreEdit(mixed, WinCondition.LOW_SCORE)
        assertEquals(listOf(1, null, 2), low.map { it.rank })
        assertEquals(listOf(true, false, false), low.map { it.isWinner })
    }

    @Test
    fun 削除で一緒に消える記録の数() {
        val lib = Library(
            games = listOf(Game("a", "本体"), Game("ex", "拡張", parentUuid = "a"), Game("b", "別のゲーム")),
            playSessions = listOf(
                play(1, "a", "2026-01-01", expansions = listOf("ex")),
                play(2, "a", "2026-01-02"),
                play(3, "ex", "2026-01-03"), // 拡張にする前に、単体のゲームとして付けた記録
                play(4, "b", "2026-01-04"),
            ),
            lendingRecords = listOf(
                LendingRecord("l1", "a", "p", today),
                LendingRecord("l2", "ex", "p", today, returnedAt = today),
                LendingRecord("l3", "b", "p", today),
            ),
        )
        fun impact(uuid: String, withExpansions: Boolean) =
            Stats.gameDeleteImpact(lib, uuid, withExpansions).let { Triple(it.sessions, it.lendings, it.unlinkedSessions) }
        // 本体だけ消す(拡張は本体なしのゲームに戻す): 本体のプレイ 2 件と貸出 1 件
        assertEquals(Triple(2, 1, 0), impact("a", withExpansions = false))
        // 拡張も消す: 拡張に付いた分も足す
        assertEquals(Triple(3, 2, 0), impact("a", withExpansions = true))
        // 拡張だけ消す: 本体のプレイ記録は消えず、「使った拡張」から外れるだけ(前は「使った拡張」の分まで失われると数えていた)
        assertEquals(Triple(1, 1, 1), impact("ex", withExpansions = false))
    }

    // ---- 絞り込みと並び替え ----

    private val shelf = Library(
        games = listOf(
            Game("a", "あ 3〜4人", minPlayers = 3, maxPlayers = 4, maxPlayTimeMinutes = 90, personalRating = 5, genreTagUuids = listOf("t1"), purchaseDate = LocalDate.parse("2026-01-01")),
            Game("a-ex", "あ の 5〜6人拡張", parentUuid = "a", minPlayers = 5, maxPlayers = 6),
            Game("a-wish", "あ の ほしい拡張", parentUuid = "a", minPlayers = 7, maxPlayers = 8, status = GameStatus.WISHLIST),
            Game("b", "い 2人専用", minPlayers = 2, minPlayTimeMinutes = 20, personalRating = 3, genreTagUuids = listOf("t2"), storageLocation = "リビング"),
            Game("c", "う 人数なし", barcodeValue = "4900000000017"),
            Game("d", "え 手放した", minPlayers = 1, maxPlayers = 9, isArchived = true),
            Game("w", "お ほしい物", status = GameStatus.WISHLIST),
        ),
        playSessions = listOf(play(1, "b", "2026-01-01"), play(2, "b", "2026-02-01"), play(3, "c", "2026-03-01")),
        lendingRecords = listOf(
            LendingRecord("l1", "b", "p", LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-27")),
            LendingRecord("l2", "a", "p", LocalDate.parse("2026-01-01"), returnedAt = LocalDate.parse("2026-01-05")),
        ),
    )

    private fun find(q: GameQuery) = GameFilter.apply(shelf, q).map { it.uuid }

    @Test
    fun 既定は所有中の本体だけをタイトル順で() {
        assertEquals(listOf("a", "b", "c"), find(GameQuery()))
        assertEquals(listOf("a", "a-ex", "b", "c"), find(GameQuery(showExpansions = true)))
        assertEquals(listOf("a", "b", "c", "d"), find(GameQuery(includeArchived = true)))
        assertEquals(listOf("w"), find(GameQuery(status = GameStatus.WISHLIST)))
    }

    @Test
    fun 人数は持っている拡張と合わせた範囲で見る() {
        assertEquals(listOf("a"), find(GameQuery(players = 4)))
        // 本体は 3〜4 人だが、5〜6 人の拡張を持っているので 6 人でも出る。ほしいだけの拡張(7〜8 人)は数えない
        assertEquals(listOf("a"), find(GameQuery(players = 6)))
        assertEquals(emptyList(), find(GameQuery(players = 7)))
        // 片方だけ入っていれば、その人数。未設定のゲームは出さない
        assertEquals(listOf("b"), find(GameQuery(players = 2)))
        assertEquals(3..6, GameFilter.playerRange(shelf, shelf.game("a")!!))
        assertNull(GameFilter.playerRange(shelf, shelf.game("c")!!))
    }

    @Test
    fun 時間_ジャンル_評価_貸出中_検索() {
        assertEquals(listOf("b"), find(GameQuery(maxMinutes = 60)))
        assertEquals(listOf("a", "b"), find(GameQuery(maxMinutes = 90)))
        // ジャンル同士は OR、項目同士は AND
        assertEquals(listOf("a", "b"), find(GameQuery(genreUuids = setOf("t1", "t2"))))
        assertEquals(listOf("a"), find(GameQuery(genreUuids = setOf("t1", "t2"), minRating = 4)))
        assertEquals(listOf("b"), find(GameQuery(lentOnly = true)))
        assertEquals(listOf("b"), find(GameQuery(text = " 専用 ")))
        assertEquals(listOf("b"), find(GameQuery(text = "リビング")))
        assertEquals(listOf("c"), find(GameQuery(text = "4900000000017")))
    }

    @Test
    fun 並び替え_値の無いゲームは後ろ() {
        assertEquals(listOf("c", "b", "a"), find(GameQuery(sort = SortKey.TITLE, descending = true)))
        assertEquals(listOf("b", "c", "a"), find(GameQuery(sort = SortKey.PLAY_COUNT, descending = true)))
        assertEquals(listOf("c", "b", "a"), find(GameQuery(sort = SortKey.LAST_PLAYED, descending = true)))
        assertEquals(listOf("b", "c", "a"), find(GameQuery(sort = SortKey.LAST_PLAYED)))
        assertEquals(listOf("a", "b", "c"), find(GameQuery(sort = SortKey.RATING, descending = true)))
        assertEquals(listOf("b", "a", "c"), find(GameQuery(sort = SortKey.RATING)))
        assertEquals(listOf("a", "b", "c"), find(GameQuery(sort = SortKey.PURCHASE_DATE)))
    }

    @Test
    fun 同じタイトルの警告() {
        assertEquals(true, GameFilter.hasSameTitle(shelf, " う 人数なし ", null))
        assertEquals(false, GameFilter.hasSameTitle(shelf, "う 人数なし", "c"))
    }

    // ---- 未プレイの提案 ----

    @Test
    fun しばらく遊んでいない本体を_長い順に出す() {
        val created = Instant.parse("2026-01-01T00:00:00Z")
        val lib = Library(
            games = listOf(
                Game("old", "100 日前", createdAt = created),
                Game("recent", "89 日前", createdAt = created),
                Game("edge", "ちょうど 90 日前", createdAt = created),
                Game("never", "遊んでいない", purchaseDate = LocalDate.parse("2025-11-26"), createdAt = created),
                Game("new", "買ったばかり", createdAt = Instant.parse("2026-11-20T00:00:00Z")),
                Game("ex", "拡張", parentUuid = "old", createdAt = created),
                Game("hidden", "除外した", excludeFromSuggestion = true, createdAt = created),
                Game("sold", "手放した", isArchived = true, createdAt = created),
                Game("wish", "ほしい物", status = GameStatus.WISHLIST, createdAt = created),
            ),
            playSessions = listOf(
                play(1, "old", today.minusDays(300).toString()),
                play(2, "old", today.minusDays(100).toString()),
                play(3, "recent", today.minusDays(89).toString()),
                play(4, "edge", today.minusDays(90).toString()),
            ),
        )
        val list = Suggestions.unplayed(lib, today, 90, ZoneOffset.UTC)
        assertEquals(listOf("never" to 365L, "old" to 100L, "edge" to 90L), list.map { it.game.uuid to it.days })
        assertNull(list.first().lastPlayed)
    }

    // ---- 返却のお知らせ・外貨・書式 ----

    @Test
    fun 返却予定日の前日と当日に知らせる() {
        fun lending(uuid: String, expected: String?, returned: String? = null) =
            LendingRecord(uuid, "g", "p", LocalDate.parse("2026-11-01"), expected?.let(LocalDate::parse), returned?.let(LocalDate::parse))
        val lib = Library(
            lendingRecords = listOf(
                lending("today", "2026-11-26"), lending("tomorrow", "2026-11-27"), lending("later", "2026-11-28"),
                lending("late", "2026-11-25"), lending("none", null), lending("back", "2026-11-26", "2026-11-20"),
            ),
        )
        assertEquals(listOf("today", "tomorrow"), Reminders.due(lib, today).map { it.uuid })
        assertEquals(listOf("late", "today", "tomorrow", "later", "none"), Reminders.open(lib).map { it.uuid })
        assertEquals("今日まで", Reminders.label(lib.lendingRecords[0], today))
        assertEquals("あと 2 日", Reminders.label(lib.lendingRecords[2], today))
        assertEquals("1 日超過", Reminders.label(lib.lendingRecords[3], today))
        assertNull(Reminders.label(lib.lendingRecords[4], today))
        assertEquals("today|2026-11-26", Reminders.key(lib.lendingRecords[0], today))
    }

    @Test
    fun 外貨の換算は四捨五入() {
        assertEquals(8960, Money.yen(BigDecimal("59.99"), BigDecimal("149.35")))
        assertEquals(1, Money.yen(BigDecimal("0.5"), BigDecimal("1")))
        assertEquals(0, Money.yen(BigDecimal("0.49"), BigDecimal("1")))
        assertNull(Money.yen(BigDecimal("1"), BigDecimal("0")))
        assertNull(Money.yen(BigDecimal("100000"), BigDecimal("1000")))
    }

    @Test
    fun 書式() {
        assertEquals("3〜4人", Format.players(3, 4))
        assertEquals("2人", Format.players(2, null))
        assertEquals("2人", Format.players(2, 2))
        assertNull(Format.players(null, null))
        assertEquals("60〜90分", Format.minutes(60, 90))
        assertEquals("45分", Format.duration(45))
        assertEquals("2時間5分", Format.duration(125))
        assertEquals("12,345円", Format.yen(12345))
        assertEquals("10", Format.number(10.0))
        assertEquals("7.5", Format.number(7.5))
    }
}
