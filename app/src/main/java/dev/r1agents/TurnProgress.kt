package dev.r1agents

/** Stato di un turno indipendente dalla schermata; le transizioni terminali ignorano eventi tardivi. */
class TurnProgress {
    enum class Status { RUNNING, DONE, ERROR, CANCELLED }
    data class Snapshot(val revision: Long = 0, val text: String = "", val activity: String = "", val status: Status = Status.RUNNING, val error: String = "")
    private var state = Snapshot()
    private val listeners = LinkedHashSet<(Snapshot) -> Unit>()
    @Synchronized fun snapshot() = state
    fun subscribe(listener: (Snapshot) -> Unit): () -> Unit {
        val initial = synchronized(this) { listeners.add(listener); state }
        runCatching { listener(initial) }
        return { synchronized(this) { listeners.remove(listener) }; Unit }
    }
    private fun change(update: (Snapshot) -> Snapshot) {
        val (next, callbacks) = synchronized(this) {
            if (state.status != Status.RUNNING) return
            state = update(state).copy(revision = state.revision + 1)
            state to listeners.toList()
        }
        callbacks.forEach { callback -> runCatching { callback(next) } }
    }
    fun append(delta: String) = change { it.copy(text = it.text + delta) }
    fun activity(label: String) = change { it.copy(activity = label) }
    fun finish() = change { it.copy(status = Status.DONE, activity = "") }
    fun fail(message: String) = change { it.copy(status = Status.ERROR, error = message, activity = "") }
    fun cancel() = change { it.copy(status = Status.CANCELLED, activity = "") }
}
