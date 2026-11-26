package io.github.takosasidev.boxledger.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * 集計と絞り込みは :core の Kotlin 側で行うので、素直な取り出しと書き込みだけを持つ。
 * 個人のコレクション(ゲーム数百、プレイ数千)なら、全件を読んでメモリの上で畳むほうが単純で速い。
 *
 * 子を持つ表に OnConflictStrategy.REPLACE は使わない。行を消して入れ直すので、
 * 外部キーのカスケードで子(プレイ記録など)が消える。@Insert と @Update を使い分ける。
 */
@Dao
interface LedgerDao {

    /**
     * どの表が変わっても流れる合図。値そのものは使わない。
     * Room は、問い合わせに出てくる表のどれかが変わるたびに流し直すので、全部の表を 1 回ずつ触っておく。
     */
    @Query(
        "SELECT (SELECT COUNT(*) FROM Game) + (SELECT COUNT(*) FROM Player) + (SELECT COUNT(*) FROM GenreTag) + " +
            "(SELECT COUNT(*) FROM GameGenreTagCrossRef) + (SELECT COUNT(*) FROM PlaySession) + " +
            "(SELECT COUNT(*) FROM PlaySessionResult) + (SELECT COUNT(*) FROM PlaySessionExpansion) + " +
            "(SELECT COUNT(*) FROM LendingRecord) + (SELECT COUNT(*) FROM UuidAlias) + (SELECT COUNT(*) FROM AppSettings)",
    )
    fun observeChanges(): Flow<Int>

    @Query("SELECT * FROM Game ORDER BY id") suspend fun games(): List<GameEntity>
    @Query("SELECT * FROM Player ORDER BY id") suspend fun players(): List<PlayerEntity>
    @Query("SELECT * FROM GenreTag ORDER BY id") suspend fun tags(): List<GenreTagEntity>
    @Query("SELECT * FROM GameGenreTagCrossRef ORDER BY gameId, genreTagId") suspend fun gameTags(): List<GameGenreTagCrossRef>
    @Query("SELECT * FROM PlaySession ORDER BY playedAt DESC, id DESC") suspend fun sessions(): List<PlaySessionEntity>
    @Query("SELECT * FROM PlaySessionResult ORDER BY id") suspend fun results(): List<PlaySessionResultEntity>
    @Query("SELECT * FROM PlaySessionExpansion ORDER BY playSessionId, expansionGameId") suspend fun sessionExpansions(): List<PlaySessionExpansionEntity>
    @Query("SELECT * FROM LendingRecord ORDER BY lentAt DESC, id DESC") suspend fun lendings(): List<LendingRecordEntity>
    @Query("SELECT * FROM UuidAlias ORDER BY id") suspend fun aliases(): List<UuidAliasEntity>
    @Query("SELECT * FROM AppSettings WHERE id = 1") suspend fun settings(): AppSettingsEntity?

    @Query("SELECT * FROM Game WHERE uuid = :uuid") suspend fun game(uuid: String): GameEntity?
    @Query("SELECT * FROM Player WHERE uuid = :uuid") suspend fun player(uuid: String): PlayerEntity?
    @Query("SELECT * FROM GenreTag WHERE uuid = :uuid") suspend fun tag(uuid: String): GenreTagEntity?
    @Query("SELECT * FROM PlaySession WHERE uuid = :uuid") suspend fun session(uuid: String): PlaySessionEntity?
    @Query("SELECT * FROM LendingRecord WHERE uuid = :uuid") suspend fun lending(uuid: String): LendingRecordEntity?

    @Insert suspend fun insert(row: GameEntity): Long
    @Insert suspend fun insert(row: PlayerEntity): Long
    @Insert suspend fun insert(row: GenreTagEntity): Long
    @Insert suspend fun insert(row: PlaySessionEntity): Long
    @Insert suspend fun insert(row: LendingRecordEntity): Long
    @Insert suspend fun insertGameTags(rows: List<GameGenreTagCrossRef>)
    @Insert suspend fun insertResults(rows: List<PlaySessionResultEntity>)
    @Insert suspend fun insertSessionExpansions(rows: List<PlaySessionExpansionEntity>)
    @Insert suspend fun insertAliases(rows: List<UuidAliasEntity>)

    @Update suspend fun update(row: GameEntity)
    @Update suspend fun update(row: PlayerEntity)
    @Update suspend fun update(row: GenreTagEntity)
    @Update suspend fun update(row: PlaySessionEntity)
    @Update suspend fun update(row: LendingRecordEntity)

    /** 設定は子を持たない 1 行なので、入れ直しても困らない。 */
    @Upsert suspend fun upsert(row: AppSettingsEntity)

    // 項目を変えるので updatedAt も進める(追加取り込みの新旧の判定に使う)
    @Query("UPDATE Player SET isSelf = 0, updatedAt = :now WHERE isSelf = 1 AND id != :exceptId")
    suspend fun clearOtherSelf(exceptId: Long, now: Instant)

    @Query("UPDATE Game SET parentGameId = NULL, updatedAt = :now WHERE parentGameId = :parentId")
    suspend fun detachExpansionsOf(parentId: Long, now: Instant)

    @Query("DELETE FROM Game WHERE id = :id") suspend fun deleteGame(id: Long)
    @Query("DELETE FROM Game WHERE parentGameId = :parentId") suspend fun deleteExpansionsOf(parentId: Long)
    @Query("DELETE FROM Player WHERE id = :id") suspend fun deletePlayer(id: Long)
    @Query("DELETE FROM GenreTag WHERE id = :id") suspend fun deleteTag(id: Long)
    @Query("DELETE FROM PlaySession WHERE id = :id") suspend fun deleteSession(id: Long)
    @Query("DELETE FROM LendingRecord WHERE id = :id") suspend fun deleteLending(id: Long)
    @Query("DELETE FROM GameGenreTagCrossRef WHERE gameId = :gameId") suspend fun deleteGameTags(gameId: Long)
    @Query("DELETE FROM PlaySessionResult WHERE playSessionId = :sessionId") suspend fun deleteResults(sessionId: Long)
    @Query("DELETE FROM PlaySessionExpansion WHERE playSessionId = :sessionId") suspend fun deleteSessionExpansions(sessionId: Long)

    // 全置換・追加取り込みの前に全部を消す。子の表はカスケードで消える
    @Query("DELETE FROM Game") suspend fun deleteAllGames()
    @Query("DELETE FROM Player") suspend fun deleteAllPlayers()
    @Query("DELETE FROM GenreTag") suspend fun deleteAllTags()
    @Query("DELETE FROM UuidAlias") suspend fun deleteAllAliases()
}

/**
 * FR-27: 版を上げるときは、必ず [MIGRATIONS] に Migration を足し、`app/schemas/` にできた JSON を commit する。
 * fallbackToDestructiveMigration() は使わない(更新で記録が黙って消えるため)。
 * 版 1 から始めているので、今は Migration が 1 つも無い。
 */
@Database(
    entities = [
        GameEntity::class,
        PlayerEntity::class,
        GenreTagEntity::class,
        GameGenreTagCrossRef::class,
        PlaySessionEntity::class,
        PlaySessionResultEntity::class,
        PlaySessionExpansionEntity::class,
        LendingRecordEntity::class,
        UuidAliasEntity::class,
        AppSettingsEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        const val VERSION = 1
        const val NAME = "boxledger.db"

        val MIGRATIONS: Array<Migration> = arrayOf()

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
                .also { instance = it }
        }
    }
}
