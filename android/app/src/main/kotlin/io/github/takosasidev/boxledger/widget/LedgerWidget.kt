package io.github.takosasidev.boxledger.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.takosasidev.boxledger.MainActivity
import io.github.takosasidev.boxledger.core.calc.Reminders
import io.github.takosasidev.boxledger.core.calc.Suggestions
import io.github.takosasidev.boxledger.data.LedgerRepository
import java.time.LocalDate
import java.time.ZoneId

/** FR-18: ホーム画面のウィジェット。貸出中のゲームの返却予定と、しばらく遊んでいないゲームを並べ、タップでアプリを開く。 */
class LedgerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LedgerWidget
}

/** 1 行分。right が赤(超過)かどうかを文字でも分かるように、文そのものに「超過」と書く。 */
private data class WidgetLine(val left: String, val right: String, val alert: Boolean = false)

private data class WidgetData(val lent: List<WidgetLine>, val lentMore: Int, val unplayed: List<WidgetLine>)

/** 並べる行の上限。3x2 の大きさに収まる数。 */
private const val MAX_LENT = 3
private const val MAX_UNPLAYED = 2

object LedgerWidget : GlanceAppWidget() {

    /** データが変わるたびに呼ぶ。30 分ごとの更新を待つと、貸し出してもすぐに映らない。 */
    suspend fun refresh(context: Context) {
        runCatching { updateAll(context) }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // provideContent は戻ってこないので、DB の読み取りは必ずその前に済ませ、失敗も握る。
        // ここで投げるとホーム画面に「読み込めません」が出る。
        val data = runCatching {
            val library = LedgerRepository.get(context).libraryOnce()
            val today = LocalDate.now()
            val open = Reminders.open(library)
            WidgetData(
                lent = open.take(MAX_LENT).map { l ->
                    val label = Reminders.label(l, today)
                    WidgetLine(library.game(l.gameUuid)?.title.orEmpty(), label ?: "予定日なし", alert = label?.endsWith("超過") == true)
                },
                lentMore = (open.size - MAX_LENT).coerceAtLeast(0),
                unplayed = Suggestions.unplayed(library, today, library.settings.unplayedSuggestionThresholdDays, ZoneId.systemDefault())
                    .take(MAX_UNPLAYED).map { WidgetLine(it.game.title, "${it.days} 日") },
            )
        }.getOrNull()

        provideContent { Body(data) }
    }
}

@Composable
private fun Body(data: WidgetData?) {
    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            if (data == null) {
                Title("BoxLedger")
                Line(WidgetLine("-", ""))
            } else {
                Title("貸出中")
                if (data.lent.isEmpty()) Line(WidgetLine("貸しているゲームはありません", ""))
                data.lent.forEach { Line(it) }
                if (data.lentMore > 0) Line(WidgetLine("ほか ${data.lentMore} 件", ""))
                if (data.unplayed.isNotEmpty()) {
                    Title("そろそろ遊びませんか")
                    data.unplayed.forEach { Line(it) }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text = text,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold),
        modifier = GlanceModifier.padding(top = 2.dp),
    )
}

@Composable
private fun Line(line: WidgetLine) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = line.left,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = line.right,
            maxLines = 1,
            style = TextStyle(
                color = if (line.alert) GlanceTheme.colors.error else GlanceTheme.colors.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}
