# ECG Monitoring Backend

Kotlin-based backend server for processing real-time 12-lead ECG data and transmitting it to the mobile frontend via WebSocket.

## 🩺 Key Features
- Receives ECG data via TCP in 12-byte packets
- Processes and forwards data to Android app in real time using WebSocket
- Stores complete ECG recordings in a database for later access
- Manages user medical profiles (e.g., chronic illnesses, age, blood type)

## ⚙️ Tech Stack
- Kotlin
- TCP Sockets
- WebSocket
- MySQL

### Prerequisites
- Kotlin
- PostgreSQL
- JVM installed

