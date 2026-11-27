package com.simohypers.limusic.tv

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.simohypers.limusic.R
import com.simohypers.limusic.common.Config
import com.simohypers.limusic.domain.data.model.browse.album.Track
import com.simohypers.limusic.domain.mediaservice.handler.MediaPlayerHandler
import com.simohypers.limusic.domain.repository.SearchRepository
import com.simohypers.limusic.domain.utils.Resource
import com.simohypers.limusic.domain.utils.toListTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.roundToInt

@UnstableApi
class TvActivity : Activity(), KoinComponent {
    private val searchRepository: SearchRepository by inject()
    private val playerHandler: MediaPlayerHandler by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllerFuture by lazy {
        val service = ComponentName(packageName, PLAYBACK_SERVICE)
        val token = SessionToken(this, service)
        MediaController.Builder(this, token).buildAsync()
    }
    private var controller: MediaController? = null
    private lateinit var query: EditText
    private lateinit var search: Button
    private lateinit var status: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var transport: Button
    private lateinit var results: LinearLayout
    private var searching = false

    private val playerListener =
        object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                refreshPlayback()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(40), dp(24), dp(40), dp(24))
            }
        content.addView(
            TextView(this).apply {
                setText(R.string.app_name)
                textSize = 28f
            },
        )
        val searchRow = LinearLayout(this)
        query =
            EditText(this).apply {
                hint = getString(R.string.tv_search_hint)
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setOnEditorActionListener { _, action, _ ->
                    (action == EditorInfo.IME_ACTION_SEARCH).also { if (it) submitSearch() }
                }
            }
        search =
            Button(this).apply {
                setText(R.string.tv_search)
                isEnabled = false
                setOnClickListener { submitSearch() }
            }
        searchRow.addView(query, LinearLayout.LayoutParams(0, dp(56), 1f))
        searchRow.addView(search)
        content.addView(searchRow)
        nowPlaying = TextView(this).apply { textSize = 20f }
        content.addView(nowPlaying)
        transport =
            Button(this).apply {
                setText(R.string.tv_play)
                isEnabled = false
                setOnClickListener {
                    controller?.let { if (it.playWhenReady) it.pause() else it.play() }
                }
            }
        content.addView(transport)
        status =
            TextView(this).apply {
                setText(R.string.tv_connecting)
                textSize = 17f
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        content.addView(status)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(
            ScrollView(this).apply { addView(results) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(content)
        query.requestFocus()

        scope.launch {
            try {
                val connected = controllerFuture.await()
                controller = connected
                connected.addListener(playerListener)
                refreshPlayback()
                search.isEnabled = true
                status.setText(R.string.tv_search_hint)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                status.setText(R.string.tv_connection_failed)
            }
        }
    }

    private fun submitSearch() {
        val text = query.text.toString().trim()
        if (text.isEmpty() || searching || controller == null) return

        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(query.windowToken, 0)
        searching = true
        if (search.hasFocus()) query.requestFocus()
        search.isEnabled = false
        results.removeAllViews()
        status.setText(R.string.tv_searching)
        scope.launch {
            try {
                val response = searchRepository.getSearchDataSong(text).lastOrNull()
                if (response is Resource.Success) {
                    showResults(response.data?.toListTrack().orEmpty().take(30))
                } else {
                    status.setText(R.string.tv_search_failed)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                status.setText(R.string.tv_search_failed)
            } finally {
                searching = false
                search.isEnabled = true
            }
        }
    }

    private fun showResults(items: List<Track>) {
        status.setText(if (items.isEmpty()) R.string.tv_no_results else R.string.tv_search_results)
        for (item in items) {
            results.addView(
                Button(this).apply {
                    val artists = item.artists.orEmpty().joinToString(", ") { it.name }
                    text = if (artists.isEmpty()) item.title else "${item.title} · $artists"
                    isAllCaps = false
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    minHeight = dp(52)
                    setOnClickListener {
                        scope.launch {
                            try {
                                playerHandler.loadMediaItem(item, Config.SONG_CLICK, null)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                status.setText(R.string.tv_playback_failed)
                            }
                        }
                    }
                },
            )
        }
        if (query.hasFocus() && items.isNotEmpty()) results.getChildAt(0).requestFocus()
    }

    private fun refreshPlayback() {
        val connected = controller ?: return
        nowPlaying.text = connected.mediaMetadata.title ?: getString(R.string.tv_nothing_playing)
        transport.setText(if (connected.playWhenReady) R.string.tv_pause else R.string.tv_play)
        transport.isEnabled =
            connected.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) && connected.currentMediaItem != null
    }

    override fun onDestroy() {
        controller?.removeListener(playerListener)
        scope.cancel()
        MediaController.releaseFuture(controllerFuture)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val PLAYBACK_SERVICE = "com.simohypers.limusic.media3.service.SimpleMediaService"
    }
}
