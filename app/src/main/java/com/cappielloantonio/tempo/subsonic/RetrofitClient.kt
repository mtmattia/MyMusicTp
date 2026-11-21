package com.cappielloantonio.tempo.subsonic

import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.BuildConfig
import com.cappielloantonio.tempo.subsonic.utils.CacheUtil
import com.google.gson.GsonBuilder
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class RetrofitClient(subsonic: Subsonic) {
    var retrofit: Retrofit

    init {
        retrofit = Retrofit.Builder()
            .baseUrl(subsonic.url)
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mm:ss").create()))
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().setLenient().create()))
            .client(okHttpClient)
            .build()
    }

    companion object {
        // A single OkHttpClient shared by every API client: one connection pool and,
        // above all, one disk Cache. Multiple Cache instances on the same directory
        // corrupt each other and every server switch used to create a new batch of them.
        @JvmStatic
        val okHttpClient: OkHttpClient by lazy { buildOkHttpClient() }

        /**
         * Drops pooled connections. Call it when the network changes: sockets opened on the
         * previous network are dead and requests on them hang until the timeout fires.
         */
        @JvmStatic
        fun evictConnections() {
            okHttpClient.connectionPool.evictAll()
        }

        private fun buildOkHttpClient(): OkHttpClient {
            val cacheUtil = CacheUtil(60, 60 * 60 * 24 * 30)

            return OkHttpClient.Builder()
                .callTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(getHttpLoggingInterceptor())
                .addInterceptor(cacheUtil.offlineInterceptor)
                .cache(Cache(App.getContext().cacheDir, 10L * 1024 * 1024))
                .build()
        }

        private fun getHttpLoggingInterceptor(): HttpLoggingInterceptor {
            val loggingInterceptor = HttpLoggingInterceptor()
            // BODY buffers and logs every full response (album lists can be huge): debug only.
            loggingInterceptor.setLevel(if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.BASIC)
            return loggingInterceptor
        }
    }
}
