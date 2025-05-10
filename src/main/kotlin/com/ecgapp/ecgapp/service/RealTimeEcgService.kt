package com.ecgapp.ecgapp.service

import com.ecgapp.ecgapp.models.EcgRecording
import com.ecgapp.ecgapp.models.MedicalInfo
import com.ecgapp.ecgapp.repository.MedicalInfoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

@Service
class RealtimeEcgService(
    private val medicalInfoRepo: MedicalInfoRepository
) {
    // Common ECG constants
    companion object {
        const val DEFAULT_SAMPLE_RATE = 400 // Hz - matching the dataset sample rate
        const val DEFAULT_NUM_LEADS = 12 // Standard 12-lead ECG
        const val DEFAULT_DATA_RESOLUTION = 16 // bits
        const val MILLIVOLTS_PER_BIT = 0.001f // Same as EcgProcessingService
        
        // Buffer settings
        const val BUFFER_SIZE = 4000 // 10 seconds of data at 400Hz 
        const val PROCESSING_WINDOW = 200 // Process in chunks of 500ms
        
        // Abnormality types
        val ABNORMALITY_TYPES = listOf("1dAVb", "RBBB", "LBBB", "SB", "AF", "ST")
    }
    
    // Store active sessions by user ID
    private val activeSessions = ConcurrentHashMap<Long, WebSocketSession>()
    
    // Buffer for each user's ECG data - now stores multi-lead data
    private val dataBuffers = ConcurrentHashMap<Long, Array<CircularBuffer<Float>>>()
    
    // Flow for broadcasting processed ECG data
    private val ecgDataFlow = MutableSharedFlow<EcgDataPacket>(replay = 0, extraBufferCapacity = 64)
    
    /**
     * Get the shared flow of ECG data for subscribers
     */
    fun getEcgDataFlow(): Flow<EcgDataPacket> = ecgDataFlow
    
    /**
     * Register a new WebSocket session for a user
     */
    fun registerSession(userId: Long, session: WebSocketSession) {
        activeSessions[userId] = session
        
        // Initialize buffer for all leads
        val multiLeadBuffer = Array(DEFAULT_NUM_LEADS) { 
            CircularBuffer<Float>(BUFFER_SIZE)
        }
        dataBuffers[userId] = multiLeadBuffer
        
        println("Registered WebSocket session for user $userId")
    }

    
    /**
     * Unregister a WebSocket session
     */
    fun unregisterSession(userId: Long) {
        activeSessions.remove(userId)
        dataBuffers.remove(userId)
        println("Unregistered WebSocket session for user $userId")
    }
    
    /**
     * Process incoming raw ECG data
     * @param rawBytes The raw bytes from the ECG device
     * @param userId User ID associated with this data
     */
    suspend fun processIncomingData(rawBytes: ByteArray, userId: Long) = withContext(Dispatchers.IO) {
        val buffers = dataBuffers[userId] ?: return@withContext
        
        // Decode the raw data into multi-lead format
        val decodedData = decodeRawData(rawBytes)
        
        // Add to each lead's buffer
        for (leadIndex in decodedData.indices) {
            val leadData = decodedData[leadIndex]
            for (sample in leadData) {
                buffers[leadIndex].add(sample)
            }
        }
        
        // Process if we have enough data
        if (buffers[0].size() >= PROCESSING_WINDOW) {
            // Extract data from each lead for processing
            val dataToProcess = Array(DEFAULT_NUM_LEADS) { leadIndex ->
                buffers[leadIndex].getLastN(PROCESSING_WINDOW)
            }
            
            // Apply filters to all leads
            val filteredData = applyFilters(dataToProcess)
            
            // Analyze the data for abnormalities
            val abnormalities = analyzeForAbnormalities(dataToProcess)
            
            // Calculate metrics using lead II (commonly used for rhythm analysis)
            val heartRate = calculateHeartRate(buffers[1].getAll())
            
            // Create a data packet
            val packet = EcgDataPacket(
                userId = userId,
                timestamp = System.currentTimeMillis(),
                ecgData = filteredData,
                heartRate = heartRate,
                abnormalities = abnormalities
            )
            
            // Send to WebSocket client
            sendToClient(userId, packet)
            
            // Emit to flow for other subscribers
            ecgDataFlow.emit(packet)
        }
    }

    /**
 * Send an EcgDataPacket directly to a client
 * Used for testing or manual data injection
 */
    suspend fun sendDirectPacket(userId: Long, packet: EcgDataPacket) {
        try {
            // Send to WebSocket client
            sendToClient(userId, packet)

            // Emit to flow for other subscribers
            ecgDataFlow.emit(packet)
        } catch (e: Exception) {
            println("Error sending direct packet: ${e.message}")
            e.printStackTrace()
        }
    }

    
    /**
     * Finalize an ECG recording session
     * @param userId User ID
     * @return The completed ECG recording or null if not enough data
     */
    suspend fun finalizeRecording(userId: Long): EcgRecording? = withContext(Dispatchers.IO) {
        val buffers = dataBuffers[userId] ?: return@withContext null
        
        if (buffers[0].size() < DEFAULT_SAMPLE_RATE) {
            return@withContext null  // Not enough data to create a recording
        }
        
        // Get all buffered data from each lead
        val allData = Array(DEFAULT_NUM_LEADS) { leadIndex ->
            buffers[leadIndex].getAll()
        }
        
        // Apply filters to the entire dataset
        val filteredData = applyFilters(allData)
        
        // Get medical info
        val medicalInfo = medicalInfoRepo.findByUserId(userId)
            ?: throw IllegalArgumentException("No medical info found for user ID $userId")
            
        // Convert multi-lead float data to byte array for storage
        val rawDataBytes = allData.toByteArray()
        
        // Calculate metrics using lead II
        val heartRate = calculateHeartRate(filteredData[1])
        val qrsComplexes = detectQrsComplexes(filteredData[1])
        
        // Analyze for abnormalities
        val abnormalities = analyzeForAbnormalities(filteredData)
        
        // Create diagnosis summary based on abnormalities
        val diagnosis = if (abnormalities.any { it.value > 0.7f }) {
            val highestProb = abnormalities.maxByOrNull { it.value }
            "Possible ${highestProb?.key} detected (${(highestProb?.value ?: 0f) * 100}% probability)"
        } else {
            "No significant abnormalities detected"
        }
        
        // Convert qrsComplexes to JSON string format
        val qrsComplexesJson = qrsComplexesToJson(qrsComplexes)
        
        // Create the ECG recording
        val ecgRecording = EcgRecording(
            rawData = rawDataBytes,
            processedData = serializeProcessedData(filteredData),
            sampleRate = DEFAULT_SAMPLE_RATE,
            numLeads = DEFAULT_NUM_LEADS,
            heartRate = heartRate,
            qrsComplexes = qrsComplexesJson,
            recordingDate = LocalDateTime.now(),
            medicalInfo = medicalInfo,
            diagnosis = diagnosis
        )
        
        // Clear the buffers
        for (buffer in buffers) {
            buffer.clear()
        }
        
        ecgRecording
    }
    
    /**
     * Serialize processed multi-lead data for storage
     */
    private fun serializeProcessedData(data: Array<FloatArray>): String {
        // Format: "lead1:[values];lead2:[values];..."
        return data.mapIndexed { index, leadData ->
            "lead${index + 1}:" + leadData.joinToString(",")
        }.joinToString(";")
    }
    
    /**
     * Convert QRS complex data to JSON string for storage
     */
    private fun qrsComplexesToJson(qrsComplexes: List<Map<String, Int>>): String {
        return qrsComplexes.joinToString(",") { complex ->
            "{" + complex.entries.joinToString(",") { (key, value) ->
                "\"$key\":$value"
            } + "}"
        }
    }
    
    /**
     * Convert float arrays to byte array for storage
     */
    private fun Array<FloatArray>.toByteArray(): ByteArray {
        // Calculate total size needed
        var totalSize = 0
        for (leadData in this) {
            totalSize += leadData.size * 2 // 2 bytes per sample (16-bit)
        }
        
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        // Store in lead-major format (all samples for lead 1, then all for lead 2, etc.)
        for (leadData in this) {
            for (value in leadData) {
                // Convert back to raw ADC value
                val adcValue = (value / MILLIVOLTS_PER_BIT).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                buffer.putShort(adcValue.toShort())
            }
        }
        
        return buffer.array()
    }
    
    /**
     * Send processed data to WebSocket client
     */
    private suspend fun sendToClient(userId: Long, packet: EcgDataPacket) {
        val session = activeSessions[userId] ?: return
        
        if (!session.isOpen) {
            unregisterSession(userId)
            return
        }
        
        // Convert to JSON - optimized for direct use in frontend visualization
        val json = buildString {
            append("{")
            append("\"timestamp\": ${packet.timestamp},")
            append("\"heartRate\": ${packet.heartRate},")
            
            // Add multi-lead data as direct arrays for easier frontend processing
            append("\"leads\": {")
            packet.ecgData.forEachIndexed { leadIndex, leadData ->
                if (leadIndex > 0) append(",")
                // Use lead number as key (1-based for standard ECG naming)
                append("\"${leadIndex + 1}\": [${leadData.joinToString(",")}]")
            }
            append("},")
            
            // Add abnormalities
            append("\"abnormalities\": {")
            packet.abnormalities.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(",")
                append("\"$key\": $value")
            }
            append("}")
            
            append("}")
        }
        
        // Send via WebSocket
        session.sendMessage(TextMessage(json))
    }
    
    /**
     * Decode raw byte data into multi-lead float arrays
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
        for (lead in 0 until DEFAULT_NUM_LEADS) {
            for (sample in 0 until samplesPerLead) {
                val rawValue = buffer.short
                // Convert to millivolts
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
     * Detect R peaks in the ECG signal using a simple threshold algorithm
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
     * Detect QRS complexes from the ECG signal
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
     * Analyze ECG for possible abnormalities
     * This uses basic algorithms for demonstration - a production app would use ML models
     */
    private fun analyzeForAbnormalities(data: Array<FloatArray>): Map<String, Float> {
        val results = mutableMapOf<String, Float>()
        
        // Initialize all abnormality types with zero probability
        ABNORMALITY_TYPES.forEach { abnormality ->
            results[abnormality] = 0.0f
        }
        
        // Basic analysis
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
        
        // For RBBB - look for wide QRS in V1 (lead 7) and RSR' pattern
        val qrsComplexes = detectQrsComplexes(data[1])
        if (qrsComplexes.isNotEmpty()) {
            // Calculate QRS duration
            val averageQrsDuration = qrsComplexes.map { 
                it["sPoint"]!! - it["qPoint"]!! 
            }.average()
            
            // QRS duration > 120ms (48 samples at 400Hz) suggests bundle branch block
            if (averageQrsDuration > (DEFAULT_SAMPLE_RATE * 0.12)) {
                // Look for specific patterns in different leads to differentiate RBBB vs LBBB
                // This is simplified logic - real detection is more complex
                
                // For RBBB: wide S wave in lead I and wide R' in V1
                val leadI = data[0]
                val leadV1 = data[6] // V1 is typically index 6 in 12-lead ECG
                
                var wideS = false
                var rPrimePresent = false
                
                // Very basic detection - would need more sophisticated algorithms
                // for accurate diagnosis
                
                if (wideS && rPrimePresent) {
                    results["RBBB"] = 0.8f
                }
            }
        }
        
        // Check for first degree AV block - PR interval > 200ms
        // Would require more sophisticated algorithms to accurately measure PR interval
        
        // Check for atrial fibrillation - irregular RR intervals, absence of P waves
        val rPeaks = detectRPeaks(lead2Data)
        if (rPeaks.size > 3) {
            val rrIntervals = mutableListOf<Int>()
            for (i in 1 until rPeaks.size) {
                rrIntervals.add(rPeaks[i] - rPeaks[i-1])
            }
            
            // Calculate irregularity (standard deviation / mean)
            val mean = rrIntervals.average()
            val stdDev = rrIntervals.map { (it - mean) * (it - mean) }.average().let { Math.sqrt(it) }
            val irregularity = stdDev / mean
            
            // High irregularity might suggest AF
            if (irregularity > 0.1) {
                results["AF"] = 0.7f
            }
        }
        
        return results
    }
    
    /**
     * Data class for real-time ECG data packets with added abnormality detection
     */
    data class EcgDataPacket(
        val userId: Long,
        val timestamp: Long,
        val ecgData: Array<FloatArray>,
        val heartRate: Int,
        val abnormalities: Map<String, Float> = emptyMap()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EcgDataPacket

            if (userId != other.userId) return false
            if (timestamp != other.timestamp) return false
            if (!ecgData.contentDeepEquals(other.ecgData)) return false
            if (heartRate != other.heartRate) return false
            if (abnormalities != other.abnormalities) return false

            return true
        }

        override fun hashCode(): Int {
            var result = userId.hashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + ecgData.contentDeepHashCode()
            result = 31 * result + heartRate
            result = 31 * result + abnormalities.hashCode()
            return result
        }
    }
    
    /**
     * Circular buffer implementation for efficient storage of streaming data
     */
    class CircularBuffer<T>(private val capacity: Int) {
        private val buffer = ArrayList<T>(capacity)
        private var head = 0
        private var size = 0
        
        fun add(item: T) {
            if (size < capacity) {
                buffer.add(item)
                size++
            } else {
                buffer[head] = item
                head = (head + 1) % capacity
            }
        }
        
        fun getAll(): FloatArray {
            val result = FloatArray(size)
            for (i in 0 until size) {
                val index = (head + i) % capacity
                result[i] = buffer[index] as Float
            }
            return result
        }
        
        fun getLastN(n: Int): FloatArray {
            val count = minOf(n, size)
            val result = FloatArray(count)
            
            for (i in 0 until count) {
                val index = (head + size - count + i) % capacity
                result[i] = buffer[index] as Float
            }
            
            return result
        }
        
        fun size(): Int = size
        
        fun clear() {
            buffer.clear()
            head = 0
            size = 0
        }
    }
}