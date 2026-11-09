package dev.r1agents

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import dev.r1os.host.ui.CharacterView
import dev.r1os.host.ui.Theme
import dev.r1os.host.ui.dp
import kotlin.math.abs

/** Una sola linea temporale: 0 = carosello, 1 = chat. Uscita e gesto indietro la percorrono al contrario. */
internal class BuddyFlight(
    private val root: FrameLayout,
    private val home: View,
    private val chat: View,
    private var homeAvatar: CharacterView,
    private var chatAvatar: CharacterView,
    initial: Float,
    private val fadePages: Boolean = true,
    private val resolveHome: (() -> CharacterView?)? = null,
    private val resolveChat: (() -> CharacterView?)? = null,
    private val finished: (BuddyFlight, Float) -> Unit
) {
    private val geometry = Matrix()
    private val liveBounds = RectF()
    private val retired = LinkedHashSet<CharacterView>()
    private val homeBounds = bounds(homeAvatar)
    private val chatBounds = bounds(chatAvatar)
    private val size = maxOf(homeAvatar.width, chatAvatar.width)
    private val clone = CharacterView(root.context, size).apply { copyAppearanceFrom(if (initial >= .5f) chatAvatar else homeAvatar); tag = "buddy-flight"; setActive(false); setOnTouchListener(null); isClickable = false; isFocusable = false }
    private val fade = AccelerateDecelerateInterpolator()
    private var animator: ValueAnimator? = null
    private var disposed = false
    private var towards = if (initial >= .5f) 0f else 1f
    val isActive: Boolean get() = !disposed && clone.parent === root
    var progress = initial
        private set

    private val layouts = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        if (isActive) {
            resolveHome?.invoke()?.takeIf { it !== homeAvatar }?.let { retired.add(homeAvatar); homeAvatar = it; hide(it); it.setActive(false) }
            resolveChat?.invoke()?.takeIf { it !== chatAvatar }?.let { retired.add(chatAvatar); chatAvatar = it; hide(it); it.setActive(false) }
            applyProgress(progress)
        }
    }
    /** I buddy veri si nascondono con INVISIBLE (non solo alpha): scroll, comparse e capolini non possono riaccenderli durante il volo. */
    private fun hide(v: CharacterView) { v.alpha = 0f; if (v.visibility != View.INVISIBLE) v.visibility = View.INVISIBLE }
    private fun show(v: CharacterView) { v.visibility = View.VISIBLE; v.alpha = 1f }
    /** Originali sostituiti durante il volo e lasciati nascosti: chi prende in carico il clone li affida a restoreBuddies(). */
    var leftHidden: List<CharacterView> = emptyList()
        private set
    /** Con [showRetired] falso (clone preso in carico, o volo chiuso senza sorgente) i ritirati restano nascosti: riaccenderli darebbe due buddy insieme. */
    private fun stopWatchingLayout(showRetired: Boolean) {
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(layouts)
        if (showRetired) retired.forEach { if (it.tag != "buddy-carry") show(it) } else leftHidden = retired.toList()
        retired.forEach { it.setActive(false) }; retired.clear()
    }
    init {
        hide(homeAvatar); hide(chatAvatar)
        homeAvatar.setActive(false); chatAvatar.setActive(false)
        if (fadePages) { home.setLayerType(View.LAYER_TYPE_HARDWARE, null); chat.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        root.addView(clone, FrameLayout.LayoutParams(size, size))
        clone.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
        clone.layout(0, 0, size, size)
        root.viewTreeObserver.addOnGlobalLayoutListener(layouts)
        applyProgress(initial)
    }
    private fun readBounds(view: View, out: RectF) {
        geometry.reset()
        view.transformMatrixToGlobal(geometry); root.transformMatrixToLocal(geometry)
        out.set(0f, 0f, view.width.toFloat(), view.height.toFloat()); geometry.mapRect(out)
    }
    private fun bounds(view: View): RectF = RectF().also { readBounds(view, it) }
    private fun applyProgress(value: Float) {
        // Anche fumetti, tastiera e anteprime possono spostare l'ancora senza cambiare la finestra.
        val destination = if (towards == 0f) homeAvatar else chatAvatar
        val page = if (towards == 0f) home else chat
        if (destination.isAttachedToWindow && destination.width > 0 && destination.height > 0) {
            readBounds(destination, liveBounds)
            if (fadePages) liveBounds.offset(-page.translationX, -page.translationY)
            if (towards == 0f) homeBounds.set(liveBounds) else chatBounds.set(liveBounds)
        }
        progress = value.coerceIn(0f, 1f)
        // Finché il volo dura, in scena c'è un solo buddy: comparse, capolini o rientri della pagina non possono riaccendere gli originali.
        if (!disposed) { hide(homeAvatar); hide(chatAvatar); retired.forEach { hide(it) } }
        clone.copyScenePoseFrom(if (progress >= .35f) chatAvatar else homeAvatar)
        val flight = Theme.SETTLE.getInterpolation(progress)
        val alpha = fade.getInterpolation(progress)
        fun mix(a: Float, b: Float) = a + (b - a) * flight
        clone.translationX = mix(homeBounds.centerX(), chatBounds.centerX()) - size / 2f
        clone.translationY = mix(homeBounds.centerY(), chatBounds.centerY()) - size / 2f
        clone.scaleX = mix(homeBounds.width(), chatBounds.width()) / size
        clone.scaleY = mix(homeBounds.height(), chatBounds.height()) / size
        if (fadePages) {
            chat.alpha = alpha; chat.translationY = root.context.dp(14f) * (1f - alpha)
            home.alpha = 1f - alpha
        }
    }
    fun bringActorToFront() { if (isActive) clone.bringToFront() }
    fun seek(value: Float) { stopAnimator(); if (!disposed) applyProgress(value) }
    fun animateTo(target: Float) {
        towards = target
        stopAnimator()
        if (disposed) return
        if (Motion.reduced(root.context) || abs(target - progress) < .001f) { applyProgress(target); finished(this, target); return }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = (Motion.SLOW * abs(target - progress)).toLong().coerceAtLeast(Motion.PRESS)
            interpolator = LinearInterpolator()
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled && !disposed) finished(this@BuddyFlight, target) }
            })
            start()
        }
    }
    private fun stopAnimator() { animator?.cancel(); animator = null }
    fun takeActor(): CharacterView {
        disposed = true; stopAnimator(); stopWatchingLayout(false); clone.tag = "buddy-carry"
        if (fadePages) for (view in listOf(home, chat)) {
            view.alpha = 1f; view.translationX = 0f; view.translationY = 0f; view.scaleX = 1f; view.scaleY = 1f
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        return clone
    }
    fun dispose(restoreSource: Boolean = true) {
        if (disposed) return
        disposed = true; stopAnimator(); stopWatchingLayout(restoreSource); root.removeView(clone)
        if (restoreSource) show(homeAvatar) else hide(homeAvatar); show(chatAvatar)
        homeAvatar.setActive(restoreSource); chatAvatar.setActive(true)
        if (fadePages) for (view in listOf(home, chat)) {
            view.alpha = 1f; view.translationX = 0f; view.translationY = 0f; view.scaleX = 1f; view.scaleY = 1f
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }
}
