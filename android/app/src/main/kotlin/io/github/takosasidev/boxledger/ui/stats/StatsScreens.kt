@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.takosasidev.boxledger.ui.stats

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

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** 「名前 ████ 3」の横棒。長さだけでなく数も書く。 */
@Composable
private fun BarRow(label: String, count: Int, max: Int, unit: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(120.dp), maxLines = 1)
        LinearProgressIndicator(
            progress = { if (max == 0) 0f else count.toFloat() / max },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)),
        )
        Text("$count $unit", Modifier.width(64.dp), textAlign = TextAlign.End)
    }
}

/** コレクションの統計(FR-10)。 */
@Composable
fun StatsScreen(library: Library, onYearReview: () -> Unit) {
    val d = remember(library) { Stats.dashboard(library) }
    Scaffold(topBar = { TopAppBar(title = { Text("統計") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("所有しているゲーム", "${d.ownedGames}", Modifier.weight(1f))
                StatCard("拡張", "${d.ownedExpansions}", Modifier.weight(1f))
                StatCard("ほしい物", "${d.wishlist}", Modifier.weight(1f))
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("購入額の合計", Format.yen(d.totalPrice), Modifier.weight(1f))
                StatCard("プレイの回数", "${d.totalPlays}", Modifier.weight(1f))
            }
            Text(
                "所有の数と購入額は、アーカイブした物とウィッシュリストを除きます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionHeader("よく遊んだゲーム")
            if (d.mostPlayed.isEmpty()) Text("まだプレイの記録がありません", Modifier.padding(horizontal = 16.dp))
            val top = d.mostPlayed.firstOrNull()?.second ?: 0
            d.mostPlayed.forEach { (game, count) -> BarRow(game.title, count, top, "回") }

            SectionHeader("複雑度の分布")
            val maxLevel = d.complexity.maxOrNull() ?: 0
            if (maxLevel == 0) Text("複雑度を入れたゲームがありません", Modifier.padding(horizontal = 16.dp))
            d.complexity.forEachIndexed { index, count -> BarRow("複雑度 ${index + 1}", count, maxLevel, "件") }

            Button(onClick = onYearReview, modifier = Modifier.padding(16.dp)) { Text("年間の振り返りを見る") }
        }
    }
}

@Composable
private fun Slide(title: String, big: String, lines: List<String>) {
    Card(Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(big, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp))
            lines.forEach { Text(it, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 2.dp)) }
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
