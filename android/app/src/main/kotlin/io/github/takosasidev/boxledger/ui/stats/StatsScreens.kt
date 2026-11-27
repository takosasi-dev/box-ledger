@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.takosasidev.boxledger.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import io.github.takosasidev.boxledger.ui.components.MeterBar
import io.github.takosasidev.boxledger.ui.components.groupShape

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.takosasidev.boxledger.core.calc.Format
import io.github.takosasidev.boxledger.core.calc.Stats
import io.github.takosasidev.boxledger.core.calc.YearReview
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.ui.components.SectionHeader
import io.github.takosasidev.boxledger.ui.components.SubScreen
import java.time.LocalDate

/** 数の札。ラベルは小さく、数は大きな強調の文字にする。 */
@Composable
private fun StatTile(label: String, value: String, shape: Shape, modifier: Modifier = Modifier) {
    Surface(modifier, shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** 見出しのついた 1 枚。 */
@Composable
private fun Panel(title: String, shape: Shape, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** 「名前 ████ 3 回」の横棒。長さだけでなく数も書く。棒の後ろに枠を敷いて全体の長さを見せる。 */
@Composable
private fun BarRow(label: String, count: Int, max: Int, unit: String, strong: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count $unit", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
        }
        MeterBar(
            if (max == 0) 0f else count.toFloat() / max,
            if (strong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
        )
    }
}

/** 複雑度 1〜5 の縦棒。どの段にも空の枠を描き、0 件の段も「無い」と分かるようにする。件数は棒の下に書く。 */
@Composable
private fun ComplexityChart(counts: List<Int>) {
    val max = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        counts.forEachIndexed { i, count ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (count > 0) {
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight(count.toFloat() / max).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f + 0.13f * i)),
                        )
                    }
                }
                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                Text("$count 件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** コレクションの統計(FR-10)。 */
@Composable
fun StatsScreen(library: Library, onYearReview: () -> Unit) {
    val d = remember(library) { Stats.dashboard(library) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeFlexibleTopAppBar(title = { Text("統計") }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 上の札は 2 段のまとまり。外側の角だけ大きく丸める
            val big = 24.dp
            val small = 6.dp
            // 札の高さをそろえる(数の字の高さが札ごとに違っても、段の下の線がずれないように)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                StatTile("所有のゲーム", "${d.ownedGames}", RoundedCornerShape(big, small, small, small), Modifier.weight(1f).fillMaxHeight())
                StatTile("拡張", "${d.ownedExpansions}", RoundedCornerShape(small), Modifier.weight(1f).fillMaxHeight())
                StatTile("ほしい物", "${d.wishlist}", RoundedCornerShape(small, big, small, small), Modifier.weight(1f).fillMaxHeight())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                StatTile("購入額の合計", Format.yen(d.totalPrice), RoundedCornerShape(small, small, small, big), Modifier.weight(1.4f).fillMaxHeight())
                StatTile("プレイの回数", "${d.totalPlays}", RoundedCornerShape(small, small, big, small), Modifier.weight(1f).fillMaxHeight())
            }
            Text(
                "所有の数と購入額は、アーカイブした物とウィッシュリストを除きます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )

            Panel("よく遊んだゲーム", groupShape(0, 2)) {
                if (d.mostPlayed.isEmpty()) Text("まだプレイの記録がありません", style = MaterialTheme.typography.bodySmall)
                val top = d.mostPlayed.firstOrNull()?.second ?: 0
                d.mostPlayed.forEachIndexed { i, (game, count) -> BarRow("${i + 1}. ${game.title}", count, top, "回", strong = i == 0) }
            }

            Panel("複雑度の分布", groupShape(1, 2)) {
                if ((d.complexity.maxOrNull() ?: 0) == 0) {
                    Text("複雑度を入れたゲームがありません", style = MaterialTheme.typography.bodySmall)
                } else {
                    ComplexityChart(d.complexity)
                }
            }

            FilledTonalButton(onClick = onYearReview, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp)) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("年間の振り返りを見る", Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun Slide(title: String, big: String, lines: List<String>) {
    Surface(Modifier.fillMaxSize().padding(16.dp), shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.titleMediumEmphasized, textAlign = TextAlign.Center)
            Text(big, style = MaterialTheme.typography.displayMediumEmphasized, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 20.dp))
            lines.forEach {
                Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 3.dp))
            }
        }
    }
}

private fun slides(y: YearReview): List<Triple<String, String, List<String>>> = listOfNotNull(
    Triple("${y.year} 年に遊んだ回数", "${y.plays} 回", listOf("${y.distinctGames} 種類のゲームを遊びました", "記録した時間の合計 ${Format.duration(y.totalMinutes)}")),
    y.topGames.firstOrNull()?.let { (game, count) ->
        Triple("いちばん遊んだゲーム", game.title, listOf("$count 回") + y.topGames.drop(1).mapIndexed { i, (g, n) -> "${i + 2} 位 ${g.title}($n 回)" })
    },
    Triple("新しく買ったゲーム", "${y.newPurchases} 件", listOf("購入額の合計 ${Format.yen(y.purchaseTotal)}")),
    y.self?.let { Triple("自分の成績", "勝率 ${it.winRateText}", listOf("${it.wins} 勝 / ${it.plays} 回")) },
    y.topPartner?.let { (name, count) -> Triple("いちばん一緒に遊んだ人", "${name}さん", listOf("$count 回")) },
)

/** 年間の振り返り(FR-25)。横にめくるスライド。今年は「進行中」として今日までの分で出す。 */
@Composable
fun YearReviewScreen(library: Library, onBack: () -> Unit) {
    val today = LocalDate.now()
    val years = remember(library) { Stats.years(library, today) }
    var year by remember { mutableIntStateOf(today.year) }
    val pages = remember(library, year) { slides(Stats.yearReview(library, year)) }
    val pager = rememberPagerState(pageCount = { pages.size })

    SubScreen("年間の振り返り", onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                years.forEach { y -> FilterChip(selected = y == year, onClick = { year = y }, label = { Text("$y 年") }) }
            }
            if (year == today.year) {
                Text(
                    "集計期間(進行中): $year 年 1 月 1 日 〜 今日",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                val (title, big, lines) = pages[page]
                Slide(title, big, lines)
            }
            Text(
                "${pager.currentPage + 1} / ${pages.size}(横にめくります)",
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
        }
    }
}
