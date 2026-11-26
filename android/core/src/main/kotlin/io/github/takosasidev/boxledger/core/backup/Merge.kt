// 追加取り込み(Windows 版の要件定義書 §6.6)。手元の全データと相手の全データから、結果の全データを作る。
// 足すだけで、削除は伝えない。DB にも画面にも触らないので、単体テストでそのまま確かめられる。
package io.github.takosasidev.boxledger.core.backup

import io.github.takosasidev.boxledger.core.model.AliasType
import io.github.takosasidev.boxledger.core.model.Game
import io.github.takosasidev.boxledger.core.model.GenreTag
import io.github.takosasidev.boxledger.core.model.LendingRecord
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.core.model.PlaySession
import io.github.takosasidev.boxledger.core.model.Player
import io.github.takosasidev.boxledger.core.model.UuidAlias
import java.time.Instant

/** uuid は違うが同じ物らしい組み合わせ。利用者に「同じ物 / 別の物」を選ばせる(既定は同じ物)。 */
data class MergeCandidate(
    val type: AliasType,
    val incomingUuid: String,
    val incomingLabel: String,
    val localUuid: String,
    val localLabel: String,
)

data class MergeCount(val added: Int = 0, val updated: Int = 0, val unchanged: Int = 0)

data class MergeResult(
    val library: Library,
    val tags: MergeCount,
    val players: MergeCount,
    val games: MergeCount,
    val sessions: MergeCount,
    val lendings: MergeCount,
    val warnings: List<String>,
    /** 確認が要る組み合わせ。[Merge.merge] の separate に入っていない物は「同じ物」として処理してある。 */
    val candidates: List<MergeCandidate>,
    /** 相手の zip から写真のフォルダへ写すファイル名(追加・上書きしたゲームの分だけ)。 */
    val photosToCopy: Set<String>,
) {
    val changed: Boolean
        get() = listOf(tags, players, games, sessions, lendings).any { it.added + it.updated > 0 }
}

object Merge {

    /**
     * @param separate 確認の画面で「別の物として追加する」を選んだ、相手の側の uuid。
     *   ここに無い候補は「同じ物」として扱い、対応表(UuidAlias)に覚える。
     */
    fun merge(local: Library, incoming: Library, separate: Set<String> = emptySet()): MergeResult {
        val warnings = ArrayList<String>()
        val candidates = ArrayList<MergeCandidate>()
        val newAliases = ArrayList<UuidAlias>()
        val photos = HashSet<String>()
        val aliasOf = local.uuidAliases.associate { (it.entityType to it.foreignUuid) to it.localUuid }

        /**
         * 1 種類分の照合。uuid(と対応表)で相手が見つかれば新しい方を採り、見つからなければ同一候補を探し、
         * それも無ければ相手の uuid のまま足す。戻り値は「相手の uuid → 手元の uuid」。
         */
        class Pass<T : Any>(
            val type: AliasType?,
            locals: List<T>,
            val uuidOf: (T) -> String,
            val updatedAt: (T) -> Instant,
        ) {
            val rows = LinkedHashMap<String, T>().apply { locals.forEach { put(uuidOf(it), it) } }
            val map = HashMap<String, String>()
            var added = 0
            var updated = 0
            var unchanged = 0

            fun direct(uuid: String): String? = when {
                uuid in rows -> uuid
                type == null -> null
                else -> aliasOf[type to uuid]?.takeIf { it in rows }
            }

            fun mergeIn(
                incomingRows: List<T>,
                /** 同一候補。Pair の 2 つ目が true なら確認が要る。 */
                findSame: (T, free: (String) -> Boolean) -> Pair<T, Boolean>? = { _, _ -> null },
                label: (T) -> String = { "" },
                /** 相手の行を、手元の uuid と参照に読み替える。null を返したら取り込まない。 */
                adapt: (incoming: T, targetUuid: String, existing: T?) -> T?,
            ) {
                // 先に uuid で決まる相手を押さえる。そうしないと、同一候補が別の行の相手を横取りする
                val taken = incomingRows.mapNotNull { direct(uuidOf(it)) }.toMutableSet()
                for (row in incomingRows) {
                    val foreign = uuidOf(row)
                    var target = direct(foreign)
                    if (target == null && type != null) {
                        val same = findSame(row) { it !in taken }
                        if (same != null) {
                            val (localRow, needsConfirm) = same
                            if (needsConfirm) {
                                candidates += MergeCandidate(type, foreign, label(row), uuidOf(localRow), label(localRow))
                            }
                            if (!needsConfirm || foreign !in separate) {
                                target = uuidOf(localRow)
                                taken += target
                                newAliases += UuidAlias(type, foreign, target)
                            }
                        }
                    }
                    val existing = target?.let { rows[it] }
                    val uuid = target ?: foreign
                    map[foreign] = uuid
                    if (existing != null && !updatedAt(row).isAfter(updatedAt(existing))) {
                        unchanged++
                        continue
                    }
                    val adapted = adapt(row, uuid, existing) ?: continue
                    rows[uuid] = adapted
                    if (existing == null) added++ else updated++
                }
            }

            fun count() = MergeCount(added, updated, unchanged)
        }

        // ---- ジャンルタグ: 名前が同じなら確認せずに同じ物 ----
        val tags = Pass(AliasType.GENRE_TAG, local.genreTags, GenreTag::uuid, GenreTag::updatedAt)
        tags.mergeIn(
            incoming.genreTags,
            findSame = { t, free -> tags.rows.values.firstOrNull { free(it.uuid) && it.name.equals(t.name, ignoreCase = true) }?.let { it to false } },
        ) { t, uuid, existing ->
            // 上書きで名前がほかのタグと重なるなら、名前だけ手元のままにする(タグ名は重ねられない)
            val clash = tags.rows.values.any { it.uuid != uuid && it.name.equals(t.name, ignoreCase = true) }
            if (clash) warnings += "ジャンルタグ「${t.name}」は同じ名前のタグが既にあるので、名前を変えずに取り込みました。"
            t.copy(uuid = uuid, name = if (clash) existing?.name ?: "${t.name} (2)" else t.name)
        }

        // ---- プレイヤー: 「自分」同士は確認せずに同じ物。名前が同じなら確認 ----
        val players = Pass(AliasType.PLAYER, local.players, Player::uuid, Player::updatedAt)
        players.mergeIn(
            incoming.players,
            findSame = { p, free ->
                val self = if (p.isSelf) players.rows.values.firstOrNull { it.isSelf && free(it.uuid) } else null
                self?.let { it to false }
                    ?: players.rows.values.firstOrNull { free(it.uuid) && it.name == p.name }?.let { it to true }
            },
            label = Player::name,
        ) { p, uuid, _ ->
            val otherSelf = p.isSelf && players.rows.values.any { it.isSelf && it.uuid != uuid }
            if (otherSelf) warnings += "「${p.name}」は相手の端末では「自分」ですが、こちらの「自分」は別にいるので、自分ではない扱いで取り込みました。"
            p.copy(uuid = uuid, isSelf = p.isSelf && !otherSelf)
        }

        // ---- ゲーム: 本体 → 拡張の順。バーコードかタイトルが同じで、本体/拡張の区別と本体も同じなら確認 ----
        val games = Pass(AliasType.GAME, local.games, Game::uuid, Game::updatedAt)
        games.mergeIn(
            incoming.games.sortedBy { it.isExpansion },
            findSame = { g, free ->
                val parent = g.parentUuid?.let { games.map[it] ?: it }
                val pool = games.rows.values.filter { free(it.uuid) && it.parentUuid == parent }
                (pool.firstOrNull { g.barcodeValue != null && it.barcodeValue == g.barcodeValue } ?: pool.firstOrNull { it.title == g.title })
                    ?.let { it to true }
            },
            label = Game::title,
        ) { g, uuid, existing ->
            var parent = g.parentUuid?.let { games.map[it] ?: it }
            // 1 階層だけの決まりを守る。手元で形が変わっていて守れないときは、本体として取り込む
            if (parent != null && (games.rows[parent]?.parentUuid != null || games.rows.values.any { it.parentUuid == uuid } || parent == uuid)) {
                warnings += "「${g.title}」は拡張の拡張になってしまうので、本体なしのゲームとして取り込みました。"
                parent = null
            }
            // 相手に写真があればそれに置き換える。相手に無く、こちらにだけあるなら残す
            if (g.boxPhotoFile != null) photos += g.boxPhotoFile
            g.copy(
                uuid = uuid,
                parentUuid = parent,
                genreTagUuids = g.genreTagUuids.map { tags.map[it] ?: it }.distinct(),
                boxPhotoFile = g.boxPhotoFile ?: existing?.boxPhotoFile,
            )
        }

        // ---- プレイ記録: uuid だけで照合。結果と使った拡張は丸ごと置き換える ----
        val sessions = Pass(null, local.playSessions, PlaySession::uuid, PlaySession::updatedAt)
        sessions.mergeIn(incoming.playSessions) { s, uuid, _ ->
            val game = games.map[s.gameUuid] ?: s.gameUuid
            s.copy(
                uuid = uuid,
                gameUuid = game,
                expansionUuids = s.expansionUuids.map { games.map[it] ?: it }.distinct().filter { games.rows[it]?.parentUuid == game },
                results = s.results.map { it.copy(playerUuid = players.map[it.playerUuid] ?: it.playerUuid) }.distinctBy { it.playerUuid },
            )
        }

        // ---- 貸し出し記録: uuid だけで照合。1 ゲーム未返却 1 件を守る ----
        val lendings = Pass(null, local.lendingRecords, LendingRecord::uuid, LendingRecord::updatedAt)
        // 返却済みを先に処理する。相手が「返してもらって、貸し直した」とき、先に古い貸出を返却済みにしないと、
        // 新しい貸出を「未返却が両方にある」と取り違える(zip の中の並び順で結果が変わってしまう)
        lendings.mergeIn(incoming.lendingRecords.sortedBy { it.returnedAt == null }) { l, uuid, _ ->
            val game = games.map[l.gameUuid] ?: l.gameUuid
            val otherOpen = l.returnedAt == null && lendings.rows.values.any { it.uuid != uuid && it.gameUuid == game && it.returnedAt == null }
            if (otherOpen) {
                warnings += "「${games.rows[game]?.title ?: game}」は、こちらにも相手にも別々の未返却の貸出があるので、相手の貸出記録を取り込みませんでした。"
                null
            } else {
                l.copy(uuid = uuid, gameUuid = game, playerUuid = players.map[l.playerUuid] ?: l.playerUuid)
            }
        }

        return MergeResult(
            // 設定と相手の対応表は取り込まない
            library = local.copy(
                genreTags = tags.rows.values.toList(),
                players = players.rows.values.toList(),
                games = games.rows.values.toList(),
                playSessions = sessions.rows.values.toList(),
                lendingRecords = lendings.rows.values.toList(),
                uuidAliases = local.uuidAliases + newAliases,
            ),
            tags = tags.count(),
            players = players.count(),
            games = games.count(),
            sessions = sessions.count(),
            lendings = lendings.count(),
            warnings = warnings,
            candidates = candidates,
            photosToCopy = photos,
        )
    }
}
