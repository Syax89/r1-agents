package dev.r1agents

/** Il gesto d'ingresso descrive la destinazione, senza dipendere dal titolo tradotto. */
internal enum class BuddySceneContext(val pose: String, val activity: String?) {
    LIVE("idle", null),
    READ("working", "files"),
    CONFIGURE("working", "build"),
    MEMORY("working", "memory"),
    CONNECT("thinking", null),
    TIME("waiting", null),
    SECURITY("waiting", null),
    ALERT("waiting", null)
}
