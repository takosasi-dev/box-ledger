// 追加取り込み(Windows 版の要件定義書 §6.6)。ゲームの名前は全部作り物。
package io.github.takosasidev.boxledger.core

import io.github.takosasidev.boxledger.core.backup.BackupCodec
import io.github.takosasidev.boxledger.core.backup.DecodeResult
import io.github.takosasidev.boxledger.core.backup.Merge
import io.github.takosasidev.boxledger.core.backup.MergeCount
import io.github.takosasidev.boxledger.core.model.AliasType
import io.github.takosasidev.boxledger.core.model.AppSettings
import io.github.takosasidev.boxledger.core.model.Game
import io.github.takosasidev.boxledger.core.model.GenreTag
import io.github.takosasidev.boxledger.core.model.LendingRecord
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.core.model.PlayResult
import io.github.takosasidev.boxledger.core.model.PlaySession
import io.github.takosasidev.boxledger.core.model.Player
import io.github.takosasidev.boxledger.core.model.UuidAlias
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MergeTest {

    private val old = Instant.parse("2026-01-01T00:00:00Z")
    private val new = Instant.parse("2026-06-01T00:00:00Z")
    private val day = LocalDate.parse("2026-05-05")

    private fun session(uuid: String, game: String, vararg players: String, at: Instant = old, expansions: List<String> = emptyList()) =
        PlaySession(uuid, game, day, results = players.map { PlayResult(it) }, expansionUuids = expansions, createdAt = at, updatedAt = at)

    /** スマホの側: 「自分」と、ゲーム 1 つと、プレイ 3 件。 */
    private val phone = Library(
        genreTags = listOf(GenreTag("t-phone", "協力", 1, old, old)),
        players = listOf(Player("me-phone", "わたし", 1, isSelf = true, createdAt = old, updatedAt = old)),
        games = listOf(Game("g1", "架空のゲームA", genreTagUuids = listOf("t-phone"), createdAt = old, updatedAt = old)),
        playSessions = listOf(session("s1", "g1", "me-phone"), session("s2", "g1", "me-phone"), session("s3", "g1", "me-phone")),
        settings = AppSettings(unplayedSuggestionThresholdDays = 30),
    )

    /** PC の側: 別に作った「自分」と同じ名前のタグ、ゲームを 2 つ追加。 */
    private val pc = Library(
        genreTags = listOf(GenreTag("t-pc", "協力", 2, old, new)),
        players = listOf(Player("me-pc", "自分", 2, isSelf = true, createdAt = old, updatedAt = old)),
        games = listOf(
            Game("g1", "架空のゲームA", createdAt = old, updatedAt = old),
            Game("g2", "架空のゲームB", genreTagUuids = listOf("t-pc"), boxPhotoFile = "b.jpg", createdAt = new, updatedAt = new),
            Game("g3", "架空のゲームC", createdAt = new, updatedAt = new),
        ),
        playSessions = listOf(session("s9", "g2", "me-pc", at = new)),
        settings = AppSettings(unplayedSuggestionThresholdDays = 999),
    )

    @Test
    fun 相手で増えた分が足され_こちらの記録は残る() {
        val r = Merge.merge(phone, pc)
        assertEquals(MergeCount(added = 2, unchanged = 1), r.games)
        assertEquals(3, r.library.games.size)
        assertTrue(r.library.playSessions.map { it.uuid }.containsAll(listOf("s1", "s2", "s3", "s9")))
        // 設定は取り込まない
        assertEquals(30, r.library.settings.unplayedSuggestionThresholdDays)
        // 追加したゲームの写真だけを写す
        assertEquals(setOf("b.jpg"), r.photosToCopy)
        assertEquals(emptyList(), r.candidates)
    }

    @Test
    fun 自分とタグ名は確認せずに同じ物にして_参照を読み替える() {
        val r = Merge.merge(phone, pc)
        // 「自分」は 1 人のまま。相手の「自分」が出たプレイは、こちらの「自分」に付く
        assertEquals(listOf("me-phone"), r.library.players.map { it.uuid })
        assertEquals("me-phone", r.library.playSessions.first { it.uuid == "s9" }.results.single().playerUuid)
        // タグは 1 つのまま。新しい方(相手)の色になり、uuid はこちらの物
        assertEquals(listOf(GenreTag("t-phone", "協力", 2, old, new)), r.library.genreTags)
        assertEquals(listOf("t-phone"), r.library.game("g2")!!.genreTagUuids)
        assertEquals(
            setOf(UuidAlias(AliasType.GENRE_TAG, "t-pc", "t-phone"), UuidAlias(AliasType.PLAYER, "me-pc", "me-phone")),
            r.library.uuidAliases.toSet(),
        )
        assertEquals(MergeCount(updated = 1), r.tags)
        assertEquals(MergeCount(unchanged = 1), r.players)
    }

    @Test
    fun 同じ_zip_の_2_回目は全部変更なし() {
        val once = Merge.merge(phone, pc)
        val twice = Merge.merge(once.library, pc)
        assertEquals(once.library, twice.library)
        assertTrue(!twice.changed)
        assertEquals(MergeCount(unchanged = 3), twice.games)
        assertEquals(MergeCount(unchanged = 1), twice.sessions)
        assertEquals(emptySet(), twice.photosToCopy)
    }

    @Test
    fun 相手の日時が細かい桁でも_2_回目は変更なし() {
        // 相手は小数 7 桁で書く。読むときにミリ秒へ丸めるので、取り込んだ後の値と同じになる
        val data = """{"games":[{"uuid":"g1","title":"架空のゲームA 改","updatedAt":"2026-06-01T00:00:00.1234567Z"}]}"""
        val incoming = assertIs<DecodeResult.Ok>(BackupCodec.decode(BackupCodec.manifestJson(Library(), "1", new, 0), data, emptySet())).library
        val once = Merge.merge(phone, incoming)
        assertEquals(MergeCount(updated = 1), once.games)
        assertEquals("架空のゲームA 改", once.library.game("g1")!!.title)
        // DB を通った後と同じ形(書いて読む)にしてから、もう一度取り込む
        val stored = assertIs<DecodeResult.Ok>(
            BackupCodec.decode(BackupCodec.manifestJson(once.library, "1", new, 0), BackupCodec.dataJson(once.library), emptySet()),
        ).library
        assertEquals(MergeCount(unchanged = 1), Merge.merge(stored, incoming).games)
    }

    @Test
    fun 新しい方を採る() {
        val local = Library(games = listOf(Game("g1", "手元", boxPhotoFile = "mine.jpg", updatedAt = new), Game("g2", "手元2", updatedAt = old)))
        val incoming = Library(games = listOf(Game("g1", "相手", updatedAt = old), Game("g2", "相手2", updatedAt = new)))
        val r = Merge.merge(local, incoming)
        assertEquals("手元", r.library.game("g1")!!.title)
        assertEquals("相手2", r.library.game("g2")!!.title)
        assertEquals(MergeCount(updated = 1, unchanged = 1), r.games)
        // 相手に写真が無く、こちらにだけあるなら残す
        val r2 = Merge.merge(local, Library(games = listOf(Game("g1", "相手", updatedAt = new.plusSeconds(1)))))
        assertEquals("mine.jpg", r2.library.game("g1")!!.boxPhotoFile)
    }

    @Test
    fun 同じ名前のプレイヤーは確認して_既定は同じ物() {
        val local = Library(players = listOf(Player("a1", "友人A", 1, updatedAt = old)), games = listOf(Game("g1", "架空のゲームA")))
        val incoming = Library(
            players = listOf(Player("a2", "友人A", 9, updatedAt = new)),
            games = listOf(Game("g1", "架空のゲームA")),
            playSessions = listOf(session("s1", "g1", "a2")),
            lendingRecords = listOf(LendingRecord("l1", "g1", "a2", day)),
        )
        val same = Merge.merge(local, incoming)
        assertEquals(1, same.candidates.size)
        assertEquals("a1", same.candidates.single().localUuid)
        assertEquals(listOf(Player("a1", "友人A", 9, updatedAt = new)), same.library.players)
        assertEquals("a1", same.library.playSessions.single().results.single().playerUuid)
        assertEquals("a1", same.library.lendingRecords.single().playerUuid)
        // 次からは確認しない
        assertEquals(emptyList(), Merge.merge(same.library, incoming).candidates)

        // 「別の物」を選んだら、相手の uuid のまま足す。次からは uuid で合う
        val separate = Merge.merge(local, incoming, separate = setOf("a2"))
        assertEquals(listOf("a1", "a2"), separate.library.players.map { it.uuid })
        assertEquals(emptyList(), separate.library.uuidAliases)
        assertEquals(emptyList(), Merge.merge(separate.library, incoming).candidates)
    }

    @Test
    fun ゲームはバーコードかタイトルが同じで_本体も同じなら確認する() {
        val local = Library(
            games = listOf(
                Game("base", "架空のゲームA", barcodeValue = "4900000000017"),
                Game("ex", "追加セット", parentUuid = "base"),
                Game("other", "追加セット"),
            ),
        )
        val incoming = Library(
            games = listOf(
                // 拡張を先に書いてあっても、本体から処理する
                Game("ex2", "追加セット", parentUuid = "base2", updatedAt = new),
                Game("base2", "なまえ違い", barcodeValue = "4900000000017"),
            ),
            playSessions = listOf(session("s1", "base2", expansions = listOf("ex2"))),
        )
        val r = Merge.merge(local, incoming)
        assertEquals(listOf("base" to "base2", "ex" to "ex2"), r.candidates.map { it.localUuid to it.incomingUuid })
        assertEquals(3, r.library.games.size)
        assertEquals("base", r.library.game("ex")!!.parentUuid)
        val s = r.library.playSessions.single()
        assertEquals("base", s.gameUuid)
        assertEquals(listOf("ex"), s.expansionUuids)

        // 本体を「別の物」にすると、拡張は本体が違うので候補にならず、別の物として足される
        val r2 = Merge.merge(local, incoming, separate = setOf("base2"))
        assertEquals(5, r2.library.games.size)
        assertEquals("base2", r2.library.game("ex2")!!.parentUuid)
    }

    @Test
    fun 未返却の貸出が両方にあれば_相手の分を取り込まない() {
        val games = listOf(Game("g1", "架空のゲームA"))
        val players = listOf(Player("p1", "友人A", 1))
        val local = Library(players = players, games = games, lendingRecords = listOf(LendingRecord("l1", "g1", "p1", day)))
        val incoming = Library(
            players = players, games = games,
            lendingRecords = listOf(LendingRecord("l2", "g1", "p1", day), LendingRecord("l3", "g1", "p1", day, returnedAt = day)),
        )
        val r = Merge.merge(local, incoming)
        assertEquals(listOf("l1", "l3"), r.library.lendingRecords.map { it.uuid })
        assertEquals(1, r.warnings.size)
        assertEquals(MergeCount(added = 1), r.lendings)
    }

    @Test
    fun 相手が返してもらって貸し直した記録は_並び順によらず取り込む() {
        val games = listOf(Game("g1", "架空のゲームA"))
        val players = listOf(Player("p1", "友人A", 1))
        val local = Library(players = players, games = games, lendingRecords = listOf(LendingRecord("l1", "g1", "p1", day, updatedAt = old)))
        // 書き出しは新しい貸出が先に並ぶ: 新しい未返却の l2 → 返却済みになった l1
        val incoming = Library(
            players = players, games = games,
            lendingRecords = listOf(
                LendingRecord("l2", "g1", "p1", day.plusDays(9), updatedAt = new),
                LendingRecord("l1", "g1", "p1", day, returnedAt = day.plusDays(5), updatedAt = new),
            ),
        )
        val r = Merge.merge(local, incoming)
        assertEquals(emptyList(), r.warnings)
        assertEquals(MergeCount(added = 1, updated = 1), r.lendings)
        assertEquals(day.plusDays(5), r.library.lendingRecords.first { it.uuid == "l1" }.returnedAt)
        assertNull(r.library.lendingRecords.first { it.uuid == "l2" }.returnedAt)
        assertTrue(!Merge.merge(r.library, incoming).changed)
    }

    @Test
    fun プレイ記録の上書きは結果ごと置き換える() {
        val base = Library(players = listOf(Player("p1", "A", 1), Player("p2", "B", 1)), games = listOf(Game("g1", "架空のゲームA")))
        val local = base.copy(playSessions = listOf(session("s1", "g1", "p1", "p2")))
        val incoming = base.copy(playSessions = listOf(session("s1", "g1", "p2", at = new).copy(memo = "直した")))
        val s = Merge.merge(local, incoming).library.playSessions.single()
        assertEquals(listOf("p2"), s.results.map { it.playerUuid })
        assertEquals("直した", s.memo)
    }

    @Test
    fun 削除は伝わらず_相手に残っていれば戻る() {
        val r = Merge.merge(Library(), Library(games = listOf(Game("g1", "架空のゲームA"))))
        assertEquals(MergeCount(added = 1), r.games)
        assertNull(r.library.game("nai"))
    }
}
