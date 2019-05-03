package com.antourage.weaverlib.other.networking.models

import android.os.Parcelable
import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName
import kotlinx.android.parcel.Parcelize


class APIError {
    @SerializedName("ErrorCode")
    @Expose
    private val statusCode: Int = 0
    @SerializedName("Error")
    @Expose
    private val message: String? = null

    fun statusCode(): Int {
        return statusCode
    }

    fun message(): String? {
        return message
    }
}

class SimpleResponse {

    @SerializedName("error")
    @Expose
    var error: String? = null
    @SerializedName("success")
    @Expose
    var success: Boolean? = null

}

@Parcelize
data class StreamResponse(
    @field:SerializedName("id") val streamId:Int,
    @field:SerializedName("name")val streamTitle:String,
    @field:SerializedName("thumbnailUrl")val thumbnailUrl:String,
    @field:SerializedName("hlsUrl")val hlsUrl:String
    ):Parcelable