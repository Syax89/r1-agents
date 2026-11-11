package dev.r1agents

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View

/**
 * Acqua vera: uno shader (AGSL, Android 13+) che deforma il contenuto dello schermo come se lo si guardasse attraverso
 * l'acqua. Un fronte d'onda parte dal punto toccato e si allarga; dietro al fronte piccole increspature che si smorzano
 * verso il centro; sulle creste un riflesso chiaro, nessun colore. Si applica solo durante una transizione e si toglie
 * alla fine: a riposo non costa nulla. Sulle versioni più vecchie non fa niente (resta il disegno delle increspature).
 */
object Water {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private const val SRC = """
        uniform shader content;
        uniform float2 center;
        uniform float radius;
        uniform float strength;
        uniform float time;
        uniform float density;
        half4 main(float2 p) {
            float2 d = p - center;
            float dist = length(d);
            float2 dir = dist > 0.001 ? d / dist : float2(0.0, 0.0);
            // Il fronte dell'onda: una cresta larga qualche dp attorno al raggio, che scorre mentre si allarga.
            float w = 36.0 * density;   // fronte più largo e morbido
            float x = (dist - radius) / w;
            float front = exp(-x * x);
            float crest = sin(x * 3.0 - time * 5.5);
            // Dietro al fronte: increspature più fitte che si smorzano andando verso il centro.
            float inside = clamp(dist / max(radius, 1.0), 0.0, 1.0);
            float behind = step(dist, radius) * inside * inside;
            float ripple = sin(dist / (8.0 * density) - time * 8.0) * behind;
            float disp = (crest * front * 5.0 + ripple * 1.1) * density * strength;   // delicata: appena un respiro d'acqua
            half4 c = content.eval(p - dir * disp);
            // Luce sulle creste: un riflesso chiaro dove l'onda si alza, niente tinta.
            float spec = clamp(crest * front, 0.0, 1.0) + clamp(ripple, 0.0, 1.0) * 0.25;
            c.rgb = mix(c.rgb, half3(1.0), half(spec * 0.09 * strength));
            return c;
        }
    """

    private var shader: Any? = null

    /** Applica l'acqua a `v` (di solito il contenitore delle pagine). `blur` in pixel, per l'effetto "sott'acqua". */
    fun apply(v: View, cx: Float, cy: Float, radius: Float, strength: Float, time: Float, blur: Float = 0f) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val s = (shader as? RuntimeShader) ?: RuntimeShader(SRC).also { shader = it }
        s.setFloatUniform("center", cx, cy)
        s.setFloatUniform("radius", radius)
        s.setFloatUniform("strength", strength)
        s.setFloatUniform("time", time)
        s.setFloatUniform("density", v.resources.displayMetrics.density)
        var effect = RenderEffect.createRuntimeShaderEffect(s, "content")
        if (blur > 0.5f) effect = RenderEffect.createChainEffect(effect, RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
        v.setRenderEffect(effect)
    }

    fun clear(v: View) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) v.setRenderEffect(null) }
}
