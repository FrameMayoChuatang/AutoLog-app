package com.framemayo.autolog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object VehicleUi {
    val Surface = Color(0xFF17181D)
    val Primary = Color(0xFFF4F5F7)
    val Secondary = Color(0xFFB9BEC8)
    val Outline = Color(0xFF696B75)
    val Positive = Color(0xFF95D7BA)
    val Caution = Color(0xFFF0CF8A)
    val Error = Color(0xFFFFB4AB)

    val Title = 26.sp
    val Body = 22.sp
    val Caption = 18.sp
    val ButtonText = 20.sp
    val MinTouch = 56.dp
}

@Composable
internal fun VehicleSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = VehicleUi.Surface) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, fontSize = VehicleUi.Title, fontWeight = FontWeight.SemiBold, color = VehicleUi.Primary)
            content()
        }
    }
}

@Composable
internal fun VehiclePrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = VehicleUi.MinTouch),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White, contentColor = VehicleUi.Surface,
            disabledContainerColor = Color(0xFF33353C), disabledContentColor = Color(0xFF8A8E98),
        ),
    ) { Text(text, fontSize = VehicleUi.ButtonText) }
}

@Composable
internal fun VehicleSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = VehicleUi.MinTouch),
        shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, VehicleUi.Outline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = VehicleUi.Surface, contentColor = VehicleUi.Primary),
    ) { Text(text, fontSize = VehicleUi.ButtonText) }
}
