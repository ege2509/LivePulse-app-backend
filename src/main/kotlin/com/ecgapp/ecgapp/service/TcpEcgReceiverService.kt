package com.ecgapp.ecgapp.service

import com.ecgapp.ecgapp.models.EcgRecording
import com.ecgapp.ecgapp.models.MedicalInfo
import com.ecgapp.ecgapp.repository.EcgRecordingRepository
import org.json.JSONArray
import org.json.JSONObject
import com.ecgapp.ecgapp.repository.MedicalInfoRepository
import com.ecgapp.ecgapp.service.RealtimeEcgService
import com.ecgapp.ecgapp.service.ActiveEcgUserService
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
import java.io.DataInputStream
import java.util.Base64

@Service
class TcpEcgReceiverService(
    private val ecgRepo: EcgRecordingRepository,
    private val medicalInfoRepo: MedicalInfoRepository,
    private val realtimeEcgService: RealtimeEcgService,
    private val messagePublisher: EcgMessagePublisher,
    private val activeEcgUserService: ActiveEcgUserService 
) {
    // ECG server settings
    private val port = 9090 // TCP server port
    
    // Set the data format here - this will be used by RealtimeEcgService
    private val dataFormat = DataFormat.SCALED_8BIT
    
    // Constants for data formats
    enum class DataFormat {
        FLOAT_MV,      // Raw float values in millivolts
        SCALED_8BIT,   // Normalized to 0-255 range
        SCALED_7BIT    // Normalized to 0-127 range
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
        println("TCP Server listening on port $port for ECG data (Format: $dataFormat)")
        
        while (true) {
            val socket = serverSocket.accept()
            println("ECG device connected from ${socket.inetAddress.hostAddress}")
            
            // Generate anonymous user ID for this connection
            val userId = activeEcgUserService.getCurrentUserId() ?: ++anonymousUserIdCounter

            println("Processing ECG data for user ID: $userId")
            
            // Emit connection event
            connectionFlow.emit(
                DeviceConnectionEvent(
                    deviceAddress = socket.inetAddress.hostAddress,
                    connectionTime = System.currentTimeMillis(),
                    connected = true,
                    anonymousUserId = userId
                )
            )
            
            // Notify WebSocket clients about new connection using the interface
            val connectionEvent = JSONObject()
            connectionEvent.put("event", "connection")
            connectionEvent.put("userId", userId)
            connectionEvent.put("status", "connected")
            messagePublisher.publishToAll(connectionEvent.toString())
            
            // Launch a coroutine to handle this connection
            launch {
                try {
                    val input = socket.getInputStream()
                    var buffer = ByteArray(1024) // Initial buffer size
                    var isConnected = true
                    
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
                            
                            realtimeEcgService.processRawData(dataBuffer, dataFormat, userId)
                            
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
                    
                    // Notify WebSocket clients about disconnection using the interface
                    val disconnectionEvent = JSONObject()
                    disconnectionEvent.put("event", "connection")
                    disconnectionEvent.put("userId", userId)
                    disconnectionEvent.put("status", "disconnected")
                    messagePublisher.publishToAll(disconnectionEvent.toString())
                    
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
        val connected: Boolean,
        val anonymousUserId: Long = -1
    )
}