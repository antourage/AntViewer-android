package com.antourage.weaverlib.screens.vod

import android.app.Application
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.Observer
import android.net.Uri
import com.antourage.weaverlib.other.firebase.QuerySnapshotLiveData
import com.antourage.weaverlib.other.models.Message
import com.antourage.weaverlib.other.models.Stream
import com.antourage.weaverlib.other.models.StreamResponse
import com.antourage.weaverlib.other.networking.Resource
import com.antourage.weaverlib.other.networking.Status
import com.antourage.weaverlib.other.observeOnce
import com.antourage.weaverlib.screens.base.Repository
import com.antourage.weaverlib.screens.base.chat.ChatViewModel
import com.antourage.weaverlib.screens.weaver.ChatStatus
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSourceFactory
import com.google.android.exoplayer2.source.ConcatenatingMediaSource
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.util.Util
import okhttp3.OkHttpClient
import javax.inject.Inject

class VideoViewModel @Inject constructor(application: Application, val repository: Repository) :
    ChatViewModel(application) {

    private var isChatTurnedOn = false
    private var streamId: Int? = null
    private val chatStatusLiveData: MutableLiveData<ChatStatus> = MutableLiveData()
    private var streamMessagesLiveData: QuerySnapshotLiveData<Message>? = null

    private val streamObserver: Observer<Resource<Stream>> = Observer { resource ->
        when (val status = resource?.status) {
            is Status.Success -> {
                status.data?.apply {
                    isChatTurnedOn = isChatActive
                    if (!isChatTurnedOn) {
                        chatStatusLiveData.postValue(ChatStatus.ChatTurnedOff)
                    } else {
                        streamMessagesLiveData = repository.getMessages(streamId)
                        streamMessagesLiveData?.observeOnce(messagesObserver)
                    }
                }
            }
        }
    }

    private val messagesObserver: Observer<Resource<List<Message>>> = Observer { resource ->
        when (val status = resource?.status) {
            is Status.Success -> {
                status.data?.let { messages ->
                    if (isChatTurnedOn) {
                        if (chatContainsNonStatusMsg(messages)) {
                            chatStatusLiveData.postValue(ChatStatus.ChatMessages)
                            messagesLiveData.postValue(messages)
                        } else {
                            chatStatusLiveData.postValue(ChatStatus.ChatNoMessages)
                        }
                    }
                }
            }
        }
    }

    private val currentVideo: MutableLiveData<StreamResponse> = MutableLiveData()

    fun initUi(streamId: Int?) {
        streamId?.let {
            this.streamId = it
            repository.getStream(streamId).observeOnce(streamObserver)
        }
    }

    override fun onVideoChanged() {
        val list: List<StreamResponse> = Repository.vods ?: arrayListOf()
        val currentVod = list[currentWindow]
        this.streamId = currentVod.streamId
        currentVod.streamId?.let { repository.getStream(it).observeOnce(streamObserver) }
        currentVideo.postValue(currentVod)
        player.playWhenReady = true
    }

    fun getChatStatusLiveData(): MutableLiveData<ChatStatus>? = chatStatusLiveData

    fun onVideoStarted(streamId: Int) {}

    fun getCurrentVideo(): LiveData<StreamResponse> = currentVideo

    fun setCurrentPlayerPosition(videoId: Int) {
        currentWindow = findVideoPositionById(videoId)
    }

    private fun findVideoPositionById(videoId: Int): Int {
        val list: List<StreamResponse> = Repository.vods ?: arrayListOf()
        for (i in 0 until list.size) {
            if (list[i].streamId == videoId) {
                currentVideo.postValue(list[i])
                return i
            }
        }
        return -1
    }

    /**
     * using this to create playlist. For now, was approved
     */
    override fun getMediaSource(streamUrl: String?): MediaSource? {
        val list: List<StreamResponse>? = Repository.vods
        val mediaSources = arrayOfNulls<MediaSource>(list?.size ?: 0)
        for (i in 0 until (list?.size ?: 0)) {
            mediaSources[i] = list?.get(i)?.videoURL?.let { buildSimpleMediaSource(it) }
        }
        return ConcatenatingMediaSource(*mediaSources)
    }

    override fun onStreamStateChanged(playbackState: Int) {}

    /**
     * videos do not play on Android 5 without this additional header. IDK why
     */
    private fun buildSimpleMediaSource(uri: String): MediaSource {
        val okHttpClient = OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                val request = chain.request().newBuilder().addHeader("Connection", "close").build()
                chain.proceed(request)
            }
            .build()

        val dataSourceFactory =
            OkHttpDataSourceFactory(okHttpClient, Util.getUserAgent(getApplication(), "Exo2"))
        return HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(Uri.parse(uri))
    }
}