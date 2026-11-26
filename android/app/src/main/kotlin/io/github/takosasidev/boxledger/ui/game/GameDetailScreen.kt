package io.github.takosasidev.boxledger.ui.game

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.takosasidev.boxledger.core.calc.Format
import io.github.takosasidev.boxledger.core.calc.GameFilter
import io.github.takosasidev.boxledger.core.calc.Reminders
import io.github.takosasidev.boxledger.core.calc.Stats
import io.github.takosasidev.boxledger.core.model.GameStatus
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.core.model.PlaySession
import io.github.takosasidev.boxledger.ui.LedgerViewModel
import io.github.takosasidev.boxledger.ui.components.GameThumb
import io.github.takosasidev.boxledger.ui.components.SectionHeader
import io.github.takosasidev.boxledger.ui.components.Stars
import io.github.takosasidev.boxledger.ui.components.SubScreen
import io.github.takosasidev.boxledger.ui.components.TagBadges
import io.github.takosasidev.boxledger.ui.lending.LendDialog
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 「2026-09-20 ・ 勝ち: A, B」。プレイ記録の 1 行の説明。 */
fun sessionSummary(library: Library, session: PlaySession): String {
    val winners = session.results.filter { it.isWinner }.mapNotNull { library.player(it.playerUuid)?.name }
    return listOfNotNull(
        "${session.results.size} 人",
        session.durationMinutes?.let(Format::duration),
        if (winners.isEmpty()) null else "勝ち: ${winners.joinToString("、")}",
    ).joinToString(" ・ ")
}

/** ゲームの詳細(FR-07 / FR-09 / FR-23 / FR-26 / FR-28)。 */
@Composable
fun GameDetailScreen(
    vm: LedgerViewModel,
    library: Library,
    uuid: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onRecordPlay: (String) -> Unit,
    onOpenGame: (String) -> Unit,
    onAddExpansion: (String) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val game = library.game(uuid)
    if (game == null) {
        // 消した直後など。前の画面へ戻る
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val today = LocalDate.now()
    val sessions = remember(library, uuid) { Stats.sessionsOf(library, uuid) }
    val stats = remember(sessions) { Stats.gameStats(sessions) }
    val lending = library.openLending(uuid)
    val expansions = library.expansionsOf(uuid)
    val parent = library.game(game.parentUuid)

    var showMenu by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showLend by remember { mutableStateOf(false) }

    SubScreen(
        title = game.title,
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "編集") }
            IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "そのほか") }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(if (game.isArchived) "アーカイブから戻す" else "アーカイブする(手放した)") },
                    onClick = {
                        showMenu = false
                        vm.act { saveGame(game.copy(isArchived = !game.isArchived)) }
                    },
                )
                if (!game.isExpansion && game.status == GameStatus.OWNED) {
                    DropdownMenuItem(
                        text = { Text(if (game.excludeFromSuggestion) "未プレイの提案に戻す" else "未プレイの提案に出さない") },
                        onClick = {
                            showMenu = false
                            vm.act { saveGame(game.copy(excludeFromSuggestion = !game.excludeFromSuggestion)) }
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        showDelete = true
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            GameThumb(game, Modifier.fillMaxWidth().padding(horizontal = 16.dp).aspectRatio(1.6f), reqPx = 1024)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(game.title, style = MaterialTheme.typography.headlineSmall)
                if (parent != null) {
                    Text(
                        "「${parent.title}」の拡張",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpenGame(parent.uuid) }.padding(vertical = 6.dp),
                    )
                }
                val state = listOfNotNull(
                    "ウィッシュリスト".takeIf { game.status == GameStatus.WISHLIST },
                    "アーカイブ済み".takeIf { game.isArchived },
                ).joinToString(" ・ ")
                if (state.isNotEmpty()) Text(state, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                val range = GameFilter.playerRange(library, game)
                val spec = listOfNotNull(
                    Format.players(game.minPlayers, game.maxPlayers),
                    // 拡張で人数が広がるなら、合わせた範囲も書く
                    range?.takeIf { it.first != game.minPlayers || it.last != (game.maxPlayers ?: game.minPlayers) }
                        ?.let { "拡張込み ${Format.players(it.first, it.last)}" },
                    Format.minutes(game.minPlayTimeMinutes, game.maxPlayTimeMinutes),
                    game.complexityRating?.let { "複雑度 $it" },
                ).joinToString(" ・ ")
                if (spec.isNotEmpty()) Text(spec)
                if (game.personalRating != null) Stars(game.personalRating)
                TagBadges(library.genreTags.filter { it.uuid in game.genreTagUuids })
                game.storageLocation?.let { Text("保管場所: $it") }
                val purchase = listOfNotNull(
                    game.purchaseDate?.let { "購入日 $it" },
                    game.purchasePrice?.let { Format.yen(it.toLong()) },
                    game.purchaseCurrency?.let { c -> "(${game.purchaseForeignAmount?.toPlainString()} $c × ${game.purchaseExchangeRate?.toPlainString()})" },
                ).joinToString(" ")
                if (purchase.isNotEmpty()) Text(purchase)
                game.barcodeValue?.let { Text("バーコード: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (game.status == GameStatus.WISHLIST) {
                    Button(onClick = { vm.act { saveGame(game.copy(status = GameStatus.OWNED, purchaseDate = game.purchaseDate ?: today)) } }) {
                        Text("買った(所有に移す)")
                    }
                } else if (parent == null) {
                    Button(onClick = { onRecordPlay(game.uuid) }) { Text("プレイを記録") }
                } else {
                    // 拡張のプレイは、本体のプレイ記録の「使った拡張」に付ける
                    Button(onClick = { onRecordPlay(parent.uuid) }) { Text("本体のプレイを記録") }
                }
            }

            if (game.status == GameStatus.OWNED) {
                SectionHeader(if (game.isExpansion) "使った記録" else "プレイの統計")
                Text(
                    listOfNotNull(
                        if (game.isExpansion) "使用 ${stats.playCount} 回" else "プレイ ${stats.playCount} 回",
                        "合計 ${Format.duration(stats.totalMinutes)}".takeIf { stats.totalMinutes > 0 },
                        stats.averageMinutes?.let { "平均 ${Format.duration(it)}" },
                        stats.lastPlayed?.let { "最後 $it" },
                    ).joinToString(" ・ "),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                SectionHeader("貸し出し")
                if (lending != null) {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${library.player(lending.playerUuid)?.name ?: "?"}さんに貸出中(${lending.lentAt} から)")
                        Text(
                            lending.expectedReturnAt?.let { "返却予定 $it(${Reminders.label(lending, today)})" } ?: "返却予定日なし",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { vm.act { saveLending(lending.copy(returnedAt = today)) } }) { Text("返却済みにする") }
                    }
                } else {
                    OutlinedButton(onClick = { showLend = true }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("貸し出す") }
                }
            }

            if (parent == null) {
                SectionHeader("拡張")
                expansions.forEach { ex ->
                    val used = Stats.gameStats(library, ex.uuid)
                    ListItem(
                        modifier = Modifier.clickable { onOpenGame(ex.uuid) },
                        headlineContent = { Text(ex.title) },
                        supportingContent = {
                            Text(
                                when {
                                    ex.status == GameStatus.WISHLIST -> "ウィッシュリスト"
                                    used.playCount == 0 -> "未使用"
                                    else -> "使用 ${used.playCount} 回 ・ 最後 ${used.lastPlayed}"
                                },
                            )
                        },
                    )
                }
                TextButton(onClick = { onAddExpansion(game.uuid) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("＋ 拡張を追加") }
            }

            if (sessions.isNotEmpty()) {
                SectionHeader("最近のプレイ")
                sessions.sortedByDescending { it.playedAt }.take(5).forEach { s ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenSession(s.uuid) },
                        headlineContent = { Text(s.playedAt.toString()) },
                        supportingContent = { Text(sessionSummary(library, s)) },
                    )
                }
            }
        }
    }

    if (showLend) {
        LendDialog(
            vm = vm,
            library = library,
            gameUuid = game.uuid,
            onDismiss = { showLend = false },
            onResult = { saved ->
                showLend = false
                if (!saved) scope.launch { snackbar.showSnackbar("このゲームはもう貸出中です") }
            },
        )
    }

    if (showDelete) {
        // 拡張があるときは、拡張をどうするかを選ばせる。既定は「本体なしのゲームに戻す」
        var deleteExpansions by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("「${game.title}」を削除しますか") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (sessions.isEmpty()) "このゲームの記録を削除します。元に戻せません。" else "${sessions.size} 件のプレイ記録が失われます。貸し出しの記録も消えます。元に戻せません。",
                    )
                    if (expansions.isNotEmpty()) {
                        Text("このゲームには拡張が ${expansions.size} 件あります。")
                        listOf(false to "拡張を本体なしのゲームに戻す", true to "拡張も削除する").forEach { (value, label) ->
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = deleteExpansions == value, role = Role.RadioButton) { deleteExpansions = value },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = deleteExpansions == value, onClick = null, modifier = Modifier.padding(12.dp))
                                Text(label)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    vm.act { deleteGame(game.uuid, deleteExpansions) }
                }) { Text("削除する", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("キャンセル") } },
        )
    }
}
