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
import kotlin.math.abs
import kotlin.math.roundToInt

@Service
class EcgProcessingService(
    private val ecgRepo: EcgRecordingRepository,
    private val medicalInfoRepo: MedicalInfoRepository
) {
    companion object {
        const val DEFAULT_SAMPLE_RATE = 400 // Hz - matching the dataset
        const val DEFAULT_NUM_LEADS = 12 // Standard 12-lead ECG
        const val MILLIVOLTS_PER_BIT = 0.001f // 1e-4V scale mentioned in dataset
        
        // Abnormality types
        val ABNORMALITY_TYPES = listOf("1dAVb", "RBBB", "LBBB", "SB", "AF", "ST")
    }

    /**
     * Process raw ECG data (from uploaded files)
     */
    suspend fun processRawData(inputStream: InputStream, userId: Long): EcgRecording = withContext(Dispatchers.IO) {
        // Read all data from the input stream
        val rawData = inputStream.readAllBytes()

        // Decode the raw data into a multi-lead format (12 leads)
        val decodedData = decodeRawData(rawData)
        
        // Apply filters to clean the signal
        val filteredData = applyFilters(decodedData)
        
        // Calculate metrics for all leads
        val primaryLeadData = filteredData[0] // Using lead I for primary metrics
        val heartRate = calculateHeartRate(primaryLeadData)
        val qrsComplexes = detectQrsComplexes(primaryLeadData)
        
        // Get medical info
        val medicalInfo = medicalInfoRepo.findByUserId(userId)
            ?: throw IllegalArgumentException("No medical info found for user ID $userId")

        // Create a new ECG recording
        val ecgRecording = EcgRecording(
            rawData = rawData,
            processedData = serializeProcessedData(filteredData),
            diagnosis = null, // Will be filled later by analysis or cardiologist
            sampleRate = DEFAULT_SAMPLE_RATE,
            numLeads = DEFAULT_NUM_LEADS,
            heartRate = heartRate,
            qrsComplexes = serializeQrsComplexes(qrsComplexes),
            recordingDate = LocalDateTime.now(),
            medicalInfo = medicalInfo
        )

        // Save the processed ECG data and return the saved entity
        val savedRecording = ecgRepo.save(ecgRecording)
        println("ECG data saved to DB (ID: ${savedRecording.id})")

        savedRecording
    }

    /**
     * Decode raw byte data into multi-lead float arrays
     * Format follows the dataset: 12 leads with samples in mV
     */
    private fun decodeRawData(data: ByteArray): Array<FloatArray> {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        
        // Determine number of samples per lead
        val bytesPerValue = 2 // Assuming 16-bit (2 byte) values
        val totalValues = data.size / bytesPerValue
        val samplesPerLead = totalValues / DEFAULT_NUM_LEADS
        
        // Create multi-lead array structure
        val decodedData = Array(DEFAULT_NUM_LEADS) { FloatArray(samplesPerLead) }
        
        // Read data in lead-major format (all samples for lead 1, then all for lead 2, etc.)
        // This matches the dataset's (827, 4096, 12) format
        for (lead in 0 until DEFAULT_NUM_LEADS) {
            for (sample in 0 until samplesPerLead) {
                val rawValue = buffer.short
                // Convert to millivolts (multiply by 1e-4 as per dataset description)
                decodedData[lead][sample] = rawValue * MILLIVOLTS_PER_BIT
            }
        }
        
        return decodedData
    }

    /**
     * Apply filters to clean the signal for all leads
     */
    private fun applyFilters(data: Array<FloatArray>): Array<FloatArray> {
        val filteredData = Array(data.size) { leadIndex ->
            val leadData = data[leadIndex]
            // Apply baseline filter, low-pass filter, and notch filter
            applyNotchFilter(
                applyLowPassFilter(
                    applyHighPassFilter(leadData)
                )
            )
        }
        return filteredData
    }

    /**
     * High-pass filter to remove baseline wander (typically below 0.5Hz)
     */
    private fun applyHighPassFilter(data: FloatArray): FloatArray {
        val filteredData = FloatArray(data.size)
        val alpha = 0.995f // Filter coefficient
        
        filteredData[0] = data[0]
        for (i in 1 until data.size) {
            filteredData[i] = alpha * (filteredData[i-1] + data[i] - data[i-1])
        }
        
        return filteredData
    }
    
    /**
     * Low-pass filter to remove high-frequency noise
     */
    private fun applyLowPassFilter(data: FloatArray): FloatArray {
        // Simple moving average filter
        val windowSize = 5
        val filteredData = FloatArray(data.size)
        
        for (i in data.indices) {
            var sum = 0f
            var count = 0
            
            for (j in maxOf(0, i - windowSize/2)..minOf(data.size - 1, i + windowSize/2)) {
                sum += data[j]
                count++
            }
            
            filteredData[i] = sum / count
        }
        
        return filteredData
    }
    
    /**
     * Notch filter to remove powerline interference (50/60 Hz)
     */
    private fun applyNotchFilter(data: FloatArray): FloatArray {
        // Simplified notch filter implementation
        // In a real application, consider using a proper DSP library
        return data // Placeholder - implement proper notch filter as needed
    }

    /**
     * Detect QRS complexes in the ECG signal
     */
    private fun detectQrsComplexes(data: FloatArray): List<Map<String, Int>> {
        val qrsComplexes = mutableListOf<Map<String, Int>>()
        val rPeaks = detectRPeaks(data)
        
        for (rPeak in rPeaks) {
            // Find Q and S points around R peak
            val qPoint = findQPoint(data, rPeak)
            val sPoint = findSPoint(data, rPeak)
            
            qrsComplexes.add(mapOf(
                "qPoint" to qPoint,
                "rPeak" to rPeak,
                "sPoint" to sPoint
            ))
        }
        
        return qrsComplexes
    }
    
    /**
     * Find Q point (local minimum before R peak)
     */
    private fun findQPoint(data: FloatArray, rPeak: Int): Int {
        val searchWindow = DEFAULT_SAMPLE_RATE / 10 // 100ms window
        val startIdx = maxOf(0, rPeak - searchWindow)
        
        var minIdx = rPeak
        var minVal = data[rPeak]
        
        for (i in rPeak-1 downTo startIdx) {
            if (data[i] < minVal) {
                minVal = data[i]
                minIdx = i
            } else if (data[i] > data[i+1]) {
                // Found a rising edge, this is our Q point
                break
            }
        }
        
        return minIdx
    }
    
    /**
     * Find S point (local minimum after R peak)
     */
    private fun findSPoint(data: FloatArray, rPeak: Int): Int {
        val searchWindow = DEFAULT_SAMPLE_RATE / 10 // 100ms window
        val endIdx = minOf(data.size - 1, rPeak + searchWindow)
        
        var minIdx = rPeak
        var minVal = data[rPeak]
        
        for (i in rPeak+1..endIdx) {
            if (data[i] < minVal) {
                minVal = data[i]
                minIdx = i
            } else if (i < endIdx && data[i] < data[i+1]) {
                // Found a rising edge, this is our S point
                break
            }
        }
        
        return minIdx
    }

    /**
     * Detect R peaks in the ECG signal using a threshold algorithm
     */
    private fun detectRPeaks(data: FloatArray): List<Int> {
        val rPeaks = mutableListOf<Int>()
        val threshold = calculateThreshold(data)
        val minDistance = DEFAULT_SAMPLE_RATE * 0.3 // Minimum 300ms between peaks
        
        var lastPeakIndex = -minDistance.toInt()
        
        for (i in 1 until data.size - 1) {
            if (i - lastPeakIndex < minDistance) continue
            
            // Check if this point is a local maximum and above threshold
            if (data[i] > threshold && data[i] > data[i-1] && data[i] > data[i+1]) {
                rPeaks.add(i)
                lastPeakIndex = i
            }
        }
        
        return rPeaks
    }
    
    /**
     * Calculate adaptive threshold for R peak detection
     */
    private fun calculateThreshold(data: FloatArray): Float {
        // Simple threshold calculation as a percentage of max amplitude
        var max = 0f
        for (sample in data) {
            if (abs(sample) > max) {
                max = abs(sample)
            }
        }
        
        return 0.6f * max // 60% of max amplitude is a common threshold
    }

    /**
     * Calculate heart rate from the ECG signal
     */
    private fun calculateHeartRate(data: FloatArray): Int {
        if (data.size < DEFAULT_SAMPLE_RATE) {
            return 70 // Default value if not enough data
        }
        
        // Detect R peaks
        val rPeaks = detectRPeaks(data)
        
        if (rPeaks.size < 2) {
            return 70 // Default value if we can't calculate
        }
        
        // Calculate average RR interval in samples
        var totalRRInterval = 0.0
        for (i in 1 until rPeaks.size) {
            totalRRInterval += (rPeaks[i] - rPeaks[i-1])
        }
        
        val avgRRInterval = totalRRInterval / (rPeaks.size - 1)
        
        // Convert to heart rate in BPM
        // HR = 60 * sample_rate / RR_interval
        return (60.0 * DEFAULT_SAMPLE_RATE / avgRRInterval).roundToInt()
    }

    /**
     * Serialize processed data for storage
     */
    private fun serializeProcessedData(data: Array<FloatArray>): String {
        // Format: "lead1:[values];lead2:[values];..."
        return data.mapIndexed { index, leadData ->
            "lead${index + 1}:" + leadData.joinToString(",")
        }.joinToString(";")
    }

    /**
     * Serialize QRS complexes for storage
     */
    private fun serializeQrsComplexes(qrsComplexes: List<Map<String, Int>>): String {
        return qrsComplexes.joinToString(",") { complex ->
            "{" + complex.entries.joinToString(",") { (key, value) ->
                "\"$key\":$value"
            } + "}"
        }
    }

    /**
     * Analyze ECG for possible abnormalities
     * This is a basic implementation - in production would use ML models
     */
    private fun analyzeForAbnormalities(data: Array<FloatArray>): Map<String, Float> {
        val results = mutableMapOf<String, Float>()
        
        // Initialize all abnormality types with zero probability
        ABNORMALITY_TYPES.forEach { abnormality ->
            results[abnormality] = 0.0f
        }
        
        // Basic analysis (in production would use trained ML models)
        val lead2Data = data[1] // Lead II is commonly used for rhythm analysis
        val heartRate = calculateHeartRate(lead2Data)
        
        // Simple rule-based analysis examples:
        // Sinus bradycardia - heart rate < 60 BPM
        if (heartRate < 60) {
            results["SB"] = 0.9f
        }
        
        // Sinus tachycardia - heart rate > 100 BPM
        if (heartRate > 100) {
            results["ST"] = 0.9f
        }
        
        // Other abnormalities would require more complex analysis
        // In a real app, you'd use ML models trained on labeled data
        
        return results
    }
}