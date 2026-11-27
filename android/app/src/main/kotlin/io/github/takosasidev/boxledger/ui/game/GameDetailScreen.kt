@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.takosasidev.boxledger.ui.game

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import io.github.takosasidev.boxledger.core.model.LendingRecord
import io.github.takosasidev.boxledger.ui.components.GroupItem
import io.github.takosasidev.boxledger.ui.components.LeadingIcon
import io.github.takosasidev.boxledger.ui.components.PlayerAvatar
import io.github.takosasidev.boxledger.ui.components.groupListItemColors
import io.github.takosasidev.boxledger.ui.theme.LocalStatusColors
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

/** 返却予定の文字の色。超過は危険の色、今日・明日は注意の色(文字でも「超過」「今日まで」と書く)。 */
@Composable
fun returnColor(lending: LendingRecord, today: LocalDate): Color {
    val due = lending.expectedReturnAt ?: return MaterialTheme.colorScheme.onSurfaceVariant
    return when {
        due.isBefore(today) -> MaterialTheme.colorScheme.error
        !due.isAfter(today.plusDays(1)) -> LocalStatusColors.current.warn
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** 統計の 1 マス。ラベルは小さく、数は大きく。 */
@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 1)
    }
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
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val tone = game.genreTagUuids.firstNotNullOfOrNull { id -> library.genreTags.firstOrNull { it.uuid == id } }?.let { Color(it.colorArgb) }
            GameThumb(game, Modifier.fillMaxWidth().aspectRatio(1.6f), reqPx = 1024, corner = 28.dp, tone = tone)
            Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(game.title, style = MaterialTheme.typography.headlineSmallEmphasized)
                if (parent != null) {
                    Text(
                        "「${parent.title}」の拡張",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.clickable { onOpenGame(parent.uuid) }.padding(vertical = 6.dp),
                    )
                }
                val state = listOfNotNull(
                    "ウィッシュリスト".takeIf { game.status == GameStatus.WISHLIST },
                    "アーカイブ済み".takeIf { game.isArchived },
                ).joinToString(" ・ ")
                if (state.isNotEmpty()) Text(state, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                if (game.personalRating != null) Stars(game.personalRating)
                TagBadges(library.genreTags.filter { it.uuid in game.genreTagUuids })
            }

            // 人数・時間・複雑度を、頭にアイコンのついた札で並べる
            val range = GameFilter.playerRange(library, game)
            // 拡張で人数が広がるなら、合わせた範囲も小さく書き添える
            val withExpansions = range?.takeIf { it.first != game.minPlayers || it.last != (game.maxPlayers ?: game.minPlayers) }
                ?.let { "拡張込み ${Format.players(it.first, it.last)}" }
            val facts = listOfNotNull(
                Format.players(game.minPlayers, game.maxPlayers)?.let { Triple(Icons.Outlined.Groups, "人数", it) },
                Format.minutes(game.minPlayTimeMinutes, game.maxPlayTimeMinutes)?.let { Triple(Icons.Outlined.Schedule, "時間", it) },
                game.complexityRating?.let { Triple(Icons.Outlined.Psychology, "複雑度", "$it") },
            )
            if (facts.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                    facts.forEachIndexed { i, (icon, label, value) ->
                        val big = 24.dp
                        val small = 6.dp
                        val shape = RoundedCornerShape(
                            topStart = if (i == 0) big else small, bottomStart = if (i == 0) big else small,
                            topEnd = if (i == facts.lastIndex) big else small, bottomEnd = if (i == facts.lastIndex) big else small,
                        )
                        Surface(Modifier.weight(1f).fillMaxHeight(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(14.dp)) {
                                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                                Text(value, style = MaterialTheme.typography.titleMediumEmphasized)
                                if (label == "人数" && withExpansions != null) {
                                    Text(withExpansions, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            Box(Modifier.padding(vertical = 12.dp)) {
                val modifier = Modifier.fillMaxWidth().height(56.dp)
                if (game.status == GameStatus.WISHLIST) {
                    Button(onClick = { vm.act { saveGame(game.copy(status = GameStatus.OWNED, purchaseDate = game.purchaseDate ?: today)) } }, modifier = modifier) {
                        Icon(Icons.Filled.ShoppingCart, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("買った(所有に移す)", Modifier.padding(start = 8.dp))
                    }
                } else if (parent == null) {
                    Button(onClick = { onRecordPlay(game.uuid) }, modifier = modifier) {
                        Icon(Icons.Filled.EmojiEvents, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("プレイを記録", Modifier.padding(start = 8.dp))
                    }
                } else {
                    // 拡張のプレイは、本体のプレイ記録の「使った拡張」に付ける
                    Button(onClick = { onRecordPlay(parent.uuid) }, modifier = modifier) {
                        Icon(Icons.Filled.EmojiEvents, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("本体のプレイを記録", Modifier.padding(start = 8.dp))
                    }
                }
            }

            if (game.status == GameStatus.OWNED) {
                SectionHeader(if (game.isExpansion) "使った記録" else "プレイの統計")
                GroupItem(0, 1) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 16.dp)) {
                        StatCell(if (game.isExpansion) "使用" else "プレイ", "${stats.playCount} 回", Modifier.weight(1f))
                        StatCell("合計", if (stats.totalMinutes > 0) Format.duration(stats.totalMinutes) else "—", Modifier.weight(1f))
                        StatCell("平均", stats.averageMinutes?.let { Format.duration(it) } ?: "—", Modifier.weight(1f))
                    }
                    stats.lastPlayed?.let {
                        Text(
                            "最後 $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, bottom = 14.dp),
                        )
                    }
                }

                SectionHeader("貸し出し")
                GroupItem(0, 1) {
                    if (lending != null) {
                        val borrower = library.player(lending.playerUuid)
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (borrower != null) PlayerAvatar(borrower.name, borrower.colorArgb) else LeadingIcon(Icons.Outlined.SwapHoriz)
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text("${borrower?.name ?: "?"}さんに貸出中(${lending.lentAt} から)", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    lending.expectedReturnAt?.let { "返却予定 $it(${Reminders.label(lending, today)})" } ?: "返却予定日なし",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = returnColor(lending, today),
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = { vm.act { saveLending(lending.copy(returnedAt = today)) } },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        ) { Text("返却済みにする") }
                    } else {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            LeadingIcon(Icons.Outlined.SwapHoriz)
                            Text("手元にあります", Modifier.weight(1f).padding(horizontal = 14.dp), style = MaterialTheme.typography.bodyLarge)
                            FilledTonalButton(onClick = { showLend = true }) { Text("貸し出す") }
                        }
                    }
                }
            }

            if (parent == null) {
                SectionHeader("拡張")
                expansions.forEachIndexed { index, ex ->
                    val used = Stats.gameStats(library, ex.uuid)
                    GroupItem(index, expansions.size, onClick = { onOpenGame(ex.uuid) }) {
                        ListItem(
                            colors = groupListItemColors(),
                            leadingContent = { GameThumb(ex, Modifier.size(44.dp), reqPx = 128, corner = 12.dp, tone = tone) },
                            headlineContent = { Text(ex.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
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
                }
                TextButton(onClick = { onAddExpansion(game.uuid) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("拡張を追加", Modifier.padding(start = 6.dp))
                }
            }

            if (sessions.isNotEmpty()) {
                SectionHeader("最近のプレイ")
                val recent = sessions.sortedByDescending { it.playedAt }.take(5)
                recent.forEachIndexed { index, s ->
                    GroupItem(index, recent.size, onClick = { onOpenSession(s.uuid) }) {
                        ListItem(
                            colors = groupListItemColors(),
                            headlineContent = { Text(s.playedAt.toString()) },
                            supportingContent = { Text(sessionSummary(library, s)) },
                        )
                    }
                }
            }

            // 購入・保管場所・バーコードは下にまとめる(遊ぶときに見る物を上に置く)
            val purchase = listOfNotNull(
                game.purchaseDate?.let { "購入日 $it" },
                game.purchasePrice?.let { Format.yen(it.toLong()) },
                game.purchaseCurrency?.let { c -> "(${game.purchaseForeignAmount?.toPlainString()} $c × ${game.purchaseExchangeRate?.toPlainString()})" },
            ).joinToString(" ")
            val details = listOfNotNull(
                game.storageLocation?.let { Triple(Icons.Outlined.Place, "保管場所", it) },
                purchase.takeIf { it.isNotEmpty() }?.let { Triple(Icons.Outlined.ShoppingBag, "購入", it) },
                game.barcodeValue?.let { Triple(Icons.Outlined.QrCode, "バーコード", it) },
            )
            if (details.isNotEmpty()) {
                SectionHeader("持ち物の情報")
                details.forEachIndexed { index, (icon, label, value) ->
                    GroupItem(index, details.size) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            LeadingIcon(icon)
                            Column(Modifier.padding(start = 14.dp)) {
                                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(value, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
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
                    // 一緒に消える数は、選んだ消し方(拡張も消すか)に合わせて数え直す。
                    // 拡張を消しても本体のプレイ記録は消えない(「使った拡張」から外れるだけ)ので、別に書く
                    val impact = Stats.gameDeleteImpact(library, game.uuid, deleteExpansions)
                    val lost = listOfNotNull(
                        "${impact.sessions} 件のプレイ記録".takeIf { impact.sessions > 0 },
                        "${impact.lendings} 件の貸し出しの記録".takeIf { impact.lendings > 0 },
                    )
                    Text(
                        (if (lost.isEmpty()) "このゲームの記録を削除します。" else "${lost.joinToString("と、")}が失われます。") +
                            (if (impact.unlinkedSessions > 0) "${impact.unlinkedSessions} 件のプレイ記録の「使った拡張」から外れます(プレイ記録は残ります)。" else "") +
                            "元に戻せません。",
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
