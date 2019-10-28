package com.antourage.weaverlib.screens.base.player

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import com.antourage.weaverlib.UserCache
import com.antourage.weaverlib.other.models.StatisticWatchVideoRequest
import com.antourage.weaverlib.other.networking.ConnectionStateMonitor
import com.antourage.weaverlib.other.statistic.StatisticActions
import com.antourage.weaverlib.other.statistic.Stopwatch
import com.antourage.weaverlib.screens.base.BaseViewModel
import com.antourage.weaverlib.screens.base.Repository
import com.antourage.weaverlib.screens.vod.VideoViewModel
import com.antourage.weaverlib.screens.weaver.PlayerViewModel
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.source.BehindLiveWindowException
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import java.sql.Timestamp

internal abstract class BasePlayerViewModel(application: Application) : BaseViewModel(application) {
    private var playWhenReady = true
    protected var currentWindow = 0
    private var playbackPosition: Long = 0
    var streamId: Int? = null
    protected var streamUrl: String? = null
    private var playbackStateLiveData: MutableLiveData<Int> = MutableLiveData()

    private var stopwatch = Stopwatch()
    private var resetChronometer = true

    protected lateinit var player: SimpleExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector

    private var batteryStatus: Intent? = null

    internal var stopWatchingTime: Long? = null

    private var timerTickHandler = Handler()
    private var timerTickRunnable = object : Runnable {
        override fun run() {
            stopWatchingTime = player.currentPosition
            timerTickHandler.postDelayed(this, 1000)
        }
    }

    init {
        currentWindow = 0
    }

    fun setStreamId(streamId: Int) {
        this.streamId = streamId
    }

    fun getPlaybackState(): LiveData<Int> = playbackStateLiveData

    abstract fun onStreamStateChanged(playbackState: Int)

    abstract fun getMediaSource(streamUrl: String?): MediaSource?

    abstract fun onVideoChanged()

    open fun onResume() {
        initStatisticsListeners()
        if (player.playbackState != Player.STATE_READY) {
            player.playWhenReady = true
        }
        batteryStatus = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            getApplication<Application>().registerReceiver(null, ifilter)
        }
        sendStatisticData(StatisticActions.JOINED)
        startUpdatingStopWatchingTime()
    }

    open fun onPause() {
        removeStatisticsListeners()
        player.playWhenReady = false
        stopwatch.stop()
        timerTickHandler.removeCallbacksAndMessages(null)
        sendStatisticData(StatisticActions.LEFT, stopwatch.toString())
    }

    fun getExoPlayer(streamUrl: String): SimpleExoPlayer? {
        player = getSimpleExoPlayer()
        this.streamUrl = streamUrl
        player.playWhenReady = playWhenReady
        player.prepare(getMediaSource(streamUrl), false, true)
        player.seekTo(currentWindow, C.TIME_UNSET)
        initStatisticsListeners()
        return player
    }

    fun getStreamGroups(): List<Format> {
        val mappedTrackInfo = trackSelector.currentMappedTrackInfo
        val list: MutableList<Format> = mutableListOf()
        mappedTrackInfo?.let {
            val trackGroupArray = mappedTrackInfo.getTrackGroups(0)
            val trackGroup = trackGroupArray[0]
            for (j in 0 until trackGroup.length) {
                list.add(trackGroup.getFormat(j))
            }
        }
        return list
    }

    fun isPlaybackPaused(): Boolean = !player.playWhenReady

    fun releasePlayer() {
        removeStatisticsListeners()
        playbackPosition = player.currentPosition
        currentWindow = player.currentWindowIndex
        playWhenReady = player.playWhenReady
        player.release()
    }

    fun onNetworkGained() {
        player.prepare(getMediaSource(streamUrl), false, true)
        player.seekTo(currentWindow, playbackPosition)
    }

    fun onResolutionChanged(pos: Int) {
        val builder = trackSelector.parameters.buildUpon()
        val i = 0
        builder
            .clearSelectionOverrides(/* rendererIndex= */i)
            .setRendererDisabled(
                /* rendererIndex= */ i,
                i == pos
            )
        val overrides: MutableList<DefaultTrackSelector.SelectionOverride> = mutableListOf()
        overrides.add(0, DefaultTrackSelector.SelectionOverride(0, pos))
        if (overrides.isNotEmpty()) {
            builder.setSelectionOverride(
                /* rendererIndex= */ i,
                trackSelector.currentMappedTrackInfo?.getTrackGroups(/* rendererIndex= */i),
                overrides[0]
            )
        }
        trackSelector.setParameters(builder)
    }

    private fun initStatisticsListeners() {
        player.addAnalyticsListener(streamAnalyticsListener)
        player.addListener(playerEventListener)
    }

    fun removeStatisticsListeners() {
        player.removeAnalyticsListener(streamAnalyticsListener)
        player.removeListener(playerEventListener)
    }

    private fun getSimpleExoPlayer(): SimpleExoPlayer {
        val adaptiveTrackSelection = AdaptiveTrackSelection.Factory()
        trackSelector = DefaultTrackSelector(adaptiveTrackSelection)
        trackSelector.parameters = DefaultTrackSelector.ParametersBuilder().build()
        return ExoPlayerFactory.newSimpleInstance(
            getApplication(),
            DefaultRenderersFactory(getApplication()),
            trackSelector
        )
    }

    private fun updateWatchingTimeSpan(watchingTime: StatisticWatchVideoRequest) {
        UserCache.getInstance(getApplication())?.updateVODWatchingTime(watchingTime)
    }

    private fun getBatteryLevel(): Int? {
        return batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    }

    private fun sendStatisticData(statisticAction: StatisticActions, span: String? = "00:00:00") {
        val statsItem = StatisticWatchVideoRequest(
            streamId,
            statisticAction.ordinal,
            getBatteryLevel(),
            Timestamp(System.currentTimeMillis()).toString(),
            span
        )
        when (this) {
            is VideoViewModel -> Repository().statisticWatchVOD(statsItem)
            is PlayerViewModel -> Repository().statisticWatchLiveStream(statsItem)
        }
    }

    private fun startUpdatingStopWatchingTime(){
        if (timerTickHandler.hasMessages(0)) {
            timerTickHandler.removeCallbacksAndMessages(null)
        } else {
            timerTickHandler.post(timerTickRunnable)
        }
    }

    //region Listeners

    private val streamAnalyticsListener = object : AnalyticsListener {}

    private val playerEventListener = object : Player.EventListener {

        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    if (isPlaybackPaused()) {
                        stopwatch.stop()
                    } else {
                        if (resetChronometer) {
                            stopwatch.start()
                            resetChronometer = false
                        } else {
                            stopwatch.resume()
                        }
                    }
                }
                Player.STATE_ENDED, Player.STATE_IDLE -> {
                    stopwatch.stop()
                }
            }
            onStreamStateChanged(playbackState)
            playbackStateLiveData.postValue(playbackState)
        }

        override fun onPlayerError(err: ExoPlaybackException) {
            if (ConnectionStateMonitor.isNetworkAvailable(application.baseContext)) {
                playbackPosition = player.currentPosition
                currentWindow = player.currentWindowIndex
            }
            if (err.cause is BehindLiveWindowException) {
                player.prepare(getMediaSource(streamUrl), false, true)
            }

            error.postValue(err.toString())
        }

        override fun onPositionDiscontinuity(reason: Int) {
            currentWindow = player.currentWindowIndex
            resetChronometer = true
            onVideoChanged()
        }
    }
    //endregion
}