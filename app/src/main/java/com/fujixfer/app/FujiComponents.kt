package com.fujixfer.app

import android.content.res.ColorStateList
import android.view.View
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** Type scale of the Compose screens (Material 3: sizes as in res/values/themes.xml, weight 500 at most). */
internal fun type(size: Int, line: Int, medium: Boolean = false) = TextStyle(
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal,
)

enum class ButtonKind { Filled, Tonal, Outlined, Text }

/**
 * The app's buttons (same look as the Button.* styles of the Views): fully round, the corners tighten while pressed
 * (Material 3 Expressive). [Filled] is the 56 dp primary action, [Tonal] and [Outlined] the secondary ones, [Text]
 * the quiet one. [contentColor] overrides the text and icon colour.
 */
@Composable
fun FujiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Tonal,
    enabled: Boolean = true,
    height: Dp = if (kind == ButtonKind.Filled) 56.dp else 48.dp,
    contentColor: Color? = null,
    icon: Int? = null,
) {
    val c = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val radius by animateDpAsState(
        if (pressed && enabled && kind != ButtonKind.Text) 12.dp else 28.dp,
        tween(if (pressed) 120 else 220), label = "radius",
    )
    val shape = RoundedCornerShape(if (kind == ButtonKind.Text) 24.dp else radius)
    val container = when {
        kind == ButtonKind.Text || kind == ButtonKind.Outlined -> Color.Transparent
        !enabled -> c.surfaceContainerHighest
        kind == ButtonKind.Filled -> c.primary
        else -> c.secondaryContainer
    }
    val content = contentColor ?: when (kind) {
        ButtonKind.Filled -> if (enabled) c.onPrimary else c.onSurfaceVariant
        ButtonKind.Tonal -> if (enabled) c.onSecondaryContainer else c.onSurfaceVariant
        else -> if (enabled) c.primary else c.outline
    }
    val bordered = if (kind == ButtonKind.Outlined) Modifier.border(1.dp, c.outline, shape) else Modifier
    Box(
        modifier.height(height).clip(shape).background(container).then(bordered)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (kind == ButtonKind.Text) 12.dp else 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(painterResource(icon), null, Modifier.size(18.dp), tint = content)
                Box(Modifier.width(8.dp))
            }
            Text(
                text, color = content, maxLines = 1, textAlign = TextAlign.Center,
                style = type(if (kind == ButtonKind.Filled) 16 else 14, 20, medium = true),
            )
        }
    }
}

/** Single-line text box like the old EditText (56 dp, 12 dp corners, 1 dp outline, hint while empty). */
@Composable
fun FujiField(value: String, onValueChange: (String) -> Unit, hint: String, enabled: Boolean, modifier: Modifier = Modifier, password: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(56.dp),
        enabled = enabled,
        singleLine = true,
        textStyle = type(16, 24).copy(color = if (enabled) c.onSurface else c.onSurfaceVariant),
        cursorBrush = SolidColor(c.primary),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxSize().clip(shape).background(c.surfaceContainerLowest).border(1.dp, c.outline, shape)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(hint, color = c.onSurfaceVariant, style = type(16, 24), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                inner()
            }
        },
    )
}

/**
 * A button of a connected pair (wide outer corners, 8 dp inner ones; pressed, the outer corners tighten).
 * [first] is the left one. With [icon] it sits before the text.
 */
@Composable
fun GroupButton(
    text: String, first: Boolean, container: Color, content: Color, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, icon: Int? = null, height: Dp = 56.dp, textSize: Int = 16,
    /** Between two others of a group: 8 dp corners on both sides. */
    middle: Boolean = false,
    description: String? = null,
) {
    val c = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val outer by animateDpAsState(if (pressed && enabled) 12.dp else 28.dp, tween(if (pressed) 120 else 220), label = "outer")
    val inner = 8.dp
    val shape = if (middle) RoundedCornerShape(inner)
    else if (first) RoundedCornerShape(topStart = outer, topEnd = inner, bottomEnd = inner, bottomStart = outer)
    else RoundedCornerShape(topStart = inner, topEnd = outer, bottomEnd = outer, bottomStart = inner)
    val fg = if (enabled) content else c.onSurfaceVariant
    Box(
        modifier.height(height).clip(shape).background(if (enabled) container else c.surfaceContainerHighest)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(painterResource(icon), null, Modifier.size(20.dp), tint = fg)
                if (text.isNotEmpty()) Box(Modifier.width(8.dp))
            }
            if (text.isNotEmpty()) Text(text, color = fg, style = type(textSize, 20, medium = true), maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

/** A layer over others: it takes the touches, so what lies under it does not react to them. */
fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}
