package com.bizoneceo.nardy.ui.board

import androidx.compose.ui.graphics.Color
import com.bizoneceo.nardy.ui.dice.DieColors
import com.bizoneceo.nardy.ui.dice.EbonyDie
import com.bizoneceo.nardy.ui.dice.IvoryDie
import com.bizoneceo.nardy.ui.dice.RubyDie

data class CheckerColors(val light: Color, val base: Color, val dark: Color, val rim: Color, val engrave: Color)

data class BoardTheme(
    val id: String,
    val title: String,
    val frameLight: Color,
    val frameDark: Color,
    val frameGrain: Color,
    val fieldLight: Color,
    val fieldDark: Color,
    val fieldGrain: Color,
    /** Field is leather/felt (no wood grain, fine noise instead). */
    val fieldIsCloth: Boolean,
    val pointA: Color,
    val pointB: Color,
    val pointEdge: Color,
    val inlay: Color,
    val tray: Color,
    val accent: Color,
    val white: CheckerColors,
    val black: CheckerColors,
    val dice: DieColors,
    val background: Color,
)

private val IvoryChecker = CheckerColors(
    light = Color(0xFFFFFBF1), base = Color(0xFFEDE2CA), dark = Color(0xFFB5A27E),
    rim = Color(0xFF8E7A58), engrave = Color(0xFFC9B48C),
)
private val EbonyChecker = CheckerColors(
    light = Color(0xFF6A5A50), base = Color(0xFF2E2622), dark = Color(0xFF0C0908),
    rim = Color(0xFF050404), engrave = Color(0xFF55463D),
)
private val GarnetChecker = CheckerColors(
    light = Color(0xFFE0545E), base = Color(0xFF9C1C28), dark = Color(0xFF4E070E),
    rim = Color(0xFF2E0307), engrave = Color(0xFFC23A45),
)

val BoardThemes: List<BoardTheme> = listOf(
    BoardTheme(
        id = "walnut",
        title = "Орех",
        frameLight = Color(0xFF7A4A2A), frameDark = Color(0xFF3E2213), frameGrain = Color(0xFF26140A),
        fieldLight = Color(0xFFE2BF8A), fieldDark = Color(0xFFC59A62), fieldGrain = Color(0xFF8C6236),
        fieldIsCloth = false,
        pointA = Color(0xFF5B2E17), pointB = Color(0xFFF3E2BE), pointEdge = Color(0xFF3A1D0E),
        inlay = Color(0xFFB8893F), tray = Color(0xFF2B170C), accent = Color(0xFFE0B25C),
        white = IvoryChecker, black = EbonyChecker, dice = IvoryDie,
        background = Color(0xFF140D09),
    ),
    BoardTheme(
        id = "ebony",
        title = "Эбен и золото",
        frameLight = Color(0xFF2C2522), frameDark = Color(0xFF0E0B0A), frameGrain = Color(0xFF000000),
        fieldLight = Color(0xFF5A1622), fieldDark = Color(0xFF34090F), fieldGrain = Color(0xFF1E0408),
        fieldIsCloth = true,
        pointA = Color(0xFF14100E), pointB = Color(0xFFE9D9B4), pointEdge = Color(0xFFC9A04E),
        inlay = Color(0xFFD9B060), tray = Color(0xFF080606), accent = Color(0xFFE8C26A),
        white = IvoryChecker, black = EbonyChecker, dice = RubyDie,
        background = Color(0xFF0A0707),
    ),
    BoardTheme(
        id = "malachite",
        title = "Малахит",
        frameLight = Color(0xFF6B3B1F), frameDark = Color(0xFF2E160A), frameGrain = Color(0xFF1C0D05),
        fieldLight = Color(0xFF1F6B4E), fieldDark = Color(0xFF0B3A29), fieldGrain = Color(0xFF052518),
        fieldIsCloth = true,
        pointA = Color(0xFF7E1420), pointB = Color(0xFFF0E4C8), pointEdge = Color(0xFFC7A053),
        inlay = Color(0xFFCFAA5E), tray = Color(0xFF1B0C05), accent = Color(0xFFE6C473),
        white = IvoryChecker, black = GarnetChecker, dice = EbonyDie,
        background = Color(0xFF07130E),
    ),
)

fun themeById(id: String?): BoardTheme = BoardThemes.firstOrNull { it.id == id } ?: BoardThemes[0]
