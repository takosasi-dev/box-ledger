// 業務の決まり。順位と勝者の自動の提案(FR-05)、未プレイの提案(FR-13)、返却のお知らせ(FR-09)、外貨の換算(FR-29)。
package io.github.takosasidev.boxledger.core.calc

import io.github.takosasidev.boxledger.core.model.Game
import io.github.takosasidev.boxledger.core.model.GameStatus
import io.github.takosasidev.boxledger.core.model.LendingRecord
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.core.model.PlayResult
import io.github.takosasidev.boxledger.core.model.WinCondition
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object Ranking {

    /**
     * スコアから順位と勝者を埋める。同点は同じ順位(1, 1, 3)、1 位が複数なら全員が勝者。
     * スコアが空の人は順位なし・勝者ではない。MANUAL のゲームと、誰もスコアを入れていないときは何も変えない。
     */
    fun autoFill(results: List<PlayResult>, condition: WinCondition): List<PlayResult> {
        if (condition == WinCondition.MANUAL || results.none { it.score != null }) return results
        fun better(a: Double, b: Double) = if (condition == WinCondition.HIGH_SCORE) a > b else a < b
        return results.map { r ->
            val score = r.score ?: return@map r.copy(rank = null, isWinner = false)
            val rank = 1 + results.count { other -> other.score?.let { better(it, score) } == true }
            r.copy(rank = rank, isWinner = rank == 1)
        }
    }
}

data class Suggestion(val game: Game, /** 1 回も遊んでいなければ null。 */ val lastPlayed: LocalDate?, val days: Long)

object Suggestions {

    /**
     * 最後に遊んでから [thresholdDays] 日以上たったゲーム。長く遊んでいない順。
     * 対象は、所有中・アーカイブしていない・除外していない本体だけ(拡張は提案しない)。
     * 1 回も遊んでいないゲームは、購入日(無ければ登録した日)から数える。
     */
    fun unplayed(library: Library, today: LocalDate, thresholdDays: Int, zone: ZoneId): List<Suggestion> {
        val last = library.playSessions.groupBy { it.gameUuid }.mapValues { e -> e.value.maxOf { it.playedAt } }
        return library.games
            .filter { !it.isExpansion && it.status == GameStatus.OWNED && !it.isArchived && !it.excludeFromSuggestion }
            .map { g ->
                val since = last[g.uuid] ?: g.purchaseDate ?: g.createdAt.atZone(zone).toLocalDate()
                Suggestion(g, last[g.uuid], ChronoUnit.DAYS.between(since, today))
            }
            .filter { it.days >= thresholdDays }
            .sortedByDescending { it.days }
    }
}

object Reminders {

    /** 返却予定日の何日前に知らせるか。前日と当日(Windows 版の要件定義書 §13.2 の暫定案)。 */
    private val DAYS_BEFORE = listOf(1L, 0L)

    /** 今日知らせる貸出。未返却で、返却予定日が今日か明日の物。 */
    fun due(library: Library, today: LocalDate): List<LendingRecord> =
        library.lendingRecords.filter { l ->
            l.returnedAt == null && l.expectedReturnAt != null && ChronoUnit.DAYS.between(today, l.expectedReturnAt) in DAYS_BEFORE
        }

    /** 「この貸出の、この日の分は知らせた」の印。同じ日に 2 回知らせないために使う。 */
    fun key(lending: LendingRecord, today: LocalDate): String = "${lending.uuid}|$today"

    /** 「あと 3 日」「今日まで」「2 日超過」。返却予定日が無ければ null。 */
    fun label(lending: LendingRecord, today: LocalDate): String? {
        val days = ChronoUnit.DAYS.between(today, lending.expectedReturnAt ?: return null)
        return when {
            days > 0 -> "あと ${days} 日"
            days == 0L -> "今日まで"
            else -> "${-days} 日超過"
        }
    }

    /** 未返却の貸出。返却予定日の近い順(予定日の無い物は後ろ)。 */
    fun open(library: Library): List<LendingRecord> =
        library.lendingRecords.filter { it.returnedAt == null }
            .sortedWith(compareBy(nullsLast<Long>()) { it.expectedReturnAt?.toEpochDay() })
}

object Money {
    /** 円 = 外貨の金額 × レートの四捨五入。範囲(0〜9,999,999)の外なら null。 */
    fun yen(foreignAmount: BigDecimal, rate: BigDecimal): Int? {
        if (foreignAmount.signum() < 0 || rate.signum() <= 0) return null
        val yen = foreignAmount.multiply(rate).setScale(0, RoundingMode.HALF_UP)
        return if (yen <= BigDecimal(9_999_999)) yen.toInt() else null
    }
}
