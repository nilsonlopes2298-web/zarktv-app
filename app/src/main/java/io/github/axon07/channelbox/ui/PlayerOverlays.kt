package io.github.axon07.channelbox.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.axon07.channelbox.data.ChannelEntity
import io.github.axon07.channelbox.data.ProgramEntity
import io.github.axon07.channelbox.player.PlayerStatus

fun nowAndNext(programs: List<ProgramEntity>?, now: Long): Pair<ProgramEntity?, ProgramEntity?> {
    if (programs.isNullOrEmpty()) return null to null
    val current = programs.firstOrNull { it.start <= now && now < it.stop }
    val after = current?.stop ?: now
    val next = programs.firstOrNull { it.start >= after }
    return current to next
}

/** Bottom "now / next" banner shown after every channel change, like a cable box. */
@Composable
fun BoxScope.InfoBanner(
    channel: ChannelEntity,
    favorite: Boolean,
    programs: List<ProgramEntity>?,
    now: Long,
    timeFormat: (Long) -> String,
    info: io.github.axon07.channelbox.player.StreamInfo? = null,
    captions: List<String> = emptyList(),
    vpnOn: Boolean = false,
    /** Room left below for the on-screen controls. */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val (current, next) = nowAndNext(programs, now)
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000), Color(0xF2000000))))
            .padding(start = 28.dp, end = 28.dp, top = 36.dp, bottom = 18.dp + bottomInset)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                channel.number.toString(),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.Text,
                modifier = Modifier.width(64.dp),
            )
            ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = 80.dp, height = 45.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (favorite) Text("  ★", fontSize = 16.sp, color = Palette.Favorite)
                    Spacer(Modifier.weight(1f))
                    Text(timeFormat(now), fontSize = 16.sp, color = Palette.TextDim)
                }
                Spacer(Modifier.height(4.dp))
                if (current != null) {
                    Text(
                        "${timeFormat(current.start)} – ${timeFormat(current.stop)}   ${current.title}",
                        fontSize = 15.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    ProgressBar(programProgress(current.start, current.stop, now), Modifier.fillMaxWidth())
                } else {
                    Text("No programme information", fontSize = 15.sp, color = Palette.TextDim)
                }
                if (next != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Next  ${timeFormat(next.start)}   ${next.title}",
                        fontSize = 13.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // What's actually playing: quality, frame rate, audio, bitrate, captions, VPN.
                val details = listOfNotNull(info?.video, info?.audio, info?.bitrate)
                if (details.isNotEmpty() || captions.isNotEmpty() || vpnOn) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        details.forEach { Tag(it) }
                        if (captions.isNotEmpty()) {
                            Tag("CC: " + captions.take(4).joinToString(", ") + if (captions.size > 4) "…" else "", icon = AppIcons.Captions)
                        }
                        if (vpnOn) Tag("VPN", icon = AppIcons.Shield)
                    }
                }
            }
        }
    }
}

/** Big channel number typed on the remote's number keys. */
@Composable
fun BoxScope.NumberEntry(digits: String, match: ChannelEntity?) {
    Column(
        Modifier
            .align(Alignment.TopEnd)
            .padding(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DialogStyle.Card.copy(alpha = 0.92f))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(digits, fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        Text(match?.name ?: "—", fontSize = 13.sp, color = Palette.TextDim, maxLines = 1)
    }
}

@Composable
fun BoxScope.StatusMessage(status: PlayerStatus, channel: ChannelEntity?, fallback: String?) {
    // ZarkTV: carregando = rodinha azul pequena no meio (sem texto), como nos apps de IPTV.
    if (channel != null && status is PlayerStatus.Buffering) {
        val giro = androidx.compose.animation.core.rememberInfiniteTransition(label = "carregando")
        val ang by giro.animateFloat(
            0f, 360f,
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)),
            label = "ang",
        )
        androidx.compose.foundation.Canvas(Modifier.align(Alignment.Center).size(46.dp)) {
            drawCircle(Color(0x33FFFFFF), style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx()))
            drawArc(
                Color(0xFF2F8CFF), ang, 100f, false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        return
    }
    val text = when {
        channel == null -> fallback
        status is PlayerStatus.Error -> status.message
        status is PlayerStatus.Buffering -> "Loading…"
        else -> null
    } ?: return
    val isError = status is PlayerStatus.Error
    Text(
        text,
        fontSize = 16.sp,
        color = if (isError) Palette.Error else Palette.Text,
        modifier = Modifier
            .align(Alignment.Center)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC0E141B))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/** Left-hand channel list opened with OK during playback. */
@Composable
fun ChannelListPanel(
    channels: List<ChannelEntity>,
    selected: Int,
    listState: LazyListState,
    filterLabel: String,
    favorites: Set<String>,
    playingId: String?,
    upcoming: Map<String, List<ProgramEntity>>,
    now: Long,
    onSelect: (Int) -> Unit,
    onFavorite: (Int) -> Unit,
    onGroup: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(340.dp)
            .background(Palette.Panel)
            .tap { } // taps on the panel's empty space shouldn't close it
            .padding(vertical = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹", fontSize = 20.sp, color = Palette.TextDim, modifier = Modifier.tap { onGroup(-1) }.padding(horizontal = 10.dp))
            Text(filterLabel, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 1)
            Text("›", fontSize = 20.sp, color = Palette.TextDim, modifier = Modifier.tap { onGroup(1) }.padding(horizontal = 10.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (channels.isEmpty()) {
            Text(
                "No channels here.\n◀ ▶ to change group",
                color = Palette.TextDim, fontSize = 14.sp,
                modifier = Modifier.padding(14.dp),
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(channels, key = { _, ch -> ch.id }) { index, ch ->
                val isSelected = index == selected
                val (current, _) = nowAndNext(ch.epgId?.let { upcoming[it] }, now)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) Palette.Accent else Color.Transparent)
                        .tap(onLongClick = { onFavorite(index) }) { onSelect(index) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        ch.number.toString(), fontSize = 14.sp, color = Palette.Text,
                        modifier = Modifier.width(40.dp),
                    )
                    ChannelLogo(ch.logoUrl, ch.name, Modifier.size(width = 52.dp, height = 30.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ch.name, fontSize = 14.sp, color = Palette.Text, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                            )
                            if (io.github.axon07.channelbox.data.Catchup.isAvailable(ch.catchupType, ch.catchupSource, ch.catchupDays)) {
                                Spacer(Modifier.width(5.dp))
                                AppIcon(AppIcons.History, tint = Palette.TextDim, size = 13.dp)
                            }
                            if (ch.id in LocalRecordingChannels.current) {
                                Spacer(Modifier.width(5.dp))
                                Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.Recording))
                            }
                            if (ch.id in LocalLockedChannels.current) {
                                Spacer(Modifier.width(4.dp))
                                AppIcon(AppIcons.Lock, tint = Palette.TextDim, size = 12.dp)
                            }
                            if (ch.id in favorites) Text(" ★", color = Palette.Favorite, fontSize = 12.sp)
                            if (ch.id == playingId) Text("  ▶", color = Palette.Text, fontSize = 11.sp)
                        }
                        if (current != null) {
                            Text(
                                current.title, fontSize = 12.sp,
                                color = if (isSelected) Palette.Text else Palette.TextDim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            ProgressBar(programProgress(current.start, current.stop, now), Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/** Red "● REC" in the corner while the channel on screen is being recorded. */
@Composable
fun BoxScope.RecIndicator() {
    Row(
        Modifier
            .align(Alignment.TopStart)
            .padding(20.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.Recording))
        Spacer(Modifier.width(6.dp))
        Text("REC", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, letterSpacing = 1.sp)
    }
}

/** Short message at the top of the screen. */
@Composable
fun BoxScope.ToastMessage(text: String) {
    Text(
        text,
        fontSize = 15.sp,
        color = Palette.Text,
        maxLines = 2,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xE6151B23))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Composable
private fun Tag(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(
        Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0x26FFFFFF))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AppIcon(icon, tint = Palette.Text, size = 12.dp)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, fontSize = 11.sp, color = Palette.Text, maxLines = 1)
    }
}

/** "Paused" / "2:15 behind live" while live TV is paused or watched from the pause buffer. */
@Composable
fun BoxScope.TimeshiftBadge(shift: TvViewModel.LiveShift, vm: TvViewModel) {
    var behind by remember { mutableLongStateOf(vm.behindLiveMs()) }
    LaunchedEffect(shift) {
        while (true) {
            behind = vm.behindLiveMs()
            kotlinx.coroutines.delay(250)
        }
    }
    Row(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = 20.dp, end = 20.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(if (shift.paused) AppIcons.Pause else AppIcons.Play, tint = Color.White, size = 13.dp)
        Spacer(Modifier.width(7.dp))
        Text(
            when {
                shift.paused && !shift.buffered -> "Paused"
                shift.paused -> "Paused  ${formatClock(behind)}"
                else -> "−${formatClock(behind)}"
            },
            fontSize = 13.sp, color = Color.White,
        )
    }
}
