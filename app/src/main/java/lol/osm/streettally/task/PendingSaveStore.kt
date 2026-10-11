package lol.osm.streettally.task

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maproulette.sdk.CredentialStore

/** One interrupted save, not an offline queue. The production slot is Keystore-encrypted. */
data class PendingSaveIntent(
    val binding: String,
    val campaignId: Long,
    val taskId: Long,
    val answers: Map<String, String>? = null,
    val outcomeId: String? = null,
) {
    init { require((answers != null) != (outcomeId != null)) }

    fun action(form: ChoiceForm): ChoiceAction? = answers?.let { ChoiceAction.Answers(it) }
        ?: form.outcomes.firstOrNull { it.id == outcomeId }?.let { ChoiceAction.Outcome(it) }
}

class PendingSaveStore(private val store: CredentialStore) {
    private val mutex = Mutex()

    suspend fun read(): PendingSaveIntent? = mutex.withLock { readLocked() }

    /** Never overwrite a different unresolved save, including one belonging to another identity. */
    suspend fun keep(intent: PendingSaveIntent) = mutex.withLock {
        val existing = readLocked()
        check(existing == null || existing == intent) { "Another save needs recovery" }
        val p = Properties().apply {
            setProperty("version", "1")
            setProperty("binding", intent.binding)
            setProperty("campaign", intent.campaignId.toString())
            setProperty("task", intent.taskId.toString())
            intent.outcomeId?.let { setProperty("outcome", it) }
            intent.answers?.forEach { (q, o) -> setProperty("answer.$q", o) }
            setProperty("kind", if (intent.answers != null) "answers" else "outcome")
        }
        store.write(StringWriter().also { p.store(it, null) }.toString())
    }

    suspend fun clear(intent: PendingSaveIntent) = mutex.withLock {
        if (readLocked() == intent) store.clear()
    }

    private suspend fun readLocked(): PendingSaveIntent? {
        val raw = store.read() ?: return null
        val p = Properties().apply { load(StringReader(raw)) }
        require(p.getProperty("version") == "1")
        val answers = if (p.getProperty("kind") == "answers") p.stringPropertyNames()
            .filter { it.startsWith("answer.") }.associate { it.removePrefix("answer.") to p.getProperty(it) } else null
        return PendingSaveIntent(
            requireNotNull(p.getProperty("binding")), p.getProperty("campaign").toLong(),
            p.getProperty("task").toLong(), answers, p.getProperty("outcome"),
        )
    }
}
