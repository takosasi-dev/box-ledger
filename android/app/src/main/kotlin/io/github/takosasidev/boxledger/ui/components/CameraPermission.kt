// カメラの許可を断られた後の、もう一度頼む道(バーコードの読み取りと、箱の写真の撮影で使う)。
package io.github.takosasidev.boxledger.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat

/**
 * 断られた直後に呼ぶ。true なら、もう許可の画面は出ない(Android 11 以上は 2 回断ると出なくなる)ので、端末の設定へ案内する。
 * ponytail: 許可の画面を外のタップで閉じただけでも true になることがある。そのときも設定から許可できるので、そのままにしている
 */
fun cameraPermissionBlocked(context: Context): Boolean {
    var c = context
    while (c is ContextWrapper && c !is Activity) c = c.baseContext
    val activity = c as? Activity ?: return false
    return !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
}

/** このアプリの「アプリ情報」の画面を開く(許可をここで ON にしてもらう)。 */
fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
