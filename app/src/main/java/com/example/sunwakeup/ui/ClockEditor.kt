package com.example.sunwakeup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StayCurrentLandscape
import androidx.compose.material.icons.outlined.StayCurrentPortrait
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.sunwakeup.data.AppSettings
import com.example.sunwakeup.data.ClockItem
import com.example.sunwakeup.data.ItemPlace
import com.example.sunwakeup.data.Repository
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the clock editor is doing, shared by the tile on the page and its copy docked at the top. */
class ClockEditorState {
    /** The part of the clock being moved, outlined in the preview. */
    var selected by mutableStateOf<ClockItem?>(null)
    /** The preview shows the clock as on a phone held upright. */
    var upright by mutableStateOf(false)
    /** How big each part is drawn in the preview, to tell which one a touch lands on. */
    val itemSizes = mutableStateMapOf<ClockItem, IntSize>()
}

/** How long the preview is, standing for the screen on its side. */
private val PreviewLength = 300.dp

/** How small and how big a part can be made; big enough for the time to fill the screen. */
private const val MIN_SCALE = 0.2f
private const val MAX_SCALE = 14f

/** How close to the middle, or to a straight angle, a part snaps there. */
private const val SNAP_DP = 4f
private const val SNAP_DEGREES = 4f

/** Puts [item] at [place] in the layout for the screen [upright] or on its side, if it isn't there already. */
private fun editLayout(item: ClockItem, upright: Boolean, place: ItemPlace) {
    if (Repository.settings.value.night.place(item, upright) == place) return
    Repository.update { it.copy(night = it.night.withPlace(item, upright, place)) }
}

/** [this] turned [degrees] clockwise on screen. */
private fun Offset.turned(degrees: Float): Offset {
    val r = degrees * PI.toFloat() / 180f
    return Offset(x * cos(r) - y * sin(r), x * sin(r) + y * cos(r))
}

private fun Offset.angle() = atan2(y, x) * 180f / PI.toFloat()

/** [degrees] brought into -180..180, and onto a straight angle when close to one. */
private fun snapAngle(degrees: Float): Float {
    val d = ((degrees % 360f) + 540f) % 360f - 180f
    val straight = (d / 90f).roundToInt() * 90f
    return if (abs(d - straight) < SNAP_DEGREES) straight else d
}

/**
 * A small copy of the phone's screen showing the night clock as it will look, live, with every size
 * scaled down alike. Each part can be picked up and moved, pinched or dragged by its corner handle to
 * size and turn it, up to filling the whole screen. The copy always lies on its side; upright, the clock
 * in it is turned a quarter round, as the phone would be.
 */
@Composable
fun ClockEditorTile(settings: AppSettings, editor: ClockEditorState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now by rememberTickingNow(settings.night.showSeconds)
    val battery = remember(now.minute) { batteryStatus(context) }
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    val longSide = maxOf(window.width, window.height)
    val shortSide = minOf(window.width, window.height)
    val previewShort = PreviewLength * shortSide / longSide
    // Every size and distance on the phone, in dp, times this is its size in the preview.
    val scale = PreviewLength.value / (longSide / density)
    val pxPerDp = density * scale
    val sizes = editor.itemSizes
    val night by rememberUpdatedState(settings.night)
    val upright by rememberUpdatedState(editor.upright)
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier
            .size(PreviewLength, previewShort)
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .pointerInput(pxPerDp) {
                val handleReach = (HandleRadius + 10.dp).toPx()
                val slop = 6.dp.toPx()
                awaitEachGesture {
                    // Touches are worked out on the clock's own screen, which upright is turned a quarter
                    // round inside the preview: its size swaps, and every point and movement turns back.
                    val screen = if (upright) IntSize(size.height, size.width) else size
                    val middle = Offset(size.width / 2f, size.height / 2f)
                    val screenMiddle = Offset(screen.width / 2f, screen.height / 2f)
                    fun onScreen(at: Offset) = if (upright) (at - middle).turned(90f) + screenMiddle else at
                    fun movedOnScreen(by: Offset) = if (upright) by.turned(90f) else by
                    fun centerOf(item: ClockItem) =
                        itemCenter(night.place(item, upright), sizes[item] ?: IntSize.Zero, screen, pxPerDp)
                    fun handleOf(item: ClockItem): Offset {
                        val s = sizes[item] ?: IntSize.Zero
                        return centerOf(item) + Offset(s.width / 2f, s.height / 2f).turned(night.place(item, upright).rotation)
                    }
                    fun hits(item: ClockItem, at: Offset): Boolean {
                        val s = sizes[item] ?: return false
                        // Into the part's own frame, as if it weren't turned, to test against its box.
                        val local = (at - centerOf(item)).turned(-night.place(item, upright).rotation)
                        return abs(local.x) <= s.width / 2f + slop && abs(local.y) <= s.height / 2f + slop
                    }

                    val down = awaitFirstDown(requireUnconsumed = false)
                    val at = onScreen(down.position)
                    val selected = editor.selected
                    val onHandle = selected != null && selected in night.shownItems &&
                        (at - handleOf(selected)).getDistance() <= handleReach
                    // The part on top wins where parts overlap; a touch on nothing lets go of the selection
                    // and leaves the page free to scroll.
                    val item = if (onHandle) selected else night.shownItems.lastOrNull { hits(it, at) }
                    editor.selected = item
                    if (item == null) return@awaitEachGesture
                    down.consume()

                    val start = night.place(item, upright)
                    val center = centerOf(item)
                    val grabbed = at - center
                    // The place as the fingers move it, before snapping, so a snap never holds a part back.
                    var x = start.x
                    var y = start.y
                    var s = start.scale
                    var r = start.rotation
                    // How far from the middle the part can go and still be wholly on the screen, as it is
                    // drawn now; a part grown past the screen's size stays in the middle.
                    fun reach(): Offset {
                        val half = turnedHalfSize(sizes[item] ?: IntSize.Zero, r)
                        return Offset(
                            (screen.width / 2f - half.x).coerceAtLeast(0f) / pxPerDp,
                            (screen.height / 2f - half.y).coerceAtLeast(0f) / pxPerDp,
                        )
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            val pan = movedOnScreen(event.calculatePan())
                            x += pan.x / pxPerDp
                            y += pan.y / pxPerDp
                            s *= event.calculateZoom()
                            r += event.calculateRotation()
                        } else if (onHandle) {
                            // The handle stays under the finger: its distance from the centre sizes the
                            // part, and its angle round the centre turns it.
                            val pointer = onScreen(pressed[0].position) - center
                            s = start.scale * pointer.getDistance() / grabbed.getDistance().coerceAtLeast(1f)
                            r = start.rotation + pointer.angle() - grabbed.angle()
                        } else {
                            val move = movedOnScreen(pressed[0].positionChange())
                            x += move.x / pxPerDp
                            y += move.y / pxPerDp
                        }
                        s = s.coerceIn(MIN_SCALE, MAX_SCALE)
                        val limit = reach()
                        x = x.coerceIn(-limit.x, limit.x)
                        y = y.coerceIn(-limit.y, limit.y)
                        editLayout(
                            item,
                            upright,
                            ItemPlace(
                                x = if (abs(x) < SNAP_DP) 0f else x,
                                y = if (abs(y) < SNAP_DP) 0f else y,
                                scale = s,
                                rotation = snapAngle(r),
                            ),
                        )
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Upright, the phone's screen is tall and narrow, turned to lie across the preview.
        Box(
            Modifier
                .requiredSize(
                    if (editor.upright) previewShort else PreviewLength,
                    if (editor.upright) PreviewLength else previewShort,
                )
                .graphicsLayer { rotationZ = if (editor.upright) -90f else 0f },
        ) {
            NightClockFace(
                settings, now, battery,
                modifier = Modifier.fillMaxSize(),
                scale = scale,
                selected = editor.selected,
                selectionColor = MaterialTheme.colorScheme.primary,
                onItemSize = { item, size -> sizes[item] = size },
            )
        }
    }
}

/** The preview, with a switch under it between the clock as on a phone on its side and upright. */
@Composable
fun ClockEditor(settings: AppSettings, editor: ClockEditorState, onDockMoved: (Float?) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Dockable(onDockMoved) { ClockEditorTile(settings, editor) }
        SingleChoiceSegmentedButtonRow(Modifier.padding(top = 10.dp).height(34.dp)) {
            OrientationButton(Icons.Outlined.StayCurrentLandscape, "Side", !editor.upright, 0) { editor.upright = false }
            OrientationButton(Icons.Outlined.StayCurrentPortrait, "Upright", editor.upright, 1) { editor.upright = true }
        }
    }
}

/** One half of the switch between the clock as on a phone on its side and upright. */
@Composable
private fun SingleChoiceSegmentedButtonRowScope.OrientationButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    index: Int,
    onClick: () -> Unit,
) {
    SegmentedButton(
        selected = selected,
        onClick = onClick,
        shape = SegmentedButtonDefaults.itemShape(index, 2),
        icon = { Icon(icon, contentDescription = null, Modifier.size(16.dp)) },
        label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
    )
}
