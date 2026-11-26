// 画面の行き先と、全画面で共有する ViewModel。
package io.github.takosasidev.boxledger.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.takosasidev.boxledger.core.calc.GameQuery
import io.github.takosasidev.boxledger.core.model.Library
import io.github.takosasidev.boxledger.core.model.ViewMode
import io.github.takosasidev.boxledger.data.LedgerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 画面の行き先。ショートカット・通知・ウィジェットからの飛び先もここを見る。ゲームなどは uuid で指す。 */
object Routes {
    const val COLLECTION = "collection"
    const val PLAYS = "plays"
    const val PLAYERS = "players"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val SCAN = "scan"
    const val LENDINGS = "lendings"
    const val TAGS = "tags"
    const val BACKUP = "backup"
    const val YEAR = "year"

    const val GAME = "game/{uuid}"
    fun game(uuid: String) = "game/$uuid"

    const val PLAYER = "player/{uuid}"
    fun player(uuid: String) = "player/$uuid"

    /** ゲームの新規登録 / 編集。uuid が空なら新規。barcode はスキャンから、parent は「拡張を追加」から引き継ぐ。 */
    const val GAME_EDIT = "gameEdit?uuid={uuid}&barcode={barcode}&parent={parent}&wish={wish}"
    fun gameEdit(uuid: String = "", barcode: String = "", parent: String = "", wish: Boolean = false) =
        "gameEdit" + query("uuid" to uuid, "barcode" to barcode, "parent" to parent, "wish" to if (wish) "true" else "")

    /** プレイ記録の新規 / 編集。uuid が空なら新規。game は最初に選んでおくゲーム。 */
    const val PLAY_EDIT = "playEdit?uuid={uuid}&game={game}"
    fun playEdit(uuid: String = "", game: String = "") = "playEdit" + query("uuid" to uuid, "game" to game)

    /** 空の値は付けない(付けなければ、行き先の側の既定値の空文字になる)。 */
    private fun query(vararg pairs: Pair<String, String>): String =
        pairs.filter { it.second.isNotEmpty() }.joinToString("&") { "${it.first}=${Uri.encode(it.second)}" }.let { if (it.isEmpty()) "" else "?$it" }

    /** ショートカットが Intent に載せる行き先。res/xml/shortcuts.xml と同じ文字にする。 */
    const val EXTRA_DESTINATION = "io.github.takosasidev.boxledger.DESTINATION"
    const val DEST_NEW_PLAY = "newPlay"

    /** 通知をタップしたときに開くゲーム(uuid)。 */
    const val EXTRA_GAME_UUID = "io.github.takosasidev.boxledger.GAME_UUID"
}

/**
 * 全画面で 1 つを共有する(MainActivity が持つ)。個人のコレクションの量なら、全件を StateFlow に載せて
 * 各画面が要る物を拾うほうが、画面ごとに問い合わせを書くより単純。判定は :core の関数に任せる。
 */
class LedgerViewModel(val repo: LedgerRepository) : ViewModel() {

    /** null は「まだ読めていない」。アプリロックの有無が分かるまで、中身を出さないために区別する。 */
    val library: StateFlow<Library?> = repo.library.map<Library, Library?> { it }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 一覧の検索・絞り込み・並び替え(FR-14)。画面を行き来しても保つ。 */
    val query = MutableStateFlow(GameQuery())

    /** 一覧の表示の形。null の間は設定の既定に従う(FR-22)。 */
    val viewMode = MutableStateFlow<ViewMode?>(null)

    /** 未プレイの提案の帯を、次に起動するまで畳む。 */
    val suggestionsCollapsed = MutableStateFlow(false)

    /** 結果を待たない書き込み。画面は `vm.act { saveGame(...) }` のように呼ぶ。 */
    fun act(block: suspend LedgerRepository.() -> Unit) {
        viewModelScope.launch { repo.block() }
    }

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { LedgerViewModel(LedgerRepository.get(context.applicationContext)) }
        }
    }
}
