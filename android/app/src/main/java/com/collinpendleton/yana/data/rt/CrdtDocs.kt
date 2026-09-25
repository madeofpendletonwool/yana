package com.collinpendleton.yana.data.rt

import com.collinpendleton.yana.crdt.crdt.Crdt

/**
 * The document surface the sync engine needs from one note: the same
 * operations the AAR's bind package exposes, narrowed to what sync
 * calls. The production implementation wraps the AAR; the JVM test
 * suite substitutes a fake, because the AAR's native libraries only
 * load on a device. Every method is blocking; the engine calls them on
 * its IO dispatcher only.
 */
interface RtDoc {
    val noteId: String

    /** The body as text; every replica converges on the same one. */
    fun text(): String

    /** The document's clock, sent with a subscribe. */
    fun stateVector(): ByteArray

    /** The whole document as one update: the persisted snapshot. */
    fun state(): ByteArray

    /** Integrates a peer's update; true when the document gained anything. */
    fun apply(update: ByteArray): Boolean

    /**
     * Mutates the body so it reads as `want` — the editor's edit path,
     * diff-applied so untouched characters keep their identity and
     * merge with concurrent typing. Returns the update to forward, or
     * null when the body already read as `want`.
     */
    fun replaceText(want: String): ByteArray?

    fun close()
}

/**
 * Opens documents and merges updates. One interface so the JVM test
 * suite can stand in for the AAR, whose native libraries only load on
 * a device.
 */
interface RtDocFactory {
    fun open(noteId: String, state: ByteArray): RtDoc

    /** Combines two updates into one a fresh replica loads directly. */
    fun merge(a: ByteArray, b: ByteArray): ByteArray
}

/** Folds the factory's pairwise merge over a batch. */
fun RtDocFactory.mergeAll(updates: List<ByteArray>): ByteArray? {
    if (updates.isEmpty()) return null
    var merged = updates[0]
    for (i in 1 until updates.size) merged = merge(merged, updates[i])
    return merged
}

/** Opens documents in the Go CRDT engine (the AAR `make android-crdt` builds). */
class GoDocFactory : RtDocFactory {
    override fun open(noteId: String, state: ByteArray): RtDoc = GoDoc(noteId, state)

    override fun merge(a: ByteArray, b: ByteArray): ByteArray = Crdt.mergeUpdates(a, b)
}

/** One note's document in the Go CRDT engine. */
private class GoDoc(override val noteId: String, state: ByteArray) : RtDoc {
    private val doc = Crdt.loadDoc(state)

    override fun text(): String = doc.text()

    override fun stateVector(): ByteArray = doc.stateVector()

    override fun state(): ByteArray = doc.state()

    override fun apply(update: ByteArray): Boolean {
        val fresh = doc.applyUpdate(update)
        return fresh != null && fresh.isNotEmpty()
    }

    override fun replaceText(want: String): ByteArray? = doc.replaceText(want)

    override fun close() = doc.close()
}
