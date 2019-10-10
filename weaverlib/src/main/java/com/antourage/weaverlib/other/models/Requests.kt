package com.antourage.weaverlib.other.models

import com.google.gson.annotations.SerializedName

data class UserRequest(
    @field:SerializedName("apiKey") val apiKey: String?,
    @field:SerializedName("refKey") val refKey: String? = null,
    @field:SerializedName("displayName") val displayName: String? = null
)

data class UpdateDisplayNameRequest(
    @field:SerializedName("displayName") val displayName: String?
)

data class StatisticWatchVideoRequest(
    @field:SerializedName("userId") val userId: String?,
    @field:SerializedName("streamId") val streamId: Int?,
    @field:SerializedName("actionId") val actionId: Int?,
    @field:SerializedName("batteryLevel") val batteryLevel: Int?,
    @field:SerializedName("timeStamp") val timeStamp: String?,
    @field:SerializedName("span") val span: String?
)