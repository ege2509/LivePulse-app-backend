package com.ecgapp.ecgapp.service

interface EcgMessagePublisher {
    fun publishToUser(userId: Long, message: String)
    fun publishToAll(message: String)
    fun publishBinaryToUser(userId: Long, data: ByteArray)
    fun publishBinaryToAll(data: ByteArray)
}
