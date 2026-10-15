package com.appdwg.app

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** One HTTP client for downloads that are not pictures. Pictures go through Coil. */
internal object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            if (req.header("User-Agent") != null) chain.proceed(req)
            else chain.proceed(req.newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()
}
