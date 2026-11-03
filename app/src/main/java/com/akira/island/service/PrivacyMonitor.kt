package com.akira.island.service

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import com.akira.island.core.IslandBus

/**
 * Камера/микрофон сейчас кем-то используются. Камеру видно по недоступности CameraManager,
 * микрофон — по активным AudioRecordingConfiguration. На части прошивок эти API отдают
 * только собственное приложение; тогда зелёная точка просто не появится.
 */
class PrivacyMonitor(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val camera = context.getSystemService(CameraManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val busyCameras = HashSet<String>()
    private var micActive = false
    private var registered = false

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (busyCameras.remove(cameraId)) publish()
        }

        override fun onCameraUnavailable(cameraId: String) {
            if (busyCameras.add(cameraId)) publish()
        }
    }

    private val audioCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val active = configs.isNotEmpty()
            if (active != micActive) {
                micActive = active
                publish()
            }
        }
    }

    fun start() {
        if (registered) return
        registered = true
        runCatching { camera?.registerAvailabilityCallback(cameraCallback, handler) }
        runCatching { audio?.registerAudioRecordingCallback(audioCallback, handler) }
        publish()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { camera?.unregisterAvailabilityCallback(cameraCallback) }
        runCatching { audio?.unregisterAudioRecordingCallback(audioCallback) }
        busyCameras.clear()
        micActive = false
        publish()
    }

    private fun publish() {
        IslandBus.privacyCamera.value = busyCameras.isNotEmpty()
        IslandBus.privacyMic.value = micActive
    }
}
