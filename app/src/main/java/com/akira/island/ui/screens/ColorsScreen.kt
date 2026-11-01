@file:OptIn(ExperimentalLayoutApi::class)

package com.akira.island.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BorderStyle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LineWeight
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akira.island.core.tr
import com.akira.island.data.SettingsRepo
import com.akira.island.ui.Group
import com.akira.island.ui.GroupSlider
import com.akira.island.ui.GroupToggle
import com.akira.island.ui.Page
import com.akira.island.ui.Pos
import com.akira.island.ui.SectionHeader
import com.akira.island.ui.Tile
import com.akira.island.ui.Ui

private val capsuleColors = listOf(
    0xFF000000, 0xFF111114, 0xFF1C1C22, 0xFF26233A, 0xFF1A2238, 0xFF102A2A,
    0xFF2B1730, 0xFF3A1A1A, 0xFF2E2A1A, 0xFF3D3D45, 0xFFF2F2F7, 0xFFC6C3F7,
)

private val outlineColors = listOf(
    0xFF3A3A40, 0xFF8E8E93, 0xFFFFFFFF, 0xFFC6C3F7, 0xFF0A84FF, 0xFF64D2FF,
    0xFF34C759, 0xFFFFD60A, 0xFFFF9F0A, 0xFFFF453A, 0xFFFF2D92, 0xFFBF5AF2,
)

@Composable
fun ColorsScreen(onBack: () -> Unit) {
    val s by SettingsRepo.state.collectAsState()
    Page(tr("Цвет капсулы", "Capsule color"), onBack) {
        IslandPreview()

        SectionHeader(tr("Цвет", "Color"))
        Swatches(capsuleColors, s.color) { c -> SettingsRepo.update { copy(color = c) } }

        SectionHeader(tr("Контур", "Outline"))
        Group {
            GroupToggle(Pos.First, Icons.Rounded.BorderStyle, tr("Контур", "Outline"),
                tr("Тонкая обводка вокруг капсулы", "A thin line around the capsule"),
                s.outline, color = Tile.Gray) { v ->
                SettingsRepo.update { copy(outline = v) }
            }
            GroupToggle(Pos.Middle, Icons.Rounded.Image, tr("Цвет из обложки", "Color from artwork"),
                tr("Пока играет музыка, контур окрашивается в цвет обложки", "While music plays, the outline takes the artwork's color"),
                s.outlineFromArt, color = Tile.Pink) { v -> SettingsRepo.update { copy(outlineFromArt = v, outline = outline || v) } }
            GroupSlider(Pos.Last, Icons.Rounded.LineWeight, tr("Толщина", "Thickness"), s.outlineWidth, 0.5f..4f,
                "%.1f dp".format(s.outlineWidth), color = Tile.Gray) { v ->
                SettingsRepo.update { copy(outlineWidth = (v * 2).toInt() / 2f) }
            }
        }
        Swatches(outlineColors, s.outlineColor) { c -> SettingsRepo.update { copy(outlineColor = c, outline = true) } }

        SectionHeader(tr("Автоматическая обводка", "Automatic outline"))
        Group {
            GroupToggle(Pos.First, Icons.Rounded.Screenshot, tr("По цвету на экране", "By screen color"),
                tr("Считывает фон вокруг острова: тёмный — серая обводка, светлый — без обводки",
                    "Samples the background around the island: dark adds a gray line, light leaves it off"),
                s.autoOutlineOnScreen, color = Tile.Teal) { v ->
                SettingsRepo.update { copy(autoOutlineOnScreen = v, autoOutlineOnDark = if (v) false else autoOutlineOnDark) }
            }
            GroupToggle(Pos.Last, Icons.Rounded.DarkMode, tr("По тёмным обоям", "By dark wallpaper"),
                tr("Резервный вариант, если чтение цвета экрана выключено",
                    "Fallback when screen color sampling is off"),
                s.autoOutlineOnDark, color = Tile.Gray) { v ->
                SettingsRepo.update { copy(autoOutlineOnDark = v, autoOutlineOnScreen = if (v) false else autoOutlineOnScreen) }
            }
        }
        Text(
            tr("Цвет считывается только вокруг острова; снимок не сохраняется и никуда не отправляется.",
                "Only the area around the island is sampled; the screenshot is not saved or sent anywhere."),
            color = Ui.Sub, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 8.dp),
        )

        SectionHeader(tr("Тень", "Shadow"))
        GroupToggle(Pos.Single, Icons.Rounded.Layers, tr("Тень", "Shadow"),
            tr("Мягкая тень под капсулой — заметна на светлых экранах", "A soft shadow under the capsule — visible on light screens"),
            s.shadow, color = Tile.Indigo) { v ->
            SettingsRepo.update { copy(shadow = v) }
        }

        Text(
            tr("Цвет кнопок «Ответить» и «Пометить прочитанным» — в «Функции» → «Уведомления».",
                "The color of the Reply and Mark as read buttons is in Features → Notifications."),
            color = Ui.Sub, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/** Кружки цветов в карточке: выбранный — с галочкой. */
@Composable
fun Swatches(colors: List<Long>, selected: Long, onPick: (Long) -> Unit) {
    FlowRow(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Ui.Card)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        colors.forEach { c ->
            val color = Color(c)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(2.dp, if (c == selected) Ui.Primary else Ui.Divider, CircleShape)
                    .clickable { onPick(c) },
                contentAlignment = Alignment.Center,
            ) {
                if (c == selected) {
                    Icon(Icons.Rounded.Check, null, tint = if (color.luminance() > 0.5f) Color.Black else Color.White)
                }
            }
        }
    }
}
