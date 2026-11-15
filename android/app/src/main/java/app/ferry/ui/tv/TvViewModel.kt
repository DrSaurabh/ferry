package app.ferry.ui.tv

import androidx.lifecycle.ViewModel
import app.ferry.AppGraph
import app.ferry.core.model.OutboxList
import app.ferry.net.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/** TV home state (plan §4.9): connection, update banner, the PC's outbox (refreshed every 5 s while visible). */
class TvViewModel : ViewModel() {
    val connection = AppGraph.connection.state
    val update = AppGraph.update
    private val _outbox = MutableStateFlow<OutboxList?>(null)
    val outbox: StateFlow<OutboxList?> = _outbox

    suspend fun refreshOutbox(c: ConnectionState.Connected) {
        try {
            _outbox.value = c.api.outbox()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AppGraph.log("tv", "outbox refresh failed: $e")
            AppGraph.connection.reportFailure()
        }
    }
}
