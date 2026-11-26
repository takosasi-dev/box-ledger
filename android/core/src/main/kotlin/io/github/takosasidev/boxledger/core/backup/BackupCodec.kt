// バックアップ zip の中身(manifest.json / data.json)の組み立て・読み取り・検証。
// 形式は Windows 版の要件定義書 §6 の文から作った。zip そのものの読み書きは :app(Zip4j)。
package io.github.takosasidev.boxledger.core.backup

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
import java.time.temporal.ChronoUnit

/** 読み取りの結果。致命的な誤りが 1 件でもあれば [Fatal] で、何も取り込まない(§6.5)。 */
sealed interface DecodeResult {
    data class Ok(
        val library: Library,
        /** 直して取り込んだ所。結果の画面に出す。 */
        val warnings: List<String>,
        val exportedAt: Instant?,
        val sourcePlatform: String?,
    ) : DecodeResult

    data class Fatal(val errors: List<String>) : DecodeResult
}

object BackupCodec {

    const val FORMAT = "boxledger-backup"
    const val FORMAT_VERSION = 1
    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_DATA = "data.json"
    const val PHOTOS_DIR = "photos/"

    /** 色が読めないときの代わり(灰色)。 */
    private val FALLBACK_COLOR = 0xFF757575.toInt()

    // ---- 書き出し ----------------------------------------------------------

    fun manifestJson(library: Library, appVersion: String, exportedAt: Instant, photoCount: Int): String = Json.write(
        linkedMapOf(
            "format" to FORMAT,
            "formatVersion" to FORMAT_VERSION,
            "sourcePlatform" to "android",
            "appVersion" to appVersion,
            "exportedAt" to instantText(exportedAt),
            "counts" to linkedMapOf(
                "games" to library.games.size,
                "players" to library.players.size,
                "genreTags" to library.genreTags.size,
                "playSessions" to library.playSessions.size,
                "lendingRecords" to library.lendingRecords.size,
                "photos" to photoCount,
            ),
        ),
    )

    fun dataJson(library: Library): String {
        val lent = library.lendingRecords.filter { it.returnedAt == null }.map { it.gameUuid }.toSet()
        return Json.write(
            linkedMapOf(
                "genreTags" to library.genreTags.map { t ->
                    linkedMapOf(
                        "uuid" to t.uuid, "name" to t.name, "color" to colorText(t.colorArgb),
                        "createdAt" to instantText(t.createdAt), "updatedAt" to instantText(t.updatedAt),
                    )
                },
                "players" to library.players.map { p ->
                    linkedMapOf(
                        "uuid" to p.uuid, "name" to p.name, "color" to colorText(p.colorArgb), "memo" to p.memo, "isSelf" to p.isSelf,
                        "createdAt" to instantText(p.createdAt), "updatedAt" to instantText(p.updatedAt),
                    )
                },
                // 本体を先、拡張を後に並べる(§6.4)
                "games" to library.games.sortedBy { it.isExpansion }.map { g ->
                    linkedMapOf(
                        "uuid" to g.uuid, "title" to g.title, "parentGameUuid" to g.parentUuid, "barcodeValue" to g.barcodeValue,
                        "boxPhoto" to g.boxPhotoFile?.let { PHOTOS_DIR + it },
                        "minPlayers" to g.minPlayers, "maxPlayers" to g.maxPlayers,
                        "minPlayTimeMinutes" to g.minPlayTimeMinutes, "maxPlayTimeMinutes" to g.maxPlayTimeMinutes,
                        "complexityRating" to g.complexityRating, "purchasePrice" to g.purchasePrice,
                        "purchaseDate" to g.purchaseDate?.toString(),
                        "purchaseCurrency" to g.purchaseCurrency,
                        "purchaseForeignAmount" to g.purchaseForeignAmount, "purchaseExchangeRate" to g.purchaseExchangeRate,
                        "storageLocation" to g.storageLocation, "personalRating" to g.personalRating,
                        "genreTagUuids" to g.genreTagUuids, "winCondition" to g.winCondition.name,
                        // 未返却の貸出があるゲームは LENT_OUT で出す(§6.4。古い Android 版の形との互換)
                        "status" to if (g.status == GameStatus.OWNED && g.uuid in lent) "LENT_OUT" else g.status.name,
                        "isArchived" to g.isArchived, "excludeFromSuggestion" to g.excludeFromSuggestion,
                        "createdAt" to instantText(g.createdAt), "updatedAt" to instantText(g.updatedAt),
                    )
                },
                "playSessions" to library.playSessions.map { s ->
                    linkedMapOf(
                        "uuid" to s.uuid, "gameUuid" to s.gameUuid, "playedAt" to s.playedAt.toString(),
                        "durationMinutes" to s.durationMinutes, "memo" to s.memo,
                        "expansionUuids" to s.expansionUuids,
                        "results" to s.results.map { r ->
                            linkedMapOf("playerUuid" to r.playerUuid, "score" to r.score, "isWinner" to r.isWinner, "rank" to r.rank)
                        },
                        "createdAt" to instantText(s.createdAt), "updatedAt" to instantText(s.updatedAt),
                    )
                },
                "lendingRecords" to library.lendingRecords.map { l ->
                    linkedMapOf(
                        "uuid" to l.uuid, "gameUuid" to l.gameUuid, "playerUuid" to l.playerUuid,
                        "lentAt" to l.lentAt.toString(), "expectedReturnAt" to l.expectedReturnAt?.toString(),
                        "returnedAt" to l.returnedAt?.toString(),
                        "createdAt" to instantText(l.createdAt), "updatedAt" to instantText(l.updatedAt),
                    )
                },
                "uuidAliases" to library.uuidAliases.map { a ->
                    linkedMapOf("entityType" to a.entityType.name, "foreignUuid" to a.foreignUuid, "localUuid" to a.localUuid)
                },
                "settings" to library.settings.let { s ->
                    linkedMapOf(
                        "defaultViewMode" to s.defaultViewMode.name,
                        "unplayedSuggestionThresholdDays" to s.unplayedSuggestionThresholdDays,
                        "darkModeMode" to s.darkModeMode.name,
                        "themeColorMode" to s.themeColorMode.name,
                        "manualThemeColor" to s.manualThemeColor?.let(::colorText),
                        "celebrationEnabled" to s.celebrationEnabled,
                    )
                },
            ),
        )
    }

    // ---- 読み取り ----------------------------------------------------------

    /**
     * manifest を確かめる。読んでよければ null、駄目ならそのまま画面に出せる文。
     * 新しい版だけを断る。古い版は受ける(§6.5)。
     */
    fun checkManifest(manifestText: String?): String? {
        if (manifestText == null) return "バックアップに $ENTRY_MANIFEST が入っていません。BoxLedger で書き出した zip を選び直してください。"
        val m = runCatching { Json.parse(manifestText) as? Map<*, *> }.getOrNull()
            ?: return "$ENTRY_MANIFEST を読めません。BoxLedger で書き出した zip を選び直してください。"
        if (m["format"] != FORMAT) return "BoxLedger のバックアップではありません(format が違います)。"
        val version = (m["formatVersion"] as? BigDecimal)?.let { runCatching { it.intValueExact() }.getOrNull() }
            ?: return "$ENTRY_MANIFEST の formatVersion を読めません。"
        return when {
            version < 1 -> "バックアップの形式が不正です(formatVersion $version)。"
            version > FORMAT_VERSION -> "このバックアップは新しい版のアプリで作られています(形式 v$version)。アプリを更新してください。"
            else -> null
        }
    }

    /**
     * manifest と data を読み、検証する。[photosInZip] は zip に実際に入っていた写真のファイル名。
     *
     * 致命的にする物: JSON として読めない、uuid や必須の項目が無い、同じ uuid が 2 つある、参照の先が無い、拡張の拡張。
     * 直して警告にする物: 値の範囲の外(未設定に戻す)、知らない列挙値(既定値)、写真の実体が無い、
     * 2 人目の「自分」、同じゲームの 2 件目の未返却の貸出、本体と合わない「使った拡張」、重なったタグ名。
     */
    fun decode(manifestText: String?, dataText: String?, photosInZip: Set<String>): DecodeResult {
        checkManifest(manifestText)?.let { return DecodeResult.Fatal(listOf(it)) }
        if (dataText == null) return DecodeResult.Fatal(listOf("バックアップに $ENTRY_DATA が入っていません。"))
        val root = try {
            Json.parse(dataText) as? Map<*, *> ?: return DecodeResult.Fatal(listOf("$ENTRY_DATA の形が違います。"))
        } catch (e: JsonException) {
            return DecodeResult.Fatal(listOf(e.message ?: "$ENTRY_DATA を読めません。"))
        }
        val manifest = Json.parse(manifestText!!) as Map<*, *>
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()

        fun rows(key: String): List<Map<*, *>> {
            val raw = root[key] ?: return emptyList()
            val list = raw as? List<*> ?: run {
                errors += "$key が配列ではありません。"
                return emptyList()
            }
            return list.mapNotNull { row ->
                (row as? Map<*, *>).also { if (it == null) errors += "$key に、形の違う行があります。" }
            }
        }

        /** uuid を読む。無ければ致命的。同じ uuid の 2 つ目も致命的。 */
        fun uuidOf(row: Map<*, *>, kind: String, seen: MutableSet<String>): String? {
            val u = uuid(row["uuid"])
            if (u == null) {
                errors += "$kind に uuid の無い行があります。"
                return null
            }
            if (!seen.add(u)) {
                errors += "$kind に同じ uuid($u)が 2 つあります。"
                return null
            }
            return u
        }

        // ---- タグ ----
        val tagSeen = HashSet<String>()
        val tagNames = HashSet<String>()
        val tags = rows("genreTags").mapNotNull { r ->
            val u = uuidOf(r, "genreTags", tagSeen) ?: return@mapNotNull null
            var name = text(r["name"]) ?: run {
                errors += "名前の無いジャンルタグがあります($u)。"
                return@mapNotNull null
            }
            if (!tagNames.add(name.lowercase())) {
                // タグ名は重ねられないので、後ろの物の名前を変える
                var n = 2
                while (!tagNames.add("$name ($n)".lowercase())) n++
                warnings += "ジャンルタグ「$name」が重なっていたので、「$name ($n)」に変えました。"
                name = "$name ($n)"
            }
            GenreTag(u, name, color(r["color"]), instant(r["createdAt"]), instant(r["updatedAt"]))
        }

        // ---- プレイヤー ----
        val playerSeen = HashSet<String>()
        var selfSeen = false
        val players = rows("players").mapNotNull { r ->
            val u = uuidOf(r, "players", playerSeen) ?: return@mapNotNull null
            val name = text(r["name"]) ?: run {
                errors += "名前の無いプレイヤーがあります($u)。"
                return@mapNotNull null
            }
            var isSelf = r["isSelf"] == true
            if (isSelf && selfSeen) {
                warnings += "「自分」のプレイヤーが 2 人いたので、「$name」は自分ではない扱いにしました。"
                isSelf = false
            }
            selfSeen = selfSeen || isSelf
            Player(u, name, color(r["color"]), text(r["memo"]), isSelf, instant(r["createdAt"]), instant(r["updatedAt"]))
        }

        // ---- 貸出(ゲームの LENT_OUT の確かめに要るので先に読む。参照は後で確かめる) ----
        val lendingSeen = HashSet<String>()
        val openGames = HashSet<String>()
        val lendings = rows("lendingRecords").mapNotNull { r ->
            val u = uuidOf(r, "lendingRecords", lendingSeen) ?: return@mapNotNull null
            val game = uuid(r["gameUuid"])
            val player = uuid(r["playerUuid"])
            val lentAt = date(r["lentAt"])
            if (game == null || player == null || lentAt == null) {
                errors += "貸し出し記録($u)の gameUuid・playerUuid・lentAt のどれかを読めません。"
                return@mapNotNull null
            }
            val returnedAt = date(r["returnedAt"])
            if (returnedAt == null && !openGames.add(game)) {
                warnings += "同じゲームに未返却の貸出が 2 件あったので、後ろの 1 件を取り込みませんでした。"
                return@mapNotNull null
            }
            var expected = date(r["expectedReturnAt"])
            if (expected != null && expected.isBefore(lentAt)) {
                warnings += "返却予定日が貸出日より前だったので、返却予定日を外しました。"
                expected = null
            }
            LendingRecord(u, game, player, lentAt, expected, returnedAt, instant(r["createdAt"]), instant(r["updatedAt"]))
        }

        // ---- ゲーム ----
        val gameSeen = HashSet<String>()
        val games = rows("games").mapNotNull { r ->
            val u = uuidOf(r, "games", gameSeen) ?: return@mapNotNull null
            val title = text(r["title"]) ?: run {
                errors += "タイトルの無いゲームがあります($u)。"
                return@mapNotNull null
            }
            fun <T> ranged(key: String, value: T?, ok: (T) -> Boolean): T? {
                if (value != null && !ok(value)) {
                    warnings += "「$title」の $key が範囲の外だったので、未設定にしました。"
                    return null
                }
                return value
            }
            fun <T : Comparable<T>> ordered(key: String, min: T?, max: T?): Pair<T?, T?> {
                if (min != null && max != null && min > max) {
                    warnings += "「$title」の $key は最小が最大より大きかったので、未設定にしました。"
                    return null to null
                }
                return min to max
            }
            val (minP, maxP) = ordered(
                "人数",
                ranged("minPlayers", int(r["minPlayers"])) { it >= 1 },
                ranged("maxPlayers", int(r["maxPlayers"])) { it >= 1 },
            )
            val (minT, maxT) = ordered(
                "プレイ時間",
                ranged("minPlayTimeMinutes", int(r["minPlayTimeMinutes"])) { it >= 1 },
                ranged("maxPlayTimeMinutes", int(r["maxPlayTimeMinutes"])) { it >= 1 },
            )
            val photo = text(r["boxPhoto"])?.let { raw ->
                val name = raw.removePrefix(PHOTOS_DIR)
                if (isSafePhotoName(name) && name in photosInZip) {
                    name
                } else {
                    warnings += "「$title」の写真が zip に入っていなかったので、写真なしにしました。"
                    null
                }
            }
            val rawStatus = text(r["status"])
            val status = when (rawStatus) {
                null, "OWNED" -> GameStatus.OWNED
                "WISHLIST" -> GameStatus.WISHLIST
                "LENT_OUT" -> {
                    if (u !in openGames) warnings += "「$title」は貸出中となっていましたが、未返却の貸出記録が無いので、所有中にしました。"
                    GameStatus.OWNED
                }
                else -> {
                    warnings += "「$title」の status($rawStatus)を知らないので、所有中にしました。"
                    GameStatus.OWNED
                }
            }
            // 外貨の 3 項目はそろっているときだけ使う
            val currency = text(r["purchaseCurrency"])?.uppercase()?.takeIf { c -> c.length == 3 && c.all { it in 'A'..'Z' } }
            val foreign = decimal(r["purchaseForeignAmount"])?.takeIf { it.signum() >= 0 }
            val rate = decimal(r["purchaseExchangeRate"])?.takeIf { it.signum() > 0 }
            val hasForeign = currency != null && foreign != null && rate != null
            Game(
                uuid = u,
                title = title,
                parentUuid = uuid(r["parentGameUuid"]),
                barcodeValue = text(r["barcodeValue"]),
                boxPhotoFile = photo,
                minPlayers = minP, maxPlayers = maxP,
                minPlayTimeMinutes = minT, maxPlayTimeMinutes = maxT,
                complexityRating = ranged("complexityRating", double(r["complexityRating"])) { it in 1.0..5.0 },
                purchasePrice = ranged("purchasePrice", int(r["purchasePrice"])) { it in 0..9_999_999 },
                purchaseCurrency = if (hasForeign) currency else null,
                purchaseForeignAmount = if (hasForeign) foreign else null,
                purchaseExchangeRate = if (hasForeign) rate else null,
                purchaseDate = date(r["purchaseDate"]),
                storageLocation = text(r["storageLocation"]),
                personalRating = ranged("personalRating", int(r["personalRating"])) { it in 1..5 },
                genreTagUuids = (r["genreTagUuids"] as? List<*>).orEmpty().mapNotNull(::uuid).distinct(),
                winCondition = enumOr(r["winCondition"], WinCondition.HIGH_SCORE),
                status = status,
                isArchived = r["isArchived"] == true,
                excludeFromSuggestion = r["excludeFromSuggestion"] == true,
                createdAt = instant(r["createdAt"]),
                updatedAt = instant(r["updatedAt"]),
            )
        }
        val gameByUuid = games.associateBy { it.uuid }
        games.forEach { g ->
            g.genreTagUuids.filter { it !in tagSeen }.forEach { errors += "「${g.title}」が、無いジャンルタグ($it)を指しています。" }
            val parent = g.parentUuid ?: return@forEach
            val p = gameByUuid[parent]
            when {
                p == null -> errors += "拡張「${g.title}」の本体($parent)がありません。"
                p.parentUuid != null -> errors += "拡張「${g.title}」の本体が、別のゲームの拡張になっています(拡張の拡張は作れません)。"
            }
        }

        // ---- プレイ記録 ----
        val sessionSeen = HashSet<String>()
        val sessions = rows("playSessions").mapNotNull { r ->
            val u = uuidOf(r, "playSessions", sessionSeen) ?: return@mapNotNull null
            val game = uuid(r["gameUuid"])
            val playedAt = date(r["playedAt"])
            if (game == null || playedAt == null) {
                errors += "プレイ記録($u)の gameUuid か playedAt を読めません。"
                return@mapNotNull null
            }
            if (game !in gameByUuid) errors += "プレイ記録($u)のゲーム($game)がありません。"
            val expansions = (r["expansionUuids"] as? List<*>).orEmpty().mapNotNull(::uuid).distinct().filter { e ->
                val ex = gameByUuid[e]
                when {
                    ex == null -> {
                        errors += "プレイ記録($u)の拡張($e)がありません。"
                        false
                    }
                    ex.parentUuid != game -> {
                        warnings += "$playedAt のプレイ記録から、本体の合わない拡張「${ex.title}」を外しました。"
                        false
                    }
                    else -> true
                }
            }
            val resultPlayers = HashSet<String>()
            val results = (r["results"] as? List<*>).orEmpty().mapNotNull { raw ->
                val row = raw as? Map<*, *>
                val player = row?.let { uuid(it["playerUuid"]) }
                if (row == null || player == null) {
                    errors += "プレイ記録($u)に、playerUuid の無い結果があります。"
                    return@mapNotNull null
                }
                if (player !in playerSeen) errors += "プレイ記録($u)のプレイヤー($player)がありません。"
                if (!resultPlayers.add(player)) {
                    warnings += "$playedAt のプレイ記録に同じプレイヤーが 2 回いたので、後ろを外しました。"
                    return@mapNotNull null
                }
                PlayResult(player, double(row["score"]), row["isWinner"] == true, int(row["rank"])?.takeIf { it >= 1 })
            }
            PlaySession(
                u, game, playedAt, int(r["durationMinutes"])?.takeIf { it >= 1 }, text(r["memo"]),
                expansions, results, instant(r["createdAt"]), instant(r["updatedAt"]),
            )
        }

        lendings.forEach { l ->
            if (l.gameUuid !in gameByUuid) errors += "貸し出し記録(${l.uuid})のゲーム(${l.gameUuid})がありません。"
            if (l.playerUuid !in playerSeen) errors += "貸し出し記録(${l.uuid})のプレイヤー(${l.playerUuid})がありません。"
        }

        // 対応表は、形の合う行だけを受ける(合わない行は使いようが無い)
        val aliases = rows("uuidAliases").mapNotNull { r ->
            val type = runCatching { AliasType.valueOf(r["entityType"] as String) }.getOrNull() ?: return@mapNotNull null
            UuidAlias(type, uuid(r["foreignUuid"]) ?: return@mapNotNull null, uuid(r["localUuid"]) ?: return@mapNotNull null)
        }.distinctBy { it.entityType to it.foreignUuid }

        if (errors.isNotEmpty()) return DecodeResult.Fatal(errors)
        return DecodeResult.Ok(
            library = Library(tags, players, games, sessions, lendings, aliases, settings(root["settings"] as? Map<*, *>)),
            warnings = warnings,
            exportedAt = (manifest["exportedAt"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() },
            sourcePlatform = manifest["sourcePlatform"] as? String,
        )
    }

    private fun settings(m: Map<*, *>?): AppSettings {
        val d = AppSettings()
        if (m == null) return d
        return d.copy(
            defaultViewMode = enumOr(m["defaultViewMode"], d.defaultViewMode),
            unplayedSuggestionThresholdDays = int(m["unplayedSuggestionThresholdDays"])?.takeIf { it in 1..3650 }
                ?: d.unplayedSuggestionThresholdDays,
            darkModeMode = enumOr(m["darkModeMode"], d.darkModeMode),
            themeColorMode = enumOr(m["themeColorMode"], d.themeColorMode),
            manualThemeColor = (m["manualThemeColor"] as? String)?.let(::parseColor),
            celebrationEnabled = m["celebrationEnabled"] as? Boolean ?: d.celebrationEnabled,
        )
    }

    // ---- 値の変換 ----------------------------------------------------------

    /** 写真の名前として安全か。zip は外から来るので、`../` などでアプリのフォルダの外を指させない。 */
    fun isSafePhotoName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }

    /** `#AARRGGBB`(§6.4)。 */
    fun colorText(argb: Int): String = "#%08X".format(argb)

    /** `#AARRGGBB` か `#RRGGBB`(不透明として読む)。読めなければ null。 */
    fun parseColor(text: String): Int? {
        val hex = text.trim().removePrefix("#")
        return when (hex.length) {
            8 -> hex.toLongOrNull(16)?.toInt()
            6 -> hex.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
            else -> null
        }
    }

    /**
     * 日時はミリ秒に丸める。DB はミリ秒で持つので、相手が細かい桁(.NET は 7 桁)を出していると、
     * 丸めなければ「相手の方が新しい」と毎回判定して、同じ zip の 2 回目が「変更なし」にならない。
     */
    fun instantText(at: Instant): String = at.truncatedTo(ChronoUnit.MILLIS).toString()

    private fun instant(raw: Any?): Instant =
        (raw as? String)?.let { runCatching { Instant.parse(it.trim()).truncatedTo(ChronoUnit.MILLIS) }.getOrNull() } ?: Instant.EPOCH

    private fun color(raw: Any?): Int = (raw as? String)?.let(::parseColor) ?: FALLBACK_COLOR

    private fun date(raw: Any?): LocalDate? = (raw as? String)?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }

    private fun text(raw: Any?): String? = (raw as? String)?.trim()?.ifEmpty { null }

    /** uuid は小文字にそろえる(Windows の Guid は大文字で出ることがある)。 */
    private fun uuid(raw: Any?): String? = text(raw)?.lowercase()

    private fun int(raw: Any?): Int? = (raw as? BigDecimal)?.let { runCatching { it.intValueExact() }.getOrNull() }

    private fun double(raw: Any?): Double? = (raw as? BigDecimal)?.toDouble()?.takeIf { it.isFinite() }

    /** 末尾の 0 を落としてそろえる(BigDecimal は 59.990 と 59.99 を別の値として比べるため)。 */
    private fun decimal(raw: Any?): BigDecimal? = (raw as? BigDecimal)?.let(::normalize)

    fun normalize(value: BigDecimal): BigDecimal = if (value.signum() == 0) BigDecimal.ZERO else value.stripTrailingZeros()

    private inline fun <reified E : Enum<E>> enumOr(raw: Any?, fallback: E): E =
        (raw as? String)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: fallback
}
