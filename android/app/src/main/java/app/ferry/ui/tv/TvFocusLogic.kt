package app.ferry.ui.tv

/** The focused card on TV home: row key ("videos", "photos", "apps", "other") and position in that row. */
data class RowFocus(val row: String, val index: Int)

/**
 * Focus memory rules of TV home (design §5.1 [A13], plan §4.9), pure so `TvFocusLogicTest` can check them:
 * the remembered card gets focus back when Home is shown again; if the outbox changed meanwhile the position is
 * clamped to its row, and if the row is gone, focus goes to the first card of the first non-empty row.
 */
object TvFocusLogic {
    /**
     * Where focus belongs, given the remembered card and the rows as (key, card count) in screen order;
     * null when there is no card at all.
     */
    fun target(saved: RowFocus?, rows: List<Pair<String, Int>>): RowFocus? {
        val nonEmpty = rows.filter { it.second > 0 }
        if (nonEmpty.isEmpty()) return null
        if (saved != null) {
            val row = nonEmpty.firstOrNull { it.first == saved.row }
            if (row != null) return RowFocus(row.first, saved.index.coerceIn(0, row.second - 1))
        }
        return RowFocus(nonEmpty.first().first, 0)
    }

    /** True when [focus] still names an existing card (otherwise the list changed under it). */
    fun exists(focus: RowFocus?, rows: List<Pair<String, Int>>): Boolean =
        focus != null && rows.any { it.first == focus.row && focus.index in 0 until it.second }
}
