package com.ecgapp.ecgapp.services

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
        const val DEFAULT_SAMPLE_RATE = 500 // Hz
        const val DEFAULT_NUM_LEADS = 12
        const val DEFAULT_DATA_RESOLUTION = 16 // bits
        const val MILLIVOLTS_PER_BIT = 0.00488f // For a typical 16-bit ADC with ±8mV range
        
        // Buffer settings
        const val BUFFER_SIZE = 5000 // 10 seconds of data at 500Hz
        const val PROCESSING_WINDOW = 250 // Process in chunks of 500ms
    }
    
    // Store active sessions by user ID
    private val activeSessions = ConcurrentHashMap<Long, WebSocketSession>()
    
    // Buffer for each user's ECG data
    private val dataBuffers = ConcurrentHashMap<Long, CircularBuffer<Float>>()
    
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
        dataBuffers[userId] = CircularBuffer(BUFFER_SIZE)
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
        val buffer = dataBuffers[userId] ?: return@withContext
        
        // Decode the raw data
        val decodedSamples = decodeRawData(rawBytes)
        
        // Add to buffer
        for (sample in decodedSamples) {
            buffer.add(sample)
        }
        
        // Process if we have enough data
        if (buffer.size() >= PROCESSING_WINDOW) {
            val dataToProcess = buffer.getLastN(PROCESSING_WINDOW)
            
            // Apply filters to the processing window
            val filteredData = applyFilters(dataToProcess)
            
            // Create a data packet
            val heartRate = calculateHeartRate(buffer.getAll())
            val packet = EcgDataPacket(
                userId = userId,
                timestamp = System.currentTimeMillis(),
                ecgData = filteredData,
                heartRate = heartRate
            )
            
            // Send to WebSocket client
            sendToClient(userId, packet)
            
            // Emit to flow for other subscribers
            ecgDataFlow.emit(packet)
        }
    }
    
    /**
     * Finalize an ECG recording session
     * @param userId User ID
     * @return The completed ECG recording or null if not enough data
     */
    suspend fun finalizeRecording(userId: Long): EcgRecording? = withContext(Dispatchers.IO) {
        val buffer = dataBuffers[userId] ?: return@withContext null
        
        if (buffer.size() < DEFAULT_SAMPLE_RATE) {
            return@withContext null  // Not enough data to create a recording
        }
        
        // Get all buffered data
        val allData = buffer.getAll() 
        
        // Apply filters to the entire dataset
        val filteredData = applyFilters(allData)
        
        // Get medical info
        val medicalInfo = medicalInfoRepo.findByUserId(userId)
            ?: throw IllegalArgumentException("No medical info found for user ID $userId")
            
        // Convert float data to byte array for storage
        val rawDataBytes = allData.toByteArray()
        
        // Calculate metrics
        val heartRate = calculateHeartRate(filteredData)
        val qrsComplexes = detectQrsComplexes(filteredData)
        
        // Convert qrsComplexes to JSON string format
        val qrsComplexesJson = qrsComplexesToJson(qrsComplexes)
        
        // Create the ECG recording
        val ecgRecording = EcgRecording(
            rawData = rawDataBytes,
            processedData = filteredData.joinToString(","),
            sampleRate = DEFAULT_SAMPLE_RATE,
            numLeads = 1, // Assuming single-lead for real-time monitoring
            heartRate = heartRate,
            qrsComplexes = qrsComplexesJson,
            recordingDate = LocalDateTime.now(),
            medicalInfo = medicalInfo,
            diagnosis = null
            // warnings will be initialized as an empty list from the default parameter
        )
        
        // Clear the buffer
        buffer.clear()
        
        ecgRecording
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
     * Convert float array to byte array for storage
     */
    private fun FloatArray.toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(this.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        for (value in this) {
            // Convert back to raw ADC value
            val adcValue = (value / MILLIVOLTS_PER_BIT).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer.putShort(adcValue.toShort())
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
        
        // Convert to JSON
        val json = """
            {
                "timestamp": ${packet.timestamp},
                "data": [${packet.ecgData.joinToString(",")}],
                "heartRate": ${packet.heartRate}
            }
        """.trimIndent()
        
        // Send via WebSocket
        session.sendMessage(TextMessage(json))
    }
    
    /**
     * Decode raw byte data into an array of voltage values
     */
    private fun decodeRawData(data: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val result = FloatArray(data.size / 2)
        
        for (i in result.indices) {
            // Convert from short (2 bytes) to actual voltage in millivolts
            val rawValue = buffer.short
            result[i] = rawValue * MILLIVOLTS_PER_BIT
        }
        
        return result
    }
    
    /**
     * Apply common ECG filters to clean the signal
     */
    private fun applyFilters(data: FloatArray): FloatArray {
        return applyNotchFilter(
            applyLowPassFilter(
                applyHighPassFilter(data)
            )
        )
    }
    
    /**
     * High-pass filter to remove baseline wander (typically below 0.5Hz)
     */
    private fun applyHighPassFilter(data: FloatArray): FloatArray {
        // Simple high-pass filter implementation
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
            // Simple approach: estimate Q and S points around R peak
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
     * Data class for real-time ECG data packets
     */
    data class EcgDataPacket(
        val userId: Long,
        val timestamp: Long,
        val ecgData: FloatArray,
        val heartRate: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EcgDataPacket

            if (userId != other.userId) return false
            if (timestamp != other.timestamp) return false
            if (!ecgData.contentEquals(other.ecgData)) return false
            if (heartRate != other.heartRate) return false

            return true
        }

        override fun hashCode(): Int {
            var result = userId.hashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + ecgData.contentHashCode()
            result = 31 * result + heartRate
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