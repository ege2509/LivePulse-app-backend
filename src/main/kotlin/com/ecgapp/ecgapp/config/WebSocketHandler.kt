package com.ecgapp.ecgapp.handlers

import com.ecgapp.ecgapp.services.RealtimeEcgService
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Component
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.AbstractWebSocketHandler
import org.springframework.web.util.UriTemplate
import java.util.concurrent.ConcurrentHashMap

@Component
class EcgWebSocketHandler(private val realtimeEcgService: RealtimeEcgService) : AbstractWebSocketHandler() {

    private val sessions = ConcurrentHashMap<String, Long>()
    private val uriTemplate = UriTemplate("/api/ecg/stream/{userId}")

    override fun afterConnectionEstablished(session: WebSocketSession) {
        // Extract user ID from path
        val userId = extractUserId(session)
        if (userId != null) {
            sessions[session.id] = userId
            realtimeEcgService.registerSession(userId, session)
            println("WebSocket connection established for user $userId")
        } else {
            session.close(CloseStatus.BAD_DATA.withReason("Invalid user ID"))
        }
    }

    override fun handleBinaryMessage(session: WebSocketSession, message: BinaryMessage) {
        val userId = sessions[session.id] ?: return
        
        // Process incoming binary data
        runBlocking {
            realtimeEcgService.processIncomingData(message.payload.array(), userId)
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val userId = sessions.remove(session.id)
        if (userId != null) {
            realtimeEcgService.unregisterSession(userId)
            
            // Finalize the recording when the session closes
            runBlocking {
                val recording = realtimeEcgService.finalizeRecording(userId)
                if (recording != null) {
                    println("Finalized ECG recording for user $userId (ID: ${recording.id})")
                }
            }
        }
    }

    private fun extractUserId(session: WebSocketSession): Long? {
        val uri = session.uri ?: return null
        val match = uriTemplate.match(uri.path)
        val userIdStr = match["userId"] ?: return null
        
        return try {
            userIdStr.toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }
}