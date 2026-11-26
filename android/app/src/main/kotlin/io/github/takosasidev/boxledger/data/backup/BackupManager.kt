// バックアップ zip の入出力。zip の扱いは収支管理アプリ(win-loss-tracker)の BackupManager と同じ。
// 中身の形(manifest.json / data.json / photos/)と検証・追加取り込みの判定は :core にある。
package io.github.takosasidev.boxledger.data.backup

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import io.github.takosasidev.boxledger.core.backup.BackupCodec
import io.github.takosasidev.boxledger.core.backup.DecodeResult
import io.github.takosasidev.boxledger.core.backup.Merge
import io.github.takosasidev.boxledger.core.backup.MergeResult
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.data.LedgerRepository
import io.github.takosasidev.boxledger.data.PhotoLoader
import io.github.takosasidev.boxledger.data.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** zip から読んだ中身。写真は一時の置き場に取り出してある。 */
data class Imported(
    val library: Library,
    val warnings: List<String>,
    val exportedAt: Instant?,
    val sourcePlatform: String?,
    val photoCount: Int,
)

/** インポートの読み取りの結果。パスワードの誤りと版の非互換を握りつぶさずに区別する。 */
sealed interface BackupReadResult {
    data class Ok(val imported: Imported) : BackupReadResult

    /** 暗号化した zip だが、パスワードが渡されていない。 */
    data object NeedPassword : BackupReadResult
    data class WrongPassword(val message: String) : BackupReadResult

    /** 版の非互換や検証の誤りなど、取り込んではいけない中身。何も変えていない。 */
    data class Rejected(val messages: List<String>) : BackupReadResult
    data class Error(val message: String) : BackupReadResult
}

/**
 * FR-15 / FR-16 / FR-17 の zip の読み書き。
 *
 * zip の読み書きは Zip4j に揃える(平文と暗号化で処理を分けないため)。Zip4j は File を求めるので、
 * SAF の Uri とは cacheDir の一時ファイルを挟んでやり取りする。
 */
object BackupManager {

    // ファイル名の頭で種類を分ける(Windows 版の要件定義書 §6.1)
    const val MANUAL_PREFIX = "boxledger_backup_"
    const val AUTO_PREFIX = "boxledger_auto_"
    const val PREIMPORT_PREFIX = "boxledger_preimport_"
    const val ZIP_EXT = ".zip"
    const val ZIP_MIME = "application/zip"

    private const val PREFS = "backup_state"
    private const val KEY_TREE_URI = "treeUri"
    private const val KEY_LAST_AUTO_ERROR = "lastAutoBackupError"

    /** zip から取り出した写真の一時の置き場。取り込みが決まるまで filesDir には書かない(確認の画面でやめられる)。 */
    private const val PHOTO_STAGING = "import-photos"

    /** インポートの前の退避の置き場(アプリの中)と、残す数。 */
    private const val PREIMPORT_DIR = "preimport"
    private const val PREIMPORT_KEEP = 3

    // 外から来る zip なので、展開した後の大きさに上限を置く(小さな zip から巨大なファイルを作らせない)
    private const val MAX_TEXT_BYTES = 64L * 1024 * 1024
    private const val MAX_PHOTO_BYTES = 20L * 1024 * 1024

    private val stampFmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault())

    fun manualFileName(at: Instant = Instant.now()) = "$MANUAL_PREFIX${stampFmt.format(at)}$ZIP_EXT"
    fun autoFileName(at: Instant = Instant.now()) = "$AUTO_PREFIX${stampFmt.format(at)}$ZIP_EXT"

    // ---- 書き出し ----------------------------------------------------------

    /** 今のデータを zip にして [target] に作る。password が空なら平文の zip。 */
    private suspend fun writeBackupFile(context: Context, target: File, password: String?) {
        val library = LedgerRepository.get(context).libraryOnce()
        // 参照されている写真だけ(差し替えの残りまで入れても、復元先で誰も参照しない)
        val referenced = library.games.mapNotNull { it.boxPhotoFile }.toSet()
        val photos = PhotoStore.dir(context).listFiles()?.filter { it.name in referenced }.orEmpty()
        val texts = listOf(
            BackupCodec.ENTRY_MANIFEST to BackupCodec.manifestJson(library, appVersionName(context), Instant.now(), photos.size),
            BackupCodec.ENTRY_DATA to BackupCodec.dataJson(library),
        )
        target.delete() // Zip4j は既にあるファイルに追記するので、必ず作り直す
        val encrypted = !password.isNullOrEmpty()
        val zip = if (encrypted) ZipFile(target, password!!.toCharArray()) else ZipFile(target)
        // 暗号化の指定はエントリごと。写真も同じ params を通すので、写真だけ平文で入ることはない
        fun params(name: String) = ZipParameters().apply {
            fileNameInZip = name
            if (encrypted) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        zip.use { z ->
            texts.forEach { (name, text) ->
                ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use { z.addStream(it, params(name)) }
            }
            photos.forEach { photo ->
                // 1 枚入らなくても、バックアップ全体は失敗させない
                runCatching { z.addFile(photo, params(BackupCodec.PHOTOS_DIR + photo.name)) }
            }
        }
    }

    /** FR-15 / FR-16: 今のデータを zip にして [out] に流す。 */
    suspend fun writeBackup(context: Context, out: OutputStream, password: String?) = withContext(Dispatchers.IO) {
        // 途中で失敗しても out を閉じ、一時ファイルを残さない
        out.use { target ->
            val temp = File.createTempFile("bl-export", ZIP_EXT, context.cacheDir)
            try {
                writeBackupFile(context, temp, password)
                temp.inputStream().use { it.copyTo(target) }
            } finally {
                temp.delete()
            }
        }
    }

    suspend fun exportToUri(context: Context, uri: Uri, password: String?) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("保存先を開けませんでした")
        writeBackup(context, out, password)
    }

    // ---- 読み取り ----------------------------------------------------------

    suspend fun readBackup(context: Context, uri: Uri, password: String?): BackupReadResult =
        withContext(Dispatchers.IO) {
            val temp = File.createTempFile("bl-import", ZIP_EXT, context.cacheDir)
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: return@withContext BackupReadResult.Error("ファイルを開けませんでした。選び直してください。")
                input.use { src -> FileOutputStream(temp).use { src.copyTo(it) } }
                readBackupFile(temp, password, stagingDir(context))
            } catch (e: SecurityException) {
                BackupReadResult.Error("ファイルを読む許可がありません。もう一度ファイルを選び直してください。")
            } catch (e: Exception) {
                BackupReadResult.Error("ファイルを読み込めませんでした: ${reason(e)}")
            } finally {
                temp.delete()
            }
        }

    /** インポートの前の退避(アプリの中の zip)を読む。 */
    suspend fun readPreimport(context: Context, file: File): BackupReadResult = withContext(Dispatchers.IO) {
        runCatching { readBackupFile(file, null, stagingDir(context)) }
            .getOrElse { BackupReadResult.Error("ファイルを読み込めませんでした: ${reason(it)}") }
    }

    private fun stagingDir(context: Context) = File(context.cacheDir, PHOTO_STAGING)

    /** zip の中の `photos/` は [photoStagingDir] に取り出す。検証は :core の [BackupCodec.decode]。 */
    fun readBackupFile(file: File, password: String?, photoStagingDir: File): BackupReadResult {
        val encrypted = try {
            ZipFile(file).use { it.isEncrypted }
        } catch (e: Exception) {
            return BackupReadResult.Error("バックアップの zip として読み取れませんでした。BoxLedger で書き出した zip を選び直してください。")
        }
        if (encrypted && password.isNullOrEmpty()) return BackupReadResult.NeedPassword

        var photoNames: Set<String> = emptySet()
        val texts: Map<String, String> = try {
            val zip = if (password.isNullOrEmpty()) ZipFile(file) else ZipFile(file, password.toCharArray())
            zip.use { z ->
                val loaded = listOf(BackupCodec.ENTRY_MANIFEST, BackupCodec.ENTRY_DATA).mapNotNull { name ->
                    val header = z.getFileHeader(name) ?: return@mapNotNull null
                    if (header.uncompressedSize > MAX_TEXT_BYTES) {
                        return BackupReadResult.Rejected(listOf("バックアップの中の $name が大きすぎます。"))
                    }
                    name to z.getInputStream(header).use { s -> s.readBytes().toString(Charsets.UTF_8) }
                }.toMap()
                photoNames = extractPhotos(z, photoStagingDir)
                loaded
            }
        } catch (e: ZipException) {
            // AES はパスワードの検証ですぐに落ちる。誤ったまま読み進めて壊れたデータを入れない
            return if (encrypted) {
                BackupReadResult.WrongPassword("パスワードが違います。もう一度入力してください。")
            } else {
                BackupReadResult.Error("バックアップの中身を読み取れませんでした: ${reason(e)}")
            }
        } catch (e: Exception) {
            return BackupReadResult.Error("バックアップの中身を読み取れませんでした: ${reason(e)}")
        }

        return when (val decoded = BackupCodec.decode(texts[BackupCodec.ENTRY_MANIFEST], texts[BackupCodec.ENTRY_DATA], photoNames)) {
            is DecodeResult.Fatal -> BackupReadResult.Rejected(decoded.errors)
            is DecodeResult.Ok -> BackupReadResult.Ok(
                Imported(decoded.library, decoded.warnings, decoded.exportedAt, decoded.sourcePlatform, photoNames.size),
            )
        }
    }

    /**
     * `photos/<ファイル名>` のエントリなら、ファイル名の部分を返す。それ以外は null。
     * ファイル名の部分だけを使い、さらに [BackupCodec.isSafePhotoName] を通す(`photos/../../x` のような
     * 名前をそのまま使うと、アプリのフォルダの外に書かされる)。
     */
    private fun photoEntryName(entry: String?): String? {
        val raw = entry ?: return null
        if (!raw.startsWith(BackupCodec.PHOTOS_DIR) || raw.endsWith("/")) return null
        return raw.substringAfterLast('/').substringAfterLast('\\').takeIf(BackupCodec::isSafePhotoName)
    }

    private fun extractPhotos(zip: ZipFile, staging: File): Set<String> {
        // 前に読んだバックアップの残りが混ざらないように空にする
        staging.deleteRecursively()
        staging.mkdirs()
        val names = HashSet<String>()
        zip.fileHeaders.forEach { header ->
            val name = photoEntryName(header.fileName) ?: return@forEach
            if (header.uncompressedSize > MAX_PHOTO_BYTES) return@forEach
            runCatching {
                zip.getInputStream(header).use { input -> FileOutputStream(File(staging, name)).use { input.copyTo(it) } }
                names += name
            }
        }
        return names
    }

    // ---- 取り込み ----------------------------------------------------------

    /** 追加取り込みの下見。何も書かない。確認が要る組み合わせ(candidates)と件数を画面に出すために使う。 */
    suspend fun previewMerge(context: Context, imported: Imported, separate: Set<String>): MergeResult =
        withContext(Dispatchers.Default) { Merge.merge(LedgerRepository.get(context).libraryOnce(), imported.library, separate) }

    /**
     * 追加取り込み(既定)。相手で増えた分を足し、同じ記録は新しい方を採る。削除は伝えない。
     * 何か変わるときだけ、直前に今のデータを退避してから書く。
     */
    suspend fun applyMerge(context: Context, imported: Imported, separate: Set<String>): MergeResult = withContext(Dispatchers.IO) {
        val repo = LedgerRepository.get(context)
        val local = repo.libraryOnce()
        val result = Merge.merge(local, imported.library, separate)
        if (result.library != local) {
            savePreimport(context)
            movePhotos(context, result.photosToCopy)
            repo.replaceAll(result.library)
        }
        stagingDir(context).deleteRecursively()
        result
    }

    /**
     * 全置換。今のデータを全部消して、バックアップの中身に入れ替える。直前に今のデータを退避する。
     * この端末だけの設定(自動バックアップ・アプリロックなど)は今の値を保つ。
     * [savePreimportFirst] は、退避そのものから戻すときだけ false にする(戻す操作で退避を押し出さないため)。
     */
    suspend fun applyReplace(context: Context, imported: Imported, savePreimportFirst: Boolean = true) = withContext(Dispatchers.IO) {
        val repo = LedgerRepository.get(context)
        if (savePreimportFirst) savePreimport(context)
        // 写真を先に置く。replaceAll の最後の掃除が、参照されない写真(古い分)を消す
        movePhotos(context, null)
        repo.replaceAll(imported.library.copy(settings = repo.settingsOnce().withExchangedFrom(imported.library.settings)))
        stagingDir(context).deleteRecursively()
    }

    /** 一時の置き場の写真を、写真のフォルダへ移す。[only] が null なら全部。 */
    private fun movePhotos(context: Context, only: Set<String>?) {
        val target = PhotoStore.dir(context)
        stagingDir(context).listFiles()?.filter { only == null || it.name in only }?.forEach { src ->
            runCatching {
                val dst = File(target, src.name)
                dst.delete()
                if (!src.renameTo(dst)) src.inputStream().use { input -> FileOutputStream(dst).use { input.copyTo(it) } }
                PhotoLoader.evict(src.name)
            }
        }
    }

    // ---- インポートの前の退避 ----------------------------------------------

    private fun preimportDir(context: Context) = File(context.filesDir, PREIMPORT_DIR).apply { if (!exists()) mkdirs() }

    /** 新しい順。ファイル名に yyyyMMdd_HHmmss を入れているので、名前の降順 = 新しい順。 */
    fun preimportFiles(context: Context): List<File> =
        preimportDir(context).listFiles()?.filter { it.name.startsWith(PREIMPORT_PREFIX) && it.name.endsWith(ZIP_EXT) }
            ?.sortedByDescending { it.name }.orEmpty()

    /** 取り込みの直前の状態を、アプリの中に zip で残す(取り消したくなったときに戻せるように)。最新の 3 件だけを残す。 */
    private suspend fun savePreimport(context: Context) {
        writeBackupFile(context, File(preimportDir(context), "$PREIMPORT_PREFIX${stampFmt.format(Instant.now())}$ZIP_EXT"), null)
        preimportFiles(context).drop(PREIMPORT_KEEP).forEach { it.delete() }
    }

    // ---- 自動バックアップの保存先のフォルダ(DocumentsContract をそのまま使う) --------

    fun treeUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE_URI, null)?.let(Uri::parse)

    /** OpenDocumentTree の結果を覚える。以後はフォルダを選び直さずに書き込める。 */
    fun persistTree(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE_URI, treeUri.toString()).apply()
        true
    }.getOrDefault(false)

    /** 許可がまだ生きていて、実際に中を読めるか。フォルダを動かされると、許可は残ったまま読み取りだけ失敗する。 */
    fun hasTreeAccess(context: Context, treeUri: Uri): Boolean {
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }
        if (!granted) return false
        return runCatching { queryChildren(context, treeUri)?.use { true } ?: false }.getOrDefault(false)
    }

    fun treeDisplayName(context: Context, treeUri: Uri): String? = runCatching {
        context.contentResolver.query(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** 覚えたフォルダに zip を作って書き出す。失敗は例外で上に投げる(黙って失敗しない)。 */
    suspend fun exportToTree(context: Context, treeUri: Uri, fileName: String, password: String?): Uri =
        withContext(Dispatchers.IO) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            val target = DocumentsContract.createDocument(context.contentResolver, parent, ZIP_MIME, fileName)
                ?: throw IllegalStateException("保存先のフォルダにファイルを作れませんでした")
            exportToUri(context, target, password)
            target
        }

    private fun queryChildren(context: Context, treeUri: Uri): Cursor? =
        context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )

    /**
     * FR-17: 保持する世代数を超えた自動バックアップを古い順に消す。
     * 消すのは `boxledger_auto_` のファイルだけ。手動のバックアップには触れない。
     */
    fun pruneAutoBackups(context: Context, treeUri: Uri, keepCount: Int): Int {
        val children = runCatching {
            queryChildren(context, treeUri)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        add(name to DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
        var deleted = 0
        children.filter { it.first.startsWith(AUTO_PREFIX) && it.first.endsWith(ZIP_EXT) }
            .sortedByDescending { it.first }
            .drop(keepCount.coerceAtLeast(1))
            .forEach { (_, uri) ->
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    .onSuccess { if (it) deleted++ }
            }
        return deleted
    }

    // ---- 自動バックアップの最後の結果 --------------------------------------

    fun lastAutoBackupError(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_AUTO_ERROR, null)

    fun setLastAutoBackupError(context: Context, message: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (message == null) remove(KEY_LAST_AUTO_ERROR) else putString(KEY_LAST_AUTO_ERROR, message)
        }.apply()
    }

    fun appVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    private fun reason(e: Throwable): String = e.message ?: e.javaClass.simpleName
}
