package com.collinpendleton.yana.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** The sign-in endpoints: no bearer token, never retried on a 401. */
interface AuthApi {
    @GET("api/auth/state")
    suspend fun state(): AuthState

    @POST("api/auth/setup")
    suspend fun setup(@Body body: Credentials): SignInResponse

    @POST("api/auth/login")
    suspend fun login(@Body body: Credentials): SignInResponse

    @POST("api/auth/logout")
    suspend fun logout(@Body body: RefreshRequest)
}

/** Everything behind the bearer token that the navigation shell reads. */
interface YanaApi {
    @GET("api/spaces")
    suspend fun spaces(): SpacesResponse

    /** One space's tree, or every visible space when [space] is null. */
    @GET("api/tree")
    suspend fun tree(@Query("space") space: String? = null): TreeResponse

    @GET("api/notes/{id}")
    suspend fun note(@Path("id") id: String): Note

    @GET("api/auth/sessions")
    suspend fun sessions(): SessionsResponse
}
