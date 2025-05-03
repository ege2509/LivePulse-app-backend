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
import java.time.LocalDateTime

@Service
class EcgProcessingService(
    private val ecgRepo: EcgRecordingRepository,
    private val medicalInfoRepo: MedicalInfoRepository
) {

    suspend fun processRawData(inputStream: InputStream, userId: Long): EcgRecording = withContext(Dispatchers.IO) {
        // Read the raw ECG data
        val rawData = inputStream.readNBytes(2_000_000) // Read a 2MB packet
        
        // Decode the raw data into an IntArray
        val decodedData = decodeRawData(rawData)
        
        // Apply any necessary filtering or processing to the data
        val filteredData = applyFilter(decodedData)
        
        // Process the data to find QRS complexes (optional)
        val qrsComplexes = detectQrsComplexes(filteredData)
        
        // Calculate heart rate
        val heartRate = calculateHeartRate(filteredData)
        
        // Get medical info
        val medicalInfo = medicalInfoRepo.findByUserId(userId)
            ?: throw IllegalArgumentException("No medical info found for user ID $userId")
        
        // Create a new ECG recording with the updated model
        val ecgRecording = EcgRecording(
            rawData = rawData,
            processedData = filteredData.joinToString(","),
            diagnosis = null,
            sampleRate = 500,
            numLeads = 12,
            heartRate = heartRate,
            qrsComplexes = qrsComplexes.joinToString(","),
            recordingDate = LocalDateTime.now(),
            medicalInfo = medicalInfo
        )
            
        // Save the processed ECG data and return the saved entity
        val savedRecording = ecgRepo.save(ecgRecording)
        println("ECG data saved to DB (ID: ${savedRecording.id})")
        
        savedRecording
    }

    // Function to decode raw byte data into IntArray
    private fun decodeRawData(data: ByteArray): IntArray {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val arr = IntArray(data.size / 2)
        for (i in arr.indices) {
            arr[i] = buffer.short.toInt() // Adjust to match your encoding (short to Int)
        }
        return arr
    }

    // Apply filtering to clean or smooth the ECG data
    private fun applyFilter(data: IntArray): IntArray {
        // Example: Apply some smoothing or filtering algorithm here
        return data // You can replace this with actual filter logic
    }

    // Detect QRS complexes in the filtered data
    private fun detectQrsComplexes(filteredData: IntArray): List<Int> {
        // This is a placeholder. In a real app, you would implement
        // an algorithm to detect QRS complexes (peaks) in the ECG signal
        return listOf() // Return empty list for now
    }

    // Calculate heart rate based on filtered data
    private fun calculateHeartRate(filteredData: IntArray): Int {
        // Example: Just a dummy heart rate value for now
        return 70  // Replace with actual heart rate calculation logic
    }
}