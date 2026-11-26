// アプリ全体で使う記録の形。参照はすべて uuid(端末をまたいで変わらない)。
// DB の数値の id は :app の中だけの物で、ここには出てこない。バックアップの形(Windows 版仕様書 §6)とほぼ 1 対 1。
package io.github.takosasidev.boxledger.core.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class WinCondition { HIGH_SCORE, LOW_SCORE, MANUAL }
enum class GameStatus { OWNED, WISHLIST }
enum class ViewMode { GRID, LIST }
enum class ThemeColorMode { DYNAMIC, MANUAL }
enum class DarkModeMode { SYSTEM, LIGHT, DARK }
enum class AliasType { GAME, PLAYER, GENRE_TAG }

data class GenreTag(
    val uuid: String,
    val name: String,
    val colorArgb: Int,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
)

data class Player(
    val uuid: String,
    val name: String,
    val colorArgb: Int,
    val memo: String? = null,
    val isSelf: Boolean = false,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
)

data class Game(
    val uuid: String,
    val title: String,
    /** 拡張なら本体の uuid。本体なら null(1 階層だけ)。 */
    val parentUuid: String? = null,
    val barcodeValue: String? = null,
    /** 写真のフォルダの中のファイル名。 */
    val boxPhotoFile: String? = null,
    val minPlayers: Int? = null,
    val maxPlayers: Int? = null,
    val minPlayTimeMinutes: Int? = null,
    val maxPlayTimeMinutes: Int? = null,
    val complexityRating: Double? = null,
    /** 円。集計はこの値だけを使う。 */
    val purchasePrice: Int? = null,
    val purchaseCurrency: String? = null,
    val purchaseForeignAmount: BigDecimal? = null,
    val purchaseExchangeRate: BigDecimal? = null,
    val purchaseDate: LocalDate? = null,
    val storageLocation: String? = null,
    val personalRating: Int? = null,
    val genreTagUuids: List<String> = emptyList(),
    val winCondition: WinCondition = WinCondition.HIGH_SCORE,
    val status: GameStatus = GameStatus.OWNED,
    val isArchived: Boolean = false,
    val excludeFromSuggestion: Boolean = false,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
) {
    val isExpansion: Boolean get() = parentUuid != null
}

data class PlayResult(
    val playerUuid: String,
    val score: Double? = null,
    val isWinner: Boolean = false,
    val rank: Int? = null,
)

data class PlaySession(
    val uuid: String,
    val gameUuid: String,
    val playedAt: LocalDate,
    val durationMinutes: Int? = null,
    val memo: String? = null,
    val expansionUuids: List<String> = emptyList(),
    val results: List<PlayResult> = emptyList(),
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
)

data class LendingRecord(
    val uuid: String,
    val gameUuid: String,
    val playerUuid: String,
    val lentAt: LocalDate,
    val expectedReturnAt: LocalDate? = null,
    /** null なら貸出中。 */
    val returnedAt: LocalDate? = null,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
)

data class UuidAlias(val entityType: AliasType, val foreignUuid: String, val localUuid: String)

data class AppSettings(
    // ---- バックアップに入れる(端末をまたいで持ち運ぶ) ----
    val defaultViewMode: ViewMode = ViewMode.GRID,
    val unplayedSuggestionThresholdDays: Int = 90,
    val themeColorMode: ThemeColorMode = ThemeColorMode.DYNAMIC,
    val manualThemeColor: Int? = null,
    val darkModeMode: DarkModeMode = DarkModeMode.SYSTEM,
    val celebrationEnabled: Boolean = true,
    // ---- この端末だけの設定(バックアップに入れない) ----
    val autoBackupEnabled: Boolean = false,
    val autoBackupIntervalDays: Int = 7,
    val autoBackupKeepCount: Int = 5,
    val lastAutoBackupAt: Instant? = null,
    val appLockEnabled: Boolean = false,
    val showExpansionsInList: Boolean = false,
) {
    /** 全置換のときに、バックアップの設定から持ち運ぶ項目だけをもらう。端末だけの設定は今の値を保つ。 */
    fun withExchangedFrom(other: AppSettings): AppSettings = copy(
        defaultViewMode = other.defaultViewMode,
        unplayedSuggestionThresholdDays = other.unplayedSuggestionThresholdDays,
        themeColorMode = other.themeColorMode,
        manualThemeColor = other.manualThemeColor,
        darkModeMode = other.darkModeMode,
        celebrationEnabled = other.celebrationEnabled,
    )
}

/** 全部のデータ。画面・集計・バックアップ・追加取り込みは、どれもこの形を受け取る。 */
data class Library(
    val genreTags: List<GenreTag> = emptyList(),
    val players: List<Player> = emptyList(),
    val games: List<Game> = emptyList(),
    val playSessions: List<PlaySession> = emptyList(),
    val lendingRecords: List<LendingRecord> = emptyList(),
    val uuidAliases: List<UuidAlias> = emptyList(),
    val settings: AppSettings = AppSettings(),
) {
    fun game(uuid: String?): Game? = games.firstOrNull { it.uuid == uuid }
    fun player(uuid: String?): Player? = players.firstOrNull { it.uuid == uuid }
    fun expansionsOf(gameUuid: String): List<Game> = games.filter { it.parentUuid == gameUuid }

    /** 未返却の貸出。貸出中かどうかは status に持たず、ここから導く。 */
    fun openLending(gameUuid: String): LendingRecord? =
        lendingRecords.firstOrNull { it.gameUuid == gameUuid && it.returnedAt == null }
}
