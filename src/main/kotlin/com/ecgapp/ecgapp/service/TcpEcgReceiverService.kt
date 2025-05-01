package com.ecgapp.ecgapp.services

import com.ecgapp.ecgapp.models.EcgRecording
import com.ecgapp.ecgapp.models.MedicalInfo
import com.ecgapp.ecgapp.repository.EcgRecordingRepository
import com.ecgapp.ecgapp.repository.MedicalInfoRepository
import kotlinx.coroutines.*
import org.springframework.stereotype.Service
import java.io.InputStream
import java.net.ServerSocket

@Service
class TcpEcgReceiverService(
    private val ecgRepo: EcgRecordingRepository,
    private val medicalInfoRepo: MedicalInfoRepository
) {
    private val port = 9999 // Change to match what ECG device sends to

    init {
        runBlocking {
            launch {
                listenForData()
            }
        }
    }

    suspend fun listenForData() = withContext(Dispatchers.IO) {
        val serverSocket = ServerSocket(port)
        println("TCP Server listening on port $port")

        while (true) {
            val socket = serverSocket.accept()
            println("✅ ECG device connected from ${socket.inetAddress.hostAddress}")

            val input: InputStream = socket.getInputStream()
            val rawData = input.readNBytes(2_000_000) // 2MB packet

            // In this example, let's assume `user_id` is provided or extracted from the device connection
            val userId: Long = 1 // You can extract user ID from device or connection

            // Fetch MedicalInfo for this user
            val medicalInfo = medicalInfoRepo.findByUserId(userId)
                ?: throw IllegalArgumentException("Medical info for user ID $userId not found")

            val ecgRecording = EcgRecording(
                rawData = rawData,
                diagnosis = null,  // Add logic for diagnosis if needed
                sample_rate = 500,
                num_leads = 12,
                heart_rate = calculateHeartRate(rawData), // Replace with actual heart rate calculation
                medicalInfo = medicalInfo
            )

            ecgRepo.save(ecgRecording)
            println("💾 ECG data saved to DB (ID: ${ecgRecording.id})")
        }
    }

    // Dummy heart rate calculation (replace with actual logic)
    private fun calculateHeartRate(rawData: ByteArray): Int {
        // Implement real logic for calculating heart rate from ECG raw data
        return 70
    }
}
