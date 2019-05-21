package com.antourage.weaverlib.other.networking

import android.arch.lifecycle.LiveData
import com.antourage.weaverlib.other.models.StreamResponse
import com.antourage.weaverlib.other.networking.base.ApiResponse
import retrofit2.http.GET

interface WebService {
    @GET("channels/live")
    fun getLiveStreams(): LiveData<ApiResponse<List<StreamResponse>>>
}