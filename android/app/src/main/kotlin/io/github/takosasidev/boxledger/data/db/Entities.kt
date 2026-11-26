// Room の表。表と列の名前は要件定義書 §4 のとおり。
// 表どうしは数値の id でつなぎ、外へ出すとき(:core のモデル・バックアップ)は uuid に読み替える(LedgerRepository)。
package io.github.takosasidev.boxledger.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import io.github.takosasidev.boxledger.core.model.AliasType
import io.github.takosasidev.boxledger.core.model.DarkModeMode
import io.github.takosasidev.boxledger.core.model.GameStatus
import io.github.takosasidev.boxledger.core.model.ThemeColorMode
import io.github.takosasidev.boxledger.core.model.ViewMode
import io.github.takosasidev.boxledger.core.model.WinCondition
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * 日付の保存の形は収支管理アプリと同じ。LocalDate は ISO の文字("2026-11-25"。辞書順 = 日付順)、
 * Instant は epoch ミリ秒。外貨の金額とレート(BigDecimal)は、桁を崩さないように文字で持つ。
 */
class Converters {
    @TypeConverter
    fun localDateToText(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun textToLocalDate(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    @TypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun decimalToText(value: BigDecimal?): String? = value?.toPlainString()

    @TypeConverter
    fun textToDecimal(value: String?): BigDecimal? = value?.toBigDecimalOrNull()
}

@Entity(
    tableName = "Game",
    // 本体を消したら、拡張は「本体なしのゲーム」に戻る(拡張も消すときは、先にコードで消す)
    foreignKeys = [
        ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["parentGameId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("uuid", unique = true), Index("parentGameId"), Index("barcodeValue")],
)
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val uuid: String,
    val title: String,
    val barcodeValue: String?,
    val parentGameId: Long?,
    val boxPhotoFile: String?,
    val minPlayers: Int?,
    val maxPlayers: Int?,
    val minPlayTimeMinutes: Int?,
    val maxPlayTimeMinutes: Int?,
    val complexityRating: Double?,
    val purchasePrice: Int?,
    val purchaseCurrency: String?,
    val purchaseForeignAmount: BigDecimal?,
    val purchaseExchangeRate: BigDecimal?,
    val purchaseDate: LocalDate?,
    val storageLocation: String?,
    val personalRating: Int?,
    /** Room は enum を名前の文字で保存する。 */
    val winCondition: WinCondition,
    val status: GameStatus,
    val isArchived: Boolean,
    val excludeFromSuggestion: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Entity(tableName = "Player", indices = [Index("uuid", unique = true)])
data class PlayerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val uuid: String,
    val name: String,
    val colorArgb: Int,
    val memo: String?,
    val isSelf: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Entity(tableName = "GenreTag", indices = [Index("uuid", unique = true)])
data class GenreTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val uuid: String,
    val name: String,
    val colorArgb: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Entity(
    tableName = "GameGenreTagCrossRef",
    primaryKeys = ["gameId", "genreTagId"],
    foreignKeys = [
        ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = GenreTagEntity::class, parentColumns = ["id"], childColumns = ["genreTagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("genreTagId")],
)
data class GameGenreTagCrossRef(val gameId: Long, val genreTagId: Long)

@Entity(
    tableName = "PlaySession",
    foreignKeys = [
        ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("uuid", unique = true), Index("gameId"), Index("playedAt")],
)
data class PlaySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val uuid: String,
    val gameId: Long,
    val playedAt: LocalDate,
    val durationMinutes: Int?,
    val memo: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Entity(
    tableName = "PlaySessionResult",
    foreignKeys = [
        ForeignKey(entity = PlaySessionEntity::class, parentColumns = ["id"], childColumns = ["playSessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PlayerEntity::class, parentColumns = ["id"], childColumns = ["playerId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["playSessionId", "playerId"], unique = true), Index("playerId")],
)
data class PlaySessionResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val playSessionId: Long,
    val playerId: Long,
    val score: Double?,
    val isWinner: Boolean,
    val rank: Int?,
)

@Entity(
    tableName = "PlaySessionExpansion",
    primaryKeys = ["playSessionId", "expansionGameId"],
    foreignKeys = [
        ForeignKey(entity = PlaySessionEntity::class, parentColumns = ["id"], childColumns = ["playSessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["expansionGameId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("expansionGameId")],
)
data class PlaySessionExpansionEntity(val playSessionId: Long, val expansionGameId: Long)

@Entity(
    tableName = "LendingRecord",
    foreignKeys = [
        ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PlayerEntity::class, parentColumns = ["id"], childColumns = ["playerId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("uuid", unique = true), Index("gameId"), Index("playerId")],
)
data class LendingRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val uuid: String,
    val gameId: Long,
    val playerId: Long,
    val lentAt: LocalDate,
    val expectedReturnAt: LocalDate?,
    val returnedAt: LocalDate?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Entity(tableName = "UuidAlias", indices = [Index(value = ["entityType", "foreignUuid"], unique = true)])
data class UuidAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val entityType: AliasType,
    val foreignUuid: String,
    val localUuid: String,
)

/** 設定は 1 行だけ。§4 に無い `id` は、その 1 行を指すための主キー(常に 1)。`celebrationEnabled` は Windows 版の §5.8 から。 */
@Entity(tableName = "AppSettings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = SINGLE_ID,
    val defaultViewMode: ViewMode,
    val unplayedSuggestionThresholdDays: Int,
    val themeColorMode: ThemeColorMode,
    val manualThemeColor: Int?,
    val darkModeMode: DarkModeMode,
    val celebrationEnabled: Boolean,
    val autoBackupEnabled: Boolean,
    val autoBackupIntervalDays: Int,
    val autoBackupKeepCount: Int,
    val lastAutoBackupAt: Instant?,
    val appLockEnabled: Boolean,
    val showExpansionsInList: Boolean,
) {
    companion object {
        const val SINGLE_ID = 1
    }
}
