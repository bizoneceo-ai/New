package com.bizoneceo.nardy.ui.screen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.bizoneceo.nardy.engine.Player
import com.bizoneceo.nardy.engine.Variant
import com.bizoneceo.nardy.ui.board.BoardGeometry
import com.bizoneceo.nardy.ui.board.BoardTransform

/**
 * Physical fold / hinge of the device, in window pixels. Platform code
 * fills it in from Jetpack WindowManager; null means a plain flat screen.
 */
data class FoldInfo(
    val bounds: Rect,
    /** The hinge runs top-to-bottom (book-style fold). */
    val vertical: Boolean,
    /** Content cannot be shown across it (dual-screen gap, or a half-open fold). */
    val separating: Boolean,
    /** Half-opened like a laptop with the hinge horizontal — Flex mode / tabletop. */
    val tabletop: Boolean,
)

enum class PanelSide { TOP, BOTTOM, LEFT, RIGHT }

data class PanelSpec(val rect: Rect, val side: PanelSide, val upsideDown: Boolean)

data class GameLayout(
    val geometry: BoardGeometry,
    val transform: BoardTransform,
    val boardRect: Rect,
    val diceArena: Rect,
    val docks: Map<Player, List<Offset>>,
    val dieSize: Float,
    val dockSize: Float,
    val panels: Map<Player, PanelSpec>,
    /** Separate dice tray below the board (tabletop / stacked dual screen). */
    val trayRect: Rect?,
    val mode: Mode,
) {
    enum class Mode { FLAT, ROTATED, TABLETOP, SPANNED }
}

/**
 * Picks the best arrangement for the current window:
 *  - **TABLETOP**: half-folded Fold (Flex mode) or a top/bottom dual screen:
 *    the board sits on the upper half, the lower half becomes a dice tray
 *    with both players' panels — like rolling dice on a table.
 *  - **SPANNED**: a separating vertical hinge (Surface Duo style): the bar
 *    of the board is stretched over the hinge so no point is ever cut.
 *  - **FLAT**: landscape-ish screens (Fold 7 inner screen, tablets).
 *  - **ROTATED**: tall screens (cover screen) — the board is turned 90°.
 *
 * [content] is the window minus system bars / cutouts. [minPanel] is the
 * smallest useful panel thickness in px.
 */
fun computeGameLayout(
    variant: Variant,
    content: Rect,
    fold: FoldInfo?,
    minPanel: Float,
    faceToFace: Boolean,
): GameLayout {
    if (fold != null && !fold.vertical && (fold.tabletop || fold.separating) &&
        fold.bounds.top > content.top + content.height * 0.25f &&
        fold.bounds.bottom < content.bottom - content.height * 0.15f
    ) {
        return tabletopLayout(variant, content, fold, minPanel)
    }
    if (fold != null && fold.vertical && fold.separating) {
        spannedLayout(variant, content, fold, minPanel, faceToFace)?.let { return it }
    }
    return flatLayout(variant, content, minPanel, faceToFace)
}

private fun flatLayout(variant: Variant, content: Rect, minPanel: Float, faceToFace: Boolean): GameLayout {
    val g = BoardGeometry(variant)
    val w = content.width
    val h = content.height
    // Horizontal board with panels above and below, or with panels at the sides.
    val sTopBottom = minOf(w / g.width, (h - 2 * minPanel) / g.height)
    val sSides = minOf((w - 2 * minPanel * 1.6f) / g.width, h / g.height)
    // Rotated board (portrait), panels above and below.
    val sRotated = minOf(w / g.height, (h - 2 * minPanel) / g.width)

    val best = maxOf(sTopBottom, sSides, sRotated)
    return when {
        best == sTopBottom || (sTopBottom >= best * 0.97f) -> {
            val s = sTopBottom
            val bw = g.width * s
            val bh = g.height * s
            val left = content.left + (w - bw) / 2
            val top = content.top + (h - bh) / 2
            val board = Rect(left, top, left + bw, top + bh)
            build(
                g, BoardTransform(s, false, board.topLeft), board,
                panels = mapOf(
                    Player.BLACK to PanelSpec(Rect(content.left, content.top, content.right, board.top), PanelSide.TOP, faceToFace),
                    Player.WHITE to PanelSpec(Rect(content.left, board.bottom, content.right, content.bottom), PanelSide.BOTTOM, false),
                ),
                mode = GameLayout.Mode.FLAT,
            )
        }
        best == sSides -> {
            val s = sSides
            val bw = g.width * s
            val bh = g.height * s
            val left = content.left + (w - bw) / 2
            val top = content.top + (h - bh) / 2
            val board = Rect(left, top, left + bw, top + bh)
            build(
                g, BoardTransform(s, false, board.topLeft), board,
                panels = mapOf(
                    Player.BLACK to PanelSpec(Rect(content.left, content.top, board.left, content.bottom), PanelSide.LEFT, false),
                    Player.WHITE to PanelSpec(Rect(board.right, content.top, content.right, content.bottom), PanelSide.RIGHT, false),
                ),
                mode = GameLayout.Mode.FLAT,
            )
        }
        else -> {
            val s = sRotated
            val bw = g.height * s
            val bh = g.width * s
            val left = content.left + (w - bw) / 2
            val top = content.top + (h - bh) / 2
            val board = Rect(left, top, left + bw, top + bh)
            build(
                g, BoardTransform(s, true, Offset(board.right, board.top)), board,
                panels = mapOf(
                    Player.BLACK to PanelSpec(Rect(content.left, content.top, content.right, board.top), PanelSide.TOP, faceToFace),
                    Player.WHITE to PanelSpec(Rect(content.left, board.bottom, content.right, content.bottom), PanelSide.BOTTOM, false),
                ),
                mode = GameLayout.Mode.ROTATED,
            )
        }
    }
}

private fun spannedLayout(
    variant: Variant,
    content: Rect,
    fold: FoldInfo,
    minPanel: Float,
    faceToFace: Boolean,
): GameLayout? {
    val hingeX = fold.bounds.center.x
    val halfAvail = minOf(hingeX - content.left, content.right - hingeX)
    if (halfAvail <= 0f) return null
    val hingeW = fold.bounds.width
    // The bar width depends on the scale and vice versa: two passes converge.
    var bar = 1f
    var s = 1f
    repeat(3) {
        val g = BoardGeometry(variant, bar)
        s = minOf(2 * halfAvail / g.width, (content.height - 2 * minPanel) / g.height)
        bar = maxOf(1f, hingeW / s + 0.3f)
    }
    val g = BoardGeometry(variant, bar)
    s = minOf(2 * halfAvail / g.width, (content.height - 2 * minPanel) / g.height)
    val bw = g.width * s
    val bh = g.height * s
    val left = hingeX - bw / 2
    val top = content.top + (content.height - bh) / 2
    val board = Rect(left, top, left + bw, top + bh)
    return build(
        g, BoardTransform(s, false, board.topLeft), board,
        panels = mapOf(
            Player.BLACK to PanelSpec(Rect(content.left, content.top, content.right, board.top), PanelSide.TOP, faceToFace),
            Player.WHITE to PanelSpec(Rect(content.left, board.bottom, content.right, content.bottom), PanelSide.BOTTOM, false),
        ),
        mode = GameLayout.Mode.SPANNED,
    )
}

private fun tabletopLayout(variant: Variant, content: Rect, fold: FoldInfo, minPanel: Float): GameLayout {
    val g = BoardGeometry(variant)
    val upper = Rect(content.left, content.top, content.right, fold.bounds.top)
    val lower = Rect(content.left, fold.bounds.bottom, content.right, content.bottom)
    val pad = minPanel * 0.15f
    val s = minOf((upper.width - 2 * pad) / g.width, (upper.height - 2 * pad) / g.height)
    val bw = g.width * s
    val bh = g.height * s
    val left = upper.left + (upper.width - bw) / 2
    val top = upper.top + (upper.height - bh) / 2
    val board = Rect(left, top, left + bw, top + bh)

    val panelW = minOf(lower.width * 0.27f, minPanel * 3.6f)
    val tray = Rect(lower.left + panelW, lower.top + pad, lower.right - panelW, lower.bottom - pad)
    val dieSize = minOf(tray.height * 0.2f, s * 1.5f)
    val dockSize = dieSize * 0.8f
    val docks = Player.entries.associateWith {
        val c = Offset(tray.center.x, tray.bottom - dockSize * 1.1f)
        listOf(c - Offset(dockSize * 0.75f, 0f), c + Offset(dockSize * 0.75f, 0f))
    }
    return GameLayout(
        geometry = g,
        transform = BoardTransform(s, false, board.topLeft),
        boardRect = board,
        diceArena = tray,
        docks = docks,
        dieSize = dieSize,
        dockSize = dockSize,
        panels = mapOf(
            Player.BLACK to PanelSpec(Rect(lower.left, lower.top, tray.left, lower.bottom), PanelSide.LEFT, false),
            Player.WHITE to PanelSpec(Rect(tray.right, lower.top, lower.right, lower.bottom), PanelSide.RIGHT, false),
        ),
        trayRect = tray,
        mode = GameLayout.Mode.TABLETOP,
    )
}

private fun build(
    g: BoardGeometry,
    t: BoardTransform,
    board: Rect,
    panels: Map<Player, PanelSpec>,
    mode: GameLayout.Mode,
): GameLayout {
    val dock = t.scale * 0.95f
    return GameLayout(
        geometry = g,
        transform = t,
        boardRect = board,
        diceArena = t.rectToScreen(g.field),
        docks = Player.entries.associateWith { p -> List(2) { t.toScreen(g.diceDock(p, it)) } },
        dieSize = t.scale * 1.25f,
        dockSize = dock,
        panels = panels,
        trayRect = null,
        mode = mode,
    )
}
