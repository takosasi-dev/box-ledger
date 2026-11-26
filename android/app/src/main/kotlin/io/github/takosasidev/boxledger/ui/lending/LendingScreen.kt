package io.github.takosasidev.boxledger.ui.lending

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.github.takosasidev.boxledger.core.calc.Reminders
import io.github.takosasidev.boxledger.core.model.LendingRecord
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.core.model.Player
import io.github.takosasidev.boxledger.data.LedgerRepository
import io.github.takosasidev.boxledger.ui.LedgerViewModel
import io.github.takosasidev.boxledger.ui.components.ConfirmDialog
import io.github.takosasidev.boxledger.ui.components.DateField
import io.github.takosasidev.boxledger.ui.components.EmptyState
import io.github.takosasidev.boxledger.ui.components.SectionHeader
import io.github.takosasidev.boxledger.ui.components.SubScreen
import io.github.takosasidev.boxledger.ui.theme.ColorChoices
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 貸し出す相手と日付を決める(FR-09)。相手が未登録なら、その場で名前を入れて登録できる。
 * [onResult] には、保存できたら true、もう貸出中で保存できなかったら false を渡す。
 */
@Composable
fun LendDialog(vm: LedgerViewModel, library: Library, gameUuid: String, onDismiss: () -> Unit, onResult: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val candidates = library.players.filter { !it.isSelf }
    var playerUuid by remember { mutableStateOf(candidates.firstOrNull()?.uuid) }
    var newName by remember { mutableStateOf("") }
    var lentAt by remember { mutableStateOf(LocalDate.now()) }
    var expected by remember { mutableStateOf<LocalDate?>(LocalDate.now().plusDays(7)) }
    var showPlayers by remember { mutableStateOf(false) }
    val dateOk = expected?.isBefore(lentAt) != true
    val canSave = (newName.isNotBlank() || playerUuid != null) && dateOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("貸し出す") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (candidates.isNotEmpty()) {
                    OutlinedButton(onClick = { showPlayers = true }, enabled = newName.isBlank()) {
                        Text("相手: ${library.player(playerUuid)?.name ?: "選ぶ"}")
                    }
                    DropdownMenu(expanded = showPlayers, onDismissRequest = { showPlayers = false }) {
                        candidates.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = {
                                playerUuid = p.uuid
                                showPlayers = false
                            })
                        }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(50) },
                    label = { Text(if (candidates.isEmpty()) "相手の名前" else "新しい相手の名前(登録していない人)") },
                    singleLine = true,
                )
                DateField("貸出日", lentAt, { lentAt = it })
                DateField("返却予定日", expected, { expected = it }, onClear = { expected = null })
                if (!dateOk) Text("返却予定日が貸出日より前です", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("返却予定日の前日と当日に、通知でお知らせします。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = {
                scope.launch {
                    val who = if (newName.isNotBlank()) {
                        val created = Player(LedgerRepository.newUuid(), newName.trim(), ColorChoices[library.players.size % ColorChoices.size].second.toArgb())
                        vm.repo.savePlayer(created)
                        created.uuid
                    } else {
                        playerUuid
                    }
                    if (who != null) onResult(vm.repo.saveLending(LendingRecord(LedgerRepository.newUuid(), gameUuid, who, lentAt, expected)))
                }
            }) { Text("貸し出す") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** 貸し出しの一覧。未返却(返却予定日の近い順)と、返却済みの履歴。 */
@Composable
fun LendingScreen(vm: LedgerViewModel, library: Library, onBack: () -> Unit, onOpenGame: (String) -> Unit) {
    val today = LocalDate.now()
    val open = remember(library) { Reminders.open(library) }
    val returned = remember(library) { library.lendingRecords.filter { it.returnedAt != null }.sortedByDescending { it.returnedAt } }
    var deleting by remember { mutableStateOf<LendingRecord?>(null) }

    fun title(l: LendingRecord) = "${library.game(l.gameUuid)?.title ?: "?"} → ${library.player(l.playerUuid)?.name ?: "?"}さん"

    SubScreen("貸し出し", onBack) { padding ->
        if (library.lendingRecords.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.SwapHoriz,
                title = "貸し出しの記録はありません",
                body = "ゲームの画面の「貸し出す」から、だれに貸したかと返却予定日を書き留められます。",
                modifier = Modifier.padding(padding),
            )
        } else LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SectionHeader("貸出中(${open.size} 件)") }
            if (open.isEmpty()) item { Text("貸しているゲームはありません", Modifier.padding(horizontal = 16.dp)) }
            items(open, key = { it.uuid }) { l ->
                ListItem(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenGame(l.gameUuid) },
                    headlineContent = { Text(title(l)) },
                    supportingContent = {
                        Text("貸出日 ${l.lentAt} ・ " + (l.expectedReturnAt?.let { "返却予定 $it(${Reminders.label(l, today)})" } ?: "返却予定日なし"))
                    },
                    trailingContent = { TextButton(onClick = { vm.act { saveLending(l.copy(returnedAt = today)) } }) { Text("返却済み") } },
                )
            }
            if (returned.isNotEmpty()) item { SectionHeader("返却済み") }
            items(returned, key = { it.uuid }) { l ->
                ListItem(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenGame(l.gameUuid) },
                    headlineContent = { Text(title(l)) },
                    supportingContent = { Text("${l.lentAt} 〜 ${l.returnedAt}") },
                    trailingContent = { IconButton(onClick = { deleting = l }) { Icon(Icons.Filled.Delete, contentDescription = "この記録を削除") } },
                )
            }
        }
    }

    deleting?.let { l ->
        ConfirmDialog(
            title = "貸し出しの記録を削除しますか",
            text = "${title(l)}(${l.lentAt} 〜 ${l.returnedAt})の記録を削除します。元に戻せません。",
            confirmLabel = "削除する",
            danger = true,
            onConfirm = {
                deleting = null
                vm.act { deleteLending(l.uuid) }
            },
            onDismiss = { deleting = null },
        )
    }
}
