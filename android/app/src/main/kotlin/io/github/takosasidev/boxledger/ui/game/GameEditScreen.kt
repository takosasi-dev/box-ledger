@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.takosasidev.boxledger.ui.game

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.takosasidev.boxledger.core.backup.BackupCodec
import io.github.takosasidev.boxledger.core.calc.Format
import io.github.takosasidev.boxledger.core.calc.GameFilter
import io.github.takosasidev.boxledger.core.calc.Money
import io.github.takosasidev.boxledger.core.model.Game
import io.github.takosasidev.boxledger.core.model.GameStatus
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.core.model.WinCondition
import io.github.takosasidev.boxledger.data.LedgerRepository
import io.github.takosasidev.boxledger.data.PhotoStore
import io.github.takosasidev.boxledger.ui.LedgerViewModel
import io.github.takosasidev.boxledger.ui.components.DateField
import io.github.takosasidev.boxledger.ui.components.PhotoBox
import io.github.takosasidev.boxledger.ui.components.SectionHeader
import io.github.takosasidev.boxledger.ui.components.Stars
import io.github.takosasidev.boxledger.ui.components.SubScreen
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 数字だけの入力欄(人数・分・円)。 */
@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(7)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/**
 * ゲームの登録と編集(FR-01 / FR-03 / FR-08 / FR-11 / FR-12 / FR-28 / FR-29)。
 * [barcode] はスキャンから、[parentUuid] は「拡張を追加」から引き継ぐ。
 */
@Composable
fun GameEditScreen(
    vm: LedgerViewModel,
    library: Library,
    uuid: String,
    barcode: String,
    parentUuid: String,
    wish: Boolean,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    // 開いたときの中身を元にする(入力の途中で、ほかの変更に上書きされないように)
    val original = remember(uuid) { library.game(uuid) }

    var title by remember { mutableStateOf(original?.title.orEmpty()) }
    var status by remember { mutableStateOf(original?.status ?: if (wish) GameStatus.WISHLIST else GameStatus.OWNED) }
    var parent by remember { mutableStateOf(original?.parentUuid ?: parentUuid.ifEmpty { null }) }
    var barcodeText by remember { mutableStateOf(original?.barcodeValue ?: barcode) }
    var photo by remember { mutableStateOf(original?.boxPhotoFile) }
    var minPlayers by remember { mutableStateOf(original?.minPlayers?.toString().orEmpty()) }
    var maxPlayers by remember { mutableStateOf(original?.maxPlayers?.toString().orEmpty()) }
    var minTime by remember { mutableStateOf(original?.minPlayTimeMinutes?.toString().orEmpty()) }
    var maxTime by remember { mutableStateOf(original?.maxPlayTimeMinutes?.toString().orEmpty()) }
    var complexity by remember { mutableStateOf(original?.complexityRating) }
    var price by remember { mutableStateOf(original?.purchasePrice?.toString().orEmpty()) }
    var foreign by remember { mutableStateOf(original?.purchaseCurrency != null) }
    var currency by remember { mutableStateOf(original?.purchaseCurrency ?: "USD") }
    var foreignAmount by remember { mutableStateOf(original?.purchaseForeignAmount?.toPlainString().orEmpty()) }
    var rate by remember { mutableStateOf(original?.purchaseExchangeRate?.toPlainString().orEmpty()) }
    var purchaseDate by remember { mutableStateOf(original?.purchaseDate) }
    var location by remember { mutableStateOf(original?.storageLocation.orEmpty()) }
    var rating by remember { mutableStateOf(original?.personalRating) }
    var tags by remember { mutableStateOf(original?.genreTagUuids.orEmpty().toSet()) }
    var winCondition by remember { mutableStateOf(original?.winCondition ?: WinCondition.HIGH_SCORE) }
    var showParentMenu by remember { mutableStateOf(false) }

    // ---- 入力の確かめ ----
    fun int(text: String): Int? = text.toIntOrNull()?.takeIf { it >= 1 }
    val playersOk = (int(minPlayers) ?: 0) <= (int(maxPlayers) ?: Int.MAX_VALUE)
    val timeOk = (int(minTime) ?: 0) <= (int(maxTime) ?: Int.MAX_VALUE)
    val foreignValue = foreignAmount.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
    val rateValue = rate.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val currencyOk = currency.length == 3 && currency.all { it in 'A'..'Z' }
    // 外貨で買ったときの円は、金額 × レートから自動で出す(FR-29)
    val foreignYen = if (foreignValue != null && rateValue != null) Money.yen(foreignValue, rateValue) else null
    val foreignOk = !foreign || (currencyOk && foreignYen != null)
    val yen = if (foreign) foreignYen else price.toIntOrNull()
    val canSave = title.isNotBlank() && playersOk && timeOk && foreignOk
    // 自分が拡張を持っているなら、自分を拡張にはできない(拡張の拡張は作れない)
    val hasExpansions = original != null && library.expansionsOf(original.uuid).isNotEmpty()
    val parentChoices = library.games.filter { !it.isExpansion && it.uuid != original?.uuid }.sortedBy { it.title }

    // ---- 写真 ----
    val importPhoto: (Uri) -> Unit = { uri ->
        scope.launch {
            val saved = PhotoStore.save(context, uri)
            if (saved != null) photo = saved else snackbar.showSnackbar("写真を読み込めませんでした")
        }
    }
    val captureUri = remember { runCatching { PhotoStore.captureUri(context) }.getOrNull() }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && captureUri != null) importPhoto(captureUri)
    }
    // CAMERA の権限を宣言しているアプリは、許可が無いままカメラのアプリを呼ぶと例外になるので、先に許可をもらう
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && captureUri != null) {
            runCatching { takePicture.launch(captureUri) }
                .onFailure { scope.launch { snackbar.showSnackbar("カメラのアプリを開けませんでした") } }
        } else {
            scope.launch { snackbar.showSnackbar("撮影にはカメラの許可が要ります") }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importPhoto(uri)
    }

    val save: () -> Unit = {
        val base = original ?: Game(uuid = LedgerRepository.newUuid(), title = "")
        val game = base.copy(
            title = title.trim(),
            status = status,
            parentUuid = if (hasExpansions) null else parent,
            barcodeValue = barcodeText.trim().ifEmpty { null },
            boxPhotoFile = photo,
            minPlayers = int(minPlayers), maxPlayers = int(maxPlayers),
            minPlayTimeMinutes = int(minTime), maxPlayTimeMinutes = int(maxTime),
            complexityRating = complexity,
            purchasePrice = yen,
            purchaseCurrency = currency.takeIf { foreign },
            purchaseForeignAmount = foreignValue?.takeIf { foreign }?.let(BackupCodec::normalize),
            purchaseExchangeRate = rateValue?.takeIf { foreign }?.let(BackupCodec::normalize),
            purchaseDate = purchaseDate,
            storageLocation = location.trim().ifEmpty { null },
            personalRating = rating,
            genreTagUuids = library.genreTags.map { it.uuid }.filter { it in tags },
            winCondition = winCondition,
        )
        scope.launch {
            vm.repo.saveGame(game)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onDone()
        }
    }

    SubScreen(
        title = if (original == null) "ゲームを追加" else "ゲームを編集",
        onBack = onBack,
        snackbar = snackbar,
        actions = { TextButton(enabled = canSave, onClick = save) { Text("保存") } },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoBox(photo, Modifier.size(88.dp))
                OutlinedButton(onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                    Text("撮る", Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Filled.Image, contentDescription = null)
                    Text("選ぶ", Modifier.padding(start = 6.dp))
                }
                if (photo != null) IconButton(onClick = { photo = null }) { Icon(Icons.Filled.Close, contentDescription = "写真を外す") }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(200) },
                label = { Text("タイトル(必須)") },
                singleLine = true,
                supportingText = {
                    // 同じタイトルは警告だけ(版違いを別に登録したいこともあるので、止めない)
                    if (GameFilter.hasSameTitle(library, title, original?.uuid)) Text("同じタイトルのゲームが既にあります(このまま保存できます)")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(GameStatus.OWNED to "持っている", GameStatus.WISHLIST to "ほしい").forEachIndexed { index, (value, label) ->
                    SegmentedButton(selected = status == value, onClick = { status = value }, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
                }
            }

            // 拡張なら本体を選ぶ(FR-28)。1 階層だけ
            Column {
                OutlinedButton(enabled = !hasExpansions, onClick = { showParentMenu = true }) {
                    Text("本体: ${library.game(parent)?.title ?: "なし(このゲームが本体)"}")
                }
                if (hasExpansions) {
                    Text("このゲームには拡張があるので、ほかのゲームの拡張にはできません。", style = MaterialTheme.typography.bodySmall)
                }
                DropdownMenu(expanded = showParentMenu, onDismissRequest = { showParentMenu = false }) {
                    DropdownMenuItem(text = { Text("なし(このゲームが本体)") }, onClick = {
                        parent = null
                        showParentMenu = false
                    })
                    parentChoices.forEach { g ->
                        DropdownMenuItem(text = { Text(g.title) }, onClick = {
                            parent = g.uuid
                            showParentMenu = false
                        })
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(minPlayers, { minPlayers = it }, "最少人数", Modifier.weight(1f), isError = !playersOk)
                NumberField(maxPlayers, { maxPlayers = it }, "最多人数", Modifier.weight(1f), isError = !playersOk)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(minTime, { minTime = it }, "最短(分)", Modifier.weight(1f), isError = !timeOk)
                NumberField(maxTime, { maxTime = it }, "最長(分)", Modifier.weight(1f), isError = !timeOk)
            }
            if (!playersOk || !timeOk) Text("最小が最大より大きくなっています", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

            // 複雑度: 1.0〜5.0 の 0.1 刻み(Windows 版の要件定義書 §13.2 の暫定案)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("複雑度: ${complexity ?: "未設定"}", Modifier.weight(1f))
                Switch(checked = complexity != null, onCheckedChange = { on -> complexity = if (on) 2.5 else null })
            }
            complexity?.let { value ->
                Slider(
                    value = value.toFloat(),
                    onValueChange = { complexity = (it * 10).roundToInt() / 10.0 },
                    valueRange = 1f..5f,
                    steps = 39,
                )
            }

            Text("評価")
            Stars(rating, onChange = { rating = it })

            if (library.genreTags.isNotEmpty()) {
                Text("ジャンル")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    library.genreTags.forEach { tag ->
                        val on = tag.uuid in tags
                        FilterChip(selected = on, onClick = { tags = if (on) tags - tag.uuid else tags + tag.uuid }, label = { Text(tag.name) })
                    }
                }
            }

            SectionHeader("購入", Modifier.padding(horizontal = 0.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("外貨で買った", Modifier.weight(1f))
                Switch(checked = foreign, onCheckedChange = { foreign = it })
            }
            if (foreign) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { currency = it.uppercase().filter { c -> c in 'A'..'Z' }.take(3) },
                        label = { Text("通貨") },
                        singleLine = true,
                        isError = !currencyOk,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = foreignAmount,
                        onValueChange = { foreignAmount = it.take(12) },
                        label = { Text("金額") },
                        singleLine = true,
                        isError = foreignValue == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = rate,
                        onValueChange = { rate = it.take(12) },
                        label = { Text("1 通貨 = 何円") },
                        singleLine = true,
                        isError = rateValue == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1.4f),
                    )
                }
                // オフラインなので、レートは手で入れる
                Text(
                    foreignYen?.let { "円にすると ${Format.yen(it.toLong())}(金額 × レートの四捨五入)" } ?: "通貨(3 文字)・金額・レートを入れると、円の金額を計算します",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                NumberField(price, { price = it }, "購入価格(円)", Modifier.fillMaxWidth())
            }
            DateField("購入日", purchaseDate, { purchaseDate = it }, onClear = { purchaseDate = null })

            OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("保管場所") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = barcodeText,
                onValueChange = { barcodeText = it.filter(Char::isDigit).take(20) },
                label = { Text("バーコード番号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("プレイ記録での順位と勝者の決め方")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val choices = listOf(WinCondition.HIGH_SCORE to "高い方が勝ち", WinCondition.LOW_SCORE to "低い方が勝ち", WinCondition.MANUAL to "手で決める")
                choices.forEachIndexed { index, (value, label) ->
                    SegmentedButton(selected = winCondition == value, onClick = { winCondition = value }, shape = SegmentedButtonDefaults.itemShape(index, choices.size)) {
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
