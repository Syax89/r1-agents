package dev.r1agents

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import dev.r1os.host.ui.Theme

/**
 * Il repertorio con cui il buddy compagno compare, si nasconde o si fa vedere a metà: dai lati, dall'alto, dal basso.
 * Ogni voce anima la riga che lo contiene (la riga, non il personaggio: così non litiga con BuddyScrollFx né con le
 * reazioni del viso) e a fine corsa, anche se interrotta, rimette tutto a riposo.
 *
 * `IN` = comparse (alcune restano a metà per un attimo, ad esempio sbirciando da un lato), `OUT` = sparizioni.
 * Il gioco a nascondino è una sparizione seguita da una comparsa.
 */
object BuddyEntrances {
    class Variant(val id: String, val build: (View) -> Animator)

    private fun kf(prop: android.util.Property<View, Float>, vararg pts: Pair<Float, Float>, ease: android.animation.TimeInterpolator = LinearInterpolator()): PropertyValuesHolder =
        PropertyValuesHolder.ofKeyframe(prop, *pts.map { (t, v) -> android.animation.Keyframe.ofFloat(t, v).also { k -> k.interpolator = ease } }.toTypedArray())

    private fun run(row: View, ms: Long, ease: android.animation.TimeInterpolator, vararg holders: PropertyValuesHolder): Animator =
        ObjectAnimator.ofPropertyValuesHolder(row, *holders).apply { duration = ms; interpolator = ease }

    private fun ke(prop: android.util.Property<View, Float>, vararg pts: Pair<Float, Float>) = kf(prop, *pts, ease = Theme.EASE_IN_OUT)
    private val X = View.TRANSLATION_X; private val Y = View.TRANSLATION_Y
    private val SX = View.SCALE_X; private val SY = View.SCALE_Y; private val R = View.ROTATION; private val A = View.ALPHA
    private fun w(v: View) = v.width.coerceAtLeast(1).toFloat()
    private fun h(v: View) = v.height.coerceAtLeast(1).toFloat()

    val IN: List<Variant> = listOf(
        // dal basso, da dietro le righe, con un piccolo rimbalzo
        Variant("rise") { v -> run(v, Motion.SLOW * 2, Theme.POP, kf(Y, 0f to h(v) * .9f, 1f to 0f), kf(A, 0f to 0f, .25f to 1f, 1f to 1f)) },
        // cade dall'alto e rimbalza
        Variant("drop") { v -> run(v, Motion.SLOW * 2, BounceInterpolator(), kf(Y, 0f to -h(v) * 1.3f, 1f to 0f), kf(A, 0f to 0f, .15f to 1f, 1f to 1f)) },
        // scivola da sinistra, schiacciato dalla velocità
        Variant("slideLeft") { v -> run(v, Motion.SLOW * 2, Theme.POP, kf(X, 0f to -w(v) * .8f, 1f to 0f), kf(R, 0f to -22f, 1f to 0f), kf(SX, 0f to 1.25f, 1f to 1f), kf(SY, 0f to .85f, 1f to 1f)) },
        // scivola da destra
        Variant("slideRight") { v -> run(v, Motion.SLOW * 2, Theme.POP, kf(X, 0f to w(v) * .8f, 1f to 0f), kf(R, 0f to 22f, 1f to 0f), kf(SX, 0f to 1.25f, 1f to 1f), kf(SY, 0f to .85f, 1f to 1f)) },
        // sbircia da sinistra: mezzo fuori, guarda, poi entra tutto
        Variant("peekLeft") { v -> run(v, Motion.SLOW * 5, LinearInterpolator(), ke(X, 0f to -w(v) * .8f, .22f to -w(v) * .5f, .62f to -w(v) * .5f, 1f to 0f), ke(R, 0f to 0f, .3f to 10f, .6f to 10f, 1f to 0f)) },
        // sbircia da destra
        Variant("peekRight") { v -> run(v, Motion.SLOW * 5, LinearInterpolator(), ke(X, 0f to w(v) * .8f, .22f to w(v) * .5f, .62f to w(v) * .5f, 1f to 0f), ke(R, 0f to 0f, .3f to -10f, .6f to -10f, 1f to 0f)) },
        // sale a metà da dietro le righe, si guarda intorno, poi sale
        Variant("peekBottom") { v -> run(v, Motion.SLOW * 5, LinearInterpolator(), ke(Y, 0f to h(v) * .95f, .25f to h(v) * .42f, .65f to h(v) * .42f, 1f to 0f), ke(SX, 0f to .9f, .25f to .96f, 1f to 1f), ke(SY, 0f to .9f, .25f to .96f, 1f to 1f)) },
        // spunta dal nulla gonfiandosi
        Variant("pop") { v -> run(v, Motion.SLOW * 2, Theme.POP, kf(SX, 0f to 0f, 1f to 1f), kf(SY, 0f to 0f, 1f to 1f), kf(A, 0f to 0f, .2f to 1f, 1f to 1f)) },
        // gira su sé stesso mentre cresce
        Variant("spin") { v -> run(v, Motion.SLOW * 3, DecelerateInterpolator(1.4f), kf(R, 0f to -540f, 1f to 0f), kf(SX, 0f to .2f, 1f to 1f), kf(SY, 0f to .2f, 1f to 1f), kf(A, 0f to 0f, .3f to 1f, 1f to 1f)) },
        // arriva da lontano, in grande, e si assesta
        Variant("zoom") { v -> run(v, Motion.SLOW * 2, Theme.SETTLE, kf(SX, 0f to 2.4f, 1f to 1f), kf(SY, 0f to 2.4f, 1f to 1f), kf(A, 0f to 0f, .4f to 1f, 1f to 1f)) },
        // arriva da destra a saltelli
        Variant("hop") { v -> run(v, Motion.SLOW * 4, LinearInterpolator(), kf(X, 0f to w(v) * .8f, 1f to 0f, ease = DecelerateInterpolator()),
            ke(Y, 0f to 0f, .1f to -h(v) * .32f, .2f to 0f, .38f to -h(v) * .26f, .5f to 0f, .68f to -h(v) * .16f, .78f to 0f, .9f to -h(v) * .06f, 1f to 0f)) },
        // si avvicina furtivo: sale un po', si ritira, poi sale del tutto
        Variant("sneak") { v -> run(v, Motion.SLOW * 6, LinearInterpolator(), ke(Y, 0f to h(v) * .95f, .2f to h(v) * .72f, .4f to h(v) * .95f, .6f to h(v) * .5f, .78f to h(v) * .5f, 1f to 0f)) },
        // rotola da sinistra
        Variant("roll") { v -> run(v, Motion.SLOW * 3, DecelerateInterpolator(1.6f), kf(X, 0f to -w(v) * .8f, 1f to 0f), kf(R, 0f to -720f, 1f to 0f)) },
        // penzola dall'alto come un pendolo
        Variant("dangle") { v -> run(v, Motion.SLOW * 4, LinearInterpolator(), ke(Y, 0f to -h(v) * 1.2f, .25f to 0f, 1f to 0f), ke(R, 0f to 0f, .25f to 0f, .4f to 16f, .55f to -12f, .7f to 7f, .85f to -3f, 1f to 0f)) },
    )

    val OUT: List<Variant> = listOf(
        Variant("hideBottom") { v -> run(v, Motion.SLOW, Theme.EASE_IN_OUT, kf(Y, 0f to 0f, 1f to h(v) * .95f), kf(A, 0f to 1f, .8f to 1f, 1f to 0f)) },
        Variant("hideLeft") { v -> run(v, Motion.SLOW, Theme.EASE_IN_OUT, kf(X, 0f to 0f, 1f to -w(v) * .8f), kf(R, 0f to 0f, 1f to -18f)) },
        Variant("hideRight") { v -> run(v, Motion.SLOW, Theme.EASE_IN_OUT, kf(X, 0f to 0f, 1f to w(v) * .8f), kf(R, 0f to 0f, 1f to 18f)) },
        Variant("hideTop") { v -> run(v, Motion.SLOW, Theme.EASE_IN_OUT, ke(Y, 0f to 0f, .25f to h(v) * .12f, 1f to -h(v) * 1.2f), ke(A, 0f to 1f, .85f to 1f, 1f to 0f)) },
        Variant("shrink") { v -> run(v, Motion.SLOW, Theme.EASE_IN_OUT, kf(SX, 0f to 1f, .2f to 1.12f, 1f to 0f), kf(SY, 0f to 1f, .2f to 1.12f, 1f to 0f), kf(A, 0f to 1f, .8f to 1f, 1f to 0f)) },
        Variant("spinOut") { v -> run(v, Motion.SLOW * 2, Theme.EASE_IN_OUT, kf(R, 0f to 0f, 1f to 540f), kf(SX, 0f to 1f, 1f to .1f), kf(SY, 0f to 1f, 1f to .1f), kf(A, 0f to 1f, .7f to 1f, 1f to 0f)) },
        Variant("hopOut") { v -> run(v, Motion.SLOW * 2, LinearInterpolator(), kf(X, 0f to 0f, 1f to -w(v) * .8f, ease = Theme.EASE_IN_OUT),
            ke(Y, 0f to 0f, .15f to -h(v) * .3f, .3f to 0f, .5f to -h(v) * .2f, .65f to 0f, .85f to -h(v) * .1f, 1f to 0f)) },
        // a metà: scende dietro le righe e resta a mezzo busto
        Variant("halfDown") { v -> run(v, Motion.SLOW, Theme.EASE_IN_OUT, kf(Y, 0f to 0f, 1f to h(v) * .42f), kf(SX, 0f to 1f, 1f to .94f), kf(SY, 0f to 1f, 1f to .94f)) },
    )

    /**
     * La recitazione: ogni voce ha un "copione" per il viso e gli occhi, oltre al movimento. Gli occhi anticipano la direzione
     * (guarda dove sta andando prima di partire), una reazione a metà corsa (sorpresa, risatina, sguardo furtivo) e un gesto
     * all'arrivo (occhiolino, scrollata, sospiro di sollievo). Le reazioni vengono dal personaggio (`CharacterView.react`).
     */
    class Acting(val lookX: Float = 0f, val lookY: Float = 0f, val during: String? = null, val duringAt: Float = .3f, val landing: String? = null, val glances: Boolean = false)
    private val ACTING = mapOf(
        "rise" to Acting(lookY = -14f, landing = "wink"),
        "drop" to Acting(lookY = 16f, during = "surprised", duringAt = .15f, landing = "giggle"),
        "slideLeft" to Acting(lookX = 26f, landing = "bounce"),
        "slideRight" to Acting(lookX = -26f, landing = "bounce"),
        "peekLeft" to Acting(lookX = 24f, glances = true, landing = "wink"),
        "peekRight" to Acting(lookX = -24f, glances = true, landing = "wink"),
        "peekBottom" to Acting(lookY = -10f, glances = true, landing = "giggle"),
        "pop" to Acting(during = "surprised", duringAt = .35f, landing = "love"),
        "spin" to Acting(during = "spin", duringAt = .1f, landing = "surprised"),
        "zoom" to Acting(lookY = 8f, during = "surprised", duringAt = .2f, landing = "nod"),
        "hop" to Acting(lookX = -26f, during = "giggle", duringAt = .45f, landing = "bounce"),
        "sneak" to Acting(glances = true, during = "shiver", duringAt = .45f, landing = "surprised"),
        "roll" to Acting(lookX = 26f, landing = "annoyed"),
        "dangle" to Acting(lookY = 18f, during = "surprised", duringAt = .12f, landing = "relief"),
        "hideBottom" to Acting(lookY = 10f, during = "wink", duringAt = .1f),
        "hideLeft" to Acting(lookX = -26f, during = "wink", duringAt = .1f),
        "hideRight" to Acting(lookX = 26f, during = "wink", duringAt = .1f),
        "hideTop" to Acting(lookY = -16f, during = "surprised", duringAt = .1f),
        "shrink" to Acting(during = "surprised", duringAt = .05f),
        "spinOut" to Acting(during = "giggle", duringAt = .1f),
        "hopOut" to Acting(lookX = -26f, during = "giggle", duringAt = .1f),
        "halfDown" to Acting(lookY = -8f, during = "shiver", duringAt = .2f),
    )

    private fun character(row: View): dev.r1os.host.ui.CharacterView? = (row as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).map { g.getChildAt(it) }.filterIsInstance<dev.r1os.host.ui.CharacterView>().firstOrNull() }

    /** Fa partire il copione in parallelo al movimento; `total` = durata in ms. */
    private fun act(row: View, id: String, total: Long) {
        val cv = character(row) ?: return
        val a = ACTING[id] ?: return
        if (a.lookX != 0f || a.lookY != 0f) cv.lookAt(a.lookX, a.lookY, hold = (total * .55f).toLong())
        a.during?.let { kind -> row.postDelayed({ if (row.isAttachedToWindow && running.containsKey(row)) cv.react(kind) }, (total * a.duringAt).toLong()) }
        if (a.glances) { // si guarda intorno: sinistra, destra, poi al centro
            row.postDelayed({ if (row.isAttachedToWindow) cv.lookAt(-24f, 0f, hold = 320) }, (total * .30f).toLong())
            row.postDelayed({ if (row.isAttachedToWindow) cv.lookAt(24f, -4f, hold = 320) }, (total * .46f).toLong())
        }
        a.landing?.let { kind -> row.postDelayed({ if (row.isAttachedToWindow) cv.react(kind) }, total + 40L) }
    }

    private val rng = java.util.Random()
    fun randomIn(not: String? = null): Variant = IN.filter { it.id != not }.let { it[rng.nextInt(it.size)] }
    fun randomOut(): Variant = OUT[rng.nextInt(OUT.size)]
    fun find(id: String): Variant? = (IN + OUT).firstOrNull { it.id == id }

    /** Rimette la riga a riposo. */
    fun rest(row: View) { row.animate().cancel(); row.translationX = 0f; row.translationY = 0f; row.scaleX = 1f; row.scaleY = 1f; row.rotation = 0f; row.alpha = 1f }

    private val running = java.util.WeakHashMap<View, Animator>()
    fun cancel(row: View) { running.remove(row)?.cancel() }

    /** Esegue una voce; `then` parte a fine corsa. Ritorna false se l'utente ha le animazioni spente. */
    fun play(row: View, variant: Variant, then: () -> Unit = {}): Boolean {
        if (!row.isAttachedToWindow || Motion.reduced(row.context)) return false
        cancel(row); row.animate().cancel()
        row.pivotX = row.width / 2f; row.pivotY = row.height / 2f
        val a = variant.build(row)
        var done = false
        a.addListener(object : AnimatorListenerAdapter() {
            private fun end(restore: Boolean) { if (done) return; done = true; running.remove(row); if (restore) rest(row); then() }
            override fun onAnimationEnd(animation: Animator) { end(variant.id !in HIDDEN_AT_END) }
            override fun onAnimationCancel(animation: Animator) { done = true; running.remove(row); rest(row) }
        })
        running[row] = a
        a.start()
        act(row, variant.id, a.duration)
        return true
    }

    /** Le sparizioni che lasciano la riga fuori vista: a fine corsa NON si rimette a riposo (lo fa la comparsa seguente). */
    private val HIDDEN_AT_END = OUT.filter { it.id != "halfDown" }.map { it.id }.toSet()

    /** Nascondino: sparisce in un modo, aspetta un po', ricompare in un altro. */
    fun hideAndSeek(row: View, then: () -> Unit = {}): Boolean {
        val out = randomOut()
        return play(row, out) {
            row.postDelayed({
                if (!row.isAttachedToWindow) { rest(row); return@postDelayed }
                if (out.id == "halfDown") rest(row)
                if (!play(row, randomIn(), then)) { rest(row); then() }
            }, 350L + rng.nextInt(900))
        }
    }

    /** Ogni tanto, da solo, il buddy fa capolino: solo con la pagina in vista e ferma. `idle` decide se è il momento. */
    fun scheduleIdlePeeks(row: View, idle: () -> Boolean) {
        fun arm() {
            row.postDelayed({
                if (!row.isAttachedToWindow) return@postDelayed
                if (row.isShown && idle()) play(row, randomIn())
                arm()
            }, 22_000L + rng.nextInt(20_000))
        }
        arm()
    }
}
