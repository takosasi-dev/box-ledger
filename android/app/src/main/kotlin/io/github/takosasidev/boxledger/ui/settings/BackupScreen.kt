@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.takosasidev.boxledger.ui.settings

import android.net.Uri
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import io.github.takosasidev.boxledger.ui.components.GroupItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.takosasidev.boxledger.core.backup.MergeCount
import io.github.takosasidev.boxledger.core.backup.MergeResult
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.data.backup.BackupManager
import io.github.takosasidev.boxledger.data.backup.BackupReadResult
import io.github.takosasidev.boxledger.data.backup.Imported
import io.github.takosasidev.boxledger.ui.LedgerViewModel
import io.github.takosasidev.boxledger.ui.components.SectionHeader
import io.github.takosasidev.boxledger.ui.components.Stepper
import io.github.takosasidev.boxledger.ui.components.SubScreen
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val StampFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault())

/** 読み込みの途中の段階。 */
private sealed interface ImportStage {
    data class Password(val uri: Uri, val message: String?) : ImportStage

    /** 追加取り込みか全置換かを選ぶ。[fromPreimport] は、取り込みの前の退避から戻すとき(全置換だけ)。 */
    data class Choose(val imported: Imported, val fromPreimport: Boolean = false) : ImportStage
    data class MergeConfirm(val imported: Imported, val preview: MergeResult, val separate: Set<String>) : ImportStage
    data class ReplaceConfirm(val imported: Imported, val fromPreimport: Boolean) : ImportStage
    data class Done(val title: String, val lines: List<String>) : ImportStage
    data class Failed(val messages: List<String>) : ImportStage
}

private fun counts(library: Library) =
    "ゲーム ${library.games.size} 件、プレイヤー ${library.players.size} 人、プレイ記録 ${library.playSessions.size} 件、" +
        "貸し出し ${library.lendingRecords.size} 件、ジャンルタグ ${library.genreTags.size} 件"

private fun countLine(label: String, c: MergeCount) = "$label: 追加 ${c.added} ・ 更新 ${c.updated} ・ 変更なし ${c.unchanged}"

private fun mergeLines(r: MergeResult) = listOf(
    countLine("ゲーム", r.games), countLine("プレイ記録", r.sessions), countLine("プレイヤー", r.players),
    countLine("貸し出し", r.lendings), countLine("ジャンルタグ", r.tags),
)

/**
 * FR-15(書き出し・追加取り込み・全置換)、FR-16(パスワード)、FR-17(自動バックアップ)。
 * ファイルの場所は SAF(端末のファイル選択)で選ぶので、ストレージの権限は要らない。
 */
@Composable
fun BackupScreen(vm: LedgerViewModel, library: Library, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val s = library.settings

    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf<ImportStage?>(null) }
    var treeName by remember { mutableStateOf(BackupManager.treeUri(context)?.let { BackupManager.treeDisplayName(context, it) }) }
    var autoError by remember { mutableStateOf(BackupManager.lastAutoBackupError(context)) }
    var preimports by remember { mutableStateOf(BackupManager.preimportFiles(context)) }

    fun show(result: BackupReadResult, uri: Uri?, fromPreimport: Boolean = false) {
        stage = when (result) {
            is BackupReadResult.Ok -> ImportStage.Choose(result.imported, fromPreimport)
            BackupReadResult.NeedPassword -> uri?.let { ImportStage.Password(it, null) }
            is BackupReadResult.WrongPassword -> uri?.let { ImportStage.Password(it, result.message) }
            is BackupReadResult.Rejected -> ImportStage.Failed(result.messages)
            is BackupReadResult.Error -> ImportStage.Failed(listOf(result.message))
        }
    }

    /** 重い処理を 1 つずつ走らせ、失敗は画面に出す。 */
    fun launchBusy(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            runCatching { block() }.onFailure { stage = ImportStage.Failed(listOf("失敗しました。データは元のままです: ${it.message ?: it.javaClass.simpleName}")) }
            preimports = BackupManager.preimportFiles(context)
            busy = false
        }
    }

    val read: (Uri, String?) -> Unit = { uri, pw -> launchBusy { show(BackupManager.readBackup(context, uri, pw), uri) } }
    val readPreimport: (File) -> Unit = { file -> launchBusy { show(BackupManager.readPreimport(context, file), null, fromPreimport = true) } }

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.ZIP_MIME)) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val result = runCatching { BackupManager.exportToUri(context, uri, password.ifEmpty { null }) }
                busy = false
                snackbar.showSnackbar(
                    if (result.isSuccess) {
                        if (password.isEmpty()) "バックアップを書き出しました" else "パスワード付きでバックアップを書き出しました"
                    } else {
                        "書き出しに失敗しました: ${result.exceptionOrNull()?.message}"
                    },
                )
            }
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) read(uri, null)
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            if (BackupManager.persistTree(context, uri)) {
                treeName = BackupManager.treeDisplayName(context, uri)
                BackupManager.setLastAutoBackupError(context, null)
                autoError = null
            } else {
                scope.launch { snackbar.showSnackbar("このフォルダは保存先にできませんでした。別のフォルダを選んでください。") }
            }
        }
    }

    SubScreen("バックアップと復元", onBack, snackbar = snackbar) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            SectionHeader("書き出す")
            GroupItem(0, 1) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ゲーム・プレイヤー・プレイ記録・貸し出し・設定・箱の写真を 1 つの zip にします。", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("パスワード(任意)") },
                    supportingText = { Text("入れると zip を暗号化します(AES-256)。忘れると読み込めません") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(enabled = !busy, onClick = { createBackup.launch(BackupManager.manualFileName()) }) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("zip に書き出す", Modifier.padding(start = 8.dp))
                }
            }
            }

            SectionHeader("読み込む")
            GroupItem(0, if (preimports.isNotEmpty()) 2 else 1) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "ほかの端末で書き出した zip を選びます。足すだけの「追加取り込み」と、全部を入れ替える「全置換」を選べます。" +
                        "どちらも、直前の状態をアプリの中に自動で残します。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FilledTonalButton(enabled = !busy, onClick = { openBackup.launch(arrayOf(BackupManager.ZIP_MIME, "application/octet-stream")) }) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("zip を選んで読み込む", Modifier.padding(start = 8.dp))
                }
            }
            }

            if (preimports.isNotEmpty()) {
                GroupItem(1, 2) {
                Column(Modifier.padding(vertical = 12.dp)) {
                Text("取り込みの前の状態に戻す", style = MaterialTheme.typography.titleSmallEmphasized, modifier = Modifier.padding(horizontal = 16.dp))
                Text(
                    "取り込みの直前に自動で残した状態です(新しい 3 件まで)。選ぶと、今のデータをその時の状態に入れ替えます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                preimports.forEach { file ->
                    TextButton(enabled = !busy, onClick = { readPreimport(file) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(StampFmt.format(java.time.Instant.ofEpochMilli(file.lastModified())) + " の状態に戻す")
                    }
                }
                }
                }
            }

            SectionHeader("自動バックアップ")
            GroupItem(0, 1) {
            Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("アプリを開いたときに自動で取る", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "前のバックアップから決めた日数が過ぎていたら、開いたときに保存先へ書き出します(パスワードなし)。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.autoBackupEnabled, onCheckedChange = { on -> vm.act { updateSettings { it.copy(autoBackupEnabled = on) } } })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("保存先: ${treeName ?: "未設定"}", Modifier.weight(1f))
                TextButton(onClick = { pickTree.launch(null) }) { Text("選ぶ") }
            }
            Stepper("間隔", s.autoBackupIntervalDays, 1..90, "日ごと") { v -> vm.act { updateSettings { it.copy(autoBackupIntervalDays = v) } } }
            Stepper("残す数", s.autoBackupKeepCount, 1..30, "個") { v -> vm.act { updateSettings { it.copy(autoBackupKeepCount = v) } } }
            Text(
                "最後の自動バックアップ: ${s.lastAutoBackupAt?.let(StampFmt::format) ?: "まだありません"}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (s.autoBackupEnabled && treeName == null) {
                Text(
                    "保存先のフォルダを選ぶまで、自動バックアップは取られません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            autoError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }
            }
            }
        }
    }

    when (val current = stage) {
        is ImportStage.Password -> {
            var input by remember(current) { mutableStateOf("") }
            val message = current.message
            val hint: (@Composable () -> Unit)? = if (message != null) {
                { Text(message) }
            } else {
                null
            }
            AlertDialog(
                onDismissRequest = { stage = null },
                title = { Text("パスワード") },
                text = {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("このバックアップのパスワード") },
                        supportingText = hint,
                        isError = message != null,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                },
                confirmButton = { TextButton(enabled = input.isNotEmpty() && !busy, onClick = { read(current.uri, input) }) { Text("読み込む") } },
                dismissButton = { TextButton(onClick = { stage = null }) { Text("キャンセル") } },
            )
        }

        is ImportStage.Choose -> {
            val imp = current.imported
            AlertDialog(
                onDismissRequest = { stage = null },
                title = { Text(if (current.fromPreimport) "この状態に戻しますか" else "どう取り込みますか") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "バックアップの中身: ${counts(imp.library)}、写真 ${imp.photoCount} 枚" +
                                (imp.exportedAt?.let { "(${StampFmt.format(it)} に書き出し" + (imp.sourcePlatform?.let { p -> "、$p" } ?: "") + ")" } ?: ""),
                        )
                        if (imp.warnings.isNotEmpty()) Text("直して読んだ所が ${imp.warnings.size} 件あります(結果の画面に出します)。", style = MaterialTheme.typography.bodySmall)
                        if (!current.fromPreimport) {
                            Button(enabled = !busy, onClick = {
                                launchBusy { stage = ImportStage.MergeConfirm(imp, BackupManager.previewMerge(context, imp, emptySet()), emptySet()) }
                            }) { Text("追加取り込み(足すだけ)") }
                            Text("相手の端末で増えた分を足し、同じ記録は新しい方を採ります。こちらの記録は消えません。", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(enabled = !busy, onClick = { stage = ImportStage.ReplaceConfirm(imp, current.fromPreimport) }) {
                            Text(if (current.fromPreimport) "この状態に入れ替える" else "全置換(入れ替える)")
                        }
                        Text("今のデータを全部消して、バックアップの中身に入れ替えます。", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { stage = null }) { Text("キャンセル") } },
            )
        }

        is ImportStage.MergeConfirm -> {
            val r = current.preview
            AlertDialog(
                onDismissRequest = { stage = null },
                title = { Text("追加取り込みの確認") },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        mergeLines(r).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Text(
                            "削除は伝わりません。こちらで消した記録が相手の端末に残っていると、取り込みで戻ってきます(「追加」に含まれます)。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (r.candidates.isNotEmpty()) {
                            Text("同じ物かどうかを確かめてください", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                            r.candidates.forEach { c ->
                                val same = c.incomingUuid !in current.separate
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("相手「${c.incomingLabel}」 と こちら「${c.localLabel}」")
                                        Text(if (same) "同じ物として扱う" else "別の物として追加する", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(
                                        checked = same,
                                        enabled = !busy,
                                        onCheckedChange = { on ->
                                            val separate = if (on) current.separate - c.incomingUuid else current.separate + c.incomingUuid
                                            // 選び直すと件数が変わるので、下見をやり直す
                                            launchBusy { stage = ImportStage.MergeConfirm(current.imported, BackupManager.previewMerge(context, current.imported, separate), separate) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        launchBusy {
                            val done = BackupManager.applyMerge(context, current.imported, current.separate)
                            stage = ImportStage.Done(
                                if (done.changed) "追加取り込みが終わりました" else "変更はありませんでした",
                                mergeLines(done) + (current.imported.warnings + done.warnings).map { "注意: $it" },
                            )
                        }
                    }) { Text("取り込む") }
                },
                dismissButton = { TextButton(onClick = { stage = null }) { Text("キャンセル") } },
            )
        }

        is ImportStage.ReplaceConfirm -> {
            val imp = current.imported
            AlertDialog(
                onDismissRequest = { stage = null },
                title = { Text("今のデータを入れ替えますか") },
                text = {
                    Text(
                        "現在のデータ(${counts(library)})は、バックアップの内容(${counts(imp.library)})で置き換わります。" +
                            if (current.fromPreimport) "" else "\n\n直前の状態はアプリの中に自動で残すので、この画面の「取り込みの前の状態に戻す」から戻せます。",
                    )
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        launchBusy {
                            BackupManager.applyReplace(context, imp, savePreimportFirst = !current.fromPreimport)
                            stage = ImportStage.Done("入れ替えました", listOf(counts(imp.library)) + imp.warnings.map { "注意: $it" })
                        }
                    }) { Text("入れ替える", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { stage = null }) { Text("キャンセル") } },
            )
        }

        is ImportStage.Done -> AlertDialog(
            onDismissRequest = { stage = null },
            title = { Text(current.title) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    current.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(onClick = { stage = null }) { Text("閉じる") } },
        )

        is ImportStage.Failed -> AlertDialog(
            onDismissRequest = { stage = null },
            title = { Text("読み込めませんでした") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("何も変更していません。")
                    current.messages.take(30).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (current.messages.size > 30) Text("ほか ${current.messages.size - 30} 件", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { stage = null }) { Text("閉じる") } },
        )

        null -> Unit
    }
}
