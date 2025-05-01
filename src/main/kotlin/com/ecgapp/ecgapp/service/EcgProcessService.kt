package com.ecgapp.ecgapp.services

import com.ecgapp.ecgapp.models.EcgRecording
import com.ecgapp.ecgapp.models.MedicalInfo
import com.ecgapp.ecgapp.repository.EcgRecordingRepository
import com.ecgapp.ecgapp.repository.MedicalInfoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate

@Service
class EcgProcessingService(
    private val ecgRepo: EcgRecordingRepository,
    private val medicalInfoRepo: MedicalInfoRepository
) {
/* 
    suspend fun processRawData(inputStream: InputStream, userId: Long) {
        // Read the raw ECG data
        val rawData = inputStream.readNBytes(2_000_000) // Read a 2MB packet
        
        // Decode the raw data into an IntArray
        val decodedData = decodeRawData(rawData)

        // Apply any necessary filtering or processing to the data
        val filteredData = applyFilter(decodedData)

        // Create a new ECG recording and save it to the database
        val dummyMedicalInfo = medicalInfoRepo.findByUserId(userId)
        ?: throw IllegalArgumentException("No medical info found for user ID $userId")

        val ecgRecording = EcgRecording(
            rawData = rawData,
            diagnosis = null,  // You can add logic here to set the diagnosis
            sample_rate = 500,
            num_leads = 12,
            heart_rate = calculateHeartRate(filteredData),  // Example: Calculate heart rate
            medicalInfo = dummyMedicalInfo
        )
        
        // Save the processed ECG data
        ecgRepo.save(ecgRecording)
        println("ECG data saved to DB (ID: ${ecgRecording.id})")
    }

    // Function to decode raw byte data into IntArray (or FloatArray)
    private fun decodeRawData(data: ByteArray): IntArray {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val arr = IntArray(data.size / 2)
        for (i in arr.indices) {
            arr[i] = buffer.short.toInt() // Adjust to match your encoding (short to Int)
        }
        return arr
    }

    // Optional: Apply filtering to clean or smooth the ECG data
    private fun applyFilter(data: IntArray): IntArray {
        // Example: Apply some smoothing or filtering algorithm here
        return data // You can replace this with actual filter logic
    }

    // Example: Calculate heart rate (this can be replaced with a more sophisticated algorithm)
    private fun calculateHeartRate(filteredData: IntArray): Int {
        // Example: Just a dummy heart rate value for now
        return 70  // Replace with actual heart rate calculation logic
    }*/
}
