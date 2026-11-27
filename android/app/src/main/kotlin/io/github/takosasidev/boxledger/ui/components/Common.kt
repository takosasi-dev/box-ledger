// 複数の画面で使う部品。
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.takosasidev.boxledger.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.takosasidev.boxledger.core.model.Game
import io.github.takosasidev.boxledger.core.model.GenreTag
import io.github.takosasidev.boxledger.data.PhotoLoader
import io.github.takosasidev.boxledger.ui.theme.ColorChoices
import io.github.takosasidev.boxledger.ui.theme.colorTones
import io.github.takosasidev.boxledger.ui.theme.onColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** 戻るボタンの付いた、掘り下げた先の画面の枠。 */
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
                actions = actions,
            )
        },
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

/**
 * 箱の写真。写真が無い・消えている・壊れているなら、淡い地の上に箱のアイコン。
 * 写真のファイルが端末から消えていても落ちない(PhotoLoader が null を返す)。大きさは呼ぶ側の modifier で決める。
 * [tone] を渡すと、写真が無いときの地と記号をその色の淡い組にする(一覧で箱ごとに見分けやすくするため)。
 */
@Composable
fun GameThumb(game: Game, modifier: Modifier = Modifier, reqPx: Int = 256, corner: Dp = 16.dp, tone: Color? = null) {
    PhotoBox(game.boxPhotoFile, modifier, reqPx, corner, tone)
}

@Composable
fun PhotoBox(photoFile: String?, modifier: Modifier = Modifier, reqPx: Int = 256, corner: Dp = 16.dp, tone: Color? = null) {
    val context = LocalContext.current
    var bitmap by remember(photoFile) { mutableStateOf<ImageBitmap?>(photoFile?.let { PhotoLoader.cached(it, reqPx)?.asImageBitmap() }) }
    LaunchedEffect(photoFile) {
        if (photoFile != null && bitmap == null) bitmap = PhotoLoader.decodeSampled(context, photoFile, reqPx)?.asImageBitmap()
    }
    val (background, foreground) = tone?.let { colorTones(it) }
        ?: (MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant)
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(corner)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            // 枠の大きさに合わせて記号の大きさを決める(小さいサムネイルで記号がはみ出さないように)
            Icon(Icons.Filled.Inventory2, contentDescription = null, tint = foreground, modifier = Modifier.size(minOf(maxWidth, maxHeight) * 0.42f))
        }
    }
}

/** まとまったリストの 1 枚の角。端だけ大きく丸め、間は小さくする(Android 16 の設定の画面と同じ形)。 */
fun groupShape(index: Int, count: Int): Shape {
    val big = 24.dp
    val small = 6.dp
    val top = if (index == 0) big else small
    val bottom = if (index == count - 1) big else small
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** まとまりの中の 1 枚。地は surfaceContainerHigh。[onClick] があれば押せる。中の ListItem は地を透明にして使う。 */
@Composable
fun GroupItem(index: Int, count: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    // 中身は縦に積む(Surface は重ねて置くので、2 つ以上並べると重なる)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = groupShape(index, count), color = color) { Column { content() } }
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = groupShape(index, count), color = color) { Column { content() } }
    }
}

/** まとまりの中に置く ListItem の色(地は透明。まとまりの地を見せる)。 */
@Composable
fun groupListItemColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** 見出しと、角の丸いまとまり。行どうしは細い隙間で区切る。 */
@Composable
fun Group(title: String?, vararg rows: @Composable () -> Unit) {
    if (title != null) SectionHeader(title, Modifier.padding(top = 4.dp))
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEachIndexed { i, row -> GroupItem(i, rows.size) { Column { row() } } }
    }
}

/** 行の頭の丸いアイコン。 */
@Composable
fun LeadingIcon(icon: ImageVector) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp)) }
}

/** プレイヤーの丸。その人の色の淡い地に、名前の頭の 1 文字を濃い色で書く(色だけで人を見分けさせない)。 */
@Composable
fun PlayerAvatar(name: String, colorArgb: Int, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val (background, foreground) = colorTones(Color(colorArgb))
    Box(modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Text(
            name.take(1),
            color = foreground,
            style = if (size >= 56.dp) MaterialTheme.typography.headlineSmallEmphasized else MaterialTheme.typography.titleMediumEmphasized,
        )
    }
}

/**
 * 横の棒。後ろに枠を敷いて全体の長さを見せる。値は呼ぶ側で文字でも書く(長さだけに頼らない)。
 * [track] は棒の下の地。まとまり(surfaceContainerHigh)の上なら既定の surfaceContainerLowest が浮いて見える。
 */
@Composable
fun MeterBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 10.dp, track: Color = MaterialTheme.colorScheme.surfaceContainerLowest) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(track)) {
        if (fraction > 0f) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(height / 2)).background(color))
        }
    }
}

/** 何も無い画面で、次にすることを促す。 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(112.dp).clip(RoundedCornerShape(36.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(56.dp)) }
        Text(title, style = MaterialTheme.typography.titleLargeEmphasized, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (actionLabel != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 24.dp)) { Text(actionLabel) }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

/** 5 段階の星。[onChange] があれば押して変えられる(同じ星をもう一度押すと未設定に戻る)。 */
@Composable
fun Stars(rating: Int?, modifier: Modifier = Modifier, onChange: ((Int?) -> Unit)? = null) {
    Row(modifier.semantics { contentDescription = rating?.let { "評価 $it / 5" } ?: "評価なし" }) {
        for (n in 1..5) {
            val filled = rating != null && n <= rating
            val icon = if (filled) Icons.Filled.Star else Icons.Filled.StarBorder
            val tint = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            if (onChange != null) {
                IconButton(onClick = { onChange(if (rating == n) null else n) }) { Icon(icon, contentDescription = "星 $n", tint = tint) }
            } else {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** ジャンルタグのバッジ。色だけに頼らず、名前も書く。濃いベタ塗りではなく、タグの色の淡い地に濃い文字。 */
@Composable
fun TagBadges(tags: List<GenreTag>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { tag ->
            val (background, foreground) = colorTones(Color(tag.colorArgb))
            Text(
                tag.name,
                color = foreground,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(background).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** 日付を選ぶボタン。押すとカレンダーが出る。[onClear] があれば「未設定」に戻せる。 */
@Composable
fun DateField(label: String, date: LocalDate?, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier, onClear: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }) { Text("$label: ${date ?: "未設定"}") }
        if (onClear != null && date != null) TextButton(onClick = onClear) { Text("外す") }
    }
    if (open) {
        // カレンダーの部品は UTC の 0 時のミリ秒で日付を持つ
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("決定") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("キャンセル") } },
        ) { DatePicker(state) }
    }
}

/** 色の候補から 1 つ選ぶ。選んでいる色にはチェックを付ける(色だけで示さない)。 */
@Composable
fun ColorRow(selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ColorChoices.forEach { (name, color) ->
            val argb = color.toArgb()
            val isSelected = argb == selected
            Box(
                Modifier.size(48.dp).clickable { onSelect(argb) }.semantics { contentDescription = name + if (isSelected) "(選択中)" else "" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(color)
                        .border(if (isSelected) 3.dp else 0.dp, if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, tint = onColor(color), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * 数を 1 つずつ増減する行(設定の日数など)。−/＋ は押している間に丸から角丸の四角へ形が変わる(Expressive)。
 * 大きさは M3 の既定のまま(見た目 40dp、タップの領域は 48dp)。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Stepper(label: String, value: Int, range: IntRange, unit: String, step: Int = 1, icon: ImageVector? = null, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) LeadingIcon(icon)
        Text(label, Modifier.weight(1f).padding(start = if (icon != null) 16.dp else 0.dp), style = MaterialTheme.typography.bodyLarge)
        FilledTonalIconButton(enabled = value - step >= range.first, onClick = { onChange(value - step) }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Filled.Remove, contentDescription = "$label を減らす", modifier = Modifier.size(20.dp))
        }
        Text("$value $unit", style = MaterialTheme.typography.titleSmallEmphasized, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 72.dp).padding(horizontal = 4.dp))
        FilledTonalIconButton(enabled = value + step <= range.last, onClick = { onChange(value + step) }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Filled.Add, contentDescription = "$label を増やす", modifier = Modifier.size(20.dp))
        }
    }
}

private const val ConfettiMs = 1_500
private const val ConfettiPieces = 44

private class ConfettiPiece(random: Random, val color: Color) {
    val x = random.nextFloat()

    /** 落ち始めるまでの遅れ。全部が同時に落ちると機械的に見えるのでばらす。 */
    val delay = random.nextFloat() * 0.3f
    val fall = 0.85f + random.nextFloat() * 0.45f
    val drift = (random.nextFloat() - 0.5f) * 0.16f
    val spin = 1.5f + random.nextFloat() * 3.5f
    val width = 12f + random.nextFloat() * 10f
    val heightRatio = 0.35f + random.nextFloat() * 0.5f
}

/**
 * プレイ記録の保存の演出。紙吹雪を 1 回だけ流し、終わったら [onFinished] を呼ぶ。
 * 書き方は収支管理アプリ(win-loss-tracker)の Celebration と同じ。ライブラリは足さず、Canvas に自分で描く。
 * タッチは奪わない(Canvas は入力を受けない)。
 */
@Composable
fun Confetti(onFinished: () -> Unit) {
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        Color(0xFFF5C518),
    )
    // 種を固定する。描き直しのたびに紙吹雪が生え変わらないように
    val pieces = remember(palette) {
        val random = Random(20261126)
        List(ConfettiPieces) { ConfettiPiece(random, palette[it % palette.size]) }
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = ConfettiMs, easing = LinearEasing))
        onFinished()
    }
    Canvas(Modifier.fillMaxSize()) {
        val p = progress.value
        for (piece in pieces) {
            val t = ((p - piece.delay) / (1f - piece.delay)).coerceIn(0f, 1f)
            if (t <= 0f) continue
            val w = piece.width
            val h = piece.width * piece.heightRatio
            val cx = size.width * piece.x + sin(t * piece.spin * PI.toFloat()) * size.width * piece.drift
            val cy = -h + t * piece.fall * (size.height + h * 2f)
            // 終わりに薄くして、下の端に張り付いたまま残らないようにする
            val alpha = if (t > 0.75f) ((1f - t) / 0.25f).coerceIn(0f, 1f) else 1f
            rotate(degrees = t * piece.spin * 180f, pivot = Offset(cx, cy)) {
                drawRect(color = piece.color.copy(alpha = alpha), topLeft = Offset(cx - w / 2f, cy - h / 2f), size = Size(w, h))
            }
        }
    }
}
