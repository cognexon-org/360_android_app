package com.propertytour360.capture.data

import android.content.Context
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val preferences = AppPreferences(context)
    val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    private val services = ConcurrentHashMap<String, ApiService>()

    fun api(baseUrl: String): ApiService = services.getOrPut(AppPreferences.normalizeBaseUrl(baseUrl)) {
        Retrofit.Builder()
            .baseUrl(AppPreferences.normalizeBaseUrl(baseUrl))
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ApiService::class.java)
    }

    val backendRepository = BackendRepository(::api, client)
}
