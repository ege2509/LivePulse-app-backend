package com.ecgapp.ecgapp.config

import com.ecgapp.ecgapp.handlers.EcgWebSocketHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean

@Configuration
@EnableWebSocket
class WebSocketConfig(private val ecgWebSocketHandler: EcgWebSocketHandler) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(ecgWebSocketHandler, "/api/ecg/stream/{userId}")
               .setAllowedOrigins("*") // In production, restrict to your domain
    }
    
    @Bean
    fun createWebSocketContainer(): ServletServerContainerFactoryBean {
        val container = ServletServerContainerFactoryBean()
        // Use Java-style setters instead of property access
        container.setMaxTextMessageBufferSize(8192)
        container.setMaxBinaryMessageBufferSize(8192)
        container.setMaxSessionIdleTimeout(60000L) // 60 seconds
        return container
    }
}