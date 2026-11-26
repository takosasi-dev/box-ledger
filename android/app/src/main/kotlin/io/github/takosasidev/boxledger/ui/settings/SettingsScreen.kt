@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.takosasidev.boxledger.ui.settings

import android.Manifest
import android.app.KeyguardManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import io.github.takosasidev.boxledger.core.model.AppSettings
import io.github.takosasidev.boxledger.core.model.DarkModeMode
import io.github.takosasidev.boxledger.core.model.GenreTag
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.core.model.ThemeColorMode
import io.github.takosasidev.boxledger.core.model.ViewMode
import io.github.takosasidev.boxledger.data.LedgerRepository
import io.github.takosasidev.boxledger.data.backup.BackupManager
import io.github.takosasidev.boxledger.ui.LedgerViewModel
import io.github.takosasidev.boxledger.ui.components.ColorRow
import io.github.takosasidev.boxledger.ui.components.ConfirmDialog
import io.github.takosasidev.boxledger.ui.components.SectionHeader
import io.github.takosasidev.boxledger.ui.components.Stepper
import io.github.takosasidev.boxledger.ui.components.SubScreen
import io.github.takosasidev.boxledger.ui.theme.ColorChoices

@Composable
private fun SwitchRow(title: String, body: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> Choice(title: String, choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            choices.forEachIndexed { index, (value, label) ->
                SegmentedButton(selected = selected == value, onClick = { onSelect(value) }, shape = SegmentedButtonDefaults.itemShape(index, choices.size)) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun LinkRow(title: String, body: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    )
}

/** 設定(FR-20 / FR-21 / FR-22 / FR-24 と、未プレイの提案・通知の許可)。 */
@Composable
fun SettingsScreen(vm: LedgerViewModel, library: Library, onOpenTags: () -> Unit, onOpenBackup: () -> Unit) {
    val context = LocalContext.current
    val s = library.settings
    fun change(transform: (AppSettings) -> AppSettings) = vm.act { updateSettings(transform) }

    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsOn = it }
    val deviceSecure = remember { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }
    val excluded = library.games.filter { it.excludeFromSuggestion }

    Scaffold(topBar = { TopAppBar(title = { Text("設定") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionHeader("データ")
            LinkRow("ジャンルタグ", "${library.genreTags.size} 件。追加・名前と色の変更・削除", onOpenTags)
            LinkRow("バックアップと復元", "zip への書き出し、ほかの端末からの取り込み、自動バックアップ", onOpenBackup)

            SectionHeader("見た目")
            SwitchRow(
                "壁紙の色に合わせる",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Material You の配色を使います" else "この端末(Android 11 以下)では使えません。下の色を使います",
                checked = s.themeColorMode == ThemeColorMode.DYNAMIC,
            ) { on -> change { it.copy(themeColorMode = if (on) ThemeColorMode.DYNAMIC else ThemeColorMode.MANUAL) } }
            if (s.themeColorMode == ThemeColorMode.MANUAL || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Text("テーマの色", Modifier.padding(horizontal = 16.dp))
                ColorRow(s.manualThemeColor, { argb -> change { it.copy(manualThemeColor = argb) } }, Modifier.padding(horizontal = 12.dp))
            }
            Choice("ダークモード", listOf(DarkModeMode.SYSTEM to "端末に合わせる", DarkModeMode.LIGHT to "ライト", DarkModeMode.DARK to "ダーク"), s.darkModeMode) { v ->
                change { it.copy(darkModeMode = v) }
            }
            Choice("一覧の最初の表示", listOf(ViewMode.GRID to "グリッド(箱の写真)", ViewMode.LIST to "リスト"), s.defaultViewMode) { v ->
                vm.viewMode.value = null
                change { it.copy(defaultViewMode = v) }
            }
            SwitchRow("一覧に拡張も出す", "切ると、拡張は本体の画面にだけ出します", s.showExpansionsInList) { on -> change { it.copy(showExpansionsInList = on) } }
            SwitchRow("記録したときの紙吹雪", "勝者を決めてプレイ記録を保存したときに流します", s.celebrationEnabled) { on -> change { it.copy(celebrationEnabled = on) } }

            SectionHeader("未プレイの提案")
            Stepper("遊んでいない日数", s.unplayedSuggestionThresholdDays, 10..730, "日以上", step = 10) { v ->
                change { it.copy(unplayedSuggestionThresholdDays = v) }
            }
            if (excluded.isNotEmpty()) {
                Text("提案に出さないゲーム", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.bodySmall)
                excluded.forEach { game ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(game.title, Modifier.weight(1f), maxLines = 1)
                        TextButton(onClick = { vm.act { saveGame(game.copy(excludeFromSuggestion = false)) } }) { Text("提案に戻す") }
                    }
                }
            }

            SectionHeader("返却のお知らせ")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (notificationsOn) "通知は許可されています。返却予定日の前日と当日の朝 9 時ごろにお知らせします。" else "通知が許可されていないので、返却のお知らせは届きません。",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!notificationsOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    OutlinedButton(onClick = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("許可する") }
                }
            }

            SectionHeader("プライバシー")
            SwitchRow(
                "アプリロック",
                if (deviceSecure) "開くときに、端末のロック解除(指紋・顔・PIN など)を求めます" else "端末に画面ロックが設定されていないので、使えません",
                checked = s.appLockEnabled,
                enabled = deviceSecure || s.appLockEnabled,
            ) { on -> change { it.copy(appLockEnabled = on) } }

            SectionHeader("このアプリについて")
            Text(
                "BoxLedger ${BackupManager.appVersionName(context)}\nデータはこの端末の中だけに保存します。通信はしません(インターネットの権限がありません)。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/** ジャンルタグの管理(FR-11)。名前は重ねられない(大文字と小文字は区別しない)。 */
@Composable
fun TagsScreen(vm: LedgerViewModel, library: Library, onBack: () -> Unit) {
    // null = 閉じている。uuid が空のタグ = 新しく作る
    var editing by remember { mutableStateOf<GenreTag?>(null) }
    var deleting by remember { mutableStateOf<GenreTag?>(null) }

    SubScreen(
        title = "ジャンルタグ",
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = GenreTag("", "", ColorChoices[library.genreTags.size % ColorChoices.size].second.toArgb())
            }) { Icon(Icons.Filled.Add, contentDescription = "タグを追加") }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (library.genreTags.isEmpty()) item { Text("タグがありません。右下の ＋ から作れます。", Modifier.padding(16.dp)) }
            items(library.genreTags, key = { it.uuid }) { tag ->
                val used = library.games.count { tag.uuid in it.genreTagUuids }
                ListItem(
                    modifier = Modifier.clickable { editing = tag },
                    leadingContent = { Box(Modifier.size(28.dp).clip(CircleShape).background(Color(tag.colorArgb))) },
                    headlineContent = { Text(tag.name) },
                    supportingContent = { Text("$used 件のゲームに付いています") },
                    trailingContent = { IconButton(onClick = { deleting = tag }) { Icon(Icons.Filled.Delete, contentDescription = "${tag.name} を削除") } },
                )
            }
        }
    }

    editing?.let { tag ->
        var name by remember(tag) { mutableStateOf(tag.name) }
        var color by remember(tag) { mutableIntStateOf(tag.colorArgb) }
        val clash = library.genreTags.any { it.uuid != tag.uuid && it.name.equals(name.trim(), ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (tag.uuid.isEmpty()) "タグを追加" else "タグを編集") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(30) },
                        label = { Text("名前") },
                        singleLine = true,
                        isError = clash,
                        supportingText = { if (clash) Text("同じ名前のタグが既にあります") },
                    )
                    ColorRow(color, { color = it })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && !clash, onClick = {
                    val saved = tag.copy(uuid = tag.uuid.ifEmpty { LedgerRepository.newUuid() }, name = name.trim(), colorArgb = color)
                    editing = null
                    vm.act { saveTag(saved) }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("キャンセル") } },
        )
    }

    deleting?.let { tag ->
        ConfirmDialog(
            title = "「${tag.name}」を削除しますか",
            text = "このタグを、付いているゲームから外して削除します。ゲームそのものは消えません。",
            confirmLabel = "削除する",
            danger = true,
            onConfirm = {
                deleting = null
                vm.act { deleteTag(tag.uuid) }
            },
            onDismiss = { deleting = null },
        )
    }
}
