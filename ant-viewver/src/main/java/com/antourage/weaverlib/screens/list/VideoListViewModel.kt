package com.antourage.weaverlib.screens.list

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.antourage.weaverlib.Global
import com.antourage.weaverlib.UserCache
import com.antourage.weaverlib.other.Debouncer
import com.antourage.weaverlib.other.models.FeedInfo
import com.antourage.weaverlib.other.models.Message
import com.antourage.weaverlib.other.models.ProfileResponse
import com.antourage.weaverlib.other.models.StreamResponse
import com.antourage.weaverlib.other.networking.Resource
import com.antourage.weaverlib.other.networking.SocketConnector
import com.antourage.weaverlib.other.networking.Status
import com.antourage.weaverlib.other.networking.feed.FeedRepository
import com.antourage.weaverlib.other.networking.profile.ProfileRepository
import com.antourage.weaverlib.other.room.RoomRepository
import com.antourage.weaverlib.screens.base.BaseViewModel
import com.antourage.weaverlib.screens.base.Repository
import com.antourage.weaverlib.screens.list.dev_settings.OnDevSettingsChangedListener

internal class VideoListViewModel(application: Application) : BaseViewModel(application),
    OnDevSettingsChangedListener,
    ReceivingVideosManager.ReceivingVideoCallback {

    val roomRepository: RoomRepository = RoomRepository.getInstance(application)
    private var pulledToRefresh: Boolean = false
    private var canRefresh: Boolean = false
    var listOfStreams: MutableLiveData<List<StreamResponse>> = MutableLiveData()
    var loaderLiveData: MutableLiveData<Boolean> = MutableLiveData()
    var errorLiveData: MutableLiveData<String> = MutableLiveData()
    var feedInfoLiveData: MutableLiveData<FeedInfo> = MutableLiveData()
    var profileLiveData: MutableLiveData<ProfileResponse> = MutableLiveData()
    var doNotTriggerNewButton: Boolean =
        false // to prevent new button to appear on pagination fetching
    private var liveVideos: MutableList<StreamResponse>? = null
    private var vods: List<StreamResponse>? = null

    private var livesToFetchInfo: MutableList<StreamResponse> = mutableListOf()

    private var showCallResult = false

    var liveVideosUpdated = false
    var vodsUpdated = false
    var vodsUpdatedWithoutError = false

    companion object {
        private const val VODS_COUNT = 15
        private const val BE_CHOICE_TIMEOUT = 4000L
        private const val BE_CHOICE_CLICKS = 4
    }

    fun subscribeToLiveStreams(isInitial: Boolean = true) {
        if (isInitial) {
            ReceivingVideosManager.isFirstRequestVod = true
            ReceivingVideosManager.isFirstRequest = true
        }
        ReceivingVideosManager.setReceivingVideoCallback(this)
        ReceivingVideosManager.startReceivingLiveStreams()
    }

    private val socketConnectionObserver = Observer<SocketConnector.SocketConnection> {
        if (it == SocketConnector.SocketConnection.DISCONNECTED) {
            if (Global.networkAvailable) {
                subscribeToLiveStreams()
            }
        }
    }

    private val liveFromSocketObserver = Observer<List<StreamResponse>> { newStreams ->
        if (newStreams != null) {
            doNotTriggerNewButton = false
            liveVideos = newStreams.toMutableList()
            liveVideos?.let { getChatPollInfoForLives(it) }
            liveVideos?.let {
                for (i in 0 until (liveVideos?.size ?: 0)) {
                    liveVideos?.get(i)?.isLive = true
                }
            }
            if (!vodsUpdatedWithoutError && vodsUpdated && vods.isNullOrEmpty()) {
                runOnUi { refreshVODs(noLoadingPlaceholder = false) }
            }
        }
    }

    private val vodFromSocketObserver = Observer<StreamResponse> { newVod ->
        if (newVod != null && FeedRepository.vods?.any { it.id == newVod.id } == false) {
            doNotTriggerNewButton = false
            val list = mutableListOf<StreamResponse>()
            list.add(newVod)
            FeedRepository.invalidateIsNewProperty(list)
            FeedRepository.vods?.let {
                list.addAll(it)
            }

            FeedRepository.vods = list.toMutableList()

            if (vods?.last()?.id == -1) {
                list.add(list.size, getListEndPlaceHolder())
            } else if (vods?.last()?.id == -2) {
                list.add(list.size, getStreamLoaderPlaceholder())
            } else if (vods?.isNotEmpty() == true && vods?.size in 1 until VODS_COUNT) {
                list.add(
                    list.size, getListEndPlaceHolder()
                )
            }

            vods = list

            vodsUpdated = true
            vodsUpdatedWithoutError = true
            if (liveVideosUpdated) {
                showCallResult = true
                runOnUi { updateVideosList() }
            }
        }
    }

    private fun runOnUi(method: () -> Unit) {
        Handler(Looper.getMainLooper()).post {
            method()
        }
    }

    private fun removeSocketListeners() {
        SocketConnector.socketConnection.removeObserver(socketConnectionObserver)
        SocketConnector.newLivesLiveData.removeObserver(liveFromSocketObserver)
        SocketConnector.newVodLiveData.removeObserver(vodFromSocketObserver)
    }

    private fun liveSocketListeners() {
        SocketConnector.clearSocketData()
        SocketConnector.socketConnection.observeForever(socketConnectionObserver)
        SocketConnector.newLivesLiveData.observeForever(liveFromSocketObserver)
        SocketConnector.newVodLiveData.observeForever(vodFromSocketObserver)
    }

    fun refreshVODs(
        count: Int = (vods?.size?.minus(1)) ?: 0,
        noLoadingPlaceholder: Boolean = false
    ) {
        var vodsCount = count
        if (vodsCount < VODS_COUNT) {
            vodsCount = 0
        }
        this.pulledToRefresh = noLoadingPlaceholder
        vodsUpdated = false
        vodsUpdatedWithoutError = false
        ReceivingVideosManager.loadVODs(vodsCount, RoomRepository.getInstance(getApplication()))
    }

    fun refreshVODsLocally() {
        if (canRefresh) {
            val resultList = mutableListOf<StreamResponse>()
            liveVideos?.let { resultList.addAll(it) }
            var addBottomLoader = false
            var addJumpToTop = false

            if (vods?.find { it.id == -2 } != null) {
                addBottomLoader = true
            }

            if (vods?.find { it.id == -1 } != null) {
                addJumpToTop = true
            }

            vods = mutableListOf()
            FeedRepository.vods?.let { (vods as MutableList<StreamResponse>).addAll(it) }

            if (addJumpToTop) {
                (vods as MutableList<StreamResponse>).add(getListEndPlaceHolder())
            } else if (addBottomLoader) {
                (vods as MutableList<StreamResponse>).add(getStreamLoaderPlaceholder())
            }

            vods?.let { resultList.addAll(it.toList()) }
            listOfStreams.postValue(resultList.toList())
            updateVideosList(shouldUpdateStopTimeFromDB = true)
        }
        canRefresh = true
    }

    fun onPause(shouldDisconnectSocket: Boolean = true) {
        showBeDialogLiveData.postValue(false)
        numberOfLogoClicks = 0
        ReceivingVideosManager.stopReceivingVideos()
        if (shouldDisconnectSocket) SocketConnector.disconnectSocket()
        removeSocketListeners()
    }

    override fun onLiveBroadcastReceived(resource: Resource<List<StreamResponse>>) {
        if (ReceivingVideosManager.isFirstRequestVod) {
            ReceivingVideosManager.isFirstRequestVod = false
            ReceivingVideosManager.pauseReceivingVideos()
            liveSocketListeners()
//            initVodSocketListeners()
            ReceivingVideosManager.checkShouldUseSockets()
        }
        when (resource.status) {
            is Status.Success -> {
                doNotTriggerNewButton = false
                liveVideos = (resource.status.data)?.toMutableList()
                liveVideos?.let { getChatPollInfoForLives(it) }
                liveVideos?.let {
                    for (i in 0 until (liveVideos?.size ?: 0)) {
                        liveVideos?.get(i)?.isLive = true
                    }
                }
                if (!vodsUpdatedWithoutError && vodsUpdated && vods.isNullOrEmpty()) {
                    refreshVODs(noLoadingPlaceholder = false)
                }
            }
            is Status.Loading -> {
                liveVideosUpdated = false
            }
            is Status.Failure -> {
                liveVideosUpdated = true
                error.postValue(resource.status.errorMessage)
                errorLiveData.postValue(resource.status.errorMessage)
            }
            else -> {
            }
        }
    }

    override fun onVODReceived(resource: Resource<List<StreamResponse>>) {
        when (resource.status) {
            is Status.Success -> {
                doNotTriggerNewButton = true
                val list = mutableListOf<StreamResponse>()
                FeedRepository.vods?.let {
                    list.addAll(it)
                }
                val newList = (resource.status.data)?.toMutableList()

                if (newList != null) {
                    FeedRepository.invalidateIsNewProperty(newList)
                    list.addAll(list.size, newList)
                }
                FeedRepository.vods = list.toMutableList()

                if (newList?.size == VODS_COUNT) {
                    list.add(
                        list.size, getStreamLoaderPlaceholder()
                    )
                } else if (newList?.size!! < VODS_COUNT) {
                    list.add(list.size, getListEndPlaceHolder())
                }

                vods = list
                vodsUpdated = true
                vodsUpdatedWithoutError = true
                if (liveVideosUpdated) {
                    updateVideosList()
                }

            }
            is Status.Loading -> {
                vodsUpdated = false
                vodsUpdatedWithoutError = false
            }
            is Status.Failure -> {
                vodsUpdated = true
                vodsUpdatedWithoutError = false
                loaderLiveData.postValue(false)
                error.postValue(resource.status.errorMessage)
                errorLiveData.postValue(resource.status.errorMessage)
            }
            else -> {
            }
        }
    }

    override fun onVODReceivedInitial(resource: Resource<List<StreamResponse>>) {
        when (resource.status) {
            is Status.Success -> {
                val newList = (resource.status.data)?.toMutableList()
                if (newList != null) {
                    FeedRepository.invalidateIsNewProperty(newList)
                }
                FeedRepository.vods = newList?.toMutableList()
                if (newList?.size == VODS_COUNT) {
                    newList.add(
                        newList.size, getStreamLoaderPlaceholder()
                    )
                } else if (newList?.isNotEmpty() == true && newList.size in 2 until VODS_COUNT) {
                    newList.add(
                        newList.size, getListEndPlaceHolder()
                    )
                }

                vods = newList

//                initVodSocketListeners()

                vodsUpdated = true
                vodsUpdatedWithoutError = true

                if (liveVideosUpdated) {
                    updateVideosList()
                }
            }
            is Status.Loading -> {
                vodsUpdated = false
                vodsUpdatedWithoutError = false

                if (!pulledToRefresh) {
                    loaderLiveData.postValue(true)
                }
                showCallResult = true
                if (liveVideosUpdated && vodsUpdated) {
                    updateVideosList()
                }
            }
            is Status.Failure -> {
                vodsUpdated = true
                vodsUpdatedWithoutError = false

                loaderLiveData.postValue(false)
                error.postValue(resource.status.errorMessage)
                errorLiveData.postValue(resource.status.errorMessage)
            }
            else -> {
            }
        }
    }

    fun refreshChatPollInfo() {
        liveVideosUpdated = false
        if (liveVideos == null) {
            liveVideosUpdated = true
        } else {
            liveVideos?.let { getChatPollInfoForLives(it) }
        }
    }

    private fun getChatPollInfoForLives(list: List<StreamResponse>) {
        livesToFetchInfo.clear()
        livesToFetchInfo.addAll(list)
        if (list.isEmpty()) {
            liveVideosUpdated = true
            if (vodsUpdated) {
                updateVideosList(true)
            }
        } else {
            list.forEach {
                getChatPollLiveInfo(it)
            }
        }
    }

    private fun getChatPollLiveInfo(stream: StreamResponse) {
        var isChatEnabled = false
        var isPollEnabled = true
        var comment: Message?
        Repository.getChatPollInfoFromLiveStream(
            stream.id!!,
            object : Repository.LiveChatPollInfoCallback {
                override fun onSuccess(
                    chatEnabled: Boolean,
                    pollEnabled: Boolean,
                    message: Message
                ) {
                    comment = message
                    isChatEnabled = chatEnabled
                    isPollEnabled = pollEnabled
                    val streamToUpdate = liveVideos?.filter { it.id == stream.id }
                    if (!streamToUpdate
                            .isNullOrEmpty()
                    ) {
                        streamToUpdate[0].isChatEnabled = isChatEnabled
                        streamToUpdate[0].arePollsEnabled = isPollEnabled
                        streamToUpdate[0].lastMessage = comment?.text ?: ""
                        streamToUpdate[0].lastMessageAuthor = comment?.nickname ?: ""
                    }

                    livesToFetchInfo.forEach {
                        if (it.id == stream.id) {
                            it.isChatEnabled = isChatEnabled
                            it.arePollsEnabled = isPollEnabled
                            it.lastMessage = comment?.text ?: ""

                            if (comment?.userID == UserCache.getInstance()?.getUserId()
                                    .toString()
                            ) {
                                it.lastMessageAuthor =
                                    UserCache.getInstance()?.getUserNickName() ?: message.nickname
                                            ?: ""
                            } else {
                                it.lastMessageAuthor = comment?.nickname ?: ""
                            }
                        }
                    }
                    if (areAllChatPollDataLoaded()) {
                        liveVideosUpdated = true
                        if (vodsUpdated) {
                            updateVideosList(true)
                        }
                    }
                }

                override fun onFailure() {
                    comment = Message()
                    livesToFetchInfo.forEach {
                        if (it.id == stream.id) {
                            it.isChatEnabled = isChatEnabled
                            it.arePollsEnabled = isPollEnabled
                            it.lastMessage = comment?.text ?: ""
                            it.lastMessageAuthor = comment?.nickname ?: ""
                        }
                    }

                    if (areAllChatPollDataLoaded()) {
                        liveVideosUpdated = true
                        if (vodsUpdated) {
                            updateVideosList(true)
                        }
                    }
                }

            })
    }

    private fun areAllChatPollDataLoaded(): Boolean {
        livesToFetchInfo.forEach {
            if (it.isChatEnabled == null) {
                return false
            }
        }
        liveVideosUpdated = true
        return true
    }

    private fun updateVideosList(
        updateLiveStreams: Boolean = false,
        shouldUpdateStopTimeFromDB: Boolean = false
    ) {
        if (showCallResult || updateLiveStreams) {
            val resultList = mutableListOf<StreamResponse>()
            liveVideos?.let {
                resultList.addAll(it)
            }

            vods?.let { resultList.addAll(it.toList()) }

            loaderLiveData.postValue(false)
            listOfStreams.postValue(resultList.toList())
            showCallResult = false
        }

        if (shouldUpdateStopTimeFromDB) {
            vods?.forEach { video ->
                video.stopTimeMillis = video.id?.let { roomRepository.getStopTimeById(it) } ?: 0
            }
        }
    }

    private fun getListEndPlaceHolder(): StreamResponse {
        return StreamResponse(
            -1, null, null,
            null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, null, null, false, null, false
        )
    }

    private fun getStreamLoaderPlaceholder(): StreamResponse {
        return StreamResponse(
            -2, null, null,
            null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, null, null, false, null, false
        )
    }

    //region backend choice

    private val showBeDialogLiveData: MutableLiveData<Boolean> = MutableLiveData()
    private var numberOfLogoClicks: Int = 0
    private val beDebouncer: Debouncer =
        Debouncer({ numberOfLogoClicks = 0 }, BE_CHOICE_TIMEOUT)

    init {
        showBeDialogLiveData.postValue(false)
    }

    fun onLogoPressed() {
        if (numberOfLogoClicks >= BE_CHOICE_CLICKS) {
            showBeDialogLiveData.value = true
            numberOfLogoClicks = 0
            beDebouncer.cancel()
        } else {
            numberOfLogoClicks++
            if (numberOfLogoClicks == 1) {
                showBeDialogLiveData.postValue(false)
                beDebouncer.run()
            }
        }
    }

    fun getShowBeDialog() = showBeDialogLiveData as LiveData<Boolean>

    override fun onBeChanged(choice: String) {
        choice.let {
            UserCache.getInstance(getApplication<Application>().applicationContext)
                ?.updateEnvChoice(choice)
            UserCache.getInstance()?.saveAccessToken(null)
            UserCache.getInstance()?.saveIdToken(null)
            UserCache.getInstance()?.saveRefreshToken(null)
        }
    }
    //endregion

    fun onNetworkGained(isErrorShown: Boolean = false) {
        if (feedInfoLiveData.value == null) getFeedInfo()
        if (profileLiveData.value == null) getProfileInfo()
        refreshVODs(noLoadingPlaceholder = !isErrorShown)
    }

    fun onNetworkChanged(isConnected: Boolean) {
        if (isConnected) {
            Handler(Looper.getMainLooper()).postDelayed({
                subscribeToLiveStreams(true)
            }, 500)
        } else {
            SocketConnector.disconnectSocket()
            ReceivingVideosManager.pauseWhileNoNetwork()
            SocketConnector.cancelReconnect()
        }
    }

    internal fun userAuthorized(): Boolean {
        return !(UserCache.getInstance(getApplication())?.getIdToken().isNullOrBlank())
    }

    private fun getCachedNickname(): String? {
        return UserCache.getInstance(getApplication())?.getUserNickName()
    }

    fun getSavedTagLine(): String? {
        return UserCache.getInstance(getApplication())?.getTagLine()
    }

    fun getSavedFeedImageUrl(): String? {
        return UserCache.getInstance(getApplication())?.getFeedImageUrl()
    }

    fun getFeedInfo() {
        val response = Repository.getFeedInfo()
        response.observeForever(object : Observer<Resource<FeedInfo>> {
            override fun onChanged(it: Resource<FeedInfo>?) {
                when (val responseStatus = it?.status) {
                    is Status.Success -> {
                        if (responseStatus.data != null) {
                            val feedInfo = responseStatus.data
                            if (!feedInfo.tagLine.isNullOrEmpty()) {
                                UserCache.getInstance(getApplication())
                                    ?.saveTagLine(feedInfo.tagLine)
                            } else {
                                UserCache.getInstance(getApplication())?.saveTagLine("")
                            }
                            if (!feedInfo.imageUrl.isNullOrEmpty()) {
                                UserCache.getInstance(getApplication())
                                    ?.saveFeedImageUrl(feedInfo.imageUrl)
                            } else {
                                UserCache.getInstance(getApplication())?.saveFeedImageUrl("")
                            }
                            feedInfoLiveData.postValue(feedInfo)
                        }
                        response.removeObserver(this)
                    }
                    is Status.Failure -> {
                        response.removeObserver(this)
                    }
                }
            }
        })
    }

    fun getProfileInfo() {
        if (!UserCache.getInstance()?.getIdToken().isNullOrEmpty()) {
            val response = ProfileRepository.getProfile()
            response.observeForever(object : Observer<Resource<ProfileResponse>> {
                override fun onChanged(it: Resource<ProfileResponse>?) {
                    when (val responseStatus = it?.status) {
                        is Status.Success -> {
                            if (responseStatus.data != null) {
                                val profile = responseStatus.data
                                UserCache.getInstance()?.saveUserNickName(profile.nickname ?: "")
                                UserCache.getInstance()?.saveUserImage(profile.imageUrl ?: "")
                                UserCache.getInstance()?.saveUserId(profile.id ?: "")
                                profileLiveData.postValue(profile)
                            }
                            response.removeObserver(this)
                        }
                        is Status.Failure -> {
                            response.removeObserver(this)
                        }
                    }
                }
            })
        }
    }
}