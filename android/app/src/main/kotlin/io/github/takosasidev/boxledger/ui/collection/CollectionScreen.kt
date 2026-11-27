@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.takosasidev.boxledger.ui.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.takosasidev.boxledger.core.calc.Format
import io.github.takosasidev.boxledger.core.calc.GameFilter
import io.github.takosasidev.boxledger.core.calc.GameQuery
import io.github.takosasidev.boxledger.core.calc.GameStats
import io.github.takosasidev.boxledger.core.calc.SortKey
import io.github.takosasidev.boxledger.core.calc.Stats
import io.github.takosasidev.boxledger.core.calc.Suggestion
import io.github.takosasidev.boxledger.core.calc.Suggestions
import io.github.takosasidev.boxledger.core.model.Game
import io.github.takosasidev.boxledger.core.model.GameStatus
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.core.model.ViewMode
import io.github.takosasidev.boxledger.ui.LedgerViewModel
import io.github.takosasidev.boxledger.ui.components.EmptyState
import io.github.takosasidev.boxledger.ui.components.GameThumb
import io.github.takosasidev.boxledger.ui.components.GroupItem
import io.github.takosasidev.boxledger.ui.components.Stars
import io.github.takosasidev.boxledger.ui.components.Stepper
import io.github.takosasidev.boxledger.ui.components.TagBadges
import io.github.takosasidev.boxledger.ui.theme.LocalStatusColors
import java.time.LocalDate
import java.time.ZoneId

/**
 * 一覧(FR-08 / FR-13 / FR-14 / FR-22)。所有中とウィッシュリストを切り替え、箱の写真のグリッドかリストで並べる。
 * 絞り込みと並び替えの判定は :core の [GameFilter]。
 */
@Composable
fun CollectionScreen(
    vm: LedgerViewModel,
    library: Library,
    onOpen: (String) -> Unit,
    onAdd: (wish: Boolean) -> Unit,
    onScan: () -> Unit,
    onRecordPlay: (String) -> Unit,
    onLendings: () -> Unit,
) {
    val rawQuery by vm.query.collectAsStateWithLifecycle()
    val chosenMode by vm.viewMode.collectAsStateWithLifecycle()
    val collapsed by vm.suggestionsCollapsed.collectAsStateWithLifecycle()
    val query = rawQuery.copy(showExpansions = library.settings.showExpansionsInList)
    val mode = chosenMode ?: library.settings.defaultViewMode
    val wish = query.status == GameStatus.WISHLIST

    val games = remember(library, query) { GameFilter.apply(library, query) }
    val stats = remember(library) { Stats.allGameStats(library) }
    val suggestions = remember(library) {
        Suggestions.unplayed(library, LocalDate.now(), library.settings.unplayedSuggestionThresholdDays, ZoneId.systemDefault())
    }
    var showFilter by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    // 大きな見出しの下の要約。数は統計の画面と同じ集計(:core の Stats.dashboard)を使う
    val dashboard = remember(library) { Stats.dashboard(library) }
    val lentCount = library.lendingRecords.count { it.returnedAt == null }
    val summary = if (wish) {
        "ほしいゲーム ${dashboard.wishlist} 本"
    } else {
        "所有 ${dashboard.ownedGames} 本" + if (lentCount > 0) " ・ 貸出中 $lentCount 本" else ""
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                LargeFlexibleTopAppBar(
                    title = { Text("コレクション") },
                    subtitle = { Text(summary) },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(onClick = { vm.viewMode.value = if (mode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID }) {
                            if (mode == ViewMode.GRID) {
                                Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "リストで表示")
                            } else {
                                Icon(Icons.Filled.GridView, contentDescription = "グリッドで表示")
                            }
                        }
                        IconButton(onClick = { showSort = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "並び替え") }
                        DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                            SortKey.entries.forEach { key ->
                                DropdownMenuItem(
                                    text = { Text(key.label + if (query.sort == key) " ✓" else "") },
                                    onClick = {
                                        vm.query.value = rawQuery.copy(sort = key)
                                        showSort = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (query.descending) "降順 → 昇順にする" else "昇順 → 降順にする") },
                                leadingIcon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
                                onClick = {
                                    vm.query.value = rawQuery.copy(descending = !query.descending)
                                    showSort = false
                                },
                            )
                        }
                        IconButton(onClick = { showFilter = true }) {
                            BadgedBox(badge = { if (query.hasFilter) Badge() }) {
                                Icon(Icons.Filled.FilterList, contentDescription = if (query.hasFilter) "絞り込み(設定あり)" else "絞り込み")
                            }
                        }
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "そのほか") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("貸し出しの一覧") },
                                onClick = {
                                    showMenu = false
                                    onLendings()
                                },
                            )
                        }
                    },
                )
                // 検索欄と「所有 / ウィッシュリスト」は見出しの下に置いたままにする(一覧が空でも切り替えられるように)
                TextField(
                    value = query.text,
                    onValueChange = { vm.query.value = rawQuery.copy(text = it) },
                    placeholder = { Text("タイトル・保管場所・バーコードで検索", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.text.isNotEmpty()) {
                            IconButton(onClick = { vm.query.value = rawQuery.copy(text = "") }) { Icon(Icons.Filled.Close, contentDescription = "検索を消す") }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    listOf(GameStatus.OWNED to "所有", GameStatus.WISHLIST to "ウィッシュリスト").forEachIndexed { index, (status, label) ->
                        SegmentedButton(
                            selected = query.status == status,
                            onClick = { vm.query.value = rawQuery.copy(status = status) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(label, maxLines = 1) }
                    }
                }
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(onClick = onScan) { Icon(Icons.Filled.QrCodeScanner, contentDescription = "バーコードを読む") }
                ExtendedFloatingActionButton(
                    onClick = { onAdd(wish) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(if (wish) "ほしいゲームを追加" else "ゲームを追加") },
                )
            }
        },
    ) { padding ->
        val nothingRegistered = library.games.none { it.status == query.status }
        when {
            nothingRegistered && !wish -> EmptyState(
                icon = Icons.Filled.Inventory2,
                title = "まだゲームがありません",
                body = "右下の ＋ から持っているゲームを登録します。箱のバーコードを読んで登録することもできます。",
                modifier = Modifier.padding(padding),
                actionLabel = "ゲームを追加",
                onAction = { onAdd(false) },
            )
            nothingRegistered -> EmptyState(
                icon = Icons.Filled.Inventory2,
                title = "ウィッシュリストは空です",
                body = "ほしいけれどまだ持っていないゲームを、右下の ＋ から書き留めておけます。",
                modifier = Modifier.padding(padding),
                actionLabel = "ほしいゲームを追加",
                onAction = { onAdd(true) },
            )
            else -> LazyVerticalGrid(
                columns = if (mode == ViewMode.GRID) GridCells.Adaptive(150.dp) else GridCells.Fixed(1),
                modifier = Modifier.padding(padding).fillMaxSize(),
                // 下の余白は、右下のボタンに最後の行が隠れないように
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                // リストは 1 つのまとまりとして細い隙間で並べる。グリッドはカードどうしを離す
                verticalArrangement = Arrangement.spacedBy(if (mode == ViewMode.GRID) 10.dp else 3.dp),
            ) {
                if (!wish && suggestions.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SuggestionBanner(
                            suggestions = suggestions,
                            collapsed = collapsed,
                            onToggle = { vm.suggestionsCollapsed.value = !collapsed },
                            onOpen = onOpen,
                            onRecordPlay = onRecordPlay,
                            onHide = { game -> vm.act { saveGame(game.copy(excludeFromSuggestion = true)) } },
                            modifier = Modifier.padding(bottom = if (mode == ViewMode.GRID) 0.dp else 9.dp),
                        )
                    }
                }
                if (games.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("条件に合うゲームはありません")
                            TextButton(onClick = { vm.query.value = GameQuery(status = query.status, sort = query.sort, descending = query.descending) }) {
                                Text("絞り込みと検索を消す")
                            }
                        }
                    }
                }
                itemsIndexed(games, key = { _, it -> it.uuid }) { index, game ->
                    if (mode == ViewMode.GRID) {
                        GameCard(library, game, stats[game.uuid]) { onOpen(game.uuid) }
                    } else {
                        GameRow(library, game, stats[game.uuid], index, games.size) { onOpen(game.uuid) }
                    }
                }
            }
        }
    }

    if (showFilter) {
        FilterSheet(library, query, onChange = { vm.query.value = it }, onDismiss = { showFilter = false })
    }
}

/** 「3〜4人 ・ 60〜90分」。どちらも無ければ空。 */
private fun specText(game: Game): String =
    listOfNotNull(Format.players(game.minPlayers, game.maxPlayers), Format.minutes(game.minPlayTimeMinutes, game.maxPlayTimeMinutes)).joinToString(" ・ ")

/** 「拡張 3」「アーカイブ」などの印。色ではなく文字で示す。「貸出中」は注意の色で別に書くので、ここには入れない。 */
private fun badgeText(library: Library, game: Game): String = listOfNotNull(
    library.expansionsOf(game.uuid).size.takeIf { it > 0 }?.let { "拡張 $it" },
    "拡張".takeIf { game.isExpansion },
    "アーカイブ".takeIf { game.isArchived },
).joinToString(" ・ ")

/** サムネイルの写真が無いときの地の色。最初のジャンルタグの色にして、箱ごとに見分けやすくする。 */
private fun thumbTone(library: Library, game: Game): Color? =
    game.genreTagUuids.firstNotNullOfOrNull { uuid -> library.genreTags.firstOrNull { it.uuid == uuid } }?.let { Color(it.colorArgb) }

/** 「貸出中 ・ 拡張 1 ・ プレイ 8 回」。貸出中だけ注意の色にする(文字でも書く)。 */
@Composable
private fun StatusLine(library: Library, game: Game, stats: GameStats?, showPlays: Boolean) {
    val lent = library.openLending(game.uuid) != null
    val rest = listOf(badgeText(library, game), if (showPlays && game.status == GameStatus.OWNED) "プレイ ${stats?.playCount ?: 0} 回" else "")
        .filter { it.isNotEmpty() }.joinToString(" ・ ")
    if (!lent && rest.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (lent) {
            Text("貸出中", style = MaterialTheme.typography.labelMedium, color = LocalStatusColors.current.warn)
            if (rest.isNotEmpty()) Text(" ・ ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(rest, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GameCard(library: Library, game: Game, stats: GameStats?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        // サムネイルはカードの内側に余白を取って置く(角の丸みはカードより一段小さく)
        GameThumb(game, Modifier.padding(6.dp).fillMaxWidth().aspectRatio(1f), corner = 18.dp, tone = thumbTone(library, game))
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(game.title, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val spec = specText(game)
            if (spec.isNotEmpty()) Text(spec, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            StatusLine(library, game, stats, showPlays = true)
            TagBadges(library.genreTags.filter { it.uuid in game.genreTagUuids }.take(3), Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun GameRow(library: Library, game: Game, stats: GameStats?, index: Int, count: Int, onClick: () -> Unit) {
    GroupItem(index, count, onClick = onClick) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            GameThumb(game, Modifier.size(64.dp), reqPx = 160, corner = 16.dp, tone = thumbTone(library, game))
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(game.title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val spec = specText(game)
                if (spec.isNotEmpty()) Text(spec, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                StatusLine(library, game, stats, showPlays = true)
            }
            if (game.personalRating != null) Stars(game.personalRating)
        }
    }
}

/** FR-13: 未プレイの提案。畳んだ状態では件数だけを出す。 */
@Composable
private fun SuggestionBanner(
    suggestions: List<Suggestion>,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onOpen: (String) -> Unit,
    onRecordPlay: (String) -> Unit,
    onHide: (Game) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Casino, contentDescription = null, modifier = Modifier.size(22.dp))
                Text(
                    "そろそろ遊びませんか(${suggestions.size} 件)",
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                Text(if (collapsed) "開く" else "畳む", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            if (!collapsed) {
                suggestions.take(5).forEach { s ->
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp)) {
                        Text(
                            "そろそろ「${s.game.title}」を遊びませんか",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.clickable { onOpen(s.game.uuid) },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (s.lastPlayed != null) "最後に遊んでから ${s.days} 日" else "まだ遊んでいません(${s.days} 日)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                        )
                        Row {
                            TextButton(onClick = { onRecordPlay(s.game.uuid) }) { Text("プレイを記録") }
                            TextButton(onClick = { onHide(s.game) }) { Text("この提案を非表示にする") }
                        }
                    }
                }
                if (suggestions.size > 5) {
                    Text("ほか ${suggestions.size - 5} 件", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
                }
            }
        }
    }
}

/** 絞り込み。項目同士は AND、ジャンル同士は OR。変えたらすぐに一覧へ映る。 */
@Composable
private fun FilterSheet(library: Library, query: GameQuery, onChange: (GameQuery) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("遊ぶ人数で絞る", Modifier.weight(1f))
                Switch(checked = query.players != null, onCheckedChange = { on -> onChange(query.copy(players = if (on) 4 else null)) })
            }
            query.players?.let { n -> Stepper("人数", n, 1..10, "人") { onChange(query.copy(players = it)) } }
            Text("プレイ時間(〜分まで)", Modifier.padding(start = 16.dp, top = 12.dp))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null, 30, 60, 90, 120, 180).forEach { limit ->
                    FilterChip(
                        selected = query.maxMinutes == limit,
                        onClick = { onChange(query.copy(maxMinutes = limit)) },
                        label = { Text(limit?.let { "$it 分" } ?: "指定なし") },
                    )
                }
            }
            if (library.genreTags.isNotEmpty()) {
                Text("ジャンル(どれかを含む)", Modifier.padding(start = 16.dp, top = 12.dp))
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    library.genreTags.forEach { tag ->
                        val on = tag.uuid in query.genreUuids
                        FilterChip(
                            selected = on,
                            onClick = { onChange(query.copy(genreUuids = if (on) query.genreUuids - tag.uuid else query.genreUuids + tag.uuid)) },
                            label = { Text(tag.name) },
                        )
                    }
                }
            }
            Text("評価(星 〜 以上)", Modifier.padding(start = 16.dp, top = 12.dp))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null, 2, 3, 4, 5).forEach { min ->
                    FilterChip(
                        selected = query.minRating == min,
                        onClick = { onChange(query.copy(minRating = min)) },
                        label = { Text(min?.let { "$it 以上" } ?: "指定なし") },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("貸出中だけ", Modifier.weight(1f))
                Switch(checked = query.lentOnly, onCheckedChange = { onChange(query.copy(lentOnly = it)) })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("アーカイブしたゲームも出す", Modifier.weight(1f))
                Switch(checked = query.includeArchived, onCheckedChange = { onChange(query.copy(includeArchived = it)) })
            }
            OutlinedButton(
                onClick = { onChange(GameQuery(text = query.text, status = query.status, sort = query.sort, descending = query.descending)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("絞り込みを消す") }
        }
    }
}
