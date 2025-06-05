package com.ecgapp.ecgapp.controller

import com.ecgapp.ecgapp.service.EcgDiagnosticObserver
import com.ecgapp.ecgapp.service.RealtimeEcgService
import com.ecgapp.ecgapp.service.RealtimeEcgService.EcgDataPacket
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import jakarta.annotation.PostConstruct

@RestController
@RequestMapping("/api/ecg")
class EcgTestController : EcgDiagnosticObserver {
    
    @Autowired
    private lateinit var realtimeEcgService: RealtimeEcgService
    
    // Store active connections for monitoring
    private val activeConnections = ConcurrentHashMap<Long, ConnectionDiagnostics>()
    private val packetCounter = AtomicInteger(0)
    private val testInProgress = AtomicBoolean(false)

    
    @PostConstruct
    fun init() {
        realtimeEcgService.registerDiagnosticObserver(this)
    }
    
    data class ConnectionDiagnostics(
        val userId: Long,
        val connectionTime: Long = System.currentTimeMillis(),
        var lastPacketTime: Long = System.currentTimeMillis(),
        var packetsSent: Int = 0,
        var lastHeartRate: Int = 0,
        var lastAbnormality: String = "None",
        var errors: MutableList<String> = mutableListOf()
    )

    /**
     * Implementation of EcgDiagnosticObserver.registerConnection
     */
    override fun registerConnection(userId: Long) {
        activeConnections.computeIfAbsent(userId) { 
            ConnectionDiagnostics(userId) 
        }
    }

    /**
     * Implementation of EcgDiagnosticObserver.unregisterConnection
     */
    override fun unregisterConnection(userId: Long) {
        activeConnections.remove(userId)
    }

    /**
     * Implementation of EcgDiagnosticObserver.updateConnectionStats
     */
    override fun updateConnectionStats(userId: Long, packet: RealtimeEcgService.EcgDataPacket) {
        val diagnostics = activeConnections.computeIfAbsent(userId) { 
            ConnectionDiagnostics(userId) 
        }
        
        diagnostics.lastPacketTime = System.currentTimeMillis()
        diagnostics.packetsSent++
        diagnostics.lastHeartRate = packet.heartRate
        diagnostics.lastAbnormality = packet.abnormalities
            .maxByOrNull { it.value }?.key ?: "None"
    }

    @GetMapping("/websocket/status")
    fun getWebSocketStatus(): ResponseEntity<Map<String, Any>> {
        // Get the active sessions from the RealtimeEcgService
        val activeSessions = activeConnections.size
        val activeUsers = activeConnections.keys.size
        
        return ResponseEntity.ok(mapOf(
            "status" to "active",
            "activeSessions" to activeSessions,
            "activeUsers" to activeUsers,
            "testInProgress" to testInProgress.get(),
            "connections" to activeConnections.map { (userId, diag) ->
                mapOf(
                    "userId" to userId,
                    "lastActive" to (System.currentTimeMillis() - diag.lastPacketTime),
                    "packetsSent" to diag.packetsSent,
                    "lastHeartRate" to diag.lastHeartRate,
                    "lastAbnormality" to diag.lastAbnormality
                )
            },
            "timestamp" to System.currentTimeMillis()
        ))
    }
    
    /**
     * Simple ping endpoint to test REST API connectivity
     */
    @GetMapping("/ping")
    fun ping(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(mapOf(
            "status" to "ok",
            "timestamp" to System.currentTimeMillis(),
            "message" to "ECG Diagnostic API is running"
        ))
    }
    
    
    /**
     * Enhanced connections endpoint with detailed diagnostics
     */
    @GetMapping("/test/connections", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getActiveConnections(): ResponseEntity<Map<String, Any>> {
        // Clean up stale connections (older than 30 seconds)
        val currentTime = System.currentTimeMillis()
        val staleThreshold = 30000L
        
        val staleConnections = activeConnections.filter { (_, diagnostics) ->
            currentTime - diagnostics.lastPacketTime > staleThreshold
        }.keys
        
        staleConnections.forEach { activeConnections.remove(it) }
        
        // Build response with detailed diagnostics
        val activeDetails = activeConnections.map { (userId, diagnostics) ->
            mapOf(
                "userId" to userId,
                "connectedSince" to diagnostics.connectionTime,
                "lastActivityMs" to (currentTime - diagnostics.lastPacketTime),
                "packetsSent" to diagnostics.packetsSent,
                "lastHeartRate" to diagnostics.lastHeartRate,
                "lastAbnormality" to diagnostics.lastAbnormality,
                "errors" to diagnostics.errors
            )
        }
        
        return ResponseEntity.ok(mapOf(
            "activeCount" to activeConnections.size,
            "testInProgress" to testInProgress.get(),
            "timestamp" to currentTime,
            "connections" to activeDetails
        ))
    }

    
    /**
     * Generate test ECG data for all leads(TESTING  PURPOSES)
     */
    private fun generateTestEcgData(abnormal: Boolean): Array<FloatArray> {
        // Create a realistic ECG waveform pattern for each lead
        return Array(12) { lead ->
            FloatArray(20) { i ->
                // Base pattern values resembling PQRST complex
                val basePattern = when (i) {
                    0, 1, 19 -> 0.0f  // Baseline
                    2, 3 -> 0.1f + (lead % 3) * 0.05f  // P wave
                    4 -> 0.0f  // PR segment
                    5 -> -0.1f  // Q wave
                    6 -> -0.2f * (1 + lead % 2)  // Deeper Q for some leads
                    7 -> 0.0f  // Q-R segment
                    8 -> 1.0f + lead * 0.2f  // R wave
                    9 -> 1.5f + lead * 0.2f  // R peak
                    10 -> 0.5f  // R-S segment
                    11 -> -0.5f - (lead % 3) * 0.2f  // S wave
                    12 -> -0.3f  // S wave end
                    13 -> 0.0f  // ST segment
                    14 -> 0.2f  // T wave start
                    15 -> 0.4f + (lead % 2) * 0.1f  // T wave peak
                    16 -> 0.2f  // T wave end
                    17, 18 -> 0.0f  // TP segment
                    else -> 0.0f
                }
                
                // Apply lead-specific adjustments
                val leadFactor = when (lead) {
                    0 -> 1.0f  // Lead I
                    1 -> 1.2f  // Lead II
                    2 -> 0.7f  // Lead III
                    3 -> -0.5f  // aVR
                    4 -> 0.8f  // aVL
                    5 -> 1.1f  // aVF
                    6 -> 1.7f  // V1
                    7 -> 2.0f  // V2
                    8 -> 2.5f  // V3
                    9 -> 2.2f  // V4
                    10 -> 1.8f  // V5
                    11 -> 1.5f  // V6
                    else -> 1.0f
                }
                
                // For abnormal patterns, modify the waves
                val abnormalityFactor = if (abnormal) {
                    when (lead) {
                        6, 7 -> { // V1, V2 - make RSR' for RBBB simulation
                            if (i == 10) 0.8f else if (i == 11) 1.2f else 1.0f
                        }
                        0, 1 -> { // Leads I, II - wider S wave for RBBB
                            if (i == 11 || i == 12) 1.3f else 1.0f
                        }
                        else -> 1.0f
                    }
                } else 1.0f
                
                // Apply all factors and add small random noise
                val noise = (Math.random() * 0.05 - 0.025).toFloat()
                basePattern * leadFactor * abnormalityFactor + noise
            }
        }
    }
    
    /**
     * Helper endpoint to generate an intentional error - useful for debugging error handling
     */
    @GetMapping("/test/error")
    fun generateError(): ResponseEntity<Map<String, Any>> {
        try {
            throw RuntimeException("Intentional test error")
        } catch (e: Exception) {
            return ResponseEntity.internalServerError().body(mapOf(
                "success" to false,
                "error" to (e.message ?: "Unknown error"),
                "errorType" to e.javaClass.simpleName
            ))
        }
    }
}