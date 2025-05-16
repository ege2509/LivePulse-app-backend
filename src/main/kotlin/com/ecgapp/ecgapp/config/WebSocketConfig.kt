package com.ecgapp.ecgapp.config

import com.ecgapp.ecgapp.config.EcgWebSocketHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfig : WebSocketConfigurer {

    @Bean
    fun ecgWebSocketHandler(): EcgWebSocketHandler {
        return EcgWebSocketHandler()
    }
    @Bean
    fun replayWebSocketHandler(): EcgWebSocketHandler {
        return EcgWebSocketHandler()
    }

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(ecgWebSocketHandler(), "/ws/ecg")
               .setAllowedOrigins("*")
        registry.addHandler(replayWebSocketHandler(), "/ws/replay")
                .setAllowedOrigins("*")  // In production, restrict to your frontend domain
    }