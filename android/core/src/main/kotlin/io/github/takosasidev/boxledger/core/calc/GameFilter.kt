// 一覧の検索・絞り込み・並び替え(FR-14)。判定は Windows 版の要件定義書 S-03 と同じ。
package io.github.takosasidev.boxledger.core.calc

import io.github.takosasidev.boxledger.core.model.Game
import io.github.takosasidev.boxledger.core.model.GameStatus
import io.github.takosasidev.boxledger.core.model.Library

enum class SortKey(val label: String) {
    TITLE("タイトル"),
    PURCHASE_DATE("購入日"),
    PLAY_COUNT("プレイ回数"),
    LAST_PLAYED("最終プレイ日"),
    RATING("評価"),
}

data class GameQuery(
    val text: String = "",
    val status: GameStatus = GameStatus.OWNED,
    /** 遊ぶ人数 N。範囲に N が入るゲームだけ。 */
    val players: Int? = null,
    /** プレイ時間の上限(分)。 */
    val maxMinutes: Int? = null,
    /** どれか 1 つでも付いていれば通す(OR)。 */
    val genreUuids: Set<String> = emptySet(),
    val minRating: Int? = null,
    val lentOnly: Boolean = false,
    val includeArchived: Boolean = false,
    /** false なら拡張は一覧に出さない(本体の画面にだけ出す)。 */
    val showExpansions: Boolean = false,
    val sort: SortKey = SortKey.TITLE,
    val descending: Boolean = false,
) {
    /** 絞り込み(検索の文字と並び順を除く)が 1 つでも入っているか。 */
    val hasFilter: Boolean
        get() = players != null || maxMinutes != null || genreUuids.isNotEmpty() || minRating != null || lentOnly || includeArchived
}

object GameFilter {

    fun apply(library: Library, q: GameQuery): List<Game> {
        val stats = Stats.allGameStats(library)
        val needle = q.text.trim()
        val matched = library.games.filter { g ->
            g.status == q.status &&
                (q.showExpansions || !g.isExpansion) &&
                (q.includeArchived || !g.isArchived) &&
                (needle.isEmpty() || g.title.contains(needle, ignoreCase = true) || g.barcodeValue == needle ||
                    g.storageLocation?.contains(needle, ignoreCase = true) == true) &&
                (q.players == null || playerRange(library, g)?.contains(q.players) == true) &&
                (q.maxMinutes == null || fitsTime(g, q.maxMinutes)) &&
                (q.genreUuids.isEmpty() || g.genreTagUuids.any { it in q.genreUuids }) &&
                (q.minRating == null || (g.personalRating ?: 0) >= q.minRating) &&
                (!q.lentOnly || library.openLending(g.uuid) != null)
        }
        // 値の無いゲームは、昇順でも降順でも後ろに置く
        fun <T : Comparable<T>> order(key: (Game) -> T?): Comparator<Game> {
            val order: Comparator<T> = if (q.descending) reverseOrder() else naturalOrder()
            return compareBy(nullsLast(order), key)
        }
        val comparator = when (q.sort) {
            SortKey.TITLE -> order { it.title.lowercase() }
            SortKey.PURCHASE_DATE -> order { it.purchaseDate?.toEpochDay() }
            SortKey.PLAY_COUNT -> order { stats[it.uuid]?.playCount ?: 0 }
            SortKey.LAST_PLAYED -> order { stats[it.uuid]?.lastPlayed?.toEpochDay() }
            SortKey.RATING -> order { it.personalRating }
        }
        return matched.sortedWith(comparator.thenBy { it.title.lowercase() })
    }

    /**
     * 人数の範囲。本体は、持っている拡張(所有中でアーカイブしていない物)の範囲と合わせる。
     * 例: 3〜4 人の本体に 5〜6 人の拡張を持っていれば 3〜6 人。人数が未設定なら null(人数の絞り込みには出さない)。
     * 片方だけ入っているときは、その 1 つの人数として扱う。
     */
    fun playerRange(library: Library, game: Game): IntRange? {
        val family = listOf(game) + if (game.isExpansion) {
            emptyList()
        } else {
            library.expansionsOf(game.uuid).filter { it.status == GameStatus.OWNED && !it.isArchived }
        }
        val ranges = family.mapNotNull { g ->
            val min = g.minPlayers ?: g.maxPlayers ?: return@mapNotNull null
            val max = g.maxPlayers ?: min
            min..max
        }
        // 本体の人数が未設定なら、拡張の人数だけでは出さない
        if (game.minPlayers == null && game.maxPlayers == null) return null
        return ranges.minOf { it.first }..ranges.maxOf { it.last }
    }

    /** 最長の時間が上限以下。最長が未設定なら最短で見る。両方未設定なら通さない。 */
    private fun fitsTime(game: Game, limit: Int): Boolean =
        (game.maxPlayTimeMinutes ?: game.minPlayTimeMinutes)?.let { it <= limit } ?: false

    /** 同じタイトルのゲームが既にあるか(登録は止めず、警告だけ出す)。 */
    fun hasSameTitle(library: Library, title: String, exceptUuid: String?): Boolean =
        library.games.any { it.uuid != exceptUuid && it.title == title.trim() }
}

object Format {
    fun players(min: Int?, max: Int?): String? = range(min, max)?.let { "${it}人" }

    fun minutes(min: Int?, max: Int?): String? = range(min, max)?.let { "${it}分" }

    private fun range(min: Int?, max: Int?): String? = when {
        min == null && max == null -> null
        min == null || max == null || min == max -> "${min ?: max}"
        else -> "$min〜$max"
    }

    /** 「2時間5分」「45分」。 */
    fun duration(totalMinutes: Int): String =
        if (totalMinutes >= 60) "${totalMinutes / 60}時間${totalMinutes % 60}分" else "${totalMinutes}分"

    fun yen(amount: Long): String = "%,d円".format(java.util.Locale.US, amount)

    /** 小数が無ければ整数で書く(スコア 10.0 → 「10」)。 */
    fun number(value: Double): String = if (value == Math.rint(value) && Math.abs(value) < 1e15) "%d".format(value.toLong()) else value.toString()
}
