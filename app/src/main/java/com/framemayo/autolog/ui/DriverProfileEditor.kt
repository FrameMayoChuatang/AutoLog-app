package com.framemayo.autolog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framemayo.autolog.data.local.DriverProfile

@Composable
internal fun DriverProfileEditor(
    profile: DriverProfile?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String, Int, Int) -> Unit,
) {
    var name by rememberSaveable(profile?.driver?.id) { mutableStateOf(profile?.displayName.orEmpty()) }
    var color by rememberSaveable(profile?.driver?.id) { mutableIntStateOf(profile?.color ?: 0) }
    var expression by rememberSaveable(profile?.driver?.id) { mutableIntStateOf(profile?.expression ?: 0) }
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
    ) {
        MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF141416))) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                val stacked = maxWidth < 640.dp
                val dialogHeight = minOf(maxHeight, if (stacked) 640.dp else 720.dp)
                Surface(Modifier.widthIn(max = 1200.dp).fillMaxWidth().height(dialogHeight), shape = RoundedCornerShape(24.dp), color = Color.White) {
                    Box {
                        val editor: @Composable (Modifier) -> Unit = { modifier ->
                            Column(modifier.verticalScroll(rememberScrollState()).padding(28.dp)) {
                                Text(if (profile == null) "프로필 생성" else "프로필 수정", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(28.dp))
                                Text("이름", color = Color(0xFF626269), fontSize = 16.sp)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = name, onValueChange = { name = it.take(40) }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "운전자 이름" },
                                    placeholder = { Text("이름을 입력하세요") }, singleLine = true,
                                    textStyle = TextStyle(fontSize = 20.sp), shape = RoundedCornerShape(12.dp),
                                )
                                Spacer(Modifier.height(24.dp))
                                Text("색상", color = Color(0xFF626269), fontSize = 16.sp)
                                Spacer(Modifier.height(12.dp))
                                ProfileColorPicker(color, enabled = !busy, onColor = { color = it })
                                Spacer(Modifier.height(24.dp))
                                Text("표정", color = Color(0xFF626269), fontSize = 16.sp)
                                Spacer(Modifier.height(12.dp))
                                ProfileExpressionPicker(expression, enabled = !busy, onExpression = { expression = it })
                                Spacer(Modifier.height(24.dp))
                                Text("캐릭터", color = Color(0xFF626269), fontSize = 16.sp)
                                Spacer(Modifier.height(12.dp))
                                ProfilePresetPicker(color, expression, enabled = !busy) { preset ->
                                    expression = preset
                                    color = ProfileExpressions[preset].presetColor
                                }
                                error?.let { Text(it, color = Color(0xFFA52C2C), fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp)) }
                            }
                        }
                        val preview: @Composable (Modifier) -> Unit = { modifier ->
                            Column(
                                modifier.padding(horizontal = 24.dp, vertical = if (stacked) 12.dp else 28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                if (!stacked) Spacer(Modifier.height(24.dp))
                                Text(name.trim().ifEmpty { "나의 프로필" }, fontSize = if (stacked) 26.sp else 34.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
                                DriverAvatar(color, Modifier.widthIn(max = if (stacked) 160.dp else 400.dp).fillMaxWidth().aspectRatio(4f / 3f), blinking = true, expression = expression)
                                Button(
                                    onClick = { onSave(name.trim(), color, expression) }, enabled = name.isNotBlank() && !busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF141416), contentColor = Color.White),
                                ) { Text(if (busy) "저장 중…" else "저장", fontSize = 18.sp) }
                            }
                        }
                        if (stacked) Column {
                            editor(Modifier.weight(1f).fillMaxWidth())
                            HorizontalDivider(color = Color(0xFFEFEFF1))
                            preview(Modifier.fillMaxWidth().height(270.dp))
                        } else Row {
                            editor(Modifier.weight(1.15f).fillMaxHeight())
                            VerticalDivider(color = Color(0xFFEFEFF1))
                            preview(Modifier.weight(1f).fillMaxHeight())
                        }
                        IconButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.align(Alignment.TopEnd).size(56.dp).semantics { contentDescription = "닫기" }) {
                            Text("×", fontSize = 30.sp, color = Color(0xFF626269))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileColorPicker(color: Int, enabled: Boolean, onColor: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProfileColors.forEachIndexed { index, swatch ->
            Surface(
                onClick = { onColor(index) }, enabled = enabled,
                modifier = Modifier.size(52.dp).semantics { contentDescription = ProfileColorNames[index]; selected = color == index },
                shape = CircleShape, color = Color.White,
                border = BorderStroke(2.dp, if (color == index) Color(0xFF242429) else Color.Transparent),
            ) {
                Box(Modifier.padding(4.dp).background(swatch, CircleShape))
            }
        }
    }
}

@Composable
private fun ProfileExpressionPicker(expression: Int, enabled: Boolean, onExpression: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProfileExpressions.forEachIndexed { index, face ->
            Surface(
                onClick = { onExpression(index) }, enabled = enabled,
                modifier = Modifier.size(80.dp).semantics { contentDescription = "${face.name} 표정"; selected = expression == index },
                shape = RoundedCornerShape(14.dp), color = ProfileColors[face.presetColor].copy(alpha = 0.18f),
                border = BorderStroke(2.dp, if (expression == index) Color(0xFF252528) else Color(0xFFE8E8EC)),
            ) {
                Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    DriverAvatar(face.presetColor, Modifier.fillMaxWidth().weight(1f), expression = index, faceOnly = true)
                    Text(face.name, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun ProfilePresetPicker(color: Int, expression: Int, enabled: Boolean, onPreset: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProfileExpressions.forEachIndexed { index, face ->
            val matches = color == face.presetColor && expression == index
            Surface(
                onClick = { onPreset(index) }, enabled = enabled,
                modifier = Modifier.size(90.dp).semantics { contentDescription = "${face.name} 구름 캐릭터"; selected = matches },
                shape = RoundedCornerShape(18.dp), color = Color.White,
                border = BorderStroke(2.dp, if (matches) Color(0xFF252528) else Color(0xFFE8E8EC)),
            ) { Box(contentAlignment = Alignment.Center) {
                DriverAvatar(face.presetColor, Modifier.fillMaxWidth().aspectRatio(4f / 3f), expression = index)
            } }
        }
    }
}
