/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.filament.hellocam

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.LinearGradient
import android.hardware.camera2.*
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.content.ContextCompat
import android.util.Log
import android.util.Size
import android.view.Surface

import android.Manifest
import android.graphics.*
import android.media.ImageReader
import android.opengl.Matrix
import android.view.Display

import com.google.android.filament.*

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit



/**
 * Toy class that handles all interaction with the Android camera2 API.
 * Sets the "textureTransform" and "videoTexture" parameters on the given Filament material.
 */
class CameraHelper(val activity: Activity, private val filamentEngine: Engine, private val filamentMaterial: MaterialInstance, private val display: Display) {
    private lateinit var cameraId: String
    private lateinit var captureRequest: CaptureRequest
    private val cameraOpenCloseLock = Semaphore(1)
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var resolution = Size(640, 480)
    private var surfaceTexture: SurfaceTexture? = null
    private val streamSource = StreamSource.CPU_TEST_STREAM_SURFACE
//    private val streamSource = StreamSource.CPU_TEST_EXTERNAL_IMAGE
    //private val streamSource = StreamSource.CPU_TEST_STREAM_TEXID
    private var imageReader: ImageReader? = null
    private var frameNumber = 0L
    var uvOffset = 0.0f
        private set

    private var canvasSurface: Surface? = null

    private val kGradientSpeed = 20
    private val kGradientCount = 5
    private val kGradientColors = intArrayOf(
            Color.RED, Color.RED,
            Color.WHITE, Color.WHITE,
            Color.GREEN, Color.GREEN,
            Color.WHITE, Color.WHITE,
            Color.BLUE, Color.BLUE)
    private val kGradientStops = floatArrayOf(
            0.0f, 0.1f,
            0.1f, 0.5f,
            0.5f, 0.6f,
            0.6f, 0.9f,
            0.9f, 1.0f)

    enum class StreamSource {
        CAMERA_FEED_STREAM_SURFACE,
        CPU_TEST_STREAM_SURFACE,
        CPU_TEST_STREAM_TEXID,
        CPU_TEST_EXTERNAL_IMAGE,
    }

    private val cameraCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@CameraHelper.cameraDevice = cameraDevice
            createCaptureSession()
        }
        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@CameraHelper.cameraDevice = null
        }
        override fun onError(cameraDevice: CameraDevice, error: Int) {
            onDisconnected(cameraDevice)
            this@CameraHelper.activity.finish()
        }
    }

    /**
     * Finds the front-facing Android camera, requests permission, and sets up a listener that will
     * start a capture session as soon as the camera is ready.
     */
    fun openCamera() {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (cameraDirection != null && cameraDirection == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }

                this.cameraId = cameraId
                Log.i(kLogTag, "Selected camera $cameraId.")

                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                resolution = map.getOutputSizes(SurfaceTexture::class.java)[0]
                Log.i(kLogTag, "Highest resolution is $resolution.")
            }
        } catch (e: CameraAccessException) {
            Log.e(kLogTag, e.toString())
        } catch (e: NullPointerException) {
            Log.e(kLogTag, "Camera2 API is not supported on this device.")
        }

        val permission = ContextCompat.checkSelfPermission(this.activity, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), kRequestCameraPermission)
            return
        }
        if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("Time out waiting to lock camera opening.")
        }
        manager.openCamera(cameraId, cameraCallback, backgroundHandler)
    }

    fun repaintCanvas() {
        val kGradientScale = resolution.width.toFloat() / kGradientCount
        val kGradientOffset = (frameNumber.toFloat() * kGradientSpeed) % resolution.width
        val surface = canvasSurface
        if (surface != null) {
            val canvas = surface.lockCanvas(null)

            val movingPaint = Paint()
            movingPaint.shader = LinearGradient(kGradientOffset, 0.0f, kGradientOffset + kGradientScale, 0.0f, kGradientColors, kGradientStops, Shader.TileMode.REPEAT)
            canvas.drawRect(Rect(0, resolution.height / 2, resolution.width, resolution.height), movingPaint)

            val staticPaint = Paint()
            staticPaint.shader = LinearGradient(0.0f, 0.0f, kGradientScale, 0.0f, kGradientColors, kGradientStops, Shader.TileMode.REPEAT)
            canvas.drawRect(Rect(0, 0, resolution.width, resolution.height / 2), staticPaint)

            surface.unlockCanvasAndPost(canvas)
        }

        frameNumber++
        uvOffset = 1.0f - kGradientOffset / resolution.width.toFloat()
    }

    fun onResume() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper!!)
    }

    fun onPause() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(kLogTag, e.toString())
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode == kRequestCameraPermission) {
            if (grantResults.size != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Log.e(kLogTag, "Unable to obtain camera position.")
            }
            return true
        }
        return false
    }

    private fun createCaptureSession() {

        // Create the Filament Texture and Sampler objects.
        val filamentTexture = Texture.Builder()
                .sampler(Texture.Sampler.SAMPLER_EXTERNAL)
                .format(Texture.InternalFormat.RGB8)
                .build(filamentEngine)

        val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.REPEAT)

        // We are texturing a front-facing square shape so we need to generate a matrix that transforms (u, v, 0, 1)
        // into a new UV coordinate according to the screen rotation and the aspect ratio of the camera image.
        val aspectRatio = resolution.width.toFloat() / resolution.height.toFloat()
        val textureTransform = FloatArray(16)
        Matrix.setIdentityM(textureTransform, 0)
        when (display.rotation) {
            Surface.ROTATION_0 -> {
                Matrix.translateM(textureTransform, 0, 1.0f, 0.0f, 0.0f)
                Matrix.rotateM(textureTransform, 0, 90.0f, 0.0f, 0.0f, 1.0f)
                Matrix.translateM(textureTransform, 0, 1.0f, 0.0f, 0.0f)
                Matrix.scaleM(textureTransform, 0, -1.0f, 1.0f / aspectRatio, 1.0f)
            }
            Surface.ROTATION_90 -> {
                Matrix.translateM(textureTransform, 0, 1.0f, 1.0f, 0.0f)
                Matrix.rotateM(textureTransform, 0, 180.0f, 0.0f, 0.0f, 1.0f)
                Matrix.translateM(textureTransform, 0, 1.0f, 0.0f, 0.0f)
                Matrix.scaleM(textureTransform, 0, -1.0f / aspectRatio, 1.0f, 1.0f)
            }
            Surface.ROTATION_270 -> {
                Matrix.translateM(textureTransform, 0, 1.0f, 0.0f, 0.0f)
                Matrix.scaleM(textureTransform, 0, -1.0f / aspectRatio, 1.0f, 1.0f)
            }
        }

        // Connect the Stream to the Texture and the Texture to the MaterialInstance.
        filamentMaterial.setParameter("videoTexture", filamentTexture, sampler)
        filamentMaterial.setParameter("textureTransform", MaterialInstance.FloatElement.MAT4, textureTransform, 0, 1)

        // Start the capture session.
        if (streamSource == StreamSource.CAMERA_FEED_STREAM_SURFACE) {

            // [Re]create the Android surface that will hold the camera image.
            surfaceTexture?.release()
            surfaceTexture = SurfaceTexture(0)
            surfaceTexture!!.setDefaultBufferSize(resolution.width, resolution.height)
            surfaceTexture!!.detachFromGLContext()
            val surface = Surface(surfaceTexture)

            // Create the Filament Stream object that gets bound to the Texture.
            val filamentStream = Stream.Builder()
                    .stream(surfaceTexture!!)
                    .build(filamentEngine)

            filamentTexture.setExternalStream(filamentEngine, filamentStream)

            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            captureRequestBuilder.addTarget(surface)

            cameraDevice?.createCaptureSession(listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            if (cameraDevice == null) return
                            captureSession = cameraCaptureSession
                            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            captureRequest = captureRequestBuilder.build()
                            captureSession!!.setRepeatingRequest(captureRequest, null, backgroundHandler)
                            Log.i(kLogTag, "Created CaptureRequest.")
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(kLogTag, "onConfigureFailed")
                        }
                    }, null)
        }

        if (streamSource == StreamSource.CPU_TEST_STREAM_SURFACE) {

            // [Re]create the Android surface that will hold the canvas image.
            surfaceTexture?.release()
            surfaceTexture = SurfaceTexture(0)
            surfaceTexture!!.setDefaultBufferSize(resolution.width, resolution.height)
            surfaceTexture!!.detachFromGLContext()
            canvasSurface = Surface(surfaceTexture)

            // Create the Filament Stream object that gets bound to the Texture.
            val filamentStream = Stream.Builder()
                    .stream(surfaceTexture!!)
                    .build(filamentEngine)

            filamentTexture.setExternalStream(filamentEngine, filamentStream)

            frameNumber = 0
            repaintCanvas()
        }

        if (streamSource == StreamSource.CPU_TEST_STREAM_TEXID) {

            // TODO: This does not work, we need an active GL context.
            val kTextureId = 42

            // [Re]create the Android surface that will hold the canvas image.
            surfaceTexture?.release()
            surfaceTexture = SurfaceTexture(kTextureId)
            surfaceTexture!!.setDefaultBufferSize(resolution.width, resolution.height)
            surfaceTexture!!.detachFromGLContext()
            canvasSurface = Surface(surfaceTexture)

            // Create the Filament Stream object that gets bound to the Texture.
            val filamentStream = Stream.Builder()
                    .stream(kTextureId.toLong())
                    .width(resolution.width)
                    .height(resolution.height)
                    .build(filamentEngine)

            filamentTexture.setExternalStream(filamentEngine, filamentStream)

            frameNumber = 0
            repaintCanvas()
        }

        if (streamSource == StreamSource.CPU_TEST_EXTERNAL_IMAGE) {
            val imageReader = ImageReader.newInstance(resolution.width, resolution.height, kImageReaderMaxImages, ImageFormat.PRIVATE) // , HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            this.imageReader = imageReader

            // The eglImageOES is passed down as a void* all the way to OpenGLDriver::setExternalImage where it is cast to GLeglImageOES as per GL_OES_EGL_image.
            // val surface = imageReader.surface
            // val image = imageReader.acquireLatestImage()
            // val hwbuffer: HardwareBuffer = image.hardwareBuffer!!
            // TODO: On every frame (i.e. in repaintCanvas), do: imageReader.acquireLatestImage => HardwareBuffer => GLeglImageOES
            // filamentTexture.setExternalImage(filamentEngine, null) // eglImageOES)
        }
    }

    companion object {
        private const val kLogTag = "CameraHelper"
        private const val kRequestCameraPermission = 1
        private const val kImageReaderMaxImages = 3
    }

}
