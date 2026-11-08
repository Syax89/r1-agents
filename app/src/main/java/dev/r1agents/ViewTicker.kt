package dev.r1agents

import android.view.View

/** Un solo aggiornamento periodico per vista; si arresta al distacco e riparte al ritorno. */
internal class ViewTicker(
    private val view: View,
    private val period: Long,
    private val active: () -> Boolean,
    private val update: () -> Unit
) : Runnable, View.OnAttachStateChangeListener {
    init {
        require(period > 0)
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) run()
    }

    override fun run() {
        view.removeCallbacks(this)
        if (!view.isAttachedToWindow) return
        if (view.isShown && active()) update()
        // L'aggiornamento può sostituire la lista e staccare proprio questa vista.
        if (view.isAttachedToWindow) view.postDelayed(this, period - System.currentTimeMillis() % period)
    }

    override fun onViewAttachedToWindow(v: View) = run()
    override fun onViewDetachedFromWindow(v: View) { view.removeCallbacks(this) }
}
