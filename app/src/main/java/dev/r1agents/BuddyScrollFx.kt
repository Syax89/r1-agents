package dev.r1agents

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import dev.r1os.host.ui.CharacterView
import dev.r1os.host.ui.Theme

/**
 * La curiosità: quando il buddy fa solo capolino (affondato dietro le righe, o nello standby) si guarda intorno a intervalli
 * irregolari: sinistra, destra, ogni tanto in su, a volte una doppia occhiata o una piccola inclinazione di testa.
 * `active` dice se è il momento (non dorme, non è offeso, non sta volando).
 */
object BuddyCuriosity {
    private val loops = java.util.WeakHashMap<View, Runnable>()
    private val rng = java.util.Random()
    fun running(cv: View) = loops.containsKey(cv)
    fun start(cv: CharacterView, pace: Float = 1f, active: () -> Boolean = { true }) {
        if (loops.containsKey(cv) || Motion.reduced(cv.context)) return
        lateinit var tick: Runnable
        tick = Runnable {
            if (!cv.isAttachedToWindow) { loops.remove(cv); return@Runnable }
            if (active()) {
                val side = if (rng.nextBoolean()) 1f else -1f
                when (rng.nextInt(5)) {
                    0 -> { cv.lookAt(30f * side, rng.nextInt(7) - 3f, hold = 900) }
                    1 -> { cv.lookAt(-30f * side, -4f, hold = 420); cv.postDelayed({ if (cv.isAttachedToWindow) cv.lookAt(30f * side, -4f, hold = 700) }, 520L) }
                    2 -> { cv.lookAt(0f, -14f, hold = 800) }
                    3 -> { cv.lookAt(26f * side, -6f, hold = 700); cv.animate().rotation(5f * side).setDuration(Motion.BASE).setInterpolator(Theme.SETTLE).withEndAction { cv.animate().rotation(0f).setStartDelay(500L).setDuration(Motion.BASE).start() }.start() }
                    else -> { cv.lookAt(32f * side, 0f, hold = 500); cv.postDelayed({ if (cv.isAttachedToWindow) cv.lookAt(-32f * side, 0f, hold = 500) }, 380L); cv.postDelayed({ if (cv.isAttachedToWindow) cv.lookAt(32f * side, 2f, hold = 600) }, 780L) }
                }
            }
            cv.postDelayed(tick, ((1100L + rng.nextInt(1700)) * pace).toLong())
        }
        loops[cv] = tick
        cv.postDelayed(tick, (500L * pace).toLong())
    }
    fun stop(cv: View) { loops.remove(cv)?.let { cv.removeCallbacks(it) } }
}

/** L'umore condiviso del buddy: se è offeso, un tocco lo fa perdonare. */
object BuddyMood {
    @Volatile var sulkUntil = 0L
    fun sulking() = SystemClock.uptimeMillis() < sulkUntil
}

/**
 * Il buddy compagno legato allo scroll della pagina in cui sta (R1ScrollView la guida anche con la rotella):
 * - scorrendo in alto affonda dietro le righe, rimpicciolendo e sfumando a metà (parallasse più lenta del contenuto);
 * - la velocità lo spinge e lo inclina nel verso dello scroll, poi ritorna con rimbalzo;
 * - guarda nel verso in cui scorre; se la pagina fa avanti e indietro troppo, gli gira la testa;
 * - tornando in cima fa un cenno di saluto;
 * - tirato in giù oltre l'inizio si allunga come gomma; se lo si tira troppo si infastidisce, poi si offende, poi
 *   fa il broncio (si volta); un tocco lo fa perdonare. Il conteggio dei torti si azzera dopo 25 s.
 */
class BuddyScrollFx(private val scroll: R1ScrollView) {
    private val density = scroll.resources.displayMetrics.density
    private var buddy: CharacterView? = null
    private var row: View? = null
    private var lastY = 0
    private var lastT = 0L
    private var lastLook = 0L
    private var vel = 0f
    private var lastDir = 0
    private var turns = 0
    private var turnsAt = 0L
    private var pull = 0f
    private var offendedThisPull = false
    private var offenses = 0
    private var offenseAt = 0L
    private var wasDeep = false
    private val settle = Runnable { calm() }

    /** Dove cercare la riga del buddy se è fuori dallo scroll (pagina con testata fissa); altrimenti dentro lo scroll. */
    var root: View? = null

    private fun find(): CharacterView? {
        buddy?.let { if (it.isAttachedToWindow) return it }
        val r = (root ?: scroll).findViewWithTag<View>("scene-companion-row") as? ViewGroup
        if (r == null) { // pagina con il buddy piccolo nella testata: niente trasformazioni, solo sguardi e cenni
            var up: View? = scroll
            repeat(4) { up = up?.parent as? View; up?.findViewWithTag<CharacterView>("scene-buddy")?.let { headerMode = true; buddy = it; row = null; return it } }
            return null
        }
        headerMode = false
        val newRow = r !== row
        row = r; buddy = r.getChildAt(0) as? CharacterView
        if (newRow || baseH == 0) baseH = ((r.layoutParams?.height ?: 0).takeIf { it > 0 } ?: r.height)   // mai riletto dalla riga già ridotta
        if (scroll.scrollY > 0) scroll.post { applyRest() } // pagina ricostruita già scorsa
        return buddy
    }

    private var baseH = 0
    private var headerMode = false
    private fun progress(y: Int) = (y.toFloat() / baseH.coerceAtLeast(1)).coerceIn(0f, 1f)
    /** La riga si restringe da piena a 44 dp mentre scorri: il buddy viene "spinto in su" e la lista guadagna spazio. */
    private fun rowHeight(p: Float) = (baseH - (baseH - density * 44f) * p).toInt().coerceAtLeast((density * 44f).toInt())
    private fun fit(r: View, p: Float) { if (root == null) return; val lp = r.layoutParams ?: return; val h = rowHeight(p); if (lp.height != h) { lp.height = h; r.layoutParams = lp } }
    /** Centro sul bordo inferiore della riga a fine corsa: resta mezza sfera con gli occhi, il resto è coperto dalle righe. */
    private fun restY(p: Float) = (rowHeight(p) / 2f - (if (root != null) density * 16f else 0f)) * p   // con la testata fissa gli occhi restano sopra il bordo della lista
    private fun applyRest() {
        val cv = buddy ?: return; val r = row ?: return
        val p = progress(scroll.scrollY)
        fit(r, p)
        cv.translationY = restY(p); cv.scaleX = 1f - 0.1f * p; cv.scaleY = cv.scaleX; cv.alpha = 1f
    }

    private fun active(): CharacterView? = if (Motion.reduced(scroll.context)) null else find()

    fun onScrolled(y: Int) {
        val cv = active() ?: return
        if (headerMode) { headerScrolled(cv, y); return }
        val r = row ?: return
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastT).coerceAtLeast(1L)
        val dy = y - lastY
        lastY = y; lastT = now
        vel = vel * 0.6f + (dy.toFloat() / dt) * 0.4f
        if (pull == 0f) {
            val p = progress(y)
            cv.animate().cancel()   // un calm() in corso non deve sovrascrivere la posizione che segue il dito
            cv.pivotX = cv.width / 2f; cv.pivotY = cv.height * 0.75f
            fit(r, p)
            cv.translationY = restY(p)
            cv.scaleX = 1f - 0.1f * p; cv.scaleY = cv.scaleX
            cv.alpha = 1f
            // Affondato: resta mezza sfera con gli occhi e si guarda intorno.
            if (p >= 1f) BuddyCuriosity.start(cv, 1f) { !BuddyMood.sulking() && pull == 0f && scroll.scrollY >= baseH } else BuddyCuriosity.stop(cv)
        }
        if (!BuddyMood.sulking()) {
            cv.rotation = (-vel * 14f).coerceIn(-12f, 12f)
            cv.removeCallbacks(settle); cv.postDelayed(settle, 140L)
            if (now - lastLook > 260L && kotlin.math.abs(vel) > 0.5f) { lastLook = now; cv.lookAt(0f, if (vel > 0) 16f else -14f, hold = 500) }
        }
        val dir = if (dy > 2) 1 else if (dy < -2) -1 else 0
        if (dir != 0 && dir != lastDir) {
            if (now - turnsAt > 2500L) turns = 0
            turns++; turnsAt = now; lastDir = dir
            if (turns >= 6) { turns = 0; cv.react("spin") }
        }
        if (y > baseH) wasDeep = true
        if (y == 0 && wasDeep) { wasDeep = false; if (!BuddyMood.sulking()) cv.react("nod") }
    }

    /** Testata: il buddy non si muove con la pagina; segue con lo sguardo il verso dello scroll e saluta tornando in cima. */
    private fun headerScrolled(cv: CharacterView, y: Int) {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastT).coerceAtLeast(1L); val dy = y - lastY
        lastY = y; lastT = now
        vel = vel * 0.6f + (dy.toFloat() / dt) * 0.4f
        if (!BuddyMood.sulking() && now - lastLook > 320L && kotlin.math.abs(vel) > 0.6f) { lastLook = now; cv.lookAt(0f, if (vel > 0) 14f else -12f, hold = 500) }
        if (y > density * 220f) wasDeep = true
        if (y == 0 && wasDeep) { wasDeep = false; if (!BuddyMood.sulking()) cv.react("nod") }
    }

    /** `d` px di trascinamento oltre l'inizio della pagina (dito o rotella). */
    fun pull(d: Float) {
        val cv = active() ?: return
        pull = d
        val f = (d / (density * 140f)).coerceIn(0f, 1.6f)
        cv.pivotX = cv.width / 2f; cv.pivotY = cv.height * 0.75f
        cv.scaleY = 1f + 0.2f * f; cv.scaleX = 1f - 0.09f * f
        cv.translationY = (d * 0.35f).coerceAtMost(density * 40f)
        cv.removeCallbacks(settle); cv.postDelayed(settle, 220L)
        if (f >= 1f && !offendedThisPull && !BuddyMood.sulking()) offend(cv)
    }

    private fun offend(cv: CharacterView) {
        offendedThisPull = true
        val now = SystemClock.uptimeMillis()
        if (now - offenseAt > 25000L) offenses = 0
        offenses++; offenseAt = now
        when (offenses) {
            1 -> cv.react("annoyed")
            2 -> cv.react("offended")
            else -> { cv.react("sulk"); BuddyMood.sulkUntil = now + 3800L }
        }
    }

    /** Fine del tiro o della corsa: torna alla posizione di riposo con un piccolo rimbalzo. */
    fun calm() {
        val cv = buddy?.takeIf { it.isAttachedToWindow } ?: return
        val f = (pull / (density * 140f)).coerceIn(0f, 1.6f)
        if (pull > 0f && f in 0.45f..0.99f && !offendedThisPull && !BuddyMood.sulking()) cv.react("giggle")
        pull = 0f; offendedThisPull = false
        val r = row
        val p = progress(scroll.scrollY)
        val s = 1f - 0.1f * p
        if (r != null) fit(r, p)
        cv.animate().cancel()
        cv.animate().rotation(0f).scaleX(s).scaleY(s).translationY(if (r == null) 0f else restY(p)).alpha(1f)
            .setDuration(Motion.BASE).setInterpolator(Theme.POP).start()
    }
}

/** Budget dei gesti spontanei: almeno 20 s tra uno e l'altro in tutta l'app. */
object BuddyEntrancesIdle {
    private var last = 0L
    fun ok(): Boolean { val now = SystemClock.uptimeMillis(); if (now - last < 20_000L) return false; last = now; return true }
}
