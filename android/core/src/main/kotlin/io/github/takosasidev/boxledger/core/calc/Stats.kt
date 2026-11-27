// 集計。プレイヤー別の成績(FR-06)、ゲーム別の統計(FR-07)、ダッシュボード(FR-10)、年間の振り返り(FR-25)。
package io.github.takosasidev.boxledger.core.calc

import io.github.takosasidev.boxledger.core.model.Game
import io.github.takosasidev.boxledger.core.model.GameStatus
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.core.model.PlaySession
import java.time.LocalDate
import kotlin.math.roundToInt

data class Record(val plays: Int, val wins: Int) {
    /**
     * 勝率 = 勝者の印の数 ÷ 参加した回数(Windows 版の要件定義書 §13.2 の暫定案)。
     * 複数の勝者はそれぞれ 1 勝。協力ゲームで全員が負ければ全員 0 勝。1 回も遊んでいなければ null。
     */
    val winRate: Double? get() = if (plays == 0) null else wins.toDouble() / plays

    /** 「70%」。遊んでいなければ「-」。 */
    val winRateText: String get() = winRate?.let { "${(it * 100).roundToInt()}%" } ?: "-"
}

data class PlayerStats(val total: Record, /** ゲームの uuid ごと。回数の多い順。 */ val byGame: List<Pair<String, Record>>)

data class GameStats(val playCount: Int, val totalMinutes: Int, val averageMinutes: Int?, val lastPlayed: LocalDate?)

data class DeleteImpact(val sessions: Int, val lendings: Int, /** 残るが「使った拡張」から外れるプレイ記録。 */ val unlinkedSessions: Int)

data class Dashboard(
    val ownedGames: Int,
    val ownedExpansions: Int,
    val wishlist: Int,
    val totalPrice: Long,
    /** 複雑度を四捨五入した 1〜5 ごとのゲームの数(添字 0 が 1)。 */
    val complexity: List<Int>,
    /** よく遊んだ本体(ゲーム, 回数)。多い順。 */
    val mostPlayed: List<Pair<Game, Int>>,
    val totalPlays: Int,
)

data class YearReview(
    val year: Int,
    val plays: Int,
    val totalMinutes: Int,
    val distinctGames: Int,
    val topGames: List<Pair<Game, Int>>,
    val newPurchases: Int,
    val purchaseTotal: Long,
    /** 「自分」の成績。「自分」がいなければ null。 */
    val self: Record?,
    /** いちばん多く一緒に遊んだ相手(名前, 回数)。 */
    val topPartner: Pair<String, Int>?,
)

object Stats {

    fun playerStats(library: Library, playerUuid: String): PlayerStats {
        val mine = library.playSessions.mapNotNull { s -> s.results.firstOrNull { it.playerUuid == playerUuid }?.let { s.gameUuid to it.isWinner } }
        fun record(rows: List<Pair<String, Boolean>>) = Record(rows.size, rows.count { it.second })
        return PlayerStats(
            total = record(mine),
            byGame = mine.groupBy { it.first }.map { (game, rows) -> game to record(rows) }.sortedByDescending { it.second.plays },
        )
    }

    /**
     * 本体は、その本体のプレイ記録を全部数える(拡張を使ったプレイも、記録は本体に付くので含まれる)。
     * 拡張は「使った拡張」に入っているプレイ記録を数える(使用回数と最終使用日)。
     */
    fun sessionsOf(library: Library, gameUuid: String): List<PlaySession> =
        library.playSessions.filter { it.gameUuid == gameUuid || gameUuid in it.expansionUuids }

    fun gameStats(library: Library, gameUuid: String): GameStats = gameStats(sessionsOf(library, gameUuid))

    /**
     * ゲームを消すと一緒に消える記録の数(FR-26 の確認の画面に出す)。DB の外部キーのカスケードと同じ数え方。
     * 消えるプレイ記録は「そのゲームに付いた記録」だけ。拡張を消しても、本体のプレイ記録は残り、
     * 「使った拡張」から外れるだけ([DeleteImpact.unlinkedSessions])。
     */
    fun gameDeleteImpact(library: Library, gameUuid: String, withExpansions: Boolean): DeleteImpact {
        val gone = buildSet {
            add(gameUuid)
            if (withExpansions) library.expansionsOf(gameUuid).forEach { add(it.uuid) }
        }
        return DeleteImpact(
            sessions = library.playSessions.count { it.gameUuid in gone },
            lendings = library.lendingRecords.count { it.gameUuid in gone },
            unlinkedSessions = library.playSessions.count { s -> s.gameUuid !in gone && s.expansionUuids.any { it in gone } },
        )
    }

    fun gameStats(sessions: List<PlaySession>): GameStats {
        val timed = sessions.mapNotNull { it.durationMinutes }
        return GameStats(
            playCount = sessions.size,
            totalMinutes = timed.sum(),
            // 時間を書いていない記録は平均に入れない
            averageMinutes = if (timed.isEmpty()) null else (timed.sum().toDouble() / timed.size).roundToInt(),
            lastPlayed = sessions.maxOfOrNull { it.playedAt },
        )
    }

    /** 全ゲームの統計をまとめて作る(一覧の並び替え用。1 件ずつ数えると件数の 2 乗になる)。 */
    fun allGameStats(library: Library): Map<String, GameStats> {
        val byGame = HashMap<String, MutableList<PlaySession>>()
        library.playSessions.forEach { s ->
            (listOf(s.gameUuid) + s.expansionUuids).distinct().forEach { byGame.getOrPut(it) { ArrayList() }.add(s) }
        }
        return byGame.mapValues { gameStats(it.value) }
    }

    fun dashboard(library: Library, top: Int = 5): Dashboard {
        val owned = library.games.filter { it.status == GameStatus.OWNED && !it.isArchived }
        val counts = library.playSessions.groupingBy { it.gameUuid }.eachCount()
        return Dashboard(
            ownedGames = owned.count { !it.isExpansion },
            ownedExpansions = owned.count { it.isExpansion },
            wishlist = library.games.count { it.status == GameStatus.WISHLIST && !it.isArchived },
            totalPrice = owned.sumOf { (it.purchasePrice ?: 0).toLong() },
            complexity = (1..5).map { level -> owned.count { it.complexityRating?.roundToInt() == level } },
            mostPlayed = topGames(library, counts, top),
            totalPlays = library.playSessions.size,
        )
    }

    fun years(library: Library, today: LocalDate): List<Int> =
        (library.playSessions.map { it.playedAt.year } + library.games.mapNotNull { it.purchaseDate?.year } + today.year)
            .distinct().sortedDescending()

    fun yearReview(library: Library, year: Int): YearReview {
        val sessions = library.playSessions.filter { it.playedAt.year == year }
        val bought = library.games.filter { it.status == GameStatus.OWNED && it.purchaseDate?.year == year }
        val self = library.players.firstOrNull { it.isSelf }
        val partners = sessions.flatMap { s -> s.results.map { it.playerUuid } }.filter { it != self?.uuid }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }
        return YearReview(
            year = year,
            plays = sessions.size,
            totalMinutes = sessions.sumOf { it.durationMinutes ?: 0 },
            distinctGames = sessions.map { it.gameUuid }.distinct().size,
            topGames = topGames(library, sessions.groupingBy { it.gameUuid }.eachCount(), 3),
            newPurchases = bought.size,
            purchaseTotal = bought.sumOf { (it.purchasePrice ?: 0).toLong() },
            self = self?.let { me ->
                val mine = sessions.mapNotNull { s -> s.results.firstOrNull { it.playerUuid == me.uuid } }
                Record(mine.size, mine.count { it.isWinner })
            },
            topPartner = partners?.let { (uuid, n) -> library.player(uuid)?.let { it.name to n } },
        )
    }

    private fun topGames(library: Library, counts: Map<String, Int>, top: Int): List<Pair<Game, Int>> =
        counts.entries.mapNotNull { (uuid, n) -> library.game(uuid)?.let { it to n } }
            .sortedWith(compareByDescending<Pair<Game, Int>> { it.second }.thenBy { it.first.title })
            .take(top)
}
