package app.ferry.ui.tv

import androidx.lifecycle.ViewModel
import app.ferry.AppGraph
import app.ferry.core.model.OutFile
import app.ferry.core.model.OutboxList
import app.ferry.net.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * TV home state (plan §4.9): connection, update banner, the PC's outbox (refreshed every 5 s while visible), the
 * focus memory ([focus], kept while the Player / Viewer is on top) and the APK being installed.
 */
class TvViewModel : ViewModel() {
    val connection = AppGraph.connection.state
    val update = AppGraph.update
    private val _outbox = MutableStateFlow<OutboxList?>(null)
    val outbox: StateFlow<OutboxList?> = _outbox

    /** The card that had focus last (design §5.1 [A13]); null before the first visit. */
    @Volatile var focus: RowFocus? = null

    /** The card that has focus right now and its title, or null (tests; the banner has its own flag). */
    @Volatile var current: RowFocus? = null
    @Volatile var currentTitle: String? = null
    @Volatile var bannerFocused = false

    /** The focused button on "PC not found" ("try", "enter", …), for tests. */
    @Volatile var pcNotFoundFocus: String? = null

    /** "Enter address": what the field holds and whether it has the focus (tests, audit O3). */
    @Volatile var addressDraft: String? = null
    @Volatile var addressFieldFocused = false

    fun onCardFocus(f: RowFocus, title: String, has: Boolean) {
        if (has) {
            focus = f
            current = f
            currentTitle = title
        } else if (current == f) {
            current = null
            currentTitle = null
        }
    }

    /** Card clicks so far (tests: one OK press = one click [PA14]). */
    val clicks = AtomicInteger()

    /** The APK whose install dialog is open (T4). */
    val installing = MutableStateFlow<OutFile?>(null)

    suspend fun refreshOutbox(c: ConnectionState.Connected) {
        try {
            val list = c.api.outbox()
            if (list != _outbox.value) _outbox.value = list // unchanged list → no recomposition
            AppGraph.outbox.value = list
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AppGraph.log("tv", "outbox refresh failed: $e")
            AppGraph.connection.reportFailure()
        }
    }
}
