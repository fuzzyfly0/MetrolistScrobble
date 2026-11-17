/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.utils.makeTimeString

@Composable
fun StartEndMarkers(
    startMs: Long?,
    endMs: Long?,
    durationMs: Long,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (durationMs <= 0 || (startMs == null && endMs == null)) return
    Canvas(modifier) {
        val markerWidth = 3.dp.toPx()
        val markerHeight = size.height * 0.6f
        val top = (size.height - markerHeight) / 2
        listOfNotNull(startMs, endMs).forEach { pointMs ->
            var fraction = (pointMs.toFloat() / durationMs).coerceIn(0f, 1f)
            if (layoutDirection == LayoutDirection.Rtl) fraction = 1f - fraction
            val x = (size.width * fraction - markerWidth / 2).coerceIn(0f, size.width - markerWidth)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, top),
                size = Size(markerWidth, markerHeight),
                cornerRadius = CornerRadius(markerWidth / 2),
            )
        }
    }
}

@Composable
fun StartEndPointButton(
    isStart: Boolean,
    pointMs: Long?,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSet = pointMs != null
    val description =
        stringResource(
            when {
                isStart && isSet -> R.string.action_clear_start_point
                isStart -> R.string.action_set_start_point
                isSet -> R.string.action_clear_end_point
                else -> R.string.action_set_end_point
            },
        )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(
            painter =
                painterResource(
                    when {
                        isStart && isSet -> R.drawable.point_a_set
                        isStart -> R.drawable.point_a
                        isSet -> R.drawable.point_b_set
                        else -> R.drawable.point_b
                    },
                ),
            contentDescription = description,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        if (pointMs != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = makeTimeString(pointMs),
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
            )
        }
    }
}
