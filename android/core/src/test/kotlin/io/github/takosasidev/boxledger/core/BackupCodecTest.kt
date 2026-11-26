// バックアップの形式(Windows 版の要件定義書 §6)の読み書きと検証。ゲームの名前は全部作り物。
package io.github.takosasidev.boxledger.core

import io.github.takosasidev.boxledger.core.backup.BackupCodec
import io.github.takosasidev.boxledger.core.backup.DecodeResult
import io.github.takosasidev.boxledger.core.backup.Json
import io.github.takosasidev.boxledger.core.backup.JsonException
import io.github.takosasidev.boxledger.core.model.AliasType
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
import io.github.takosasidev.boxledger.core.model.UuidAlias
import io.github.takosasidev.boxledger.core.model.ViewMode
import io.github.takosasidev.boxledger.core.model.WinCondition
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupCodecTest {

    private val at = Instant.parse("2026-11-20T01:02:03.456Z")

    /** 全部の項目に値を入れた見本。 */
    private val full = Library(
        genreTags = listOf(GenreTag("t1", "協力", 0xFF4CAF50.toInt(), at, at)),
        players = listOf(
            Player("p1", "自分", 0xFF2196F3.toInt(), null, true, at, at),
            Player("p2", "友人A", 0x80112233.toInt(), "メモ \"引用\" と\n改行", false, at, at.plusSeconds(5)),
        ),
        games = listOf(
            Game(
                uuid = "g1", title = "架空の開拓ゲーム", barcodeValue = "4900000000017", boxPhotoFile = "a1.jpg",
                minPlayers = 3, maxPlayers = 4, minPlayTimeMinutes = 60, maxPlayTimeMinutes = 90,
                complexityRating = 2.3, purchasePrice = 8959, purchaseCurrency = "USD",
                purchaseForeignAmount = BigDecimal("59.99"), purchaseExchangeRate = BigDecimal("149.35"),
                purchaseDate = LocalDate.parse("2025-12-24"), storageLocation = "棚A", personalRating = 5,
                genreTagUuids = listOf("t1"), winCondition = WinCondition.LOW_SCORE, status = GameStatus.OWNED,
                isArchived = true, excludeFromSuggestion = true, createdAt = at, updatedAt = at.plusSeconds(1),
            ),
            // 書き出しは本体を先に並べるので、往復で順番が変わらないように、見本も本体を先に置く
            Game(uuid = "g3", title = "ほしい物", status = GameStatus.WISHLIST, createdAt = at, updatedAt = at),
            Game(uuid = "g2", title = "架空の開拓ゲーム 拡張", parentUuid = "g1", createdAt = at, updatedAt = at),
        ),
        playSessions = listOf(
            PlaySession(
                "s1", "g1", LocalDate.parse("2026-09-20"), 75, "楽しかった", listOf("g2"),
                listOf(PlayResult("p1", 10.0, true, 1), PlayResult("p2", 7.5, false, 2)), at, at,
            ),
        ),
        lendingRecords = listOf(
            LendingRecord("l1", "g1", "p2", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), null, at, at),
        ),
        uuidAliases = listOf(UuidAlias(AliasType.PLAYER, "far-1", "p2")),
        settings = AppSettings(
            defaultViewMode = ViewMode.LIST, unplayedSuggestionThresholdDays = 30, themeColorMode = ThemeColorMode.MANUAL,
            manualThemeColor = 0xFF3F51B5.toInt(), darkModeMode = DarkModeMode.DARK, celebrationEnabled = false,
        ),
    )

    private fun manifest(lib: Library = full) = BackupCodec.manifestJson(lib, "0.1.0", at, 1)

    private fun decode(data: String, photos: Set<String> = setOf("a1.jpg"), manifest: String = manifest()): DecodeResult =
        BackupCodec.decode(manifest, data, photos)

    private fun ok(data: String, photos: Set<String> = setOf("a1.jpg")): DecodeResult.Ok = assertIs<DecodeResult.Ok>(decode(data, photos))

    @Test
    fun 全部の項目が往復する() {
        val result = ok(BackupCodec.dataJson(full))
        assertEquals(full, result.library)
        assertEquals(emptyList(), result.warnings)
        assertEquals(at, result.exportedAt)
        assertEquals("android", result.sourcePlatform)
    }

    @Test
    fun 空のデータも往復する() {
        val empty = Library()
        assertEquals(empty, ok(BackupCodec.dataJson(empty)).library)
    }

    @Test
    fun 書き出しは仕様の形になる() {
        val root = Json.parse(BackupCodec.dataJson(full)) as Map<*, *>
        val games = root["games"] as List<*>
        val g1 = games[0] as Map<*, *>
        // 未返却の貸出があるゲームは LENT_OUT、色は #AARRGGBB、写真は photos/ から、外貨は数値
        assertEquals("LENT_OUT", g1["status"])
        assertEquals("photos/a1.jpg", g1["boxPhoto"])
        assertEquals(BigDecimal("59.99"), g1["purchaseForeignAmount"])
        assertEquals("2025-12-24", g1["purchaseDate"])
        assertEquals("2026-11-20T01:02:03.456Z", g1["createdAt"])
        assertEquals("#FF4CAF50", ((root["genreTags"] as List<*>)[0] as Map<*, *>)["color"])
        assertEquals("#80112233", ((root["players"] as List<*>)[1] as Map<*, *>)["color"])
        // 数値の id は出さない
        assertTrue(games.none { (it as Map<*, *>).containsKey("id") })
        // 本体が先、拡張が後
        val reordered = full.copy(games = full.games.reversed())
        val titles = ((Json.parse(BackupCodec.dataJson(reordered)) as Map<*, *>)["games"] as List<*>).map { (it as Map<*, *>)["parentGameUuid"] }
        assertEquals(listOf(null, null, "g1"), titles)

        val m = Json.parse(manifest()) as Map<*, *>
        assertEquals("boxledger-backup", m["format"])
        assertEquals(BigDecimal(1), m["formatVersion"])
        assertEquals(BigDecimal(3), (m["counts"] as Map<*, *>)["games"])
        assertEquals(BigDecimal(1), (m["counts"] as Map<*, *>)["photos"])
    }

    @Test
    fun 相手の書き方の違いを吸収する() {
        // 小数 7 桁の日時、大文字の uuid、末尾に 0 のある金額、知らない項目、無い項目
        val data = """
            {
              "futureSection": [1, 2, {"x": null}],
              "players": [ { "uuid": "P-ABC", "name": "相手", "color": "#FF000000", "isSelf": false, "updatedAt": "2026-11-20T01:02:03.4567891Z" } ],
              "games": [ { "uuid": "G-ABC", "title": "架空のゲーム", "purchaseCurrency": "eur",
                           "purchaseForeignAmount": 59.990, "purchaseExchangeRate": 160.0, "unknownField": true,
                           "status": "OWNED", "updatedAt": "2026-11-20T01:02:03.4567891Z" } ],
              "playSessions": [ { "uuid": "S-1", "gameUuid": "g-abc", "playedAt": "2026-01-02",
                                  "results": [ { "playerUuid": "p-abc", "score": 10, "isWinner": true } ] } ]
            }
        """.trimIndent()
        val lib = ok(data).library
        val game = lib.games.single()
        assertEquals("g-abc", game.uuid)
        assertEquals(Instant.parse("2026-11-20T01:02:03.456Z"), game.updatedAt)
        assertEquals(Instant.EPOCH, game.createdAt)
        assertEquals("EUR", game.purchaseCurrency)
        assertEquals(BigDecimal("59.99"), game.purchaseForeignAmount)
        assertEquals(WinCondition.HIGH_SCORE, game.winCondition)
        assertEquals("p-abc", lib.playSessions.single().results.single().playerUuid)
        assertEquals(10.0, lib.playSessions.single().results.single().score)
        assertEquals(AppSettings(), lib.settings)
        // 読んだ物をもう一度書いて読んでも変わらない
        assertEquals(lib, assertIs<DecodeResult.Ok>(BackupCodec.decode(manifest(lib), BackupCodec.dataJson(lib), emptySet())).library)
    }

    @Test
    fun LENT_OUT_は所有中に戻し_貸出記録が無ければ警告する() {
        val data = """{"games":[{"uuid":"g1","title":"架空のゲーム","status":"LENT_OUT"}]}"""
        val result = ok(data)
        assertEquals(GameStatus.OWNED, result.library.games.single().status)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun 範囲の外の値は未設定にして警告する() {
        val data = """{"games":[{"uuid":"g1","title":"架空のゲーム","minPlayers":5,"maxPlayers":2,"personalRating":9,
            "complexityRating":0.5,"purchasePrice":-1,"boxPhoto":"photos/../../evil.jpg","status":"SOLD"}]}"""
        val result = ok(data)
        val g = result.library.games.single()
        assertNull(g.minPlayers)
        assertNull(g.maxPlayers)
        assertNull(g.personalRating)
        assertNull(g.complexityRating)
        assertNull(g.purchasePrice)
        assertNull(g.boxPhotoFile)
        assertEquals(GameStatus.OWNED, g.status)
        assertEquals(6, result.warnings.size)
    }

    @Test
    fun zip_に無い写真は写真なしにする() {
        val result = ok(BackupCodec.dataJson(full), photos = emptySet())
        assertNull(result.library.games.first { it.uuid == "g1" }.boxPhotoFile)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun 直して取り込む物() {
        val data = """{
            "genreTags":[{"uuid":"t1","name":"協力"},{"uuid":"t2","name":"協力"}],
            "players":[{"uuid":"p1","name":"A","isSelf":true},{"uuid":"p2","name":"B","isSelf":true}],
            "games":[{"uuid":"g1","title":"本体1"},{"uuid":"g2","title":"本体2"},{"uuid":"e2","title":"拡張","parentGameUuid":"g2"}],
            "playSessions":[{"uuid":"s1","gameUuid":"g1","playedAt":"2026-01-01","expansionUuids":["e2"],
                "results":[{"playerUuid":"p1"},{"playerUuid":"p1","score":3}]}],
            "lendingRecords":[{"uuid":"l1","gameUuid":"g1","playerUuid":"p2","lentAt":"2026-01-01"},
                              {"uuid":"l2","gameUuid":"g1","playerUuid":"p2","lentAt":"2026-01-02"}]
        }"""
        val result = ok(data)
        val lib = result.library
        assertEquals(listOf("協力", "協力 (2)"), lib.genreTags.map { it.name })
        assertEquals(listOf(true, false), lib.players.map { it.isSelf })
        assertEquals(emptyList(), lib.playSessions.single().expansionUuids)
        assertEquals(1, lib.playSessions.single().results.size)
        assertEquals(listOf("l1"), lib.lendingRecords.map { it.uuid })
        assertEquals(5, result.warnings.size)
    }

    @Test
    fun 致命的な誤りは何も取り込まない() {
        fun fatal(data: String?) = assertIs<DecodeResult.Fatal>(decode(data ?: "", manifest = manifest())).errors
        assertIs<DecodeResult.Fatal>(BackupCodec.decode(manifest(), null, emptySet()))
        fatal("{ こわれた")
        fatal("[1,2]")
        fatal("""{"games":{}}""")
        fatal("""{"games":[{"title":"uuid が無い"}]}""")
        fatal("""{"games":[{"uuid":"g1"}]}""")
        fatal("""{"games":[{"uuid":"g1","title":"a"},{"uuid":"G1","title":"b"}]}""")
        fatal("""{"games":[{"uuid":"g1","title":"a","parentGameUuid":"nai"}]}""")
        fatal("""{"games":[{"uuid":"g1","title":"a"},{"uuid":"g2","title":"b","parentGameUuid":"g1"},{"uuid":"g3","title":"c","parentGameUuid":"g2"}]}""")
        fatal("""{"games":[{"uuid":"g1","title":"a","genreTagUuids":["nai"]}]}""")
        fatal("""{"games":[{"uuid":"g1","title":"a"}],"playSessions":[{"uuid":"s1","gameUuid":"g1","playedAt":"きのう"}]}""")
        fatal("""{"playSessions":[{"uuid":"s1","gameUuid":"nai","playedAt":"2026-01-01"}]}""")
        fatal("""{"games":[{"uuid":"g1","title":"a"}],"playSessions":[{"uuid":"s1","gameUuid":"g1","playedAt":"2026-01-01","results":[{"playerUuid":"nai"}]}]}""")
        fatal("""{"games":[{"uuid":"g1","title":"a"}],"lendingRecords":[{"uuid":"l1","gameUuid":"g1","playerUuid":"nai","lentAt":"2026-01-01"}]}""")
        // 誤りは全部まとめて返す
        assertEquals(2, fatal("""{"games":[{"uuid":"g1"},{"title":"x"}]}""").size)
    }

    @Test
    fun manifest_の確かめ() {
        fun manifest(format: String = "boxledger-backup", version: String = "1") = """{"format":"$format","formatVersion":$version}"""
        assertNull(BackupCodec.checkManifest(manifest()))
        // 新しい版だけを断り、アプリの更新を案内する
        assertTrue(BackupCodec.checkManifest(manifest(version = "2"))!!.contains("アプリを更新"))
        assertTrue(BackupCodec.checkManifest(manifest(version = "0")) != null)
        assertTrue(BackupCodec.checkManifest(manifest(version = "\"x\"")) != null)
        assertTrue(BackupCodec.checkManifest(manifest(format = "other")) != null)
        assertTrue(BackupCodec.checkManifest(null) != null)
        assertTrue(BackupCodec.checkManifest("こわれた") != null)
        assertIs<DecodeResult.Fatal>(BackupCodec.decode(manifest(version = "2"), "{}", emptySet()))
    }

    @Test
    fun 色の文字() {
        assertEquals("#FF4CAF50", BackupCodec.colorText(0xFF4CAF50.toInt()))
        assertEquals("#00000001", BackupCodec.colorText(1))
        assertEquals(0xFF4CAF50.toInt(), BackupCodec.parseColor("#FF4CAF50"))
        assertEquals(0xFF4CAF50.toInt(), BackupCodec.parseColor("#4caf50"))
        assertNull(BackupCodec.parseColor("green"))
    }

    @Test
    fun JSON_の読み書き() {
        val value = mapOf("s" to "a\"b\\c\n\t\u0001あ", "n" to BigDecimal("-12.50"), "b" to true, "z" to null, "l" to listOf(1, 2.5, emptyList<Any>(), emptyMap<String, Any>()))
        val back = Json.parse(Json.write(value)) as Map<*, *>
        assertEquals("a\"b\\c\n\t\u0001あ", back["s"])
        assertEquals(BigDecimal("-12.50"), back["n"])
        assertEquals(true, back["b"])
        assertTrue(back.containsKey("z") && back["z"] == null)
        assertEquals(listOf(BigDecimal(1), BigDecimal("2.5"), emptyList<Any>(), emptyMap<String, Any>()), back["l"])
        assertEquals("é", Json.parse("\uFEFF\"\\u00e9\""))
        listOf("", "{", "{\"a\"}", "[1,]", "tru", "\"abc", "1 2", "1e999999999", "[".repeat(100)).forEach {
            assertFailsWith<JsonException>(it) { Json.parse(it) }
        }
    }
}
