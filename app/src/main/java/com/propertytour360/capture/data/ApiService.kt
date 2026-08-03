package com.propertytour360.capture.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface ApiService {
    @POST("v1/auth/otp/request")
    suspend fun requestOtp(@Body body: OtpRequestBody): OtpRequestResponse

    @POST("v1/auth/otp/verify")
    suspend fun verifyOtp(@Body body: OtpVerifyBody): AuthResponse

    @GET("v1/properties")
    suspend fun listProperties(@Header("Authorization") authorization: String): List<PropertyDto>

    @POST("v1/properties")
    suspend fun createProperty(
        @Header("Authorization") authorization: String,
        @Body body: PropertyCreateBody
    ): PropertyDto

    @POST("v1/properties/{propertyId}/units")
    suspend fun createUnit(
        @Header("Authorization") authorization: String,
        @Path("propertyId") propertyId: String,
        @Body body: UnitCreateBody
    ): UnitDto

    @POST("v1/captures")
    suspend fun createCapture(
        @Header("Authorization") authorization: String,
        @Body body: CaptureCreateBody
    ): CaptureDto

    @GET("v1/captures/{captureId}")
    suspend fun getCapture(
        @Header("Authorization") authorization: String,
        @Path("captureId") captureId: String
    ): CaptureDto

    @POST("v1/captures/{captureId}/rooms")
    suspend fun createRoom(
        @Header("Authorization") authorization: String,
        @Path("captureId") captureId: String,
        @Body body: RoomCreateBody
    ): RoomDto

    @PATCH("v1/captures/{captureId}/rooms/{roomId}")
    suspend fun patchRoom(
        @Header("Authorization") authorization: String,
        @Path("captureId") captureId: String,
        @Path("roomId") roomId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): RoomDto

    @POST("v1/captures/{captureId}/connections")
    suspend fun connectRooms(
        @Header("Authorization") authorization: String,
        @Path("captureId") captureId: String,
        @Body body: ConnectionBody
    ): ConnectionDto

    @POST("v1/captures/{captureId}/assets/upload-url")
    suspend fun requestUploadUrl(
        @Header("Authorization") authorization: String,
        @Path("captureId") captureId: String,
        @Body body: UploadUrlBody
    ): UploadUrlResponse

    @POST("v1/captures/{captureId}/assets/{assetId}/complete")
    suspend fun completeUpload(
        @Header("Authorization") authorization: String,
        @Path("captureId") captureId: String,
        @Path("assetId") assetId: String,
        @Body body: CompleteUploadBody = CompleteUploadBody()
    ): AssetDto

    @POST("v1/captures/{captureId}/rooms/{roomId}/stitch-panorama")
    suspend fun stitchPanorama(
        @Header("Authorization") authorization: String,
        @Path("captureId") captureId: String,
        @Path("roomId") roomId: String,
        @Body body: StitchBody
    ): JobResponse

    @POST("v1/captures/{captureId}/submit")
    suspend fun submitCapture(
        @Header("Authorization") authorization: String,
        @Path("captureId") captureId: String
    ): JobResponse

    @GET("v1/jobs/{jobId}")
    suspend fun getJob(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String
    ): ProcessingJobDto

    @POST("v1/tours")
    suspend fun createTour(
        @Header("Authorization") authorization: String,
        @Body body: TourCreateBody
    ): TourDto

    @POST("v1/tours/{tourId}/hotspots")
    suspend fun createHotspot(
        @Header("Authorization") authorization: String,
        @Path("tourId") tourId: String,
        @Body body: HotspotBody
    )

    @POST("v1/tours/{tourId}/publish")
    suspend fun publishTour(
        @Header("Authorization") authorization: String,
        @Path("tourId") tourId: String
    ): PublishTourResponse

    @POST("v1/design-projects")
    suspend fun createDesignProject(
        @Header("Authorization") authorization: String,
        @Body body: DesignProjectCreateBody
    ): DesignProjectDto

    @POST("v1/design-projects/{projectId}/generate-shell")
    suspend fun generateShell(
        @Header("Authorization") authorization: String,
        @Path("projectId") projectId: String
    ): JobResponse

    /**
     * Registers the manifest + archive pair as a Capture Package v2 and starts
     * server-side checksum validation. Without this call the archive sits in
     * object storage as an unlinked asset and geometry generation has nothing
     * to read, which is why Mode B scans previously never produced a model.
     */
    @POST("v2/captures/{captureId}/packages/finalize")
    suspend fun finalizeCapturePackages(
        @Header("Authorization") authorization: String,
        @Path("captureId") captureId: String,
        @Body body: FinalizePackagesBody
    ): FinalizePackagesResponse

    @POST("v1/design-projects/{projectId}/publish")
    suspend fun publishDesign(
        @Header("Authorization") authorization: String,
        @Path("projectId") projectId: String
    ): PublishDesignResponse
}
