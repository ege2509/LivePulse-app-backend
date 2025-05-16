package com.ecgapp.ecgapp.service

import com.ecgapp.ecgapp.models.EcgRecording
import com.ecgapp.ecgapp.models.MedicalInfo
import com.ecgapp.ecgapp.repository.MedicalInfoRepository
import com.ecgapp.ecgapp.service.TcpEcgReceiverService
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.json.JSONArray
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import com.ecgapp.ecgapp.service.EcgMessagePublisher
import kotlin.math.abs
import kotlin.math.roundToInt
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher

@Service
class RealtimeEcgService(
    private val medicalInfoRepo: MedicalInfoRepository,
    @Autowired private val eventPublisher: ApplicationEventPublisher,
    @Autowired private val ecgMessagePublisher: EcgMessagePublisher
) {
    // Other properties remain the same...
    private val logger = LoggerFactory.getLogger(RealtimeEcgService::class.java)
    private val objectMapper = ObjectMapper()

    private val recordingBuffers = ConcurrentHashMap<Long, Array<ArrayList<Float>>>()
    
    // Dependency injection for observers (replacing direct EcgTestController reference)
    private val diagnosticObservers = mutableListOf<EcgDiagnosticObserver>()
    
    // Register an observer
    fun registerDiagnosticObserver(observer: EcgDiagnosticObserver) {
        diagnosticObservers.add(observer)
    }
    
    // Remove an observer
    fun unregisterDiagnosticObserver(observer: EcgDiagnosticObserver) {
        diagnosticObservers.remove(observer)
    }
    
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
    
    
    // Buffer for each user's ECG data - now stores multi-lead data
    private val dataBuffers = ConcurrentHashMap<Long, Array<CircularBuffer<Float>>>()
    
    // Flow for broadcasting processed ECG data
    private val ecgDataFlow = MutableSharedFlow<EcgDataPacket>(replay = 0, extraBufferCapacity = 64)
    
    /**
     * Get the shared flow of ECG data for subscribers
     */
    fun getEcgDataFlow(): Flow<EcgDataPacket> = ecgDataFlow
    


    suspend fun processData(ecgData: Array<FloatArray>, userId: Long) = withContext(Dispatchers.IO) {
        // Initialize buffer if needed
        if (!dataBuffers.containsKey(userId)) {
            val buffers = Array(DEFAULT_NUM_LEADS) { CircularBuffer<Float>(BUFFER_SIZE) }
            dataBuffers[userId] = buffers
            
            // Initialize recording buffer
            recordingBuffers[userId] = Array(DEFAULT_NUM_LEADS) { ArrayList<Float>() }
        }
        
        val buffers = dataBuffers[userId] ?: return@withContext
        val recording = recordingBuffers[userId] ?: return@withContext
        
        // Keep track of how many new samples we've added
        var samplesAdded = 0
        
        // Add data to each lead's buffer and recording
        for (leadIndex in ecgData.indices) {
            val leadData = ecgData[leadIndex]
            samplesAdded = leadData.size // Assuming all leads have same size
            
            for (sample in leadData) {
                // Add to circular buffer for real-time processing
                buffers[leadIndex].add(sample)
                
                // Add to recording buffer for storage
                recording[leadIndex].add(sample)
                
                // Limit recording size
                val maxRecordingSize = 5 * 60 * DEFAULT_SAMPLE_RATE
                if (recording[leadIndex].size > maxRecordingSize) {
                    recording[leadIndex].removeAt(0)
                }
            }
        }
        
        // CRITICAL FIX: Only process if we have new data to process
        if (samplesAdded > 0) {
            // Extract ONLY THE NEW data points that were just added
            val dataToProcess = Array(DEFAULT_NUM_LEADS) { leadIndex ->
                // Get only the most recent samples we just added
                val recentData = FloatArray(samplesAdded)
                val buffer = buffers[leadIndex]
                val allData = buffer.getAll()
                
                // Copy just the newest samples
                for (i in 0 until samplesAdded) {
                    val index = allData.size - samplesAdded + i
                    if (index >= 0 && index < allData.size) {
                        recentData[i] = allData[index]
                    }
                }
                recentData
            }
            
            // Apply filters to the new data segment
            // This is a key enhancement to match finalizeRecording's approach
            val filteredDataToProcess = applyFilters(dataToProcess)
            
            // Get a larger context window for better analysis
            val contextData = Array(DEFAULT_NUM_LEADS) { leadIndex ->
                buffers[leadIndex].getLastN(PROCESSING_WINDOW)
            }
            
            // Apply filters to context data for more accurate analysis
            val filteredContextData = applyFilters(contextData)
            
            // Analyze for abnormalities using the filtered context data
            val abnormalities = analyzeForAbnormalities(filteredContextData)
            
            // Calculate heart rate using filtered data for better accuracy
            val heartRate = calculateHeartRate(filteredContextData[1])
            
            // Create a data packet with ONLY THE NEW DATA
            // Keep the packet structure exactly the same as before
            val packet = EcgDataPacket(
                userId = userId,
                timestamp = System.currentTimeMillis(),
                ecgData = filteredDataToProcess, // Use filtered data instead of raw
                heartRate = heartRate,
                abnormalities = abnormalities
            )
            
            // Send to WebSocket client
            sendToClient(userId, packet)
            
            // Emit to flow for other subscribers
            ecgDataFlow.emit(packet)
            
            // Log detailed metrics for debugging/monitoring (optional)
            if (logger.isDebugEnabled()) {
                logDetailedMetrics(userId, filteredContextData, heartRate, abnormalities)
            }
        }
    }

    // New private helper method for logging detailed metrics
    private fun logDetailedMetrics(userId: Long, filteredData: Array<FloatArray>, heartRate: Int, abnormalities: Map<String, Float>) {
        // This doesn't change your code structure but gives you more insight
        try {
            val leadII = filteredData[1] // Most commonly used for rhythm analysis
            
            // Calculate signal quality
            val noiseLevel = calculateNoiseLevel(leadII)
            val signalQuality = if (noiseLevel < 0.05f) "Excellent" 
                            else if (noiseLevel < 0.1f) "Good"
                            else if (noiseLevel < 0.2f) "Fair"
                            else "Poor"
            
            // Detect QRS complexes for additional metrics
            val qrsComplexes = detectQrsComplexes(leadII)
            
            // Calculate average QRS duration
            val avgQrsDuration = if (qrsComplexes.isNotEmpty()) {
                val totalDuration = qrsComplexes.sumOf { 
                    (it["sPoint"] ?: 0) - (it["qPoint"] ?: 0) 
                }
                (totalDuration.toFloat() / qrsComplexes.size / DEFAULT_SAMPLE_RATE * 1000).toInt()
            } else 0
            
            // Most concerning abnormality
            val topAbnormality = abnormalities.entries
                .filter { it.value > 0.5f }
                .maxByOrNull { it.value }
            
            // Log the detailed information
            logger.debug("""
                User $userId ECG Metrics:
                - Heart Rate: $heartRate BPM
                - Signal Quality: $signalQuality (noise: ${noiseLevel.format(2)})
                - QRS Complexes: ${qrsComplexes.size} detected
                - Avg QRS Duration: ${avgQrsDuration}ms
                - Top Abnormality: ${topAbnormality?.key ?: "None"} (${topAbnormality?.value?.times(100)?.format(1)}%)
            """.trimIndent())
        } catch (e: Exception) {
            logger.error("Error logging detailed metrics", e)
        }
    }

    // Helper function to calculate noise level
    private fun calculateNoiseLevel(data: FloatArray): Float {
        if (data.size < 10) return 0f
        
        var sum = 0f
        for (i in 2 until data.size) {
            // Second derivative approximation (rate of change of slope)
            val secondDerivative = data[i] - 2 * data[i-1] + data[i-2]
            sum += abs(secondDerivative)
        }
        
        return sum / (data.size - 2)
    }

    // Helper extension function for Float formatting
    private fun Float.format(digits: Int): String = String.format("%.${digits}f", this)
    
    /**
     * Send ECG data to a specific user
     */
    suspend fun sendDirectPacket(userId: Long, packet: EcgDataPacket) {
        try {
            val jsonMessage = objectMapper.writeValueAsString(packet)
            ecgMessagePublisher.publishToUser(userId, jsonMessage)
            
            // Notify observers about successful send
            diagnosticObservers.forEach { it.updateConnectionStats(userId, packet) }
        } catch (e: Exception) {
            logger.error("Error sending data to user $userId: ${e.message}")
            throw e
        }
    }

    

    suspend fun finalizeRecording(userId: Long): EcgRecording? = withContext(Dispatchers.IO) {
        // Use the recording buffer that's been collecting data during the session
        val recording = recordingBuffers[userId] ?: return@withContext null
        
        // Check if we have enough data to create a recording
        if (recording.isEmpty() || recording[0].size < DEFAULT_SAMPLE_RATE) {
            logger.warn("Not enough data to create a recording for user $userId")
            return@withContext null
        }
        
        logger.info("Finalizing recording for user $userId with ${recording[0].size} samples")
        
        // Convert ArrayList<Float> to FloatArray for each lead
        val allData = Array(DEFAULT_NUM_LEADS) { leadIndex ->
            recording[leadIndex].toFloatArray()
        }
        
        // Apply filters to the entire dataset
        val filteredData = applyFilters(allData)
        
        // Get medical info
        val medicalInfo = medicalInfoRepo.findByUserId(userId)
            ?: return@withContext null 
            
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
        
        // Extract max and min values for each lead and store them in separate arrays
        val maxValues = FloatArray(filteredData.size) { leadIndex ->
            filteredData[leadIndex].maxOrNull() ?: 1f
        }
        
        val minValues = FloatArray(filteredData.size) { leadIndex ->
            filteredData[leadIndex].minOrNull() ?: -1f
        }
        
        // Use the 8-bit encoding function for efficient storage
        val rawDataBytes = encodeEcgTo8Bit(filteredData)
        
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
            diagnosis = diagnosis,
            maxValues = maxValues,  // Store max values for decoding
            minValues = minValues,  // Store min values for decoding
            numSamples = filteredData[0].size // Store sample count for decoding
        )
        
        // Reset the recording buffer for this user but keep it initialized
        recordingBuffers[userId] = Array(DEFAULT_NUM_LEADS) { ArrayList<Float>() }
        
        ecgRecording
    }
    
    /**
     * Retrieve encoded ECG data and decode it back to Array<FloatArray>
     */
    fun retrieveAndDecodeEcg(recording: EcgRecording): Array<FloatArray>? {
        val maxValues = recording.maxValues ?: return null
        val minValues = recording.minValues ?: return null
        
        // Create maxMins list for the decoder in the format it expects
        val maxMinsList = List(recording.numLeads) { leadIndex ->
            Pair(maxValues[leadIndex], minValues[leadIndex])
        }
        
        // Decode using the existing function
        return decodeEcgFrom8Bit(
            recording.rawData,
            recording.numLeads,
            recording.numSamples,
            maxMinsList
        )
    }
    /**
     * Convert max/min values to JSON string for storage
     */
    private fun maxMinsToJson(maxMins: List<Pair<Float, Float>>): String {
        return maxMins.joinToString(",") { (max, min) ->
            "{\"max\":$max,\"min\":$min}"
        }
    }
    
    /**
     * Parse max/min values from JSON string
     */
    private fun parseMaxMinsFromJson(json: String): List<Pair<Float, Float>> {
        // Simple parsing - in production use a proper JSON parser
        return json.split(",").map { pairStr ->
            val max = pairStr.substringAfter("\"max\":").substringBefore(",").toFloat()
            val min = pairStr.substringAfter("\"min\":").substringBefore("}").toFloat()
            Pair(max, min)
        }
    }
    /**
 * Process raw binary ECG data received from TCP service
 * Converts the raw data based on specified format, then passes to standard processData method
 */
    suspend fun processRawData(dataBuffer: ByteArray, format: TcpEcgReceiverService.DataFormat, userId: Long) = withContext(Dispatchers.IO) {
        try {
            // Process the raw data based on the format
            val ecgData = when (format) {
                TcpEcgReceiverService.DataFormat.FLOAT_MV -> processFloatFormat(dataBuffer)
                TcpEcgReceiverService.DataFormat.SCALED_8BIT -> process8BitFormat(dataBuffer)
                TcpEcgReceiverService.DataFormat.SCALED_7BIT -> process7BitFormat(dataBuffer)
            }
            
            // Send the processed float data to the main processing function
            if (ecgData != null) {
                processData(ecgData, userId)
            }
        } catch (e: Exception) {
            logger.error("Failed to process raw ECG data: ${e.message}", e)
        }
    }

    /**
     * Process binary ECG data in float format (4 bytes per value)
     */
    private fun processFloatFormat(dataBuffer: ByteArray): Array<FloatArray>? {
        try {
            val buffer = ByteBuffer.wrap(dataBuffer).order(ByteOrder.LITTLE_ENDIAN)
            
            // For float format: 4 bytes per value * 12 leads = 48 bytes per sample
            val bytesPerSample = 4 * DEFAULT_NUM_LEADS
            
            // Calculate actual number of complete samples
            val numSamples = dataBuffer.size / bytesPerSample
            if (numSamples == 0) return null
            
            // Create array to hold data
            val ecgData = Array(DEFAULT_NUM_LEADS) { FloatArray(numSamples) }
            
            // Parse the binary data as float format
            for (sampleIndex in 0 until numSamples) {
                for (leadIndex in 0 until DEFAULT_NUM_LEADS) {
                    try {
                        val rawValue = buffer.getFloat()
                        ecgData[leadIndex][sampleIndex] = rawValue // Direct float value in mV
                    } catch (e: Exception) {
                        // If we run out of buffer, just return what we have
                        logger.warn("Buffer underflow at sample $sampleIndex, lead $leadIndex")
                        return ecgData
                    }
                }
            }
            
            logger.debug("Processed ${numSamples} samples of float ECG data")
            return ecgData
        } catch (e: Exception) {
            logger.error("Error processing binary float ECG data: ${e.message}", e)
            return null
        }
    }

    /**
     * Process 8-bit scaled ECG data
     * Each lead's data is represented by a byte value between 0-255
     */
    private fun process8BitFormat(dataBuffer: ByteArray): Array<FloatArray>? {
        try {
            val bytesPerSample = DEFAULT_NUM_LEADS // 1 byte per lead
            
            // Calculate actual number of complete samples
            val numSamples = dataBuffer.size / bytesPerSample
            if (numSamples == 0) return null
            
            // Create array to hold data
            val ecgData = Array(DEFAULT_NUM_LEADS) { FloatArray(numSamples) }
            
            // Parse the binary data as 8-bit format
            for (sampleIndex in 0 until numSamples) {
                for (leadIndex in 0 until DEFAULT_NUM_LEADS) {
                    val byteIndex = sampleIndex * bytesPerSample + leadIndex
                    if (byteIndex < dataBuffer.size) {
                        val rawValue = dataBuffer[byteIndex].toInt() and 0xFF
                        // Convert to mV: Center at 0 and scale to reasonable ECG range (typically +/- 1-2 mV)
                        val CENTER_VALUE_8BIT = 127.5f
                        val MAX_VALUE_8BIT = 255
                        ecgData[leadIndex][sampleIndex] = (rawValue - CENTER_VALUE_8BIT) / (MAX_VALUE_8BIT / 2) * 1.0f
                    }
                }
            }
            
            println("Processed ${numSamples} samples of 8-bit ECG data")
            return ecgData
        } catch (e: Exception) {
            logger.error("Error processing 8-bit ECG data: ${e.message}", e)
            return null
        }
    }

    /**
     * Process 7-bit scaled ECG data
     * Each lead's data is represented by a byte value between 0-127
     */
    private fun process7BitFormat(dataBuffer: ByteArray): Array<FloatArray>? {
        try {
            val bytesPerSample = DEFAULT_NUM_LEADS // 1 byte per lead
            
            // Calculate actual number of complete samples
            val numSamples = dataBuffer.size / bytesPerSample
            if (numSamples == 0) return null
            
            // Create array to hold data
            val ecgData = Array(DEFAULT_NUM_LEADS) { FloatArray(numSamples) }
            
            // Parse the binary data as 7-bit format
            for (sampleIndex in 0 until numSamples) {
                for (leadIndex in 0 until DEFAULT_NUM_LEADS) {
                    val byteIndex = sampleIndex * bytesPerSample + leadIndex
                    if (byteIndex < dataBuffer.size) {
                        val rawValue = dataBuffer[byteIndex].toInt() and 0x7F // Mask to 7 bits
                        // Convert to mV: Center at 0 and scale to reasonable ECG range
                        val CENTER_VALUE_7BIT = 63.5f
                        val MAX_VALUE_7BIT = 127
                        ecgData[leadIndex][sampleIndex] = (rawValue - CENTER_VALUE_7BIT) / (MAX_VALUE_7BIT / 2) * 1.0f
                    }
                }
            }
            
            logger.debug("Processed ${numSamples} samples of 7-bit ECG data")
            return ecgData
        } catch (e: Exception) {
            logger.error("Error processing 7-bit ECG data: ${e.message}", e)
            return null
        }
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

    fun encodeEcgTo8Bit(data: Array<FloatArray>): ByteArray {
        val numLeads = data.size
        val numSamples = data[0].size
        val encoded = ByteArray(numLeads * numSamples)

        for (lead in 0 until numLeads) {
            val leadData = data[lead]
            val max = leadData.maxOrNull() ?: 1f
            val min = leadData.minOrNull() ?: -1f
            val diff = max - min + 1e-6f
            val avg = (max + min) / 2f
            val scale = 255f / diff

            for (i in leadData.indices) {
                val normalized = (scale * (leadData[i] - avg) + 127.5f).toInt().coerceIn(0, 255)
                encoded[lead * numSamples + i] = normalized.toByte()
            }
        }

        return encoded
    }


    fun decodeEcgFrom8Bit(
        encoded: ByteArray,
        numLeads: Int,
        numSamples: Int,
        maxMins: List<Pair<Float, Float>>
    ): Array<FloatArray> {
        val decoded = Array(numLeads) { FloatArray(numSamples) }

        for (lead in 0 until numLeads) {
            val (max, min) = maxMins[lead]
            val diff = max - min + 1e-6f
            val avg = (max + min) / 2f
            val scale = diff / 255f

            for (i in 0 until numSamples) {
                val byteVal = encoded[lead * numSamples + i].toInt() and 0xFF
                decoded[lead][i] = ((byteVal - 127.5f) * scale) + avg
            }
        }

        return decoded
    }
    
    /**
     * Convert float arrays to byte array for storage
     */
    private fun Array<FloatArray>.toByteArray(): ByteArray {
    // Calculate total size needed
    var totalSize = 0
    for (leadData in this) {
        totalSize += 4 // 4 bytes to store the length of each lead array
        totalSize += leadData.size * 4 // 4 bytes per float sample
    }
    
    // Add 4 bytes to store the total number of leads
    totalSize += 4
    
    val buffer = ByteBuffer.allocate(totalSize)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    
    // Store number of leads
    buffer.putInt(this.size)
    
    // Store each lead data
    for (leadData in this) {
        // Store length of this lead's data
        buffer.putInt(leadData.size)
        
        // Store each float value directly
        for (value in leadData) {
            buffer.putFloat(value)
        }
    }
    
    return buffer.array()
}
    
    /**
     * Send processed data to WebSocket client
     */
    // In RealtimeEcgService

    private suspend fun sendToClient(userId: Long, packet: EcgDataPacket) {
        try {
            // Create the root JSON object
            val dataJson = JSONObject()
            dataJson.put("timestamp", packet.timestamp)
            dataJson.put("heartRate", packet.heartRate)
            
            // Create ecgData as a JSONArray of JSONArrays, matching your frontend expectations
            val ecgDataArray = JSONArray()
            
            // Add each lead's data as a JSONArray element
            for (leadData in packet.ecgData) {
                val leadDataArray = JSONArray()
                for (value in leadData) {
                    leadDataArray.put(value)
                }
                ecgDataArray.put(leadDataArray)
            }
            
            // Put the ecgData array into the main JSON object
            dataJson.put("ecgData", ecgDataArray)
            
            // Add abnormalities
            val abnormalitiesJson = JSONObject()
            packet.abnormalities.forEach { (key, value) ->
                abnormalitiesJson.put(key, value)
            }
            dataJson.put("abnormalities", abnormalitiesJson)
            
            // Send the data
            val jsonMessage = dataJson.toString()
            ecgMessagePublisher.publishToUser(userId, jsonMessage)
            
            // Log a short sample to keep logs clean
            logger.debug("Sent ECG data for user $userId, HR: ${packet.heartRate}, sample size: ${packet.ecgData[0].size}")
            
            // Notify observers
            diagnosticObservers.forEach { it.updateConnectionStats(userId, packet) }
        } catch (e: Exception) {
            logger.error("Failed to send ECG data to client: ${e.message}", e)
        }
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
    fun calculateHeartRate(data: FloatArray): Int {
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
    val ecgData: Array<FloatArray>,  // Original format: 2D array [lead][datapoints]
    val heartRate: Int,
    val abnormalities: Map<String, Float> = emptyMap()
) {
    /**
     * Convert to frontend-compatible JSON format
     */
    fun toFrontendJson(): String {
        val mapper = ObjectMapper()
        
        // Create a map with lead indices as keys
        val leadDataMap = mutableMapOf<String, FloatArray>()
        for (i in ecgData.indices) {
            leadDataMap[i.toString()] = ecgData[i]
        }
        
        // Create the frontend-compatible structure
        val frontendStructure = mapOf(
            "userId" to userId,
            "timestamp" to timestamp,
            "heartRate" to heartRate,
            "abnormalities" to abnormalities,
            "leadData" to leadDataMap  // This is what the frontend expects
        )
        
        return mapper.writeValueAsString(frontendStructure)
    }
    
    // Existing equals/hashCode methods...
}
    
class CircularBuffer<T>(private val capacity: Int) {
    private val buffer = ArrayList<T>(capacity)
    private var head = 0
    private var size = 0
    
    fun add(item: T) {
        if (size < capacity) {
            buffer.add(item)
            size++
        } else {
            // Replace oldest item with new one
            buffer[head] = item
            head = (head + 1) % capacity
        }
    }
    
    fun getAll(): FloatArray {
        if (size == 0) return FloatArray(0)
        
        val result = FloatArray(size)
        for (i in 0 until size) {
            val index = (head + i) % capacity
            if (index < buffer.size) {
                result[i] = buffer[index] as Float
            }
        }
        return result
    }
    
    fun getLastN(n: Int): FloatArray {
        val count = minOf(n, size)
        if (count == 0) return FloatArray(0)
        
        val result = FloatArray(count)
        
        // Important: Calculate the correct starting position for the last N elements
        for (i in 0 until count) {
            // If we want the last N elements, we need to start at (head + size - count)
            val sourceIndex = (head + size - count + i) % capacity
            if (sourceIndex < buffer.size) {
                result[i] = buffer[sourceIndex] as Float
            }
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