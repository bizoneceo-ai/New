package com.bizoneceo.nardy.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizoneceo.nardy.engine.GameState
import com.bizoneceo.nardy.engine.Phase
import com.bizoneceo.nardy.engine.Variant
import com.bizoneceo.nardy.ui.board.BoardGeometry
import com.bizoneceo.nardy.ui.board.BoardTheme
import com.bizoneceo.nardy.ui.board.BoardThemes
import com.bizoneceo.nardy.ui.board.drawBoard
import com.bizoneceo.nardy.ui.board.themeById

private val TextMain = Color(0xFFF2E6CF)
private val TextDim = Color(0xFFA89C88)

@Composable
fun MenuScreen(
    settings: Settings,
    saved: GameState?,
    onSettings: (Settings) -> Unit,
    onContinue: () -> Unit,
    onNewGame: (Variant) -> Unit,
) {
    val theme = themeById(settings.themeId)
    var showRules by remember { mutableStateOf(false) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(theme.frameDark, theme.background), radius = 1800f)),
    ) {
        val wide = maxWidth > 640.dp
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "НАРДЫ",
                color = theme.accent, fontSize = 52.sp, fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold, letterSpacing = 10.sp,
            )
            Text("игра вдвоём на одном устройстве", color = TextDim, fontSize = 15.sp)
            Spacer(Modifier.height(18.dp))

            if (wide) {
                Row(Modifier.widthIn(max = 1100.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) { PlayColumn(theme, saved, onContinue, onNewGame) }
                    Column(Modifier.weight(1f)) { SettingsColumn(settings, onSettings) { showRules = true } }
                }
            } else {
                Column(Modifier.widthIn(max = 560.dp)) {
                    PlayColumn(theme, saved, onContinue, onNewGame)
                    Spacer(Modifier.height(18.dp))
                    SettingsColumn(settings, onSettings) { showRules = true }
                }
            }
        }
        if (showRules) RulesDialog(theme) { showRules = false }
    }
}

@Composable
private fun PlayColumn(theme: BoardTheme, saved: GameState?, onContinue: () -> Unit, onNewGame: (Variant) -> Unit) {
    BoardPreview(theme, Modifier.fillMaxWidth().aspectRatio(1.45f))
    Spacer(Modifier.height(16.dp))
    val resumable = saved != null && saved.phase != Phase.MATCH_OVER
    if (resumable && saved != null) {
        BigButton(
            "Продолжить партию",
            "${variantName(saved.variant)} · счёт ${saved.score(com.bizoneceo.nardy.engine.Player.WHITE)}:" +
                "${saved.score(com.bizoneceo.nardy.engine.Player.BLACK)}",
            theme, primary = true, onClick = onContinue,
        )
        Spacer(Modifier.height(10.dp))
    }
    BigButton("Длинные нарды", "без боя, все с «головы», в одну сторону", theme, primary = !resumable) {
        onNewGame(Variant.LONG)
    }
    Spacer(Modifier.height(10.dp))
    BigButton("Короткие нарды", "бой фишек, бар, марс и кокс", theme, primary = false) {
        onNewGame(Variant.SHORT)
    }
}

@Composable
private fun BigButton(title: String, subtitle: String, theme: BoardTheme, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (primary) theme.accent else Color(0x33000000))
            .border(1.5.dp, theme.accent.copy(alpha = if (primary) 1f else 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(
            title, fontSize = 21.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif,
            color = if (primary) Color(0xFF1B1410) else TextMain,
        )
        Text(subtitle, fontSize = 13.sp, color = if (primary) Color(0xFF3A2A1C) else TextDim)
    }
}

@Composable
private fun SettingsColumn(settings: Settings, onSettings: (Settings) -> Unit, onRules: () -> Unit) {
    val theme = themeById(settings.themeId)
    SectionTitle("Матч до", theme)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (n in listOf(0, 1, 3, 5, 7)) {
            Chip(if (n == 0) "∞" else n.toString(), settings.matchLength == n, theme) {
                onSettings(settings.copy(matchLength = n))
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    SectionTitle("Доска", theme)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (t in BoardThemes) {
            val selected = t.id == settings.themeId
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.dp, if (selected) t.accent else Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                    .clickable { onSettings(settings.copy(themeId = t.id)) }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BoardPreview(t, Modifier.fillMaxWidth().aspectRatio(1.45f))
                Text(t.title, color = if (selected) t.accent else TextDim, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    ToggleRow(
        "Лицом к лицу",
        "Панель чёрных повёрнута к игроку напротив — кладите раскрытый Fold на стол между собой",
        settings.faceToFace, theme,
    ) { onSettings(settings.copy(faceToFace = it)) }
    ToggleRow("Звук", "Стук кубиков и фишек", settings.sound, theme) { onSettings(settings.copy(sound = it)) }
    ToggleRow("Вибрация", "Отдача при ударах кубиков", settings.vibration, theme) {
        onSettings(settings.copy(vibration = it))
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onRules) { Text("Правила длинных и коротких нард", color = theme.accent, fontSize = 15.sp) }
}

@Composable
private fun SectionTitle(text: String, theme: BoardTheme) {
    Text(
        text.uppercase(), color = theme.accent.copy(alpha = 0.8f), fontSize = 13.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun Chip(text: String, selected: Boolean, theme: BoardTheme, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) theme.accent else Color(0x33000000))
            .border(1.dp, theme.accent.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (selected) Color(0xFF1B1410) else TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, value: Boolean, theme: BoardTheme, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!value) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 16.sp)
            Text(subtitle, color = TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = theme.accent, checkedThumbColor = Color(0xFF1B1410)),
        )
    }
}

/** Miniature of the real board art, initial long-nardy set-up omitted for clarity. */
@Composable
fun BoardPreview(theme: BoardTheme, modifier: Modifier) {
    val g = remember { BoardGeometry(Variant.LONG) }
    Canvas(modifier) {
        val s = minOf(size.width / g.width, size.height / g.height)
        translate((size.width - g.width * s) / 2, (size.height - g.height * s) / 2) {
            scale(s, s, Offset.Zero) { drawBoard(g, theme) }
        }
    }
}

@Composable
private fun RulesDialog(theme: BoardTheme, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(20.dp)
                .widthIn(max = 640.dp)
                .background(Color(0xFF1C140E), RoundedCornerShape(20.dp))
                .border(1.dp, theme.accent, RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
        ) {
            Text("Длинные нарды", color = theme.accent, fontSize = 22.sp, fontFamily = FontFamily.Serif)
            Text(LONG_RULES, color = TextMain, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(14.dp))
            Text("Короткие нарды", color = theme.accent, fontSize = 22.sp, fontFamily = FontFamily.Serif)
            Text(SHORT_RULES, color = TextMain, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(14.dp))
            Text("Управление", color = theme.accent, fontSize = 22.sp, fontFamily = FontFamily.Serif)
            Text(CONTROLS, color = TextMain, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = theme.accent, contentColor = Color(0xFF1B1410)),
                modifier = Modifier.align(Alignment.End),
            ) { Text("Понятно") }
        }
    }
}

private const val LONG_RULES = """
• У каждого 15 шашек, все стоят на «голове» — угловом пункте своей стороны. Оба игрока ходят против часовой стрелки.
• Бить нельзя: на пункт, занятый хоть одной шашкой соперника, ставить нельзя.
• С головы за ход снимается только одна шашка. Исключение — первый бросок 6-6, 4-4 или 3-3: можно снять две.
• Нельзя строить «забор» из шести шашек подряд, если впереди него нет ни одной шашки соперника.
• Дубль играется четыре раза. Если можно сыграть оба кубика — нужно сыграть оба; если только один — больший.
• Когда все 15 шашек в доме, их выбрасывают. Кто первым снял всех — победил. Если соперник не снял ни одной — это марс (2 очка).
"""

private const val SHORT_RULES = """
• Классическая расстановка: 2 · 5 · 3 · 5. Игроки движутся навстречу друг другу.
• Одиночную шашку соперника («блот») можно побить — она уходит на бар и должна войти в ваш дом, прежде чем соперник сделает любой другой ход.
• Пункт с двумя и более шашками соперника закрыт.
• Выбрасывать шашки можно, только когда все в доме.
• Марс (2 очка) — соперник не снял ни одной шашки. Кокс (3 очка) — к тому же у него шашка на баре или в вашем доме.
"""

private const val CONTROLS = """
• Кубики: прижмите палец к доске — кубики окажутся в руке; встряхните и смахните, чтобы бросить. Чем резче движение, тем сильнее бросок.
• Шашки: коснитесь шашки, чтобы увидеть, куда она может пойти, затем коснитесь подсвеченного пункта. Можно просто перетащить шашку пальцем.
• Ход на сумму кубиков делается одним касанием — промежуточный пункт проверяется автоматически.
• ↶ отменяет ход до нажатия «Готово».
• На Galaxy Fold в полураскрытом виде (Flex-режим) доска встаёт на верхнюю половину, а нижняя превращается в лоток для кубиков.
"""
