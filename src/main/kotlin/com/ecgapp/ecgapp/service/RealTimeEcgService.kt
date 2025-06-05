package com.ecgapp.ecgapp.service

import com.ecgapp.ecgapp.models.EcgRecording
import com.ecgapp.ecgapp.models.MedicalInfo
import kotlinx.coroutines.Job
import com.ecgapp.ecgapp.repository.MedicalInfoRepository
import com.ecgapp.ecgapp.service.TcpEcgReceiverService
import com.ecgapp.ecgapp.models.Warning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.sqrt
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
import com.ecgapp.ecgapp.repository.WarningRepository
import com.ecgapp.ecgapp.repository.EcgRecordingRepository
import kotlin.math.abs
import kotlin.math.roundToInt
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher

@Service
class RealtimeEcgService(
    private val medicalInfoRepo: MedicalInfoRepository,
    @Autowired private val eventPublisher: ApplicationEventPublisher,
    @Autowired private val ecgMessagePublisher: EcgMessagePublisher,
     @Autowired private val warningRepository: WarningRepository,
     @Autowired private val ecgRecordingRepository: EcgRecordingRepository
) {
    // Other properties remain the same...
    private val logger = LoggerFactory.getLogger(RealtimeEcgService::class.java)
    private val objectMapper = ObjectMapper()

    private val recordingBuffers = ConcurrentHashMap<Long, Array<ArrayList<Float>>>()

    private val lastHeartRates = mutableMapOf<Long, Int>()
    private val lastAbnormalities = mutableMapOf<Long, Map<String, Float>>()
    private val lastAnalysisTime = mutableMapOf<Long, Long>()
    private val processingJobs = mutableMapOf<Long, Job>()
    
    // Dependency injection for observers 
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
        const val DEFAULT_SAMPLE_RATE = 400 // Hz - matching the dataset's sample rate
        const val DEFAULT_NUM_LEADS = 12 
        const val DEFAULT_DATA_RESOLUTION = 16 // bits
        const val MILLIVOLTS_PER_BIT = 0.001f // Same as EcgProcessingService
        
        // Buffer settings
        const val BUFFER_SIZE = 4000 // 10 seconds of data at 400Hz 
        const val PROCESSING_WINDOW = 50 // Process in chunks of 500ms

        private const val DISPLAY_UPDATE_RATE = 30 // 30 FPS for smooth display
        private const val HEART_RATE_UPDATE_INTERVAL = 500L // Update heart rate every 500ms
        private const val ABNORMALITY_CHECK_INTERVAL = 1000L // Check abnormalities every 1 second
        
        private const val DISPLAY_WINDOW_MS = 200 // 0.5 seconds for display
        private const val ANALYSIS_WINDOW_MS = 3000 // 2 seconds for analysis
        private const val MAX_PROCESSING_TIME_MS = 16 // ~60 FPS target
        
        private const val OPTIMAL_BATCH_SIZE = 32 // Optimal batch size for processing
        
        // Abnormality types
        val ABNORMALITY_TYPES = listOf("1dAVb", "RBBB", "LBBB", "SB", "AF", "ST")
    }
    
    
    // Buffer for each user's ECG data stores multi-lead data
    private val dataBuffers = ConcurrentHashMap<Long, Array<CircularBuffer<Float>>>()
    
    // Flow for broadcasting processed ECG data
    private val ecgDataFlow = MutableSharedFlow<EcgDataPacket>(replay = 0, extraBufferCapacity = 64)
    
    /**
     * Get the shared flow of ECG data 
     */
    fun getEcgDataFlow(): Flow<EcgDataPacket> = ecgDataFlow
    


    suspend fun processData(ecgData: Array<FloatArray>, userId: Long) = withContext(Dispatchers.Default) {
        // Initialize buffer if needed
        if (!dataBuffers.containsKey(userId)) {
            val buffers = Array(DEFAULT_NUM_LEADS) { CircularBuffer<Float>(BUFFER_SIZE) }
            dataBuffers[userId] = buffers
            
            // Initialize recording buffer
            recordingBuffers[userId] = Array(DEFAULT_NUM_LEADS) { ArrayList<Float>() }
            
            // Initialize cached values
            lastHeartRates[userId] = 70
            lastAbnormalities[userId] = emptyMap()
            lastAnalysisTime[userId] = 0L
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
        
        // Only process if we have new data
        if (samplesAdded > 0) {
            val bufferSize = buffers[0].size()
            val currentTime = System.currentTimeMillis()
            
            // Always send display data immediately for smooth real-time view
            sendDisplayData(ecgData, userId, bufferSize, samplesAdded)
            
            // Check if we should run heavy analysis
            if (shouldRunAnalysis(userId, bufferSize, currentTime)) {
                runBackgroundAnalysis(userId, buffers, currentTime)
            }
        }
    }
    
    private suspend fun sendDisplayData(
        ecgData: Array<FloatArray>, 
        userId: Long, 
        bufferSize: Int, 
        samplesAdded: Int
    ) {
        // Use cached values for immediate display
        val cachedHeartRate = lastHeartRates[userId] ?: 70
        val cachedAbnormalities = lastAbnormalities[userId] ?: emptyMap()
        
        // Prepare display data (just the new samples, lightly filtered)
        val displayData = Array(DEFAULT_NUM_LEADS) { leadIndex ->
            // For display, just use the incoming data with minimal processing
            ecgData[leadIndex]
        }
        
        val packet = EcgDataPacket(
            userId = userId,
            timestamp = System.currentTimeMillis(),
            ecgData = displayData,
            heartRate = cachedHeartRate,
            abnormalities = cachedAbnormalities
        )
        
        // Send immediately for smooth display
        sendToClient(userId, packet)
        ecgDataFlow.emit(packet)
        
        logger.debug("Display data sent: ${samplesAdded} samples, HR: $cachedHeartRate")
    }
    
    private fun shouldRunAnalysis(userId: Long, bufferSize: Int, currentTime: Long): Boolean {
        val lastAnalysis = lastAnalysisTime[userId] ?: 0L
        val timeSinceLastAnalysis = currentTime - lastAnalysis
        
        // Run analysis if:
        // 1. We have enough data AND
        // 2. Enough time has passed since last analysis
        val hasEnoughData = bufferSize >= (DEFAULT_SAMPLE_RATE * ANALYSIS_WINDOW_MS / 1000)
        val timeForAnalysis = timeSinceLastAnalysis >= HEART_RATE_UPDATE_INTERVAL
        
        return hasEnoughData && timeForAnalysis
    }
    
    private fun runBackgroundAnalysis(
        userId: Long, 
        buffers: Array<CircularBuffer<Float>>, 
        currentTime: Long
    ) {
        // Cancel any existing analysis job for this user
        processingJobs[userId]?.cancel()
        
        // Start new background analysis
        processingJobs[userId] = CoroutineScope(Dispatchers.Default).launch {
            try {
                val analysisWindowSize = minOf(
                    buffers[0].size(), 
                    (DEFAULT_SAMPLE_RATE * ANALYSIS_WINDOW_MS / 1000).toInt()
                )
                
                // Extract analysis data
                val contextData = Array(DEFAULT_NUM_LEADS) { leadIndex ->
                    buffers[leadIndex].getLastN(analysisWindowSize)
                }
                
                logger.debug("Background analysis started: window size = $analysisWindowSize samples")
                
                // Apply filters for analysis (if needed)
                val filteredContextData = contextData // or applyFilters(contextData)
                
                // Calculate heart rate (runs more frequently)
                val newHeartRate = calculateHeartRate(filteredContextData[1])
                
                // Check abnormalities (runs less frequently)
                val newAbnormalities = if (shouldCheckAbnormalities(userId, currentTime)) {
                    analyzeForAbnormalities(filteredContextData)
                } else {
                    lastAbnormalities[userId] ?: emptyMap()
                }
                
                // Update cached values
                lastHeartRates[userId] = newHeartRate
                lastAbnormalities[userId] = newAbnormalities
                lastAnalysisTime[userId] = currentTime
                
                logger.debug("Analysis completed: HR=$newHeartRate, abnormalities=${newAbnormalities.size}")
                
            } catch (e: Exception) {
                logger.error("Background analysis failed for user $userId", e)
            }
        }
    }
    
    private fun shouldCheckAbnormalities(userId: Long, currentTime: Long): Boolean {
        val lastAbnormalityCheck = lastAnalysisTime[userId] ?: 0L
        return (currentTime - lastAbnormalityCheck) >= ABNORMALITY_CHECK_INTERVAL
    }
    
    // Helper method to create optimized display packets
    private fun createOptimizedDisplayPacket(
        newData: Array<FloatArray>,
        userId: Long,
        useSmoothing: Boolean = true
    ): EcgDataPacket {
        val processedData = if (useSmoothing) {
            // Apply minimal smoothing for display only
            Array(DEFAULT_NUM_LEADS) { leadIndex ->
                applySmoothingFilter(newData[leadIndex])
            }
        } else {
            newData
        }
        
        return EcgDataPacket(
            userId = userId,
            timestamp = System.currentTimeMillis(),
            ecgData = processedData,
            heartRate = lastHeartRates[userId] ?: 70,
            abnormalities = lastAbnormalities[userId] ?: emptyMap()
        )
    }
    
    // Lightweight smoothing filter for display
    private fun applySmoothingFilter(data: FloatArray): FloatArray {
        if (data.size < 3) return data
        
        val smoothed = FloatArray(data.size)
        smoothed[0] = data[0]
        
        for (i in 1 until data.size - 1) {
            // Simple 3-point moving average
            smoothed[i] = (data[i - 1] + data[i] + data[i + 1]) / 3f
        }
        
        smoothed[data.size - 1] = data[data.size - 1]
        return smoothed
    }
    
    // Cleanup method to call when user disconnects
    fun cleanup(userId: Long) {
        processingJobs[userId]?.cancel()
        processingJobs.remove(userId)
        lastHeartRates.remove(userId)
        lastAbnormalities.remove(userId)
        lastAnalysisTime.remove(userId)
        dataBuffers.remove(userId)
        recordingBuffers.remove(userId)
    }

    private fun logDetailedAnalysis(
        userId: Long, 
        filteredData: Array<FloatArray>, 
        heartRate: Int, 
        abnormalities: Map<String, Float>,
        newSamples: Int,
        analysisWindowSize: Int
    ) {
        try {
            val leadII = filteredData[1] // Most commonly used for rhythm analysis
            
            // Calculate signal statistics
            val mean = leadII.average().toFloat()
            val maxVal = leadII.maxOrNull() ?: 0f
            val minVal = leadII.minOrNull() ?: 0f
            val range = maxVal - minVal
            
            // Detect R-peaks for validation
            val rPeaks = detectRPeaks(leadII)
            
            logger.debug("""
                === ECG Analysis Summary ===
                User: $userId
                New samples: $newSamples
                Analysis window: $analysisWindowSize samples (${analysisWindowSize.toFloat() / DEFAULT_SAMPLE_RATE}s)
                Lead II stats: mean=${mean.format(3)}, range=${range.format(3)} (${minVal.format(3)} to ${maxVal.format(3)})
                R-peaks detected: ${rPeaks.size}
                Heart rate: $heartRate BPM
                Abnormalities: ${abnormalities.entries.filter { it.value > 0.1f }.map { "${it.key}=${it.value.format(2)}" }}
                ==============================
            """.trimIndent())
            
            // Additional validation
            if (rPeaks.isEmpty() && analysisWindowSize >= DEFAULT_SAMPLE_RATE) {
                logger.warn("No R-peaks detected despite having ${analysisWindowSize} samples. Signal range: $range")
                // Log a sample of the data for inspection
                val sampleIndices = (0 until minOf(10, leadII.size)).map { it }
                val sampleValues = sampleIndices.map { leadII[it].format(3) }
                logger.warn("Lead II sample values: $sampleValues")
            }
            
        } catch (e: Exception) {
            logger.error("Error in detailed analysis logging", e)
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
        logger.info("=== FINALIZE RECORDING DEBUG ===")
        logger.info("User ID: $userId")
        logger.info("recordingBuffers contains userId: ${recordingBuffers.containsKey(userId)}")
        
        val recording = recordingBuffers[userId]
        if (recording != null) {
            logger.info("Recording array size: ${recording.size}")
            logger.info("Lead sizes: ${recording.mapIndexed { i, list -> "Lead$i: ${list.size}" }}")
        } else {
            logger.error("Recording is null!")
            return@withContext null
        }
        
        // Check if we have enough data - FIXED VALIDATION LOGIC
        val lead0Size = recording.getOrNull(0)?.size ?: 0
        logger.info("Validation check - Lead 0 size: $lead0Size, Required: $DEFAULT_SAMPLE_RATE")
        
        if (recording.isEmpty()) {
            logger.warn("Recording array is empty (no leads)")
            return@withContext null
        }
        
        if (lead0Size < DEFAULT_SAMPLE_RATE) {
            logger.warn("Not enough data - Lead 0 size: $lead0Size < Required: $DEFAULT_SAMPLE_RATE")
            return@withContext null
        }
        
        logger.info("Validation passed! Finalizing recording for user $userId with $lead0Size samples")
        
        // Log first few values from each lead for debugging
        for (i in recording.indices) {
            if (recording[i].size > 0) {
                logger.info("Lead $i first few values: ${recording[i].take(5)}")
            }
        }
        
        // Convert ArrayList<Float> to FloatArray for each lead
        val allData = Array(DEFAULT_NUM_LEADS) { leadIndex ->
            recording[leadIndex].toFloatArray()
        }
        
        // Apply filters to the entire dataset
        val filteredData = allData
        
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
        
        logger.info("ECG recording created successfully with ${filteredData[0].size} samples")
        
        // SAVE THE ECG RECORDING FIRST TO GET THE ID
        val savedEcgRecording = ecgRecordingRepository.save(ecgRecording)
        logger.info("ECG recording saved with ID: ${savedEcgRecording.id}")
        
        // NOW SAVE ABNORMALITIES AS WARNINGS
        saveAbnormalitiesAsWarnings(abnormalities, savedEcgRecording.id)
        
        // Reset the recording buffer for this user but keep it initialized
        recordingBuffers[userId] = Array(DEFAULT_NUM_LEADS) { ArrayList<Float>() }
        
        savedEcgRecording
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
        
        // Parse the binary data as 8-bit format with improved scaling
        for (sampleIndex in 0 until numSamples) {
            for (leadIndex in 0 until DEFAULT_NUM_LEADS) {
                val byteIndex = sampleIndex * bytesPerSample + leadIndex
                if (byteIndex < dataBuffer.size) {
                    val rawValue = dataBuffer[byteIndex].toInt() and 0xFF
                    
                    // Improved scaling based on typical ECG amplitude ranges
                    // Most ECG signals are in the range of ±2-5mV
                    val normalizedValue = (rawValue - 127.5f) / 127.5f // Normalize to [-1, 1]
                    
                    // Scale to appropriate mV range based on lead type
                    val scaleFactor = when (leadIndex) {
                        0, 1, 2 -> 3.0f        // Limb leads (I, II, III): ±3mV
                        3, 4, 5 -> 2.0f        // Augmented leads (aVR, aVL, aVF): ±2mV  
                        6, 7, 8, 9, 10, 11 -> 4.0f  // Precordial leads (V1-V6): ±4mV
                        else -> 2.5f
                    }
                    
                    ecgData[leadIndex][sampleIndex] = normalizedValue * scaleFactor
                }
            }
        }
        
        logger.debug("Processed ${numSamples} samples of 8-bit ECG data with improved scaling")
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
    fun applyFilters(data: Array<FloatArray>): Array<FloatArray> {
            return Array(data.size) { leadIndex ->
                val leadData = data[leadIndex]
                applyNotchFilter(
                    applyLowPassFilter(
                        applyHighPassFilter(leadData)
                    )
                )
            }
        }

        /** High-pass filter to remove baseline wander (typically below 0.5Hz) */
        private fun applyHighPassFilter(data: FloatArray): FloatArray {
            val filteredData = FloatArray(data.size)
            val alpha = 0.995f
            filteredData[0] = data[0]
            for (i in 1 until data.size) {
                filteredData[i] = alpha * (filteredData[i - 1] + data[i] - data[i - 1])
            }
            return filteredData
        }

        /** Low-pass filter to remove high-frequency noise */
        private fun applyLowPassFilter(data: FloatArray): FloatArray {
            val windowSize = 5
            val filteredData = FloatArray(data.size)
            for (i in data.indices) {
                var sum = 0f
                var count = 0
                for (j in maxOf(0, i - windowSize / 2)..minOf(data.size - 1, i + windowSize / 2)) {
                    sum += data[j]
                    count++
                }
                filteredData[i] = sum / count
            }
            return filteredData
        }

        /** Notch filter to remove powerline interference (placeholder) */
        private fun applyNotchFilter(data: FloatArray): FloatArray {
            return data // placeholder - use DSP library in production
        }

        /** Calculate heart rate from the ECG signal */
        fun calculateHeartRate(data: FloatArray): Int {
            // Need at least 2 seconds of data for reliable heart rate calculation
            if (data.size < DEFAULT_SAMPLE_RATE * 2) {
                logger.debug("Insufficient data for heart rate calculation: ${data.size} samples (need ${DEFAULT_SAMPLE_RATE * 2})")
                return 70
            }
            
            val rPeaks = detectRPeaks(data)
            logger.debug("Heart rate calculation: ${rPeaks.size} R-peaks in ${data.size} samples")
            
            if (rPeaks.size < 2) {
                logger.debug("Insufficient R-peaks for heart rate calculation: ${rPeaks.size}")
                return 70
            }
            
            // Calculate average RR interval
            val rrIntervals = (1 until rPeaks.size).map { rPeaks[it] - rPeaks[it - 1] }
            val avgRR = rrIntervals.average()
            val heartRate = (60.0 * DEFAULT_SAMPLE_RATE / avgRR).roundToInt()
            
            // Validate heart rate is in reasonable range
            val validatedHR = when {
                heartRate < 30 -> 70 // Too low, likely detection error
                heartRate > 220 -> 70 // Too high, likely detection error
                else -> heartRate
            }
            
            logger.debug("Heart rate: $validatedHR BPM (from ${rrIntervals.size} RR intervals, avg=${avgRR.toFloat().format(1)} samples)")
            
            return validatedHR
        }

        // Updated detectRPeaks with better debugging
        private fun detectRPeaks(data: FloatArray): List<Int> {
            if (data.isEmpty()) return emptyList()
            
            val threshold = calculateThreshold(data)
            val minDistance = (DEFAULT_SAMPLE_RATE * 0.3).toInt() // 300ms minimum between peaks
            val rPeaks = mutableListOf<Int>()
            var lastPeak = -minDistance
            
            logger.debug("R-peak detection: threshold=$threshold, minDistance=$minDistance, dataSize=${data.size}")
            
            for (i in 1 until data.size - 1) {
                if (i - lastPeak < minDistance) continue
                
                // Peak detection: current value is greater than threshold and is a local maximum
                if (data[i] > threshold && data[i] > data[i - 1] && data[i] > data[i + 1]) {
                    rPeaks.add(i)
                    lastPeak = i
                }
            }
            
            logger.debug("Found ${rPeaks.size} R-peaks at positions: ${rPeaks.take(5)}${if (rPeaks.size > 5) "..." else ""}")
            
            return rPeaks
        }

        // Updated threshold calculation with debugging
        private fun calculateThreshold(data: FloatArray): Float {
            val maxAbs = data.maxOf { abs(it) }
            val threshold = 0.6f * maxAbs
            
            logger.debug("Threshold calculation: maxAbs=$maxAbs, threshold=$threshold")
            
            return threshold
        }

        // Helper extension function for Float formatting
        private fun Float.format(digits: Int): String = String.format("%.${digits}f", this)

        /** Detect QRS complexes */
        private fun detectQrsComplexes(data: FloatArray): List<Map<String, Int>> {
            return detectRPeaks(data).map { r ->
                mapOf("qPoint" to findQPoint(data, r), "rPeak" to r, "sPoint" to findSPoint(data, r))
            }
        }

        private fun findQPoint(data: FloatArray, rPeak: Int): Int {
            val window = DEFAULT_SAMPLE_RATE / 10
            val start = maxOf(0, rPeak - window)
            var minIdx = rPeak
            var minVal = data[rPeak]
            for (i in rPeak - 1 downTo start) {
                if (data[i] < minVal) {
                    minVal = data[i]
                    minIdx = i
                } else if (data[i] > data[i + 1]) break
            }
            return minIdx
        }

        private fun findSPoint(data: FloatArray, rPeak: Int): Int {
            val window = DEFAULT_SAMPLE_RATE / 10
            val end = minOf(data.size - 1, rPeak + window)
            var minIdx = rPeak
            var minVal = data[rPeak]
            for (i in rPeak + 1..end) {
                if (data[i] < minVal) {
                    minVal = data[i]
                    minIdx = i
                } else if (i < end && data[i] < data[i + 1]) break
            }
            return minIdx
        }
    private fun detectRSRPattern(lead: FloatArray, qrsComplex: Map<String, Int>): Boolean {
        val qPoint = qrsComplex["qPoint"]!!
        val rPeak = qrsComplex["rPeak"]!!
        val sPoint = qrsComplex["sPoint"]!!
        
        // Look for R' (second R wave) after the S wave
        val searchStart = sPoint
        val searchEnd = minOf(lead.size - 1, qPoint + (DEFAULT_SAMPLE_RATE * 0.12).toInt())
        
        var maxAfterS = lead[sPoint]
        var rPrimeCandidate = -1
        
        // Find the highest peak after S wave
        for (i in searchStart + 3 until searchEnd) {
            if (lead[i] > maxAfterS && lead[i] > lead[i-1] && lead[i] > lead[i+1]) {
                maxAfterS = lead[i]
                rPrimeCandidate = i
            }
        }
        
        // R' should be significantly higher than S point and substantial compared to original R
        val baseline = calculateBaseline(lead)
        return rPrimeCandidate != -1 && 
            maxAfterS > baseline + 0.1f && 
            maxAfterS > lead[rPeak] * 0.3f // R' should be at least 30% of original R
    }

    /** Check for broad S-wave in leads V5, V6, I, aVL for RBBB */
    private fun hasBroadSWave(lead: FloatArray, qrsComplexes: List<Map<String, Int>>): Boolean {
        if (qrsComplexes.isEmpty()) return false
        
        val qrs = qrsComplexes.first()
        val rPeak = qrs["rPeak"]!!
        val sPoint = qrs["sPoint"]!!
        
        // S-wave duration should be > 40ms (for V6, I) or > R-wave duration
        val sWaveDuration = sPoint - rPeak
        val rWaveDuration = rPeak - qrs["qPoint"]!!
        
        val minDurationMs = DEFAULT_SAMPLE_RATE * 0.04 // 40ms
        
        return sWaveDuration > minDurationMs || sWaveDuration > rWaveDuration
    }

    /** Detect deep and broad S-wave in V1/V2 for LBBB */
    private fun hasDeepBroadSWave(lead: FloatArray, qrsComplex: Map<String, Int>): Boolean {
        val rPeak = qrsComplex["rPeak"]!!
        val sPoint = qrsComplex["sPoint"]!!
        
        val baseline = calculateBaseline(lead)
        val sDepth = baseline - lead[sPoint]
        val sDuration = sPoint - rPeak
        
        // S-wave should be deep (>0.3mV equivalent) and broad
        val minDepth = 0.3f //can  be  adjusted
        val minDuration = DEFAULT_SAMPLE_RATE * 0.04 // 40ms
        
        return sDepth > minDepth && sDuration > minDuration
    }

    /** Check if small r-wave is missing or smaller than normal in V1/V2 for LBBB */
    private fun hasMissingOrSmallRWave(lead: FloatArray, qrsComplex: Map<String, Int>): Boolean {
        val qPoint = qrsComplex["qPoint"]!!
        val rPeak = qrsComplex["rPeak"]!!
        
        val baseline = calculateBaseline(lead)
        val rAmplitude = lead[rPeak] - baseline
        
        // R-wave should be very small or absent (< 0.1mV equivalent)
        val maxRAmplitude = 0.1f // can  be  adjusted
        
        return rAmplitude < maxRAmplitude
    }

    /** Check for broad, positive R-wave in V5/V6 for LBBB */
    private fun hasBroadPositiveRWave(lead: FloatArray, qrsComplex: Map<String, Int>): Boolean {
        val qPoint = qrsComplex["qPoint"]!!
        val rPeak = qrsComplex["rPeak"]!!
        val sPoint = qrsComplex["sPoint"]!!
        
        val baseline = calculateBaseline(lead)
        val rAmplitude = lead[rPeak] - baseline
        val rDuration = sPoint - qPoint // Approximation of R-wave width
        
        // R-wave should be positive and broad
        val minAmplitude = 0.2f // can  be  adjusted
        val minDuration = DEFAULT_SAMPLE_RATE * 0.06 // 60ms
        
        return rAmplitude > minAmplitude && rDuration > minDuration
    }

    /** Check for absent Q waves in leads I, V5, V6 for LBBB */
    private fun hasAbsentQWaves(lead: FloatArray, qrsComplex: Map<String, Int>): Boolean {
        val qPoint = qrsComplex["qPoint"]!!
        val rPeak = qrsComplex["rPeak"]!!
        
        val baseline = calculateBaseline(lead)
        val qDepth = baseline - lead[qPoint]
        
        // Q-wave should be absent or very small (< 0.04mV equivalent)
        val maxQDepth = 0.04f // can  be  adjusted
        
        return qDepth < maxQDepth
    }

    /** Calculate approximate baseline from early part of signal */
    private fun calculateBaseline(lead: FloatArray): Float {
        val sampleSize = minOf(lead.size / 10, 100) // Use first 10% or 100 samples
        return lead.take(sampleSize).average().toFloat()
    }

    /** Enhanced abnormality analysis with proper RBBB/LBBB detection */
    fun analyzeForAbnormalities(data: Array<FloatArray>): Map<String, Float> {
        val results = ABNORMALITY_TYPES.associateWith { 0.0f }.toMutableMap()
        
        println("=== ECG Analysis Debug ===")
        println("Input data: ${data.size} leads")
        
        // Standard 12-lead ECG order: I, II, III, aVR, aVL, aVF, V1, V2, V3, V4, V5, V6
        val leadI = if (data.size > 0) data[0] else FloatArray(0)
        val leadII = if (data.size > 1) data[1] else FloatArray(0)
        val leadV1 = if (data.size > 6) data[6] else FloatArray(0)
        val leadV2 = if (data.size > 7) data[7] else FloatArray(0)
        val leadV5 = if (data.size > 10) data[10] else FloatArray(0)
        val leadV6 = if (data.size > 11) data[11] else FloatArray(0)

        println("Lead sizes - I: ${leadI.size}, II: ${leadII.size}, V1: ${leadV1.size}, V2: ${leadV2.size}, V5: ${leadV5.size}, V6: ${leadV6.size}")

        val heartRate = calculateHeartRate(leadII)
        println("Heart Rate: $heartRate BPM")

        // Sinus Bradycardia
        if (heartRate < 65) {
            results["SB"] = 0.9f
            println("✓ Sinus Bradycardia detected (HR: $heartRate)")
        }

        // Sinus Tachycardia  
        if (heartRate > 100) {
            results["ST"] = 0.9f
            println("✓ Sinus Tachycardia detected (HR: $heartRate)")
        }

        // Get QRS complexes from Lead II for duration analysis
        val qrsComplexes = detectQrsComplexes(leadII)
        println("QRS complexes found in Lead II: ${qrsComplexes.size}")
        
        if (qrsComplexes.isNotEmpty()) {
            val avgQrsDuration = qrsComplexes.map { 
                it["sPoint"]!! - it["qPoint"]!! 
            }.average()
            
            val qrsDurationSeconds = avgQrsDuration / DEFAULT_SAMPLE_RATE
            println("Average QRS duration: ${qrsDurationSeconds * 1000}ms (${avgQrsDuration} samples)")
            
            // Check if QRS is wide (≥0.12 seconds)
            val isWideQRS = qrsDurationSeconds >= 0.12
            println("Wide QRS (≥120ms): $isWideQRS")
            
            if (isWideQRS) {
                println("\n--- RBBB Analysis ---")
                // RBBB Detection
                val v1Qrs = if (leadV1.isNotEmpty()) detectQrsComplexes(leadV1) else emptyList()
                val v2Qrs = if (leadV2.isNotEmpty()) detectQrsComplexes(leadV2) else emptyList()
                
                println("V1 QRS complexes: ${v1Qrs.size}")
                println("V2 QRS complexes: ${v2Qrs.size}")
                
                var rbbbScore = 0f
                
                // Check for RSR' pattern in V1/V2
                if (v1Qrs.isNotEmpty()) {
                    val hasRSR = detectRSRPattern(leadV1, v1Qrs.first())
                    println("V1 RSR' pattern: $hasRSR")
                    if (hasRSR) {
                        rbbbScore += 0.4f
                        println("  → RBBB score +0.4 (V1 RSR')")
                    }
                }
                if (v2Qrs.isNotEmpty()) {
                    val hasRSR = detectRSRPattern(leadV2, v2Qrs.first())
                    println("V2 RSR' pattern: $hasRSR")
                    if (hasRSR) {
                        rbbbScore += 0.3f
                        println("  → RBBB score +0.3 (V2 RSR')")
                    }
                }
                
                // Check for broad S-wave in V5, V6, I
                if (leadV5.isNotEmpty()) {
                    val v5Qrs = detectQrsComplexes(leadV5)
                    if (v5Qrs.isNotEmpty()) {
                        val hasBroadS = hasBroadSWave(leadV5, v5Qrs)
                        println("V5 broad S-wave: $hasBroadS")
                        if (hasBroadS) {
                            rbbbScore += 0.2f
                            println("  → RBBB score +0.2 (V5 broad S)")
                        }
                    }
                }
                if (leadV6.isNotEmpty()) {
                    val v6Qrs = detectQrsComplexes(leadV6)
                    if (v6Qrs.isNotEmpty()) {
                        val hasBroadS = hasBroadSWave(leadV6, v6Qrs)
                        println("V6 broad S-wave: $hasBroadS")
                        if (hasBroadS) {
                            rbbbScore += 0.2f
                            println("  → RBBB score +0.2 (V6 broad S)")
                        }
                    }
                }
                if (leadI.isNotEmpty()) {
                    val iQrs = detectQrsComplexes(leadI)
                    if (iQrs.isNotEmpty()) {
                        val hasBroadS = hasBroadSWave(leadI, iQrs)
                        println("Lead I broad S-wave: $hasBroadS")
                        if (hasBroadS) {
                            rbbbScore += 0.2f
                            println("  → RBBB score +0.2 (Lead I broad S)")
                        }
                    }
                }
                
                println("Total RBBB score: $rbbbScore (threshold: 0.6)")
                if (rbbbScore >= 0.6f) {
                    results["RBBB"] = minOf(0.9f, rbbbScore)
                    println("✓ RBBB DETECTED with confidence: ${results["RBBB"]}")
                }
                
                println("\n--- LBBB Analysis ---")
                // LBBB Detection
                var lbbbScore = 0f
                
                // Check for deep, broad S-wave in V1/V2
                if (v1Qrs.isNotEmpty()) {
                    val hasDeepS = hasDeepBroadSWave(leadV1, v1Qrs.first())
                    println("V1 deep/broad S-wave: $hasDeepS")
                    if (hasDeepS) {
                        lbbbScore += 0.3f
                        println("  → LBBB score +0.3 (V1 deep S)")
                    }
                }
                if (v2Qrs.isNotEmpty()) {
                    val hasDeepS = hasDeepBroadSWave(leadV2, v2Qrs.first())
                    println("V2 deep/broad S-wave: $hasDeepS")
                    if (hasDeepS) {
                        lbbbScore += 0.2f
                        println("  → LBBB score +0.2 (V2 deep S)")
                    }
                }
                
                // Check for missing/small r-wave in V1/V2
                if (v1Qrs.isNotEmpty()) {
                    val hasSmallR = hasMissingOrSmallRWave(leadV1, v1Qrs.first())
                    println("V1 missing/small r-wave: $hasSmallR")
                    if (hasSmallR) {
                        lbbbScore += 0.2f
                        println("  → LBBB score +0.2 (V1 small r)")
                    }
                }
                
                // Check for broad, positive R-wave in V5/V6
                if (leadV5.isNotEmpty()) {
                    val v5Qrs = detectQrsComplexes(leadV5)
                    if (v5Qrs.isNotEmpty()) {
                        val hasBroadR = hasBroadPositiveRWave(leadV5, v5Qrs.first())
                        println("V5 broad positive R-wave: $hasBroadR")
                        if (hasBroadR) {
                            lbbbScore += 0.2f
                            println("  → LBBB score +0.2 (V5 broad R)")
                        }
                    }
                }
                if (leadV6.isNotEmpty()) {
                    val v6Qrs = detectQrsComplexes(leadV6)
                    if (v6Qrs.isNotEmpty()) {
                        val hasBroadR = hasBroadPositiveRWave(leadV6, v6Qrs.first())
                        println("V6 broad positive R-wave: $hasBroadR")
                        if (hasBroadR) {
                            lbbbScore += 0.2f
                            println("  → LBBB score +0.2 (V6 broad R)")
                        }
                    }
                }
                
                // Check for absent Q waves in I, V5, V6
                if (leadI.isNotEmpty()) {
                    val iQrs = detectQrsComplexes(leadI)
                    if (iQrs.isNotEmpty()) {
                        val hasAbsentQ = hasAbsentQWaves(leadI, iQrs.first())
                        println("Lead I absent Q-waves: $hasAbsentQ")
                        if (hasAbsentQ) {
                            lbbbScore += 0.1f
                            println("  → LBBB score +0.1 (Lead I absent Q)")
                        }
                    }
                }
                if (leadV5.isNotEmpty()) {
                    val v5Qrs = detectQrsComplexes(leadV5)
                    if (v5Qrs.isNotEmpty()) {
                        val hasAbsentQ = hasAbsentQWaves(leadV5, v5Qrs.first())
                        println("V5 absent Q-waves: $hasAbsentQ")
                        if (hasAbsentQ) {
                            lbbbScore += 0.1f
                            println("  → LBBB score +0.1 (V5 absent Q)")
                        }
                    }
                }
                if (leadV6.isNotEmpty()) {
                    val v6Qrs = detectQrsComplexes(leadV6)
                    if (v6Qrs.isNotEmpty()) {
                        val hasAbsentQ = hasAbsentQWaves(leadV6, v6Qrs.first())
                        println("V6 absent Q-waves: $hasAbsentQ")
                        if (hasAbsentQ) {
                            lbbbScore += 0.1f
                            println("  → LBBB score +0.1 (V6 absent Q)")
                        }
                    }
                }
                
                println("Total LBBB score: $lbbbScore (threshold: 0.6)")
                if (lbbbScore >= 0.6f) {
                    results["LBBB"] = minOf(0.9f, lbbbScore)
                    println("✓ LBBB DETECTED with confidence: ${results["LBBB"]}")
                }
            }
        } else {
            println("No QRS complexes detected in Lead II!")
        }

        println("\n--- Other Analysis ---")
        // Atrial Fibrillation 
        val rPeaks = detectRPeaks(leadII)
        println("R-peaks detected: ${rPeaks.size}")
        if (rPeaks.size > 3) {
            val rrIntervals = (1 until rPeaks.size).map { rPeaks[it] - rPeaks[it - 1] }
            val mean = rrIntervals.average()
            val std = sqrt(rrIntervals.map { (it - mean) * (it - mean) }.average())
            val variability = std / mean
            println("RR variability: $variability (threshold: 0.1)")
            if (variability > 0.1) {
                results["AF"] = 0.7f
                println("✓ Atrial Fibrillation detected")
            }
        }

        // 1st-Degree AV Block (existing logic)
        val prInterval = detectPrInterval(leadII)
        val prIntervalMs = (prInterval.toFloat() / DEFAULT_SAMPLE_RATE) * 1000
        println("PR interval: ${prIntervalMs}ms (threshold: 200ms)")
        if (prInterval > DEFAULT_SAMPLE_RATE * 0.2) {
            results["1dAVB"] = 0.85f
            println("✓ 1st-degree AV Block detected")
        }

        println("\n=== Final Results ===")
        results.forEach { (condition, confidence) ->
            if (confidence > 0) {
                println("$condition: ${(confidence * 100).toInt()}%")
            }
        }
        println("========================\n")

        return results
    }


    private suspend fun saveAbnormalitiesAsWarnings(
        abnormalities: Map<String, Float>,
        ecgRecordingId: Int
    ) = withContext(Dispatchers.IO) {
        logger.info("=== SAVING ABNORMALITIES AS WARNINGS ===")
        logger.info("ECG Recording ID: $ecgRecordingId")
        logger.info("Abnormalities found: ${abnormalities.size}")
        
        val warningsToSave = mutableListOf<Warning>()

        val ecgRecording = ecgRecordingRepository.findById(ecgRecordingId).orElseThrow { IllegalArgumentException("ECG Recording not found with ID: $ecgRecordingId") }
        
        // Define severity thresholds and warning details
        val abnormalityDetails = mapOf(
            "1dAVb" to WarningInfo("First-degree AV Block", "Prolonged PR interval detected"),
            "RBBB" to WarningInfo("Right Bundle Branch Block", "Delayed conduction in right bundle branch"),
            "LBBB" to WarningInfo("Left Bundle Branch Block", "Delayed conduction in left bundle branch"),
            "SB" to WarningInfo("Sinus Bradycardia", "Heart rate below normal range"),
            "AF" to WarningInfo("Atrial Fibrillation", "Irregular heart rhythm detected"),
            "ST" to WarningInfo("Sinus Tachycardia", "Heart rate above normal range")
        )
        
        abnormalities.forEach { (abnormalityType, confidence) ->
            logger.info("Processing abnormality: $abnormalityType with confidence: $confidence")
            
            // Only save warnings for abnormalities with significant confidence
            if (confidence > 0.1f) { // Threshold for saving warnings
                val warningInfo = abnormalityDetails[abnormalityType]
                if (warningInfo != null) {
                    val warningType = determineWarningType(abnormalityType, confidence)
                    val details = createWarningDetails(abnormalityType, confidence, warningInfo.description)
                    
                    val warning = Warning(
                        type = warningType,
                        details = details,
                        ecgRecording = ecgRecording
                    )
                    
                    warningsToSave.add(warning)
                    logger.info("Created warning: type=$warningType, details=$details")
                } else {
                    logger.warn("No warning info found for abnormality type: $abnormalityType")
                }
            } else {
                logger.debug("Skipping abnormality $abnormalityType (confidence too low: $confidence)")
            }
        }
        
        // Save all warnings in batch
        if (warningsToSave.isNotEmpty()) {
            try {
                val savedWarnings = warningRepository.saveAll(warningsToSave)
                logger.info("Successfully saved ${savedWarnings.size} warnings for ECG recording $ecgRecordingId")
                
                // Log details of saved warnings
                savedWarnings.forEach { warning ->
                    logger.info("Saved warning ID: ${warning.id}, Type: ${warning.type}, Details: ${warning.details}")
                }
            } catch (e: Exception) {
                logger.error("Failed to save warnings for ECG recording $ecgRecordingId", e)
                throw e
            }
        } else {
            logger.info("No warnings to save (no significant abnormalities detected)")
        }
    }
    
    /**
     * Determine warning type based on abnormality and confidence level
     */
    private fun determineWarningType(abnormalityType: String, confidence: Float): String {
        return when {
            confidence >= 0.8f -> "CRITICAL"
            confidence >= 0.6f -> "HIGH"
            confidence >= 0.4f -> "MEDIUM"
            confidence >= 0.2f -> "LOW"
            else -> "INFO"
        }
    }
    
    private fun createWarningDetails(
        abnormalityType: String,
        confidence: Float,
        description: String
    ): String {
        val confidencePercentage = (confidence * 100).roundToInt()
        return "$description (Confidence: ${confidencePercentage}%)"
    }
    
    private data class WarningInfo(
        val name: String,
        val description: String
    )
    

    // Additional debug helper functions
    private fun debugQrsComplex(lead: FloatArray, qrs: Map<String, Int>, leadName: String) {
        val qPoint = qrs["qPoint"]!!
        val rPeak = qrs["rPeak"]!!
        val sPoint = qrs["sPoint"]!!
        
        println(String.format(
            "%s QRS - Q:%d(%.3f) R:%d(%.3f) S:%d(%.3f)",
            leadName, qPoint, lead[qPoint], rPeak, lead[rPeak], sPoint, lead[sPoint]
            ))
    }


    fun detectPrInterval(lead: FloatArray): Int {
        val rPeaks = detectRPeaks(lead)
        if (rPeaks.isEmpty()) return 0
        val firstR = rPeaks.first()
        val pStartEstimate = (firstR - DEFAULT_SAMPLE_RATE * 0.2).toInt().coerceAtLeast(0)
        return firstR - pStartEstimate
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