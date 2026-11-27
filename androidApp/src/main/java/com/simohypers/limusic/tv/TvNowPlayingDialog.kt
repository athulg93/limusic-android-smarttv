package com.simohypers.limusic.tv

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.simohypers.limusic.R
import com.simohypers.limusic.domain.data.model.browse.album.Track
import com.simohypers.limusic.domain.mediaservice.handler.MediaPlayerHandler
import com.simohypers.limusic.domain.mediaservice.handler.PlayerEvent
import com.simohypers.limusic.domain.repository.DeviceLinkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal class TvNowPlayingDialog(
    private val activity: Activity,
    private val player: MediaPlayerHandler,
    private val devices: DeviceLinkRepository,
    private val scope: CoroutineScope,
) {
    fun show() {
        val dialog = Dialog(activity, android.R.style.Theme_Material_NoActionBar)
        val screen = column().apply {
            setBackgroundColor(Color.rgb(18, 18, 24))
            setPadding(dp(32), dp(20), dp(32), dp(20))
        }
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(26f, R.string.tv_now_playing), LinearLayout.LayoutParams(0, -2, 1f))
        val close = button(R.string.tv_back) { dialog.dismiss() }
        heading.addView(close)
        screen.addView(heading)

        val panels = LinearLayout(activity).apply { baselineAligned = false }
        screen.addView(panels, LinearLayout.LayoutParams(-1, 0, 1f))
        val details = column().apply { setPadding(0, 0, dp(24), 0) }
        panels.addView(details, LinearLayout.LayoutParams(0, -1, 1f))
        val artwork = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        details.addView(artwork, LinearLayout.LayoutParams(-1, 0, 1f))
        val title = label(24f, R.string.tv_nothing_playing)
        val artist = label(17f)
        val location = label(15f)
        details.addView(title)
        details.addView(artist)
        details.addView(location)
        val time = label(15f)
        details.addView(time)
        val seek = SeekBar(activity).apply {
            max = 1000
            keyProgressIncrement = 10
            isFocusable = true
            contentDescription = activity.getString(R.string.tv_seek)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) scope.launch { player.onPlayerEvent(PlayerEvent.UpdateProgress(progress / 10f)) }
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit

                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                },
            )
        }
        details.addView(seek)
        val controls = LinearLayout(activity)
        val previous = button(R.string.tv_previous) { dispatch(PlayerEvent.Previous) }
        val play = button(R.string.tv_play) { dispatch(PlayerEvent.PlayPause) }
        val next = button(R.string.tv_next) { dispatch(PlayerEvent.Next) }
        listOf(previous, play, next).forEach { controls.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        details.addView(controls)

        val queuePanel = column()
        panels.addView(queuePanel, LinearLayout.LayoutParams(0, -1, 1f))
        queuePanel.addView(label(24f, R.string.tv_queue))
        val emptyQueue = label(17f, R.string.tv_queue_empty)
        queuePanel.addView(emptyQueue)
        val adapter = QueueAdapter()
        val queue = ListView(activity).apply {
            this.adapter = adapter
            emptyView = emptyQueue
            setOnItemClickListener { _, _, position, _ ->
                val shown = adapter.tracks.getOrNull(position)
                val current = player.queueData.value?.data?.listTracks?.getOrNull(position)
                if (!player.followingRoom && shown != null && shown == current) {
                    player.playMediaItemInMediaSource(position)
                }
            }
        }
        queuePanel.addView(queue, LinearLayout.LayoutParams(-1, 0, 1f))

        dialog.setContentView(screen)
        dialog.show()
        dialog.window?.setLayout(-1, -1)

        val job =
            scope.launch {
                launch {
                    player.nowPlaying.collect { item ->
                        val metadata = item?.metadata
                        title.text = metadata?.title ?: activity.getString(R.string.tv_nothing_playing)
                        artist.text = metadata?.artist.orEmpty()
                        play.isEnabled = item != null
                    }
                }
                launch {
                    player.nowPlaying.map { it?.metadata?.artworkUri }.distinctUntilChanged().collectLatest { uri ->
                        artwork.setImageResource(android.R.drawable.ic_media_play)
                        if (!uri.isNullOrBlank()) {
                            val request = ImageRequest.Builder(activity).data(uri).size(dp(300)).build()
                            val result = SingletonImageLoader.get(activity).execute(request)
                            if (result is SuccessResult) artwork.setImageBitmap(result.image.toBitmap())
                        }
                    }
                }
                launch {
                    devices.link.map { if (it.controlling) it.player?.name else null }.distinctUntilChanged().collect { device ->
                        location.text = device?.let { activity.getString(R.string.tv_playing_on, it) }.orEmpty()
                        location.visibility = if (device == null) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    player.controlState.collect { state ->
                        previous.isEnabled = state.isPreviousAvailable
                        next.isEnabled = state.isNextAvailable
                        play.setText(if (state.isPlaying) R.string.tv_pause else R.string.tv_play)
                    }
                }
                launch {
                    combine(player.queueData, player.currentSongIndex) { data, current ->
                        data?.data?.listTracks.orEmpty() to current
                    }.distinctUntilChanged().collect { (tracks, current) ->
                        val firstLoad = adapter.tracks.isEmpty()
                        adapter.update(tracks, current)
                        if (firstLoad && current in tracks.indices) queue.setSelection(current)
                    }
                }
                launch {
                    while (true) {
                        val duration = player.getPlayerDuration()
                        val position = player.getProgress().coerceAtLeast(0L)
                        seek.isEnabled = duration > 0L
                        seek.progress = if (duration > 0L) (1000.0 * position / duration).toInt().coerceIn(0, 1000) else 0
                        time.text = activity.getString(R.string.tv_playback_time, formatTime(position), if (duration > 0L) formatTime(duration) else "–")
                        delay(500)
                    }
                }
            }
        dialog.setOnDismissListener { job.cancel() }
        if (play.isEnabled) play.requestFocus() else close.requestFocus()
    }

    private fun dispatch(event: PlayerEvent) {
        scope.launch { player.onPlayerEvent(event) }
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private fun label(size: Float, text: Int? = null) =
        TextView(activity).apply {
            textSize = size
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text?.let { setText(it) }
        }

    private fun button(text: Int, action: () -> Unit) =
        Button(activity).apply {
            setText(text)
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()

    private inner class QueueAdapter : BaseAdapter() {
        var tracks = emptyList<Track>()
            private set
        private var current = -1

        fun update(tracks: List<Track>, current: Int) {
            this.tracks = tracks
            this.current = current
            notifyDataSetChanged()
        }

        override fun getCount() = tracks.size

        override fun getItem(position: Int) = tracks[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val track = getItem(position)
            return ((recycled as? TextView) ?: label(17f).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                minHeight = dp(48)
            }).apply {
                val artists = track.artists.orEmpty().joinToString(", ") { it.name }
                val song = if (artists.isEmpty()) track.title else "${track.title} · $artists"
                text = if (position == current) activity.getString(R.string.tv_current_track, song) else song
                typeface = if (position == current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
    }
}
