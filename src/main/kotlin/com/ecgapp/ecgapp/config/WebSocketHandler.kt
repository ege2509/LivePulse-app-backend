package com.ecgapp.ecgapp.config

import com.ecgapp.ecgapp.service.RealtimeEcgService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

import org.slf4j.LoggerFactory

@Component
class EcgWebSocketHandler : TextWebSocketHandler() {
    
    private val logger = LoggerFactory.getLogger(EcgWebSocketHandler::class.java)
    
    @Autowired
    private lateinit var ecgService: RealtimeEcgService
    
    override fun afterConnectionEstablished(session: WebSocketSession) {
        // Extract userId from session attributes or URI parameters
        val userId = extractUserId(session)
        
        if (userId != null) {
            // Register this session with our ECG service
            ecgService.registerSession(userId, session)
            
            // Send a connection confirmation
            session.sendMessage(TextMessage("""
                {
                    "type": "CONNECTION_ESTABLISHED",
                    "userId": $userId,
                    "timestamp": ${System.currentTimeMillis()},
                    "message": "WebSocket connection established"
                }
            """.trimIndent()))
            
            logger.info("WebSocket connection established for user $userId")
        } else {
            // If userId is not provided, close the connection
            session.close(CloseStatus.BAD_DATA.withReason("Missing userId parameter"))
            logger.warn("Rejected WebSocket connection: missing userId parameter")
        }
    }
    
    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val userId = extractUserId(session)
        
        if (userId != null) {
            // Unregister this session
            ecgService.unregisterSession(userId)
            logger.info("WebSocket connection closed for user $userId: ${status.reason}")
        }
    }
    
    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        // If your frontend needs to send commands (like pausing, changing display settings)
        // you'd handle them here
        logger.debug("Received message from client: ${message.payload}")
        
        // For now, we'll just acknowledge receipt
        session.sendMessage(TextMessage("""
            {
                "type": "MESSAGE_RECEIVED",
                "timestamp": ${System.currentTimeMillis()},
                "originalMessage": ${message.payload.length} // sending length to avoid echo
            }
        """.trimIndent()))
    }
    
    /**
     * Extract userId from URL parameters
     */
    private fun extractUserId(session: WebSocketSession): Long? {
        val uri = session.uri ?: return null
        val query = uri.query ?: return null
        
        return query.split("&")
            .firstOrNull { it.startsWith("userId=") }
            ?.substringAfter("userId=")
            ?.toLongOrNull()
    }
}
