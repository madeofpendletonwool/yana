package com.collinpendleton.yana.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
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

    /**
     * The flat note list the offline replica syncs from: every visible
     * note with its metadata and tags, or just one space's when given.
     */
    @GET("api/notes")
    suspend fun notes(@Query("space") space: String? = null): NotesResponse

    @GET("api/notes/{id}")
    suspend fun note(@Path("id") id: String): Note

    /** Full-text search with the operator grammar, the server's half. */
    @GET("api/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("space") space: String? = null,
        @Query("limit") limit: Int = 50,
    ): SearchResponse

    @POST("api/notes")
    suspend fun createNote(@Body body: CreateNoteRequest): CreateNoteResponse

    @POST("api/notes/{id}/move")
    suspend fun moveNote(@Path("id") id: String, @Body body: MoveRequest): retrofit2.Response<Unit>

    @PUT("api/notes/{id}/source")
    suspend fun putSource(@Path("id") id: String, @Body body: SourceSave): retrofit2.Response<Unit>

    @GET("api/auth/sessions")
    suspend fun sessions(): SessionsResponse
}
