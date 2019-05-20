package com.antourage.weaverlib.screens.vod

import android.app.Application
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.antourage.weaverlib.other.models.StreamResponse
import com.antourage.weaverlib.screens.base.streaming.StreamingViewModel
import com.google.android.exoplayer2.source.ConcatenatingMediaSource
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import java.net.URLEncoder

class VideoViewModel(application: Application):StreamingViewModel(application){

    private val currentVideo : MutableLiveData<StreamResponse> = MutableLiveData()

    fun getCurrentVideo():LiveData<StreamResponse>{
        return currentVideo
    }

    override fun onVideoChanged() {
        val list:List<StreamResponse> = repository.getListOfVideos()
        currentVideo.postValue(list[currentWindow])
    }

    fun setCurrentPlayerPosition(videoId: Int){
        currentWindow = findVideoPositionById(videoId)
    }

    private fun findVideoPositionById(videoId: Int): Int {
        val list:List<StreamResponse> = repository.getListOfVideos()
        for(i in 0 until list.size){
            if(list[i].streamId == videoId){
                currentVideo.postValue(list[i])
                return i
            }
        }
        return -1
    }

    override fun getMediaSource(streamUrl: String?): MediaSource? {
        val list:List<StreamResponse> = repository.getListOfVideos()
        val mediaSources= arrayOfNulls<MediaSource>(list.size)
        for (i in 0 until list.size){
            mediaSources[i] = buildSimpleMediaSource(list[i].hlsUrl)
        }
        val mediaSource = ConcatenatingMediaSource(*mediaSources)
        return mediaSource
    }

    override fun onStreamStateChanged(playbackState: Int) {

    }
    private fun buildSimpleMediaSource( uri: String):MediaSource{
        val defaultBandwidthMeter = DefaultBandwidthMeter()
        val dataSourceFactory = DefaultDataSourceFactory(
            getApplication(),
            Util.getUserAgent(getApplication(), "Exo2"), defaultBandwidthMeter
        )
        //TODO 10/5/2018 choose one
        //hls
        return HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(Uri.parse(uri))
    }


}