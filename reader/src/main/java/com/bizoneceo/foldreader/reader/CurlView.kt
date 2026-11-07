package com.bizoneceo.foldreader.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Page view with a physical page-curl: the turning sheet is wrapped around a
 * virtual cylinder that follows the finger. The sheet is drawn with
 * [Canvas.drawBitmapMesh] twice — the front side (flat part + lower half of
 * the cylinder) and the back side (upper half of the cylinder + the part that
 * has folded over), with per-vertex shading — plus a soft shadow cast on the
 * page underneath.
 *
 * In two-page mode (unfolded Galaxy Fold) the right page lifts from the
 * spine and lands on the left one, exactly like a paper book; the back of
 * the sheet is the next left page. In one-page mode the sheet's back is
 * blank paper with the text faintly showing through.
 */
class CurlView(context: Context) : View(context) {

    interface Host {
        fun next(p: PagePos): PagePos?
        fun prev(p: PagePos): PagePos?
        fun pageBitmap(p: PagePos?, side: Side, variant: Variant): Bitmap?
        fun onPageChanged(p: PagePos)
        fun onCenterTap()
        fun onViewSize(w: Int, h: Int)
    }

    var host: Host? = null

    private var spread = false
    private var pageW = 0
    private var pageH = 0
    var base: PagePos? = null
        private set

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = 500 * density

    // ---- current turn ----
    private class Turn(
        val staticLeft: PagePos?, // spread only: page under the left half
        val front: PagePos,
        val back: PagePos?,       // spread only: next left page (null in single mode)
        val under: PagePos?,
        val flatBase: PagePos,    // resulting position if the sheet lies flat on the right
        val turnedBase: PagePos,  // resulting position if the sheet is fully turned
        val cx: Float,            // grabbed corner on the sheet's outer edge (sheet coords)
        val cy: Float,
    ) {
        var fx = cx
        var fy = cy
    }

    private var turn: Turn? = null
    private var animator: ValueAnimator? = null

    // ---- touch ----
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragForward = true
    private var dragIgnored = false
    private var startFx = 0f
    private var startFy = 0f
    private var velocity: VelocityTracker? = null

    // ---- mesh ----
    private val meshW = 24
    private val meshH = 32
    private val vCount = (meshW + 1) * (meshH + 1)
    private val frontVerts = FloatArray(vCount * 2)
    private val backVerts = FloatArray(vCount * 2)
    private val frontColors = IntArray(vCount)
    private val backColors = IntArray(vCount)
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** (Re)configures the layout; called after pagination for the current size. */
    fun configure(spread: Boolean, pageW: Int, pageH: Int, base: PagePos) {
        animator?.cancel()
        animator = null
        turn = null
        dragging = false
        this.spread = spread
        this.pageW = pageW
        this.pageH = pageH
        this.base = base
        invalidate()
    }

    fun jumpTo(p: PagePos) {
        animator?.cancel()
        turn = null
        base = p
        host?.onPageChanged(p)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        animator?.cancel()
        turn = null
        if (w > 0 && h > 0) host?.onViewSize(w, h)
    }

    // =====================================================================
    // Turning logic
    // =====================================================================

    private fun cornerY(y: Float): Float = when {
        y > pageH * 0.62f -> pageH.toFloat()
        y < pageH * 0.38f -> 0f
        else -> y
    }

    /** Starts a turn. forward = towards the next page. Returns false if impossible. */
    private fun beginTurn(forward: Boolean, cy: Float): Boolean {
        val h = host ?: return false
        val b = base ?: return false
        val cx = pageW.toFloat()
        val t: Turn = if (spread) {
            val tb: PagePos = if (forward) b else {
                val p1 = h.prev(b) ?: return false
                val p2 = h.prev(p1)
                if (p2 == null) { jumpTo(p1); return false }
                p2
            }
            val front = h.next(tb) ?: return false
            val back = h.next(front) ?: return false
            val under = h.next(back)
            Turn(tb, front, back, under, tb, back, cx, cy)
        } else {
            val tb: PagePos = if (forward) b else (h.prev(b) ?: return false)
            val under = h.next(tb) ?: return false
            Turn(null, tb, null, under, tb, under, cx, cy)
        }
        if (!forward) { t.fx = cx - 2 * pageW; t.fy = cy }
        turn = t
        return true
    }

    private fun finishTurn(turned: Boolean) {
        val t = turn ?: return
        turn = null
        val nb = if (turned) t.turnedBase else t.flatBase
        val changed = nb != base
        base = nb
        if (changed) host?.onPageChanged(nb)
        invalidate()
    }

    /** Animates the grabbed corner to the flat (false) or fully turned (true) position. */
    private fun animateTo(turned: Boolean, arc: Boolean) {
        val t = turn ?: return
        val fromX = t.fx
        val fromY = t.fy
        val toX = if (turned) t.cx - 2 * pageW else t.cx
        val toY = t.cy
        val dist = abs(toX - fromX) / (2f * pageW)
        val lift = if (arc) pageH * 0.10f else 0f
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (180 + 420 * dist).toLong()
            interpolator = DecelerateInterpolator(1.3f)
            addUpdateListener {
                val k = it.animatedValue as Float
                t.fx = fromX + (toX - fromX) * k
                t.fy = fromY + (toY - fromY) * k - lift * sin(PI * k).toFloat()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) animator = null
                    if (!cancelled && turn === t) finishTurn(turned)
                }
            })
            start()
        }
    }

    /** Completes a running animation immediately (fast repeated taps). */
    private fun settleAnimation() {
        val a = animator ?: return
        a.end()
    }

    fun turnForward() {
        settleAnimation()
        if (turn == null && beginTurn(true, pageH.toFloat())) animateTo(turned = true, arc = true)
    }

    fun turnBackward() {
        settleAnimation()
        if (turn == null && beginTurn(false, pageH.toFloat())) animateTo(turned = false, arc = true)
    }

    // =====================================================================
    // Touch
    // =====================================================================

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (pageW == 0 || base == null) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settleAnimation()
                downX = e.x
                downY = e.y
                dragging = false
                dragIgnored = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if (!dragging && !dragIgnored && abs(dx) > touchSlop && abs(dx) > abs(dy) * 0.7f) {
                    dragForward = dx < 0
                    if (turn == null && beginTurn(dragForward, cornerY(downY))) {
                        dragging = true
                        startFx = turn!!.fx
                        startFy = turn!!.fy
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else {
                        dragIgnored = true
                    }
                }
                if (dragging) updateDrag(e.x - downX, e.y - downY)
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(e)
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    val t = turn
                    if (t != null) {
                        val progress = (t.cx - t.fx) / (2f * pageW)
                        val turned = when {
                            vx < -flingVelocity -> true
                            vx > flingVelocity -> false
                            dragForward -> progress > 0.22f
                            else -> progress > 0.78f
                        }
                        animateTo(turned, arc = false)
                    }
                } else if (!dragIgnored && abs(e.x - downX) < touchSlop && abs(e.y - downY) < touchSlop) {
                    onTap(e.x)
                }
                dragging = false
                velocity?.recycle()
                velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) animateTo(turned = !dragForward, arc = false)
                dragging = false
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    private fun updateDrag(dx: Float, dy: Float) {
        val t = turn ?: return
        // In one-page mode the sheet must travel twice the screen width.
        val k = if (spread) 1f else if (dragForward) 1.5f else 2f
        var fx = startFx + dx * k
        var fy: Float
        val h = pageH.toFloat()
        when (t.cy) {
            h -> fy = (startFy + dy).coerceIn(h * 0.45f, h)
            0f -> fy = (startFy + dy).coerceIn(0f, h * 0.55f)
            else -> fy = (startFy + dy * 0.5f).coerceIn(t.cy - h * 0.15f, t.cy + h * 0.15f)
        }
        fx = fx.coerceIn(t.cx - 2 * pageW, t.cx)
        t.fx = fx
        t.fy = fy
        invalidate()
    }

    private fun onTap(x: Float) {
        val w = width.toFloat()
        when {
            x > w * 0.68f -> turnForward()
            x < w * 0.32f -> turnBackward()
            else -> host?.onCenterTap()
        }
    }

    // =====================================================================
    // Drawing
    // =====================================================================

    override fun onDraw(canvas: Canvas) {
        val h = host
        val b = base
        if (h == null || b == null || pageW == 0) {
            canvas.drawColor(0xFF202020.toInt())
            return
        }
        val t = turn
        if (t == null) {
            if (spread) {
                h.pageBitmap(b, Side.LEFT, Variant.NORMAL)?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
                h.pageBitmap(h.next(b), Side.RIGHT, Variant.NORMAL)?.let { canvas.drawBitmap(it, pageW.toFloat(), 0f, bitmapPaint) }
            } else {
                h.pageBitmap(b, Side.SINGLE, Variant.NORMAL)?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
            }
            return
        }
        drawTurn(canvas, h, t)
    }

    private fun drawTurn(canvas: Canvas, h: Host, t: Turn) {
        val ox = if (spread) pageW.toFloat() else 0f
        val sideR = if (spread) Side.RIGHT else Side.SINGLE
        if (spread) h.pageBitmap(t.staticLeft, Side.LEFT, Variant.NORMAL)?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        h.pageBitmap(t.under, sideR, Variant.NORMAL)?.let { canvas.drawBitmap(it, ox, 0f, bitmapPaint) }

        val front = h.pageBitmap(t.front, sideR, Variant.NORMAL) ?: return
        val back = if (spread) h.pageBitmap(t.back, Side.LEFT, Variant.MIRRORED) else h.pageBitmap(t.front, Side.SINGLE, Variant.SHOW_THROUGH)

        val W = pageW.toFloat()
        val H = pageH.toFloat()
        val dx = t.cx - t.fx
        val dy = t.cy - t.fy
        val dist = hypot(dx, dy)
        if (dist < 1f) {
            canvas.drawBitmap(front, ox, 0f, bitmapPaint)
            return
        }
        val nx = dx / dist
        val ny = dy / dist
        // Cylinder radius: largest mid-turn, shrinking to 0 when flat or fully turned.
        val rMax = W * 0.13f
        val r = max(0f, min(rMax, min(dist / PI.toFloat(), (2 * W - dist) / PI.toFloat())))
        val dC = (dist + PI.toFloat() * r) / 2f
        var ax = t.cx - nx * dC
        var ay = t.cy - ny * dC
        // The sheet is bound at the spine (x = 0): it can't curl past it.
        val spine = max((0 - ax) * nx + (0 - ay) * ny, (0 - ax) * nx + (H - ay) * ny)
        if (spine > 0) { ax += nx * spine; ay += ny * spine }

        // furthest curled distance of any corner
        var dMax = 0f
        for (px in floatArrayOf(0f, W)) for (py in floatArrayOf(0f, H)) dMax = max(dMax, (px - ax) * nx + (py - ay) * ny)

        val half = (PI / 2).toFloat() * r
        val extent = if (r < 0.5f) 0f else if (dMax >= half) r else r * sin(dMax / r)

        canvas.save()
        canvas.translate(ox, 0f)

        // Shadow cast by the lifted sheet on the page underneath.
        canvas.save()
        canvas.clipRect(0f, 0f, W, H)
        val progress = (dist / (2 * W)).coerceIn(0f, 1f)
        val sw = max(r * 1.3f, 18 * density) * (0.6f + 0.8f * sin(PI.toFloat() * progress))
        val lx = ax + nx * extent
        val ly = ay + ny * extent
        val alpha = (0x70 * sin(PI.toFloat() * progress).coerceAtLeast(0.25f)).toInt()
        shadowPaint.shader = LinearGradient(lx, ly, lx + nx * sw, ly + ny * sw, alpha shl 24, 0, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, W, H, shadowPaint)
        canvas.restore()

        buildMeshes(W, H, ax, ay, nx, ny, r)
        canvas.drawBitmapMesh(front, meshW, meshH, frontVerts, 0, frontColors, 0, meshPaint)
        if (back != null && dMax > half) {
            canvas.drawBitmapMesh(back, meshW, meshH, backVerts, 0, backColors, 0, meshPaint)
        }
        canvas.restore()
    }

    private fun gray(v: Float): Int {
        val c = (v.coerceIn(0f, 1f) * 255).toInt()
        return (0xFF shl 24) or (c shl 16) or (c shl 8) or c
    }

    private fun buildMeshes(W: Float, H: Float, ax: Float, ay: Float, nx: Float, ny: Float, r: Float) {
        val half = (PI / 2).toFloat() * r
        val full = PI.toFloat() * r
        var i = 0
        for (row in 0..meshH) {
            val y = H * row / meshH
            for (col in 0..meshW) {
                val x = W * col / meshW
                val d = (x - ax) * nx + (y - ay) * ny
                val px = x - nx * d // projection onto the axis
                val py = y - ny * d
                val v = i * 2
                // ---- front side ----
                if (d <= 0f) {
                    frontVerts[v] = x; frontVerts[v + 1] = y; frontColors[i] = gray(1f)
                } else if (r < 0.5f) {
                    frontVerts[v] = px; frontVerts[v + 1] = py; frontColors[i] = gray(1f)
                } else {
                    val th = min(d, half) / r
                    val s = r * sin(th)
                    frontVerts[v] = px + nx * s; frontVerts[v + 1] = py + ny * s
                    frontColors[i] = gray(0.62f + 0.38f * cos(th))
                }
                // ---- back side ----
                if (r < 0.5f) {
                    if (d <= 0f) { backVerts[v] = px; backVerts[v + 1] = py }
                    else { backVerts[v] = px - nx * d; backVerts[v + 1] = py - ny * d }
                    backColors[i] = gray(0.96f)
                } else {
                    val dd = max(d, half)
                    if (dd < full) {
                        val th = dd / r
                        val s = r * sin(th)
                        backVerts[v] = px + nx * s; backVerts[v + 1] = py + ny * s
                        backColors[i] = gray(0.72f + 0.26f * -cos(th))
                    } else {
                        val k = dd - full
                        backVerts[v] = px - nx * k; backVerts[v + 1] = py - ny * k
                        backColors[i] = gray(0.98f)
                    }
                }
                i++
            }
        }
    }
}
