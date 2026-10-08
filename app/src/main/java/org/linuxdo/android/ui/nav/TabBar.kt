package org.linuxdo.android.ui.nav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.linuxdo.android.ui.design.IosFullDivider
import org.linuxdo.android.ui.design.IosTheme

enum class MainTab(val label: String, val root: Route) {
    Latest("最新", Route.TopicList), Categories("分类", Route.CategoryList),
    Search("搜索", Route.Search), Profile("我的", Route.Profile),
}

@Composable
fun TabBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(IosTheme.colors.card)) {
        IosFullDivider()
        Row(Modifier.height(49.dp)) {
            MainTab.entries.forEach { tab ->
                val color = if (tab == selected) IosTheme.colors.accent else IosTheme.colors.secondaryLabel
                Column(Modifier.weight(1f).fillMaxHeight().selectable(tab == selected,
                    role = Role.Tab, onClick = { onSelect(tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Canvas(Modifier.size(23.dp)) {
                        val stroke = 1.7.dp.toPx()
                        when (tab) {
                            MainTab.Latest -> repeat(3) { index ->
                                val y = size.height * (0.22f + index * 0.28f)
                                drawLine(color, Offset(size.width * 0.1f, y), Offset(size.width * 0.9f, y), stroke, StrokeCap.Round)
                            }
                            MainTab.Categories -> repeat(4) { index ->
                                drawRect(color, Offset(size.width * (0.08f + index % 2 * 0.52f), size.height * (0.08f + index / 2 * 0.52f)),
                                    Size(size.width * 0.32f, size.height * 0.32f), style = Stroke(stroke))
                            }
                            MainTab.Search -> {
                                drawCircle(color, size.width * 0.29f, Offset(size.width * 0.4f, size.height * 0.4f), style = Stroke(stroke))
                                drawLine(color, Offset(size.width * 0.62f, size.height * 0.62f), Offset(size.width * 0.9f, size.height * 0.9f), stroke, StrokeCap.Round)
                            }
                            MainTab.Profile -> {
                                drawCircle(color, size.width * 0.2f, Offset(size.width / 2, size.height * 0.26f), style = Stroke(stroke))
                                drawArc(color, 180f, 180f, false, Offset(size.width * 0.12f, size.height * 0.56f),
                                    Size(size.width * 0.76f, size.height * 0.7f), style = Stroke(stroke))
                            }
                        }
                    }
                    Text(tab.label, color = color, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        }
    }
}
