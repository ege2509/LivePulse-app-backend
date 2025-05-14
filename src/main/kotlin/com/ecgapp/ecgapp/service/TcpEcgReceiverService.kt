package com.ecgapp.ecgapp.services

import com.ecgapp.ecgapp.models.EcgRecording
import com.ecgapp.ecgapp.models.MedicalInfo
import com.ecgapp.ecgapp.repository.EcgRecordingRepository
import org.json.JSONArray
import org.json.JSONObject
import com.ecgapp.ecgapp.repository.MedicalInfoRepository
import com.ecgapp.ecgapp.service.RealtimeEcgService
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.BinaryMessage
import com.ecgapp.ecgapp.config.EcgWebSocketHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import jakarta.annotation.PostConstruct 
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Service
class TcpEcgReceiverService(
    private val ecgRepo: EcgRecordingRepository,
    private val medicalInfoRepo: MedicalInfoRepository,
    private val realtimeEcgService: RealtimeEcgService,
    private val webSocketHandler: EcgWebSocketHandler
) {
    // ECG server settings
    private val port = 9090 // TCP server port
    
    // Constants for data formats
    companion object {
        const val DEFAULT_SAMPLE_RATE = 400 // Hz - matching the dataset sample rate
        const val DEFAULT_NUM_LEADS = 12 // Standard 12-lead ECG
        
        // Constants for the 8-bit normalized integer data format
        const val MAX_VALUE_8BIT = 255
        const val CENTER_VALUE_8BIT = 127.5f
        
        // Constants for the 7-bit normalized integer data format
        const val MAX_VALUE_7BIT = 127
        const val CENTER_VALUE_7BIT = 63.5f
        
        // Thresholds for automatic format detection
        const val FLOAT_MAX_THRESHOLD = 10.0f // Typical ECG values in mV are smaller than this
        const val FLOAT_MIN_THRESHOLD = -10.0f // Typical ECG values in mV are larger than this
        const val SCALED_MIN_THRESHOLD = 0 // Scaled integer values should be non-negative
        const val SCALED_8BIT_MAX_THRESHOLD = 255 // 8-bit max value
        const val SCALED_7BIT_MAX_THRESHOLD = 127 // 7-bit max value
        
        // Lead names in standard order
        val LEAD_NAMES = arrayOf("I", "II", "III", "aVL", "aVR", "aVF", "V1", "V2", "V3", "V4", "V5", "V6")
    }
    
    // Flow for broadcasting ECG connection events
    private val connectionFlow = MutableSharedFlow<DeviceConnectionEvent>()
    
    // Generate a sequential user ID for anonymous connections
    private var anonymousUserIdCounter = 0L
    
    @PostConstruct
    fun startTcpServer() {
        CoroutineScope(Dispatchers.IO).launch {
            listenForData()
        }
    }
    
    /**
     * Get the connection event flow for subscribers
     */
    fun getConnectionFlow(): Flow<DeviceConnectionEvent> = connectionFlow
    
    /**
     * Start TCP server and listen for incoming ECG data
     */
    suspend fun listenForData() = withContext(Dispatchers.IO) {
        val serverSocket = ServerSocket(port)
        println("TCP Server listening on port $port for ECG data")
        
        while (true) {
            val socket = serverSocket.accept()
            println("ECG device connected from ${socket.inetAddress.hostAddress}")
            
            // Generate anonymous user ID for this connection
            val userId = synchronized(this) { ++anonymousUserIdCounter }
            
            // Emit connection event
            connectionFlow.emit(
                DeviceConnectionEvent(
                    deviceAddress = socket.inetAddress.hostAddress,
                    connectionTime = System.currentTimeMillis(),
                    connected = true,
                    anonymousUserId = userId
                )
            )
            
            // Also notify WebSocket clients about new connection
            webSocketHandler.sendToAllClients("""{"event":"connection","userId":$userId,"status":"connected"}""")
            
            // Launch a coroutine to handle this connection
            launch {
                try {
                    val input = socket.getInputStream()
                    var buffer = ByteArray(1024) // Initial buffer size
                    var isConnected = true
                    
                    // Keep track of detected format for this connection
                    var detectedFormat: DataFormat? = null
                    
                    println("Processing ECG data for anonymous user ID: $userId")
                    
                    while (isConnected) {
                        try {
                            // Read data chunk
                            val bytesRead = input.read(buffer)
                            
                            if (bytesRead <= 0) {
                                isConnected = false
                                break
                            }
                            
                            // Create the actual data buffer with only the bytes read
                            val dataBuffer = buffer.copyOfRange(0, bytesRead)
                            
                            // Forward raw data to WebSocket clients
                            // Option 1: Send as binary message
                            webSocketHandler.sendToAllClients(BinaryMessage(dataBuffer))
                            
                            // OR Option 2: Send as JSON with Base64 encoded data
                            // val base64Data = Base64.getEncoder().encodeToString(dataBuffer)
                            // webSocketHandler.sendToAllClients("""{"type":"ecgData","userId":$userId,"data":"$base64Data"}""")
                            
                            // Process the ECG data based on format
                            val ecgData = processEcgData(dataBuffer, detectedFormat)
                            
                            // Update the detected format if we've identified it
                            if (ecgData != null) {
                                detectedFormat = ecgData.first
                                
                                // Process the ECG data through the RealtimeEcgService
                                realtimeEcgService.processData(ecgData.second, userId)
                                
                                // Convert processed data to JSON and send to WebSocket clients
                                val processedDataJson = convertProcessedDataToJson(ecgData.second, userId)
                                webSocketHandler.sendToAllClients(processedDataJson)
                                
                                /*// Every N chunks, save an ECG recording (optional)
                                if (Math.random() < 0.05) { // ~5% chance, just for demonstration
                                    val recording = realtimeEcgService.finalizeRecording(userId)
                                    if (recording != null) {
                                        ecgRepo.save(recording)
                                        println("ECG recording saved for anonymous user ID: $userId")
                                    }
                                }*/
                            }
                        } catch (e: Exception) {
                            println("Error processing ECG data: ${e.message}")
                            e.printStackTrace()
                            isConnected = false
                        }
                    }
                    
                    // Device disconnected
                    println("ECG device disconnected: ${socket.inetAddress.hostAddress}")
                    connectionFlow.emit(
                        DeviceConnectionEvent(
                            deviceAddress = socket.inetAddress.hostAddress,
                            connectionTime = System.currentTimeMillis(),
                            connected = false,
                            anonymousUserId = userId
                        )
                    )
                    
                    // Also notify WebSocket clients about disconnection
                    webSocketHandler.sendToAllClients("""{"event":"connection","userId":$userId,"status":"disconnected"}""")
                    
                    socket.close()
                } catch (e: Exception) {
                    println("Error with ECG device connection: ${e.message}")
                    e.printStackTrace()
                    socket.close()
                }
            }
        }
    }
    
    /**
     * Convert processed ECG data to JSON format suitable for WebSocket
     */
    /**
 * Convert processed ECG data to JSON format suitable for WebSocket
 */
        private fun convertProcessedDataToJson(ecgData: Array<FloatArray>, userId: Long): String {
        // Handle empty data case
        if (ecgData.isEmpty() || ecgData[0].isEmpty()) {
            val emptyJson = JSONObject().apply {
                put("userId", userId)
                put("timestamp", System.currentTimeMillis())
                put("ecgData", JSONArray())
                put("heartRate", 0)
            }
            return emptyJson.toString()
        }
        
        // Create JSON structure using JSONObject and JSONArray
        val rootJson = JSONObject()
        val ecgDataArray = JSONArray()
        
        // Add each lead's data as a JSONArray
        for (leadIndex in ecgData.indices) {
            val leadArray = JSONArray()
            for (sampleIndex in ecgData[leadIndex].indices) {
                leadArray.put(ecgData[leadIndex][sampleIndex])
            }
            ecgDataArray.put(leadArray)
        }
        
        val heartRate = realtimeEcgService.calculateHeartRate(ecgData[1])
        val timestamp = System.currentTimeMillis()
        
        // Build the final JSON object
        rootJson.apply {
            put("userId", userId)
            put("timestamp", timestamp)
            put("ecgData", ecgDataArray)
            put("heartRate", heartRate)
        }
        
        return rootJson.toString()
    }
    
    /**
     * Process incoming ECG data, auto-detecting the format
     * Returns the detected format and the processed data in mV
     */
    private fun processEcgData(dataBuffer: ByteArray, previousFormat: DataFormat?): Pair<DataFormat, Array<FloatArray>>? {
    try {
        // Use float format for now and ignore other formats
        return processBinaryEcgData(dataBuffer, DataFormat.FLOAT_MV)
        
        // Legacy code for other formats (commented out)
        /* 
        // First, try to parse as comma-separated string
        val dataString = String(dataBuffer).trim()
        if (dataString.contains(',')) {
            return processStringEcgData(dataString, previousFormat)
        }
        
        // If not string, try binary formats
        return processBinaryEcgData(dataBuffer, previousFormat)
        */
    } catch (e: Exception) {
        println("Failed to process ECG data: ${e.message}")
        e.printStackTrace()
        return null
    }
}

/**
 * Process binary ECG data in float format
 */
    private fun processBinaryEcgData(dataBuffer: ByteArray, format: DataFormat): Pair<DataFormat, Array<FloatArray>>? {
        try {
            val buffer = ByteBuffer.wrap(dataBuffer).order(ByteOrder.LITTLE_ENDIAN)
            
            // For float format: 4 bytes per value * 12 leads = 48 bytes per sample
            val bytesPerSample = 48
            
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
                        println("Buffer underflow at sample $sampleIndex, lead $leadIndex")
                        return Pair(DataFormat.FLOAT_MV, ecgData)
                    }
                }
            }
            
            println("Processed ${numSamples} samples of float ECG data")
            return Pair(DataFormat.FLOAT_MV, ecgData)
        } catch (e: Exception) {
            println("Error processing binary ECG data: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

// The other format processing functions can remain but will be unused for now
// processStringEcgData and detectBinaryFormat will remain unchanged
    
    /**
     * Attempt to detect the binary data format based on the byte patterns
     */
    private fun detectBinaryFormat(dataBuffer: ByteArray): DataFormat? {
        if (dataBuffer.size < 48) {
            // Try 8-bit or 7-bit format first if buffer is small
            val allWithin8BitRange = dataBuffer.all { (it.toInt() and 0xFF) <= MAX_VALUE_8BIT }
            val allWithin7BitRange = dataBuffer.all { (it.toInt() and 0xFF) <= MAX_VALUE_7BIT }
            
            return when {
                allWithin7BitRange -> DataFormat.SCALED_7BIT
                allWithin8BitRange -> DataFormat.SCALED_8BIT
                else -> null
            }
        }
        
        // First check if it could be float format by examining patterns
        val buffer = ByteBuffer.wrap(dataBuffer).order(ByteOrder.LITTLE_ENDIAN)
        
        // Try reading the first 12 values as floats
        val sampleFloats = Array(12) { 0.0f }
        try {
            for (i in 0 until 12) {
                sampleFloats[i] = buffer.getFloat()
            }
            
            // Check if all values are in reasonable range for mV
            val allInMvRange = sampleFloats.all { it in FLOAT_MIN_THRESHOLD..FLOAT_MAX_THRESHOLD }
            if (allInMvRange) {
                println("Detected FLOAT_MV format")
                return DataFormat.FLOAT_MV
            }
        } catch (e: Exception) {
            // If reading floats failed, continue to try other formats
        }
        
        // Reset position to beginning
        buffer.position(0)
        
        // Try reading the first 12 values as bytes and see if they fit 7-bit or 8-bit range
        val sampleBytes = Array(12) { 0 }
        try {
            for (i in 0 until 12) {
                sampleBytes[i] = buffer.get().toInt() and 0xFF
            }
            
            val allWithin7BitRange = sampleBytes.all { it <= MAX_VALUE_7BIT }
            val allWithin8BitRange = sampleBytes.all { it <= MAX_VALUE_8BIT }
            
            return when {
                allWithin7BitRange -> {
                    // Additional check: See if most values are closer to 7-bit center
                    val avg = sampleBytes.average()
                    if (abs(avg - CENTER_VALUE_7BIT) < abs(avg - CENTER_VALUE_8BIT)) {
                        println("Detected SCALED_7BIT format")
                        DataFormat.SCALED_7BIT
                    } else {
                        println("Detected SCALED_8BIT format (with values in 7-bit range)")
                        DataFormat.SCALED_8BIT
                    }
                }
                allWithin8BitRange -> {
                    println("Detected SCALED_8BIT format")
                    DataFormat.SCALED_8BIT
                }
                else -> null
            }
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Convert a raw value to millivolts based on the data format
     */
    private fun convertToMv(rawValue: Float, format: DataFormat): Float {
        return when (format) {
            DataFormat.FLOAT_MV -> rawValue // Already in mV
            DataFormat.SCALED_8BIT -> (rawValue - CENTER_VALUE_8BIT) / (MAX_VALUE_8BIT / 2) * 1.0f
            DataFormat.SCALED_7BIT -> (rawValue - CENTER_VALUE_7BIT) / (MAX_VALUE_7BIT / 2) * 1.0f
        }
    }
    
    /**
     * Enum for supported data formats
     */
    enum class DataFormat {
        FLOAT_MV,      // Raw float values in millivolts
        SCALED_8BIT,   // Normalized to 0-255 range
        SCALED_7BIT    // Normalized to 0-127 range
    }
    
    /**
     * Data class for device connection events
     */
    data class DeviceConnectionEvent(
        val deviceAddress: String,
        val connectionTime: Long,
        val connected: Boolean,
        val anonymousUserId: Long = -1
    )
}