package com.offline.dpadmessenger.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Width of the tail "ear" that hangs off the bubble's trailing bottom corner. */
val BubbleTailWidth: Dp = 6.dp

/** Corner radius of a tailed bubble. */
private val BubbleCorner: Dp = 18.dp

/**
 * iMessage/OpenBubbles bubble outline: a rounded rect with a small tail curling
 * out of its bottom corner — bottom-right for a sent (outgoing) bubble,
 * bottom-left for a received one.
 *
 * The tail is drawn as a separate closed path and UNIONed with the body rather
 * than woven into one continuous path. Both approaches render the same fill, but
 * the union means the body stays an exact [RoundRect]: the corner radii are
 * whatever Compose's own rounding produces, so a tailed bubble and an untailed
 * one (media, the composer field) can't drift apart visually. It also keeps the
 * tail geometry independent — tweaking [BubbleTailWidth] can't deform the body.
 *
 * The body is inset by the tail width on the tail side, so the tail hangs in
 * that reserved strip and the overall shape still fits its measured bounds. The
 * caller must therefore pad the bubble's CONTENT by an extra [BubbleTailWidth]
 * on the tail side, or text will run under the tail — see MessageBubble.
 *
 * @param isOutgoing tail on the right (sent) vs. the left (received).
 */
data class TailedBubbleShape(
    private val isOutgoing: Boolean,
    private val cornerRadius: Dp = BubbleCorner,
    private val tailWidth: Dp = BubbleTailWidth,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        val t = with(density) { tailWidth.toPx() }

        // The body occupies everything except the tail strip on its own side.
        val bodyLeft = if (isOutgoing) 0f else t
        val bodyRight = if (isOutgoing) w - t else w

        // Clamp so a very short/narrow bubble (a lone "ok", an emoji) degrades to
        // a pill instead of a shape with overlapping corner arcs.
        val r = with(density) { cornerRadius.toPx() }
            .coerceAtMost(h / 2f)
            .coerceAtMost((bodyRight - bodyLeft).coerceAtLeast(0f) / 2f)

        val body = Path().apply {
            addRoundRect(
                RoundRect(
                    left = bodyLeft,
                    top = 0f,
                    right = bodyRight,
                    bottom = h,
                    cornerRadius = CornerRadius(r, r),
                ),
            )
        }

        // The tail: a convex outer edge sweeping from the body's side wall down to
        // the tip at the very bottom corner, then a concave underside hooking back
        // up into the body's bottom edge. That concave return is what makes it read
        // as an iMessage tail rather than a triangle glued on.
        val tail = Path().apply {
            if (isOutgoing) {
                moveTo(bodyRight, h - r)
                // Outer edge: bulge right and down to the tip at (w, h).
                quadraticTo(bodyRight, h, w, h)
                // Underside: hook back up-and-left into the bubble's bottom edge.
                quadraticTo(bodyRight + t * 0.35f, h - t * 0.35f, bodyRight - r * 0.6f, h)
            } else {
                moveTo(bodyLeft, h - r)
                quadraticTo(bodyLeft, h, 0f, h)
                quadraticTo(bodyLeft - t * 0.35f, h - t * 0.35f, bodyLeft + r * 0.6f, h)
            }
            close()
        }

        return Outline.Generic(
            Path().apply { op(body, tail, PathOperation.Union) },
        )
    }
}
