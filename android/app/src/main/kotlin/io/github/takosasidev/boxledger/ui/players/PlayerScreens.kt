@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.takosasidev.boxledger.ui.players

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.github.takosasidev.boxledger.core.calc.Format
import io.github.takosasidev.boxledger.core.calc.Record
import io.github.takosasidev.boxledger.core.calc.Stats
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.core.model.Player
import io.github.takosasidev.boxledger.data.LedgerRepository
import io.github.takosasidev.boxledger.ui.LedgerViewModel
import io.github.takosasidev.boxledger.ui.components.ColorRow
import io.github.takosasidev.boxledger.ui.components.ConfirmDialog
import io.github.takosasidev.boxledger.ui.components.EmptyState
import io.github.takosasidev.boxledger.ui.components.SectionHeader
import io.github.takosasidev.boxledger.ui.components.SubScreen
import io.github.takosasidev.boxledger.ui.theme.ColorChoices

/** 勝率のバー。色だけに頼らないよう、隣に「7 勝 / 10 回(70%)」と数も書く。 */
@Composable
private fun WinRateBar(record: Record, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (record.plays == 0) "まだ記録がありません" else "${record.wins} 勝 / ${record.plays} 回(勝率 ${record.winRateText})",
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(
            progress = { (record.winRate ?: 0.0).toFloat() },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
private fun PlayerDot(player: Player, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).clip(CircleShape).background(Color(player.colorArgb)))
}

/** プレイヤーの一覧(FR-04 / FR-06)。 */
@Composable
fun PlayersScreen(vm: LedgerViewModel, library: Library, onOpen: (String) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    val stats = remember(library) { library.players.associate { it.uuid to Stats.playerStats(library, it.uuid).total } }
    Scaffold(
        topBar = { TopAppBar(title = { Text("プレイヤー") }) },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "プレイヤーを追加") } },
    ) { padding ->
        if (library.players.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Groups,
                title = "プレイヤーがいません",
                body = "一緒に遊ぶ人と、自分を登録します。プレイ記録の勝ち負けを人ごとに集計できます。",
                modifier = Modifier.padding(padding),
                actionLabel = "プレイヤーを追加",
                onAction = { adding = true },
            )
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(library.players, key = { it.uuid }) { p ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(p.uuid) },
                        leadingContent = { PlayerDot(p) },
                        headlineContent = { Text(p.name + if (p.isSelf) "(自分)" else "") },
                        supportingContent = { WinRateBar(stats[p.uuid] ?: Record(0, 0), Color(p.colorArgb)) },
                    )
                }
            }
        }
    }
    if (adding) {
        PlayerDialog(
            library = library,
            original = null,
            onDismiss = { adding = false },
            onSave = { player ->
                adding = false
                vm.act { savePlayer(player) }
            },
        )
    }
}

/** プレイヤーの追加と編集。認証の情報(パスワードやアカウント)は持たない。名前とメモだけ。 */
@Composable
fun PlayerDialog(library: Library, original: Player?, onDismiss: () -> Unit, onSave: (Player) -> Unit) {
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var color by remember { mutableIntStateOf(original?.colorArgb ?: ColorChoices[library.players.size % ColorChoices.size].second.toArgb()) }
    var memo by remember { mutableStateOf(original?.memo.orEmpty()) }
    var isSelf by remember { mutableStateOf(original?.isSelf ?: false) }
    val sameName = library.players.any { it.uuid != original?.uuid && it.name == name.trim() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "プレイヤーを追加" else "プレイヤーを編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(50) },
                    label = { Text("名前") },
                    singleLine = true,
                    supportingText = { if (sameName) Text("同じ名前の人が既にいます(このまま保存できます)") },
                )
                ColorRow(color, { color = it })
                OutlinedTextField(value = memo, onValueChange = { memo = it }, label = { Text("メモ(任意)") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("この人は自分", Modifier.weight(1f))
                    Switch(checked = isSelf, onCheckedChange = { isSelf = it })
                }
                if (isSelf && library.players.any { it.isSelf && it.uuid != original?.uuid }) {
                    Text("「自分」は 1 人だけです。保存すると、今の「自分」の印は外れます。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val base = original ?: Player(LedgerRepository.newUuid(), "", color)
                onSave(base.copy(name = name.trim(), colorArgb = color, memo = memo.trim().ifEmpty { null }, isSelf = isSelf))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** プレイヤーの成績(FR-06)。全体の勝率、ゲーム別の成績、対戦の履歴。 */
@Composable
fun PlayerDetailScreen(vm: LedgerViewModel, library: Library, uuid: String, onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    val player = library.player(uuid)
    if (player == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val stats = remember(library, uuid) { Stats.playerStats(library, uuid) }
    val history = remember(library, uuid) {
        library.playSessions.filter { s -> s.results.any { it.playerUuid == uuid } }.sortedByDescending { it.playedAt }
    }
    val color = Color(player.colorArgb)
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val openLendings = library.lendingRecords.count { it.playerUuid == uuid && it.returnedAt == null }

    SubScreen(
        title = player.name,
        onBack = onBack,
        actions = {
            IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = "編集") }
            IconButton(onClick = { deleting = true }) { Icon(Icons.Filled.Delete, contentDescription = "削除") }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlayerDot(player)
                    Column(Modifier.weight(1f)) {
                        Text(player.name + if (player.isSelf) "(自分)" else "", style = MaterialTheme.typography.titleLarge)
                        player.memo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                WinRateBar(stats.total, color, Modifier.padding(horizontal = 16.dp))
            }
            if (stats.byGame.isNotEmpty()) item { SectionHeader("ゲーム別の成績") }
            items(stats.byGame, key = { "g-" + it.first }) { (gameUuid, record) ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(library.game(gameUuid)?.title ?: "?", style = MaterialTheme.typography.titleSmall)
                    WinRateBar(record, color)
                }
            }
            if (history.isNotEmpty()) item { SectionHeader("対戦の履歴") }
            items(history, key = { "s-" + it.uuid }) { s ->
                val mine = s.results.first { it.playerUuid == uuid }
                ListItem(
                    modifier = Modifier.clickable { onOpenSession(s.uuid) },
                    overlineContent = { Text(s.playedAt.toString()) },
                    headlineContent = { Text(library.game(s.gameUuid)?.title ?: "?") },
                    supportingContent = {
                        Text(listOfNotNull(if (mine.isWinner) "勝ち" else "負け", mine.rank?.let { "$it 位" }, mine.score?.let { "スコア ${Format.number(it)}" }).joinToString(" ・ "))
                    },
                )
            }
        }
    }

    if (editing) {
        PlayerDialog(library, player, onDismiss = { editing = false }) { changed ->
            editing = false
            vm.act { savePlayer(changed) }
        }
    }
    if (deleting) {
        if (openLendings > 0) {
            // 貸しているゲームがある人は消せない(だれに貸したかが分からなくなる)
            AlertDialog(
                onDismissRequest = { deleting = false },
                title = { Text("削除できません") },
                text = { Text("${player.name}さんには、まだ返ってきていないゲームが $openLendings 件あります。返却済みにしてから削除してください。") },
                confirmButton = { TextButton(onClick = { deleting = false }) { Text("閉じる") } },
            )
        } else {
            ConfirmDialog(
                title = "「${player.name}」を削除しますか",
                text = "この人の結果(${stats.total.plays} 件のプレイの分)と、貸し出しの履歴も削除されます。元に戻せません。",
                confirmLabel = "削除する",
                danger = true,
                onConfirm = {
                    deleting = false
                    vm.act { deletePlayer(player.uuid) }
                },
                onDismiss = { deleting = false },
            )
        }
    }
}
