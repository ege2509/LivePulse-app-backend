package com.ecgapp.ecgapp.services

import com.ecgapp.ecgapp.models.EcgRecording
import com.ecgapp.ecgapp.models.MedicalInfo
import com.ecgapp.ecgapp.repository.EcgRecordingRepository
import com.ecgapp.ecgapp.repository.MedicalInfoRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service
import java.io.InputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import kotlin.math.abs
import jakarta.annotation.PostConstruct // If you're using Spring Boot 3+
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Service
class TcpEcgReceiverService(
    private val ecgRepo: EcgRecordingRepository,
    private val medicalInfoRepo: MedicalInfoRepository,
    private val realtimeEcgService: RealtimeEcgService // Inject the RealtimeEcgService
) {
    // ECG server settings
    private val port = 9090 // TCP server port
    
    // Match the same constants as in RealtimeEcgService for consistency
    companion object {
        const val DEFAULT_SAMPLE_RATE = 400 // Hz - matching the dataset sample rate
        const val DEFAULT_NUM_LEADS = 12 // Standard 12-lead ECG
        const val MILLIVOLTS_PER_BIT = 0.001f
        
        // Processing settings
        const val PROCESSING_CHUNK_SIZE = 800 // Process 2 seconds worth of data at a time
    }
    
    // Flow for broadcasting ECG connection events
    private val connectionFlow = MutableSharedFlow<DeviceConnectionEvent>()
    
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
        println("TCP Server listening on port $port")
        
        while (true) {
            val socket = serverSocket.accept()
            println("✅ ECG device connected from ${socket.inetAddress.hostAddress}")
            
            // Emit connection event
            connectionFlow.emit(
                DeviceConnectionEvent(
                    deviceAddress = socket.inetAddress.hostAddress,
                    connectionTime = System.currentTimeMillis(),
                    connected = true
                )
            )
            
            // Launch a coroutine to handle this connection
            launch {
                try {
                    // In this example, we assume user ID is provided in the first few bytes
                    val input = socket.getInputStream()
                    val userIdBuffer = ByteArray(8) // 8 bytes for long user ID
                    input.read(userIdBuffer)
                    val userId = ByteBuffer.wrap(userIdBuffer).order(ByteOrder.LITTLE_ENDIAN).long
                    
                    println("Processing data for user ID: $userId")
                    
                    // Continuously read and process data from this connection
                    var isConnected = true
                    while (isConnected) {
                        try {
                            // Read data in chunks
                            val headerBuffer = ByteArray(4) // 4 bytes for int size header
                            val bytesRead = input.read(headerBuffer)
                            
                            if (bytesRead < 0) {
                                isConnected = false
                                break
                            }
                            
                            // Parse the incoming data size
                            val dataSize = ByteBuffer.wrap(headerBuffer).order(ByteOrder.LITTLE_ENDIAN).int
                            val dataBuffer = ByteArray(dataSize)
                            
                            // Read the full data packet
                            var totalBytesRead = 0
                            while (totalBytesRead < dataSize) {
                                val read = input.read(dataBuffer, totalBytesRead, dataSize - totalBytesRead)
                                if (read < 0) {
                                    isConnected = false
                                    break
                                }
                                totalBytesRead += read
                            }
                            
                            if (!isConnected) break
                            
                            // Process the data through the RealtimeEcgService
                            realtimeEcgService.processIncomingData(dataBuffer, userId)
                            
                            // Every N chunks, save an ECG recording
                            // This could be triggered by a specific command instead
                            if (Math.random() < 0.05) { // ~5% chance, just for demonstration
                                val recording = realtimeEcgService.finalizeRecording(userId)
                                if (recording != null) {
                                    ecgRepo.save(recording)
                                    println("ECG recording saved (ID: ${recording.id})")
                                }
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
                            connected = false
                        )
                    )
                    
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
     * Data class for device connection events
     */
    data class DeviceConnectionEvent(
        val deviceAddress: String,
        val connectionTime: Long,
        val connected: Boolean
    )
}