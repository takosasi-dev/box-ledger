// FR-09: 貸し出しの返却のお知らせ。書き方は収支管理アプリ(win-loss-tracker)の通知と同じ。
package io.github.takosasidev.boxledger.notify

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.takosasidev.boxledger.MainActivity
import io.github.takosasidev.boxledger.R
import io.github.takosasidev.boxledger.core.calc.Reminders
import io.github.takosasidev.boxledger.data.LedgerRepository
import io.github.takosasidev.boxledger.ui.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 1 日 1 回の見回り。返却予定日の前日と当日に知らせる。どれを知らせるかは :core の [Reminders] が決める。
 * アプリの起動時にも同じ物を呼ぶ(アラームが遅れた日でも、開けば届く)。
 */
object DailyCheck {

    private const val PREFS = "notify_state"
    private const val KEY_NOTIFIED = "returnNotified"
    private const val CHANNEL = "return_reminder"

    suspend fun run(context: Context) {
        val library = LedgerRepository.get(context).libraryOnce()
        val today = LocalDate.now()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 今日の分の印だけを残す(昨日までの印はもう使わない)
        val notified = prefs.getStringSet(KEY_NOTIFIED, emptySet()).orEmpty().filter { it.endsWith("|$today") }.toSet()
        // 通知できた分だけ印を付ける(権限をもらう前の分は、もらった後に通知する)
        val shown = Reminders.due(library, today)
            .filter { Reminders.key(it, today) !in notified }
            .filter { lending ->
                val game = library.game(lending.gameUuid) ?: return@filter false
                val who = library.player(lending.playerUuid)?.name ?: "だれか"
                show(
                    context,
                    id = lending.uuid.hashCode(),
                    gameUuid = game.uuid,
                    title = "「${game.title}」の返却予定日は${if (lending.expectedReturnAt == today) "今日" else "明日"}です",
                    body = "${who}さんに貸しています(貸出日 ${lending.lentAt})。",
                )
            }
            .map { Reminders.key(it, today) }
        prefs.edit().putStringSet(KEY_NOTIFIED, notified + shown).apply()
    }

    /**
     * ローカル通知だけ(INTERNET の権限が無いので、ほかの手段は無い)。
     * 出せたら true、権限が無い・切られているなら静かに false。
     */
    @SuppressLint("MissingPermission") // areNotificationsEnabled() で確かめ、失敗も握っている
    private fun show(context: Context, id: Int, gameUuid: String, title: String, body: String): Boolean = runCatching {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false

        // API 26 以上はチャンネルが要る。同じ ID で作り直しても、利用者が変えた設定は壊れない
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "返却のお知らせ", NotificationManager.IMPORTANCE_DEFAULT),
        )
        // requestCode を通知ごとに変える。PendingIntent の同一判定は extras を見ないので、
        // 同じ番号だと後の通知が前の通知の行き先(ゲーム)を上書きする
        val pending = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(Routes.EXTRA_GAME_UUID, gameUuid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        // 権限が無い端末では SecurityException が飛ぶので runCatching の内側で呼ぶ
        manager.notify(id, notification)
        true
    }.getOrDefault(false)
}

/**
 * AlarmManager で「次の 9:00」に 1 回だけ鳴る、時刻のずれを許すアラームを仕込む(WorkManager は使わない)。
 * 秒単位の正確さは要らないので、SCHEDULE_EXACT_ALARM の権限は求めない。
 * 鳴るたびに [DailyCheckReceiver] が翌日の分を積み直す。
 */
object DailyCheckScheduler {

    /** 見回りの時刻(時)。Windows 版の要件定義書 §13.2 の暫定案(9:00)に合わせる。 */
    private const val HOUR = 9
    private const val REQUEST_CODE = 4001

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val now = LocalDateTime.now()
        var next = now.withHour(HOUR).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, DailyCheckReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending) }
    }
}

/**
 * アラームと、端末の起動(再起動でアラームが消えるので積み直す)の両方で呼ばれる。
 * onReceive は同期のメソッドだが DB を読むので、goAsync() で猶予をもらって IO のスレッドで処理する。
 */
class DailyCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { DailyCheck.run(appContext) }
            DailyCheckScheduler.schedule(appContext)
            pending.finish()
        }
    }
}
