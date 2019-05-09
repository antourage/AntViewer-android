package com.antourage.weaverlib.screens.weaver

import android.app.Application
import android.net.Uri
import android.view.Surface
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.antourage.weaverlib.other.models.Message
import com.antourage.weaverlib.screens.base.BaseViewModel
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.decoder.DecoderCounters
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MediaSourceEventListener
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import java.io.IOException

class WeaverViewModel(application: Application) : BaseViewModel(application) {
    private var playWhenReady = true
    private var currentWindow = 0
    private var playbackPosition: Long = 0

    private var player: SimpleExoPlayer? = null
    private var playbackStateLiveData: MutableLiveData<Int> = MutableLiveData()

    fun getPlaybackState(): LiveData<Int> {
        return playbackStateLiveData
    }

    //temporary - chat is here
    private var messagesLiveData:MutableLiveData<List<Message>> = MutableLiveData()
    fun getMessagesLiveData():LiveData<List<Message>>{return messagesLiveData}

    init {
        messagesLiveData.postValue(listOf())
    }

    fun addMessage(text:String){
        if (!text.isEmpty()&& !text.isBlank()){
            val temp:MutableList<Message> = (messagesLiveData.value)!!.toMutableList()
            temp.add(Message((temp.size+1).toString(),null,
                "osoluk@leobit.co","ooollleeennnaaaa",text,null))
            messagesLiveData.postValue(temp as List<Message>)
        }
    }

    fun getExoPlayer(streamUrl: String?): SimpleExoPlayer? {
        player = getSimpleExoplayer()
        val mediaSource = getMediaSource(streamUrl)
        player?.prepare(mediaSource)
        player?.playWhenReady = playWhenReady
        player?.seekTo(currentWindow, playbackPosition)
        player?.prepare(mediaSource, true, false)
        initStatisticsListeners()
        return player
    }

    fun onResume(){
        if (player!!.playbackState != Player.STATE_READY) {
            player!!.playWhenReady = true
        }
    }
    fun onPause(){
        if (player!!.playbackState == Player.STATE_READY)
            player!!.playWhenReady = false
    }

     fun releasePlayer() {
        if (player != null) {
            playbackPosition = player?.currentPosition!!
            currentWindow = player?.currentWindowIndex!!
            playWhenReady = player?.playWhenReady!!
            player?.release()
            player = null
        }
    }

    fun initStatisticsListeners() {
        player?.addAnalyticsListener(object : AnalyticsListener {
            override fun onSeekProcessed(eventTime: AnalyticsListener.EventTime?) {

            }

            override fun onPlaybackParametersChanged(
                eventTime: AnalyticsListener.EventTime?,
                playbackParameters: PlaybackParameters?
            ) {

            }

            override fun onPlayerError(eventTime: AnalyticsListener.EventTime?, error: ExoPlaybackException?) {
                Toast.makeText(getApplication(), "playerError", Toast.LENGTH_LONG).show()
            }

            override fun onSeekStarted(eventTime: AnalyticsListener.EventTime?) {
            }

            override fun onLoadingChanged(eventTime: AnalyticsListener.EventTime?, isLoading: Boolean) {
            }

            override fun onDownstreamFormatChanged(
                eventTime: AnalyticsListener.EventTime?,
                mediaLoadData: MediaSourceEventListener.MediaLoadData?
            ) {
            }

            override fun onDrmKeysLoaded(eventTime: AnalyticsListener.EventTime?) {
            }

            override fun onMediaPeriodCreated(eventTime: AnalyticsListener.EventTime?) {
            }

            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime?, surface: Surface?) {
            }

            override fun onReadingStarted(eventTime: AnalyticsListener.EventTime?) {
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime?,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long
            ) {
                Toast.makeText(getApplication(), "bitrate estimate = " + bitrateEstimate, Toast.LENGTH_SHORT).show()
            }


            override fun onPlayerStateChanged(
                eventTime: AnalyticsListener.EventTime?,
                playWhenReady: Boolean,
                playbackState: Int
            ) {
            }

            override fun onDrmKeysRestored(eventTime: AnalyticsListener.EventTime?) {
            }

            override fun onDecoderDisabled(
                eventTime: AnalyticsListener.EventTime?,
                trackType: Int,
                decoderCounters: DecoderCounters?
            ) {
            }

            override fun onShuffleModeChanged(eventTime: AnalyticsListener.EventTime?, shuffleModeEnabled: Boolean) {
            }

            override fun onDecoderInputFormatChanged(
                eventTime: AnalyticsListener.EventTime?,
                trackType: Int,
                format: Format?
            ) {
            }

            override fun onAudioSessionId(eventTime: AnalyticsListener.EventTime?, audioSessionId: Int) {
            }

            override fun onDrmSessionManagerError(eventTime: AnalyticsListener.EventTime?, error: Exception?) {
            }

            override fun onLoadStarted(
                eventTime: AnalyticsListener.EventTime?,
                loadEventInfo: MediaSourceEventListener.LoadEventInfo?,
                mediaLoadData: MediaSourceEventListener.MediaLoadData?
            ) {
            }

            override fun onTracksChanged(
                eventTime: AnalyticsListener.EventTime?,
                trackGroups: TrackGroupArray?,
                trackSelections: TrackSelectionArray?
            ) {
            }

            override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime?, reason: Int) {
            }

            override fun onRepeatModeChanged(eventTime: AnalyticsListener.EventTime?, repeatMode: Int) {
            }

            override fun onUpstreamDiscarded(
                eventTime: AnalyticsListener.EventTime?,
                mediaLoadData: MediaSourceEventListener.MediaLoadData?
            ) {
            }

            override fun onLoadCanceled(
                eventTime: AnalyticsListener.EventTime?,
                loadEventInfo: MediaSourceEventListener.LoadEventInfo?,
                mediaLoadData: MediaSourceEventListener.MediaLoadData?
            ) {
            }

            override fun onMediaPeriodReleased(eventTime: AnalyticsListener.EventTime?) {
            }

            override fun onTimelineChanged(eventTime: AnalyticsListener.EventTime?, reason: Int) {
            }

            override fun onDecoderInitialized(
                eventTime: AnalyticsListener.EventTime?,
                trackType: Int,
                decoderName: String?,
                initializationDurationMs: Long
            ) {
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime?,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                // Toast.makeText(context,"Number of dropped frames ="+droppedFrames, Toast.LENGTH_SHORT ).show()
            }

            override fun onDecoderEnabled(
                eventTime: AnalyticsListener.EventTime?,
                trackType: Int,
                decoderCounters: DecoderCounters?
            ) {
            }

            override fun onVideoSizeChanged(
                eventTime: AnalyticsListener.EventTime?,
                width: Int,
                height: Int,
                unappliedRotationDegrees: Int,
                pixelWidthHeightRatio: Float
            ) {
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime?,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long
            ) {
            }

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime?,
                loadEventInfo: MediaSourceEventListener.LoadEventInfo?,
                mediaLoadData: MediaSourceEventListener.MediaLoadData?
            ) {
            }

            override fun onDrmKeysRemoved(eventTime: AnalyticsListener.EventTime?) {
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime?,
                loadEventInfo: MediaSourceEventListener.LoadEventInfo?,
                mediaLoadData: MediaSourceEventListener.MediaLoadData?,
                error: IOException?,
                wasCanceled: Boolean
            ) {
            }

            override fun onMetadata(eventTime: AnalyticsListener.EventTime?, metadata: Metadata?) {
            }

        })
        player?.addListener(object : Player.EventListener {
            override fun onTimelineChanged(timeline: Timeline, manifest: Any?, reason: Int) {

            }

            override fun onTracksChanged(trackGroups: TrackGroupArray, trackSelections: TrackSelectionArray) {

            }

            override fun onLoadingChanged(isLoading: Boolean) {

            }

            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                playbackStateLiveData.postValue(playbackState)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {

            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {

            }

            override fun onPlayerError(error: ExoPlaybackException) {
                Toast.makeText(getApplication(), error.toString(), Toast.LENGTH_LONG).show()
            }

            override fun onPositionDiscontinuity(reason: Int) {

            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {

            }

            override fun onSeekProcessed() {

            }
        })
    }

    private fun getSimpleExoplayer(): SimpleExoPlayer? {
        val adaptiveTrackSelection = AdaptiveTrackSelection.Factory()
        return ExoPlayerFactory.newSimpleInstance(
            getApplication(),
            DefaultRenderersFactory(getApplication()),
            DefaultTrackSelector(adaptiveTrackSelection)
        )
    }

    private fun getMediaSource(streamUrl: String?): MediaSource? {
        val defaultBandwidthMeter = DefaultBandwidthMeter()
        val dataSourceFactory = DefaultDataSourceFactory(
            getApplication(),
            Util.getUserAgent(getApplication(), "Exo2"), defaultBandwidthMeter
        )

        return HlsMediaSource.Factory(dataSourceFactory).createMediaSource(Uri.parse(streamUrl))

    }
}