package io.github.takosasidev.boxledger.data

import android.content.Context
import androidx.room.withTransaction
import io.github.takosasidev.boxledger.core.model.AppSettings
import io.github.takosasidev.boxledger.core.model.Game
import io.github.takosasidev.boxledger.core.model.GenreTag
import io.github.takosasidev.boxledger.core.model.LendingRecord
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.core.model.PlayResult
import io.github.takosasidev.boxledger.core.model.PlaySession
import io.github.takosasidev.boxledger.core.model.Player
import io.github.takosasidev.boxledger.core.model.UuidAlias
import io.github.takosasidev.boxledger.data.db.AppDatabase
import io.github.takosasidev.boxledger.data.db.AppSettingsEntity
import io.github.takosasidev.boxledger.data.db.GameEntity
import io.github.takosasidev.boxledger.data.db.GameGenreTagCrossRef
import io.github.takosasidev.boxledger.data.db.GenreTagEntity
import io.github.takosasidev.boxledger.data.db.LendingRecordEntity
import io.github.takosasidev.boxledger.data.db.PlaySessionEntity
import io.github.takosasidev.boxledger.data.db.PlaySessionExpansionEntity
import io.github.takosasidev.boxledger.data.db.PlaySessionResultEntity
import io.github.takosasidev.boxledger.data.db.PlayerEntity
import io.github.takosasidev.boxledger.data.db.UuidAliasEntity
import io.github.takosasidev.boxledger.widget.LedgerWidget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 画面・通知・ウィジェット・バックアップが触る唯一のデータの入口。
 * 外へは :core の [Library](参照は uuid)を渡し、DB の数値の id はここから外へ出さない。
 * 集計や判定は :core の関数に任せ、ここは読み書きだけを持つ。
 */
class LedgerRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val dao = db.dao()

    /** どれかの表が変わるたびに、全部を読み直して流す。 */
    val library: Flow<Library> = dao.observeChanges().map { libraryOnce() }

    /** 1 つのトランザクションで全部の表を読む(途中の状態を混ぜない)。 */
    suspend fun libraryOnce(): Library = db.withTransaction {
        val gameRows = dao.games()
        val playerRows = dao.players()
        val tagRows = dao.tags()
        val gameUuid = gameRows.associate { it.id to it.uuid }
        val playerUuid = playerRows.associate { it.id to it.uuid }
        val tagUuid = tagRows.associate { it.id to it.uuid }
        val tagsOfGame = dao.gameTags().groupBy({ it.gameId }, { tagUuid[it.genreTagId] })
        val resultsOf = dao.results().groupBy { it.playSessionId }
        val expansionsOf = dao.sessionExpansions().groupBy({ it.playSessionId }, { gameUuid[it.expansionGameId] })
        Library(
            genreTags = tagRows.map { GenreTag(it.uuid, it.name, it.colorArgb, it.createdAt, it.updatedAt) },
            players = playerRows.map { Player(it.uuid, it.name, it.colorArgb, it.memo, it.isSelf, it.createdAt, it.updatedAt) },
            games = gameRows.map { g ->
                Game(
                    uuid = g.uuid, title = g.title, parentUuid = g.parentGameId?.let(gameUuid::get), barcodeValue = g.barcodeValue,
                    boxPhotoFile = g.boxPhotoFile, minPlayers = g.minPlayers, maxPlayers = g.maxPlayers,
                    minPlayTimeMinutes = g.minPlayTimeMinutes, maxPlayTimeMinutes = g.maxPlayTimeMinutes,
                    complexityRating = g.complexityRating, purchasePrice = g.purchasePrice, purchaseCurrency = g.purchaseCurrency,
                    purchaseForeignAmount = g.purchaseForeignAmount, purchaseExchangeRate = g.purchaseExchangeRate,
                    purchaseDate = g.purchaseDate, storageLocation = g.storageLocation, personalRating = g.personalRating,
                    genreTagUuids = tagsOfGame[g.id].orEmpty().filterNotNull(), winCondition = g.winCondition, status = g.status,
                    isArchived = g.isArchived, excludeFromSuggestion = g.excludeFromSuggestion,
                    createdAt = g.createdAt, updatedAt = g.updatedAt,
                )
            },
            playSessions = dao.sessions().mapNotNull { s ->
                PlaySession(
                    uuid = s.uuid, gameUuid = gameUuid[s.gameId] ?: return@mapNotNull null, playedAt = s.playedAt,
                    durationMinutes = s.durationMinutes, memo = s.memo,
                    expansionUuids = expansionsOf[s.id].orEmpty().filterNotNull(),
                    results = resultsOf[s.id].orEmpty().mapNotNull { r ->
                        PlayResult(playerUuid[r.playerId] ?: return@mapNotNull null, r.score, r.isWinner, r.rank)
                    },
                    createdAt = s.createdAt, updatedAt = s.updatedAt,
                )
            },
            lendingRecords = dao.lendings().mapNotNull { l ->
                LendingRecord(
                    l.uuid, gameUuid[l.gameId] ?: return@mapNotNull null, playerUuid[l.playerId] ?: return@mapNotNull null,
                    l.lentAt, l.expectedReturnAt, l.returnedAt, l.createdAt, l.updatedAt,
                )
            },
            uuidAliases = dao.aliases().map { UuidAlias(it.entityType, it.foreignUuid, it.localUuid) },
            settings = dao.settings()?.toModel() ?: AppSettings(),
        )
    }

    suspend fun settingsOnce(): AppSettings = dao.settings()?.toModel() ?: AppSettings()

    /** 初めての起動で、設定の 1 行と「自分」と、例のジャンルタグを入れる。設定の行があれば何もしない。 */
    suspend fun ensureDefaults() = db.withTransaction {
        if (dao.settings() != null) return@withTransaction
        dao.upsert(AppSettings().toEntity())
        val now = now()
        if (dao.players().isEmpty()) dao.insert(PlayerEntity(0, newUuid(), "自分", 0xFF3F51B5.toInt(), null, true, now, now))
        if (dao.tags().isEmpty()) {
            DEFAULT_TAGS.forEach { (name, color) -> dao.insert(GenreTagEntity(0, newUuid(), name, color, now, now)) }
        }
    }

    // ---- 書き込み(どれも、項目を 1 つでも変えたら updatedAt を今にする) ----------------

    /** 新規なら足し、あれば書き換える。拡張の拡張は作らない(本体が拡張なら、本体なしで保存する)。 */
    suspend fun saveGame(game: Game) {
        db.withTransaction {
            val now = now()
            val existing = dao.game(game.uuid)
            // 本体にできるのは「拡張ではないゲーム」だけ。自分が拡張を持っているなら、自分は拡張になれない
            val hasExpansions = existing != null && dao.games().any { it.parentGameId == existing.id }
            val parent = game.parentUuid?.let { dao.game(it) }?.takeIf { it.parentGameId == null && it.uuid != game.uuid && !hasExpansions }
            val id = if (existing == null) {
                dao.insert(game.toEntity(0, parent?.id, createdAt = now, updatedAt = now))
            } else {
                dao.update(game.toEntity(existing.id, parent?.id, createdAt = existing.createdAt, updatedAt = now))
                existing.id
            }
            dao.deleteGameTags(id)
            dao.insertGameTags(game.genreTagUuids.distinct().mapNotNull { dao.tag(it) }.map { GameGenreTagCrossRef(id, it.id) })
        }
        afterChange(photosMayHaveChanged = true)
    }

    /** ゲームを消す。プレイ記録と貸出は一緒に消える。拡張は、消すか「本体なしのゲーム」に戻すかを選ぶ。 */
    suspend fun deleteGame(uuid: String, deleteExpansions: Boolean) {
        db.withTransaction {
            val row = dao.game(uuid) ?: return@withTransaction
            if (deleteExpansions) dao.deleteExpansionsOf(row.id) else dao.detachExpansionsOf(row.id, now())
            dao.deleteGame(row.id)
        }
        afterChange(photosMayHaveChanged = true)
    }

    /** 「自分」は 1 人だけ。自分にしたら、ほかの人の印を外す。 */
    suspend fun savePlayer(player: Player) {
        db.withTransaction {
            val now = now()
            val existing = dao.player(player.uuid)
            val id = if (existing == null) {
                dao.insert(PlayerEntity(0, player.uuid, player.name, player.colorArgb, player.memo, player.isSelf, now, now))
            } else {
                dao.update(existing.copy(name = player.name, colorArgb = player.colorArgb, memo = player.memo, isSelf = player.isSelf, updatedAt = now))
                existing.id
            }
            if (player.isSelf) dao.clearOtherSelf(id, now)
        }
        afterChange()
    }

    /** プレイヤーを消す。その人の結果と貸出の記録も消える。 */
    suspend fun deletePlayer(uuid: String) {
        dao.player(uuid)?.let { dao.deletePlayer(it.id) }
        afterChange()
    }

    suspend fun saveTag(tag: GenreTag) {
        val now = now()
        val existing = dao.tag(tag.uuid)
        if (existing == null) {
            dao.insert(GenreTagEntity(0, tag.uuid, tag.name, tag.colorArgb, now, now))
        } else {
            dao.update(existing.copy(name = tag.name, colorArgb = tag.colorArgb, updatedAt = now))
        }
    }

    suspend fun deleteTag(uuid: String) {
        dao.tag(uuid)?.let { dao.deleteTag(it.id) }
    }

    /** プレイ記録を保存する。結果と使った拡張は丸ごと入れ替える。使った拡張は、そのゲームの拡張だけを受ける。 */
    suspend fun saveSession(session: PlaySession) {
        db.withTransaction {
            val now = now()
            val game = dao.game(session.gameUuid) ?: return@withTransaction
            val existing = dao.session(session.uuid)
            val id = if (existing == null) {
                dao.insert(PlaySessionEntity(0, session.uuid, game.id, session.playedAt, session.durationMinutes, session.memo, now, now))
            } else {
                dao.update(
                    existing.copy(
                        gameId = game.id, playedAt = session.playedAt, durationMinutes = session.durationMinutes,
                        memo = session.memo, updatedAt = now,
                    ),
                )
                existing.id
            }
            dao.deleteResults(id)
            dao.insertResults(
                session.results.distinctBy { it.playerUuid }.mapNotNull { r ->
                    dao.player(r.playerUuid)?.let { PlaySessionResultEntity(0, id, it.id, r.score, r.isWinner, r.rank) }
                },
            )
            dao.deleteSessionExpansions(id)
            dao.insertSessionExpansions(
                session.expansionUuids.distinct().mapNotNull { dao.game(it) }.filter { it.parentGameId == game.id }
                    .map { PlaySessionExpansionEntity(id, it.id) },
            )
        }
        afterChange()
    }

    suspend fun deleteSession(uuid: String) {
        dao.session(uuid)?.let { dao.deleteSession(it.id) }
        afterChange()
    }

    /** 貸出を保存する。未返却の貸出は 1 ゲーム 1 件まで。もう貸出中なら false(何も変えない)。 */
    suspend fun saveLending(lending: LendingRecord): Boolean {
        val saved = db.withTransaction {
            val now = now()
            val game = dao.game(lending.gameUuid) ?: return@withTransaction false
            val player = dao.player(lending.playerUuid) ?: return@withTransaction false
            val existing = dao.lending(lending.uuid)
            if (lending.returnedAt == null && dao.lendings().any { it.gameId == game.id && it.returnedAt == null && it.id != existing?.id }) {
                return@withTransaction false
            }
            if (existing == null) {
                dao.insert(
                    LendingRecordEntity(0, lending.uuid, game.id, player.id, lending.lentAt, lending.expectedReturnAt, lending.returnedAt, now, now),
                )
            } else {
                dao.update(
                    existing.copy(
                        gameId = game.id, playerId = player.id, lentAt = lending.lentAt,
                        expectedReturnAt = lending.expectedReturnAt, returnedAt = lending.returnedAt, updatedAt = now,
                    ),
                )
            }
            true
        }
        afterChange()
        return saved
    }

    suspend fun deleteLending(uuid: String) {
        dao.lending(uuid)?.let { dao.deleteLending(it.id) }
        afterChange()
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = db.withTransaction {
        dao.upsert(transform(settingsOnce()).toEntity())
    }

    // ---- バックアップ ------------------------------------------------------

    /**
     * 今のデータを全部消して、[content] に入れ替える(全置換と、追加取り込みの結果の書き込みの両方が使う)。
     * 途中で失敗したら全部が元に戻る(1 つのトランザクション)。数値の id は振り直しになるが、外には出していない。
     * 入れる順: タグ → プレイヤー → 本体 → 拡張 → 中間表 → プレイ → 結果と使った拡張 → 貸出 → 対応表。
     */
    suspend fun replaceAll(content: Library) {
        db.withTransaction {
            // 子の表(中間表・プレイ・結果・貸出)は外部キーのカスケードで消える
            dao.deleteAllGames()
            dao.deleteAllPlayers()
            dao.deleteAllTags()
            dao.deleteAllAliases()

            val tagId = content.genreTags.associate { it.uuid to dao.insert(GenreTagEntity(0, it.uuid, it.name, it.colorArgb, it.createdAt, it.updatedAt)) }
            val playerId = content.players.associate {
                it.uuid to dao.insert(PlayerEntity(0, it.uuid, it.name, it.colorArgb, it.memo, it.isSelf, it.createdAt, it.updatedAt))
            }
            val gameId = HashMap<String, Long>()
            content.games.sortedBy { it.isExpansion }.forEach { g ->
                gameId[g.uuid] = dao.insert(g.toEntity(0, g.parentUuid?.let(gameId::get), g.createdAt, g.updatedAt))
            }
            dao.insertGameTags(
                content.games.flatMap { g ->
                    val id = gameId.getValue(g.uuid)
                    g.genreTagUuids.distinct().mapNotNull { t -> tagId[t]?.let { GameGenreTagCrossRef(id, it) } }
                },
            )
            content.playSessions.forEach { s ->
                val game = gameId[s.gameUuid] ?: return@forEach
                val id = dao.insert(PlaySessionEntity(0, s.uuid, game, s.playedAt, s.durationMinutes, s.memo, s.createdAt, s.updatedAt))
                dao.insertResults(
                    s.results.distinctBy { it.playerUuid }.mapNotNull { r ->
                        playerId[r.playerUuid]?.let { PlaySessionResultEntity(0, id, it, r.score, r.isWinner, r.rank) }
                    },
                )
                dao.insertSessionExpansions(s.expansionUuids.distinct().mapNotNull { e -> gameId[e]?.let { PlaySessionExpansionEntity(id, it) } })
            }
            content.lendingRecords.forEach { l ->
                val game = gameId[l.gameUuid] ?: return@forEach
                val player = playerId[l.playerUuid] ?: return@forEach
                dao.insert(LendingRecordEntity(0, l.uuid, game, player, l.lentAt, l.expectedReturnAt, l.returnedAt, l.createdAt, l.updatedAt))
            }
            dao.insertAliases(
                content.uuidAliases.distinctBy { it.entityType to it.foreignUuid }.map { UuidAliasEntity(0, it.entityType, it.foreignUuid, it.localUuid) },
            )
            dao.upsert(content.settings.toEntity())
        }
        afterChange(photosMayHaveChanged = true)
    }

    private suspend fun afterChange(photosMayHaveChanged: Boolean = false) {
        if (photosMayHaveChanged) PhotoStore.pruneOrphans(appContext, dao.games().mapNotNull { it.boxPhotoFile }.toSet())
        LedgerWidget.refresh(appContext)
    }

    companion object {
        /** 初めての起動で入れる例(要件定義書 §4 の例)。名前も色も後から変えられる。 */
        private val DEFAULT_TAGS = listOf(
            "協力" to 0xFF2E7D32.toInt(),
            "対戦" to 0xFFB3325B.toInt(),
            "パーティー" to 0xFFE07A2F.toInt(),
        )

        fun newUuid(): String = UUID.randomUUID().toString()

        /** DB はミリ秒で持つので、メモリの上の値も最初からミリ秒に丸めておく。 */
        fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

        @Volatile
        private var instance: LedgerRepository? = null

        fun get(context: Context): LedgerRepository = instance ?: synchronized(this) {
            instance ?: LedgerRepository(context).also { instance = it }
        }
    }
}

fun Game.toEntity(id: Long, parentId: Long?, createdAt: Instant, updatedAt: Instant) = GameEntity(
    id = id, uuid = uuid, title = title, barcodeValue = barcodeValue, parentGameId = parentId, boxPhotoFile = boxPhotoFile,
    minPlayers = minPlayers, maxPlayers = maxPlayers, minPlayTimeMinutes = minPlayTimeMinutes, maxPlayTimeMinutes = maxPlayTimeMinutes,
    complexityRating = complexityRating, purchasePrice = purchasePrice, purchaseCurrency = purchaseCurrency,
    purchaseForeignAmount = purchaseForeignAmount, purchaseExchangeRate = purchaseExchangeRate, purchaseDate = purchaseDate,
    storageLocation = storageLocation, personalRating = personalRating, winCondition = winCondition, status = status,
    isArchived = isArchived, excludeFromSuggestion = excludeFromSuggestion, createdAt = createdAt, updatedAt = updatedAt,
)

fun AppSettingsEntity.toModel() = AppSettings(
    defaultViewMode = defaultViewMode, unplayedSuggestionThresholdDays = unplayedSuggestionThresholdDays,
    themeColorMode = themeColorMode, manualThemeColor = manualThemeColor, darkModeMode = darkModeMode,
    celebrationEnabled = celebrationEnabled, autoBackupEnabled = autoBackupEnabled, autoBackupIntervalDays = autoBackupIntervalDays,
    autoBackupKeepCount = autoBackupKeepCount, lastAutoBackupAt = lastAutoBackupAt, appLockEnabled = appLockEnabled,
    showExpansionsInList = showExpansionsInList,
)

fun AppSettings.toEntity() = AppSettingsEntity(
    id = AppSettingsEntity.SINGLE_ID, defaultViewMode = defaultViewMode,
    unplayedSuggestionThresholdDays = unplayedSuggestionThresholdDays, themeColorMode = themeColorMode,
    manualThemeColor = manualThemeColor, darkModeMode = darkModeMode, celebrationEnabled = celebrationEnabled,
    autoBackupEnabled = autoBackupEnabled, autoBackupIntervalDays = autoBackupIntervalDays,
    autoBackupKeepCount = autoBackupKeepCount, lastAutoBackupAt = lastAutoBackupAt, appLockEnabled = appLockEnabled,
    showExpansionsInList = showExpansionsInList,
)
