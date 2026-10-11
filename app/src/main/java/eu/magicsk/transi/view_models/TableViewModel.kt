package eu.magicsk.transi.view_models

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.data.models.MHDTableVehicle
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.repository.DataRepository
import eu.magicsk.transi.repository.IdsbkSession
import eu.magicsk.transi.repository.LiveBoard
import eu.magicsk.transi.util.Prefs
import javax.inject.Inject

/** The table's live departure board of one stop, kept connected while the table is on screen. */
@HiltViewModel
class TableViewModel @Inject constructor(
    repository: DataRepository,
    idsbk: IdsbkSession,
    @ApplicationContext context: Context,
) : ViewModel() {
    val departures = MutableLiveData<List<MHDTableData>>(emptyList())
    val vehicles = MutableLiveData<Map<String, MHDTableVehicle>>(emptyMap())
    val status = MutableLiveData(LiveBoard.Status.CONNECTING)
    /** True only after both feeds answered and neither has a departure. */
    val empty = MutableLiveData(false)
    val infoTexts = MutableLiveData<List<String>>(emptyList())
    val unreadInfoCount = MutableLiveData(0)

    // iOS VirtualTableList: rows; a spinner until the socket connects; the empty state once both feeds answered.
    val showsLoading get() = departures.value.isNullOrEmpty() && status.value != LiveBoard.Status.CONNECTED
    val showsEmpty get() = departures.value.isNullOrEmpty() && status.value == LiveBoard.Status.CONNECTED && empty.value == true

    private val prefs = Prefs.get(context)
    private val readInfoTexts = prefs.getStringSet(Prefs.READ_INFO_TEXTS, null).orEmpty().toMutableSet()

    private val board = LiveBoard(repository, idsbk, viewModelScope)

    init {
        board.onStatus = { status.value = it }
        board.onDepartures = { departures.value = it }
        board.onEmpty = { empty.value = it }
        board.onVehicles = { vehicles.value = it }
        board.onInfoTexts = { texts ->
            infoTexts.value = texts
            unreadInfoCount.value = texts.count { it !in readInfoTexts }
        }
    }

    /** The table is on screen: keep the board connected. */
    fun connect() = board.connect()

    /** The table left the screen. */
    fun disconnect() = board.disconnect()

    fun reconnect() = board.reconnect()

    fun setStop(newStop: Stop?) = board.setStop(newStop)

    fun markInfoTextsRead() {
        readInfoTexts.addAll(infoTexts.value.orEmpty())
        // A copy: the preferences keep the set they are given and skip writing an equal one.
        prefs.edit { putStringSet(Prefs.READ_INFO_TEXTS, readInfoTexts.toSet()) }
        unreadInfoCount.value = 0
    }

    override fun onCleared() = board.close()
}
