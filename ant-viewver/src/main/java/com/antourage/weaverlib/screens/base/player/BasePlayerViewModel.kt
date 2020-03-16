package com.antourage.weaverlib.screens.base.player

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.antourage.weaverlib.Global
import com.antourage.weaverlib.R
import com.antourage.weaverlib.UserCache
import com.antourage.weaverlib.other.models.StatisticWatchVideoRequest
import com.antourage.weaverlib.other.models.StreamResponse
import com.antourage.weaverlib.other.networking.ConnectionStateMonitor
import com.antourage.weaverlib.other.networking.Resource
import com.antourage.weaverlib.other.networking.Status
import com.antourage.weaverlib.other.statistic.StatisticActions
import com.antourage.weaverlib.other.statistic.Stopwatch
import com.antourage.weaverlib.screens.base.BaseViewModel
import com.antourage.weaverlib.screens.base.Repository
import com.antourage.weaverlib.screens.list.ReceivingVideosManager
import com.antourage.weaverlib.screens.vod.VideoViewModel
import com.antourage.weaverlib.screens.weaver.PlayerViewModel
import com.antourage.weaverlib.ui.fab.AntourageFab.Companion.TAG
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.DefaultLoadControl.Builder
import com.google.android.exoplayer2.ExoPlaybackException.*
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.source.BehindLiveWindowException
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.upstream.DefaultAllocator
import java.sql.Timestamp

internal abstract class BasePlayerViewModel(application: Application) : BaseViewModel(application) {
    companion object {
        private const val STATISTIC_WATCHING_TIME_UPDATE_INTERVAL_MS = 2000L
    }

    private var playWhenReady = true
    protected var currentWindow = 0
    var streamId: Int? = null
    var currentlyWatchedVideoId: Int? = null
    protected var streamUrl: String? = null
    private var playbackStateLiveData: MutableLiveData<Int> = MutableLiveData()
    var errorLiveData: MutableLiveData<String> = MutableLiveData()

    private var stopwatch = Stopwatch()
    private var resetChronometer = true

    protected var player: SimpleExoPlayer? = null
    private lateinit var trackSelector: DefaultTrackSelector

    private var batteryStatus: Intent? = null

    internal var stopWatchingTime: Long? = null

    val requestingStreamInfoHandler = Handler()
    val currentStreamViewsLiveData: MutableLiveData<Int> = MutableLiveData()

    private var timerTickHandler = Handler()
    private var timerTickRunnable = object : Runnable {
        override fun run() {
            streamId?.let { streamId ->
                updateWatchingTimeSpan(
                    StatisticWatchVideoRequest(
                        streamId,
                        StatisticActions.LEFT.ordinal,
                        getBatteryLevel(),
                        stopwatch.toString(),
                        Timestamp(System.currentTimeMillis()).toString()
                    )
                )
            }
            timerTickHandler.postDelayed(this, STATISTIC_WATCHING_TIME_UPDATE_INTERVAL_MS)
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

    open fun onVideoChanged() {}

    open fun onResume() {
        initStatisticsListeners()
        if (player?.playbackState != Player.STATE_READY) {
            player?.playWhenReady = true
        }
        batteryStatus = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            getApplication<Application>().registerReceiver(null, filter)
        }
        sendStatisticData(StatisticActions.JOINED)
        startUpdatingStopWatchingTime()

        this.currentlyWatchedVideoId?.let { subscribeToCurrentStreamInfo(it) }
    }

    open fun onPause() {
        removeStatisticsListeners()
        player?.playWhenReady = false
        stopwatch.stopIfRunning()
        timerTickHandler.removeCallbacksAndMessages(null)
        sendStatisticData(StatisticActions.LEFT, stopwatch.toString())
        stopUpdatingCurrentStreamInfo()
    }

    fun getExoPlayer(streamUrl: String): SimpleExoPlayer? {
        player = getSimpleExoPlayer()
        this.streamUrl = streamUrl
        player?.playWhenReady = playWhenReady
        player?.prepare(getMediaSource(streamUrl), false, true)
        player?.seekTo(currentWindow, C.TIME_UNSET)
        initStatisticsListeners()
        return player
    }

    fun isPlaybackPaused(): Boolean = !(player?.playWhenReady ?: false)

    fun releasePlayer() {
        removeStatisticsListeners()
        currentWindow = player?.currentWindowIndex ?: 0
        playWhenReady = player?.playWhenReady ?: false
        player?.release()
    }

    fun onNetworkGained() {
        player?.prepare(getMediaSource(streamUrl), false, true)
        player?.seekTo(currentWindow, player?.currentPosition ?: 0)
    }

    private fun initStatisticsListeners() {
        player?.addAnalyticsListener(streamAnalyticsListener)
        player?.addListener(playerEventListener)
    }

    fun removeStatisticsListeners() {
        player?.removeAnalyticsListener(streamAnalyticsListener)
        player?.removeListener(playerEventListener)
    }

    private fun getSimpleExoPlayer(): SimpleExoPlayer {
        val adaptiveTrackSelection = AdaptiveTrackSelection.Factory()
        trackSelector = DefaultTrackSelector(adaptiveTrackSelection)
        trackSelector.parameters = DefaultTrackSelector.ParametersBuilder().build()
        val loadControl = Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(
                2500,
                5000,
                2500,
                2500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .createDefaultLoadControl()
        return ExoPlayerFactory.newSimpleInstance(
            getApplication(),
            DefaultRenderersFactory(getApplication()),
            trackSelector,
            loadControl
        )
    }

    private fun updateWatchingTimeSpan(watchingTime: StatisticWatchVideoRequest) {
        UserCache.getInstance(getApplication())?.updateStatisticWatchingTime(watchingTime)
    }

    private fun getBatteryLevel(): Int {
        return batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 0
    }

    private fun sendStatisticData(
        statisticAction: StatisticActions,
        span: String = "00:00:00",
        timestamp: String = Timestamp(System.currentTimeMillis()).toString()
    ) {
        streamId?.let { streamId ->
            StatisticWatchVideoRequest(
                streamId,
                statisticAction.ordinal,
                getBatteryLevel(),
                timestamp,
                span
            )
        }?.let { statsItem ->
            when (this) {
                is VideoViewModel -> Repository.statisticWatchVOD(statsItem)
                is PlayerViewModel -> Repository.statisticWatchLiveStream(statsItem)
                else -> {
                }
            }
        }
    }

    private fun startUpdatingStopWatchingTime() {
        if (timerTickHandler.hasMessages(0)) {
            timerTickHandler.removeCallbacksAndMessages(null)
        } else {
            timerTickHandler.post(timerTickRunnable)
        }
    }

    //region Listeners

    private val streamAnalyticsListener = object : AnalyticsListener {
        override fun onTracksChanged(
            eventTime: AnalyticsListener.EventTime?,
            trackGroups: TrackGroupArray?,
            trackSelections: TrackSelectionArray?
        ) {
            Log.d("TAG", "onTracksChanged")
        }
    }

    private val playerEventListener = object : Player.EventListener {

        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    if (isPlaybackPaused()) {
                        stopwatch.stopIfRunning()
                    } else {
                        if (resetChronometer) {
                            stopwatch.start()
                            resetChronometer = false
                        } else {
                            stopwatch.resume()
                        }
                    }
                }
                Player.STATE_ENDED -> {
                    stopwatch.stopIfRunning()
                    onLiveStreamEnded()
                }

                Player.STATE_IDLE -> {
                    stopwatch.stopIfRunning()
                }
            }
            onStreamStateChanged(playbackState)
            playbackStateLiveData.postValue(playbackState)
        }

        override fun onPlayerError(err: ExoPlaybackException) {
            if (ConnectionStateMonitor.isNetworkAvailable(application.baseContext)) {
                currentWindow = player?.currentWindowIndex ?: 0
                errorLiveData.postValue(application.resources.getString(R.string.ant_failed_to_load_video))
            }
            Log.d(TAG, "player error: ${err.cause.toString()}")
            Log.d(
                TAG, when (err.type) {
                    TYPE_SOURCE -> "error type: ${err.type} error message: ${err.sourceException.message}"
                    TYPE_RENDERER -> "error type: ${err.type} error message: ${err.rendererException.message}"
                    TYPE_UNEXPECTED -> "error type: ${err.type} error message: ${err.unexpectedException.message}"
                    TYPE_REMOTE -> "error type: ${err.type} error message: ${err.message}"
                    TYPE_OUT_OF_MEMORY -> "error type: ${err.type} error message: ${err.outOfMemoryError.message}"
                    else -> err.message
                } ?: ""
            )
            if (err.cause is BehindLiveWindowException) {
                player?.prepare(getMediaSource(streamUrl), false, true)
            } else {
                error.postValue(err.toString())
            }
        }

        override fun onPositionDiscontinuity(reason: Int) {
            currentWindow = player?.currentWindowIndex ?: 0
            //TODO: change this, so reset chronometer only in case user switches to next or previous
            // video;
            resetChronometer = true
            stopUpdatingCurrentStreamInfo()
            onVideoChanged()
            currentlyWatchedVideoId?.let { subscribeToCurrentStreamInfo(it) }
        }
    }
    //endregion

    /**
    For mow this method is used to update live viewers count in real time
    on player screen
     */
    private fun subscribeToCurrentStreamInfo(currentlyWatchedVideoId: Int) {
        requestingStreamInfoHandler.postDelayed(object : Runnable {
            override fun run() {
                if (Global.networkAvailable) {
                    val currentStreamInfo = when (this@BasePlayerViewModel) {
                        is VideoViewModel -> Repository.getVODById(currentlyWatchedVideoId)
                        else -> Repository.getLiveVideoById(currentlyWatchedVideoId)
                    }
                    val streamInfoObserver = object : Observer<Resource<StreamResponse>> {
                        override fun onChanged(resource: Resource<StreamResponse>?) {
                            if (resource != null) {
                                when (val result = resource.status) {
                                    is Status.Failure -> {
                                        currentStreamInfo.removeObserver(this)
                                    }
                                    is Status.Success -> {
                                        val streamInfo = result.data
                                        when (this@BasePlayerViewModel) {
                                            is VideoViewModel -> currentStreamViewsLiveData.postValue(
                                                streamInfo?.viewsCount
                                            )
                                            is PlayerViewModel -> currentStreamViewsLiveData.postValue(
                                                streamInfo?.viewersCount
                                            )
                                        }
                                        currentStreamInfo.removeObserver(this)
                                    }
                                }
                            }
                        }
                    }
                    currentStreamInfo.observeForever(streamInfoObserver)
                    requestingStreamInfoHandler.postDelayed(
                        this,
                        ReceivingVideosManager.LIVE_STREAMS_REQUEST_INTERVAL
                    )
                }
            }
        }, 0)
    }

    private fun stopUpdatingCurrentStreamInfo() {
        requestingStreamInfoHandler.removeCallbacksAndMessages(null)
    }

    open fun onLiveStreamEnded() {}
}