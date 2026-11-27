@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.takosasidev.boxledger.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import io.github.takosasidev.boxledger.ui.components.GameThumb
import io.github.takosasidev.boxledger.ui.components.GroupItem
import io.github.takosasidev.boxledger.ui.components.LeadingIcon
import io.github.takosasidev.boxledger.ui.components.PlayerAvatar
import io.github.takosasidev.boxledger.ui.theme.LocalStatusColors
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.takosasidev.boxledger.core.calc.Format
import io.github.takosasidev.boxledger.core.calc.Ranking
import io.github.takosasidev.boxledger.core.model.GameStatus
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.core.model.PlayResult
import io.github.takosasidev.boxledger.core.model.PlaySession
import io.github.takosasidev.boxledger.core.model.Player
import io.github.takosasidev.boxledger.core.model.WinCondition
import io.github.takosasidev.boxledger.data.LedgerRepository
import io.github.takosasidev.boxledger.ui.LedgerViewModel
import io.github.takosasidev.boxledger.ui.components.Confetti
import io.github.takosasidev.boxledger.ui.components.ConfirmDialog
import io.github.takosasidev.boxledger.ui.components.DateField
import io.github.takosasidev.boxledger.ui.components.EmptyState
import io.github.takosasidev.boxledger.ui.components.SectionHeader
import io.github.takosasidev.boxledger.ui.components.SubScreen
import io.github.takosasidev.boxledger.ui.game.sessionSummary
import io.github.takosasidev.boxledger.ui.theme.ColorChoices
import kotlinx.coroutines.launch
import java.time.LocalDate

/** プレイ記録のタイムライン(新しい順)。月ごとの見出しで区切る。 */
@Composable
fun PlaysScreen(library: Library, onNew: () -> Unit, onOpen: (String) -> Unit) {
    val sessions = remember(library) { library.playSessions.sortedByDescending { it.playedAt } }
    val byMonth = remember(sessions) { sessions.groupBy { YearMonth.from(it.playedAt) }.toList() }
    val thisYear = LocalDate.now().year
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("プレイ記録") },
                subtitle = { if (sessions.isNotEmpty()) Text("全 ${sessions.size} 回 ・ ${thisYear} 年は ${sessions.count { it.playedAt.year == thisYear }} 回") },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Filled.Add, contentDescription = null) }, text = { Text("プレイを記録") })
        },
    ) { padding ->
        if (sessions.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.EmojiEvents,
                title = "まだプレイの記録がありません",
                body = "遊んだら、右下の ＋ から日付・参加した人・スコアと勝者を書き留めます。",
                modifier = Modifier.padding(padding),
                actionLabel = "プレイを記録",
                onAction = onNew,
            )
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                byMonth.forEach { (month, list) ->
                    item(key = "m-$month") { SectionHeader("${month.year} 年 ${month.monthValue} 月 ・ ${list.size} 回", Modifier.padding(top = 4.dp)) }
                    itemsIndexed(list, key = { _, s -> s.uuid }) { index, s ->
                        GroupItem(index, list.size, onClick = { onOpen(s.uuid) }) { SessionRow(library, s) }
                    }
                }
            }
        }
    }
}

/** タイムラインの 1 行。左に日付の札、右にゲームと結果。勝った人は文字で書く(色だけに頼らない)。 */
@Composable
private fun SessionRow(library: Library, s: PlaySession) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("${s.playedAt.dayOfMonth}", style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                s.playedAt.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(library.game(s.gameUuid)?.title ?: "?", style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sessionSummary(library, s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 入力の途中の 1 人分。スコアと順位は、打っている途中の文字のまま持つ。 */
private data class Entry(val playerUuid: String, val score: String = "", val isWinner: Boolean = false, val rank: String = "")

private fun PlayResult.toEntry() = Entry(playerUuid, score?.let(Format::number).orEmpty(), isWinner, rank?.toString().orEmpty())

private fun Entry.toResult() = PlayResult(playerUuid, score.toDoubleOrNull()?.takeIf { it.isFinite() }, isWinner, rank.toIntOrNull()?.takeIf { it >= 1 })

/**
 * プレイ記録の入力(FR-05)。スコアを打つと、ゲームの決め方(高い方 / 低い方が勝ち)に合わせて順位と勝者を埋める。
 * 埋めた後に手で直せる(次にスコアを打つまで保つ)。
 */
@Composable
fun PlayEditScreen(vm: LedgerViewModel, library: Library, uuid: String, initialGameUuid: String, onDone: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val original = remember(uuid) { library.playSessions.firstOrNull { it.uuid == uuid } }

    /** そのゲームの前回のプレイ。使った拡張と参加した人の初期値にする(少ないタップで記録するため)。 */
    fun previous(gameUuid: String?): PlaySession? = library.playSessions.filter { it.gameUuid == gameUuid }.maxByOrNull { it.playedAt }

    var gameUuid by remember { mutableStateOf(original?.gameUuid ?: initialGameUuid.ifEmpty { null }) }
    var playedAt by remember { mutableStateOf(original?.playedAt ?: LocalDate.now()) }
    var duration by remember { mutableStateOf(original?.durationMinutes?.toString().orEmpty()) }
    var memo by remember { mutableStateOf(original?.memo.orEmpty()) }
    var expansions by remember { mutableStateOf((original ?: previous(gameUuid))?.expansionUuids.orEmpty().toSet()) }
    var entries by remember {
        mutableStateOf(
            original?.results?.map { it.toEntry() }
                ?: previous(gameUuid)?.results?.map { Entry(it.playerUuid) }
                ?: library.players.filter { it.isSelf }.map { Entry(it.uuid) },
        )
    }
    var newPlayer by remember { mutableStateOf("") }
    var showGames by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var celebrating by remember { mutableStateOf(false) }

    val game = library.game(gameUuid)
    val condition = game?.winCondition ?: WinCondition.MANUAL
    val gameExpansions = library.games.filter { it.parentUuid != null && it.parentUuid == gameUuid && it.status == GameStatus.OWNED }
    val canSave = game != null && entries.isNotEmpty() && !celebrating

    /** スコアが変わったら、順位と勝者を埋め直す。 */
    fun refill(list: List<Entry>): List<Entry> {
        val filled = Ranking.autoFill(list.map { it.toResult() }, condition)
        return list.zip(filled) { e, r -> if (condition == WinCondition.MANUAL) e else e.copy(isWinner = r.isWinner, rank = r.rank?.toString().orEmpty()) }
    }

    fun update(playerUuid: String, change: (Entry) -> Entry) {
        entries = entries.map { if (it.playerUuid == playerUuid) change(it) else it }
    }

    val save: () -> Unit = {
        val g = game
        if (g != null) {
            val session = (original ?: PlaySession(LedgerRepository.newUuid(), g.uuid, playedAt)).copy(
                gameUuid = g.uuid,
                playedAt = playedAt,
                durationMinutes = duration.toIntOrNull()?.takeIf { it >= 1 },
                memo = memo.trim().ifEmpty { null },
                expansionUuids = gameExpansions.map { it.uuid }.filter { it in expansions },
                results = entries.map { it.toResult() },
            )
            scope.launch {
                vm.repo.saveSession(session)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                // 新しい記録で、勝者が決まっているときだけ紙吹雪を流す(設定で切れる)
                if (original == null && library.settings.celebrationEnabled && session.results.any { it.isWinner }) celebrating = true else onDone()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        SubScreen(
            title = if (original == null) "プレイを記録" else "プレイ記録を編集",
            onBack = onBack,
            actions = {
                if (original != null) IconButton(onClick = { showDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "削除") }
                TextButton(enabled = canSave, onClick = save) { Text("保存") }
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // どのゲームかを、箱の写真と大きな文字で見せる。押すと選び直せる
                    Surface(
                        onClick = { showGames = true },
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "ゲーム: ${game?.title ?: "選ぶ"}" },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (game != null) GameThumb(game, Modifier.size(56.dp), reqPx = 160) else LeadingIcon(Icons.Filled.EmojiEvents)
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text("ゲーム", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(game?.title ?: "選ぶ", style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // 貸出中でも記録は止めない(もう手元に戻っていることもあるため)
                    if (game != null && library.openLending(game.uuid) != null) {
                        Text("このゲームは貸出中です(記録はこのまま保存できます)", color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall)
                    }
                    DateField("日付", playedAt, { playedAt = it })
                    OutlinedTextField(
                        value = duration,
                        onValueChange = { duration = it.filter(Char::isDigit).take(4) },
                        label = { Text("プレイ時間(分・任意)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                if (gameExpansions.isNotEmpty()) {
                    SectionHeader("使った拡張")
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        gameExpansions.forEach { ex ->
                            val on = ex.uuid in expansions
                            FilterChip(selected = on, onClick = { expansions = if (on) expansions - ex.uuid else expansions + ex.uuid }, label = { Text(ex.title) })
                        }
                    }
                }

                SectionHeader("参加した人")
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    library.players.forEach { p ->
                        val on = entries.any { it.playerUuid == p.uuid }
                        FilterChip(
                            selected = on,
                            onClick = { entries = refill(if (on) entries.filter { it.playerUuid != p.uuid } else entries + Entry(p.uuid)) },
                            label = { Text(p.name) },
                        )
                    }
                }
                // 未登録の人が参加していたら、その場で登録して加える
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newPlayer,
                        onValueChange = { newPlayer = it.take(50) },
                        label = { Text("新しい人の名前") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = newPlayer.isNotBlank(), onClick = {
                        val created = Player(LedgerRepository.newUuid(), newPlayer.trim(), ColorChoices[library.players.size % ColorChoices.size].second.toArgb())
                        newPlayer = ""
                        scope.launch {
                            vm.repo.savePlayer(created)
                            entries = entries + Entry(created.uuid)
                        }
                    }) { Text("登録して加える") }
                }

                if (entries.isNotEmpty()) {
                    SectionHeader(
                        when (condition) {
                            WinCondition.HIGH_SCORE -> "スコア(高い方が勝ち。打つと順位と勝者が入ります)"
                            WinCondition.LOW_SCORE -> "スコア(低い方が勝ち。打つと順位と勝者が入ります)"
                            WinCondition.MANUAL -> "スコアと勝者(手で決めます)"
                        },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                entries.forEachIndexed { index, e ->
                    val player = library.player(e.playerUuid)
                    val name = player?.name ?: "?"
                    // 1 人分を 1 枚にし、細い隙間で並べる(勝った人は「勝ち」の印と文字で示す)
                    GroupItem(index, entries.size, Modifier.padding(horizontal = 12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (player != null) PlayerAvatar(player.name, player.colorArgb, size = 32.dp)
                        Text(name, Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.titleSmallEmphasized)
                        OutlinedTextField(
                            value = e.score,
                            onValueChange = { text -> entries = refill(entries.map { if (it.playerUuid == e.playerUuid) it.copy(score = text.take(10)) else it }) },
                            label = { Text("スコア") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = e.rank,
                            onValueChange = { text -> update(e.playerUuid) { it.copy(rank = text.filter(Char::isDigit).take(2)) } },
                            label = { Text("順位") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.7f),
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Checkbox(
                                checked = e.isWinner,
                                onCheckedChange = { on -> update(e.playerUuid) { it.copy(isWinner = on) } },
                                modifier = Modifier.semantics { contentDescription = "$name が勝ち" },
                            )
                            Text("勝ち", style = MaterialTheme.typography.labelSmall, color = if (e.isWinner) LocalStatusColors.current.good else Color.Unspecified)
                        }
                    }
                    }
                }
                }

                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    label = { Text("メモ(任意)") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
        if (celebrating) Confetti(onFinished = onDone)
    }

    if (showGames) {
        // 記録を付けるのは本体だけ(拡張は「使った拡張」で選ぶ)。所有中でアーカイブしていない物
        var search by remember { mutableStateOf("") }
        val choices = library.games
            .filter { !it.isExpansion && it.status == GameStatus.OWNED && !it.isArchived && it.title.contains(search.trim(), ignoreCase = true) }
            .sortedBy { it.title }
        AlertDialog(
            onDismissRequest = { showGames = false },
            title = { Text("ゲームを選ぶ") },
            text = {
                Column {
                    OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("検索") }, singleLine = true)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(choices, key = { it.uuid }) { g ->
                            Text(
                                g.title,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    gameUuid = g.uuid
                                    // 新しい記録なら、選んだゲームの前回のプレイに合わせ直す
                                    if (original == null) {
                                        val before = previous(g.uuid)
                                        expansions = before?.expansionUuids.orEmpty().toSet()
                                        if (before != null && entries.all { it.score.isEmpty() }) entries = before.results.map { Entry(it.playerUuid) }
                                    }
                                    showGames = false
                                }.padding(vertical = 14.dp),
                            )
                        }
                    }
                    if (choices.isEmpty()) Text("当てはまるゲームがありません。先にコレクションへ登録してください。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showGames = false }) { Text("閉じる") } },
        )
    }

    if (showDelete && original != null) {
        ConfirmDialog(
            title = "このプレイ記録を削除しますか",
            text = "${original.playedAt} の記録と、参加した人の結果を削除します。元に戻せません。",
            confirmLabel = "削除する",
            danger = true,
            onConfirm = {
                showDelete = false
                vm.act { deleteSession(original.uuid) }
                onDone()
            },
            onDismiss = { showDelete = false },
        )
    }
}
