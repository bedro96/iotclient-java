# IoT Client Java

Maven-based IoT client for Azure IoT Hub with WebSocket-based remote control capabilities. This application connects to a central IoT service server via WebSocket and receives commands to control an Azure IoT Hub device client.

## Architecture Overview

The application consists of two main components:

1. **SimulatorWSClient** - WebSocket client that connects to a remote IoT service server to receive commands and report status
2. **IotClient** - Azure IoT Hub device client that sends telemetry data using MQTT protocol

```
┌─────────────────────────────────────────────────────────────────┐
│                     Remote IoT Service Server                   │
│       (wss://iot-service-server...azurecontainerapps.io)        │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │ WebSocket (WSS)
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     SimulatorWSClient                           │
│  - Receives commands: start, stop, config update                │
│  - Reports status and events to server                          │
│  - Controls IotClient lifecycle                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ Controls
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                         IotClient                               │
│  - Connects to Azure IoT Hub via MQTT (port 8883)               │
│  - Sends telemetry messages with retry logic                    │
│  - Exponential backoff for connection failures                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ MQTT (8883)
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                       Azure IoT Hub                             │
└─────────────────────────────────────────────────────────────────┘
```

## Features

- **WebSocket Remote Control**: Connects to a central IoT service server to receive commands and report status
- **Retry Logic with Exponential Backoff**: Automatically reconnects on network disruptions with exponential delay starting at 30 seconds, doubling each retry up to a maximum of 960 seconds (16 minutes), with up to 10 retry attempts
- **Device Identification**: Each device is assigned a UUID and can be configured with a device ID and model ID
- **Configurable Parameters**: Device ID, connection string, retry settings can be updated remotely via WebSocket commands
- **Graceful Shutdown**: Proper cleanup of resources on application termination

## Project Structure

```
src/main/java/com/example/iot/
├── SimulatorWSClient.java  # Main entry point - WebSocket client
├── IotClient.java          # Azure IoT Hub device client
└── IotDeviceStatus.java    # Device status and sensor data generator
```

## Class Details

### SimulatorWSClient.java

The main entry point of the application. A WebSocket client that connects to a remote IoT service server.

**Key Features:**
- Connects to `wss://iot-service-server.wonderfulrock-1223eeed.koreacentral.azurecontainerapps.io/ws/`
- Generates a unique device UUID on startup
- Uses Jackson ObjectMapper for JSON message processing
- Implements JSR 356 WebSocket API annotations

**WebSocket Event Handlers:**
| Annotation | Method | Description |
|------------|--------|-------------|
| `@OnOpen` | `onOpen(Session)` | Called when WebSocket connection is established. Sends initial status and requests device_id from server |
| `@OnMessage` | `onMessage(String)` | Processes incoming commands from the server |
| `@OnClose` | `onClose(Session, CloseReason)` | Called when connection closes. Logs close reason and code |
| `@OnError` | `onError(Session, Throwable)` | Handles WebSocket errors and logs the exception |

**Supported Server Commands (action field):**
| Action | Description |
|--------|-------------|
| `device.start` | Starts the IotClient to begin sending telemetry |
| `device.stop` | Stops the IotClient from sending telemetry |
| `device.restart` | Restarts the IotClient (stops, waits 2 seconds, then starts) |
| `device.config.update` | Updates device configuration from payload |

**Configuration Update Payload Fields:**
| Field | Description |
|-------|-------------|
| `device_id` | Sets the device identifier |
| `IOTHUB_DEVICE_CONNECTION_STRING` | Sets the Azure IoT Hub connection string |
| `initialRetryTimeout` | Sets initial retry delay in seconds |
| `maxRetry` | Sets maximum number of retry attempts |
| `messageIntervalSeconds` | Sets interval between telemetry messages in seconds (default: 5) |

**Message Types (MessageType enum):**
| Type | Value | Usage |
|------|-------|-------|
| REQUEST | `"request"` | Requesting information from server |
| RESPONSE | `"response"` | Responding to server requests |
| EVENT | `"event"` | Reporting events and status changes |
| ERROR | `"error"` | Reporting error conditions |

**Outgoing Message Format:**
```json
{
  "version": "1.0",
  "type": "event|request|response|error",
  "id": "<device-uuid>",
  "correlation_id": "",
  "ts": "2024-01-01T00:00:00.000Z",
  "action": "",
  "status": "<status-message>",
  "payload": { "DEVICE_UUID": "<device-uuid>" },
  "meta": { "source": "simulator" }
}
```

### IotClient.java

Azure IoT Hub device client that sends telemetry data using MQTT protocol.

**Key Features:**
- Uses Azure IoT Hub SDK (`com.microsoft.azure.sdk.iot.device`)
- MQTT protocol for communication (port 8883)
- Exponential backoff retry for both connection and message sending
- Configurable through setter methods
- Non-blocking start/stop lifecycle management using ExecutorService
- Generates realistic sensor data using IotDeviceStatus

**Default Configuration Values:**
| Parameter | Default Value | Description |
|-----------|---------------|-------------|
| `DEVICE_ID` | `"javadevice001"` | Device identifier (from env `DEVICE_ID`) |
| `MODEL_ID` | `"dtmi:iotdevice"` | Device model identifier (from env `MODEL_ID`) |
| `PROTOCOL` | `MQTT` | IoT Hub client protocol |
| `INITIAL_RETRY_DELAY_SECONDS` | `30` | Initial retry delay |
| `MAX_RETRY_DELAY_SECONDS` | `960` | Maximum retry delay (~16 minutes) |
| `MAX_RETRIES` | `10` | Maximum number of retry attempts |
| `MESSAGE_INTERVAL_SECONDS` | `5` | Interval between telemetry messages |

**Public Methods:**
| Method | Parameters | Description |
|--------|------------|-------------|
| `run(String[] args)` | args: command line arguments | Main loop that connects to IoT Hub and sends telemetry |
| `start()` | None | Starts the IotClient worker in a separate thread (non-blocking). Returns Future for cancellation |
| `stop()` | None | Stops the IotClient worker and shuts down the executor |
| `setDeviceString(String)` | deviceId | Sets the device identifier |
| `setReadytoRun(boolean)` | ready | Controls whether the run loop should execute |
| `setInitialRetryDelaySeconds(int)` | seconds | Sets initial retry delay |
| `setMaxRetries(int)` | maxRetries | Sets maximum retry attempts |
| `setMaxRetryDelaySeconds(int)` | seconds | Sets maximum retry delay |
| `setMessageIntervalSeconds(int)` | seconds | Sets interval between telemetry messages |
| `setIothubConnectionString(String)` | connectionString | Sets IoT Hub connection string |

**Telemetry Message Format:**
```json
{
  "deviceId": "javadevice001",
  "Type": "Thermo-hygrometer",
  "modelId": "dtmi:iotdevice",
  "Status": "online",
  "temp": 25,
  "Humidity": 50,
  "ts": "2024-01-01T00:00:00.000Z"
}
```

**Message Properties:**
| Property | Value | Description |
|----------|-------|-------------|
| `Content-Type` | `application/json` | Message content type |
| `level` | `info` | Log level indicator |
| `deviceId` | `<device-id>` | Device identifier |
| `modelId` | `<model-id>` | Device model identifier |

**Retry Logic:**
- Connection retry: Exponential backoff starting at 30 seconds, doubling each attempt up to 960 seconds, maximum 10 attempts
- Message send retry: Same exponential backoff pattern, uses async scheduler to avoid blocking callback threads

### IotDeviceStatus.java

Device status and sensor data generator that simulates a thermo-hygrometer device.

**Key Features:**
- Generates realistic temperature and humidity values using Gaussian distribution
- Automatically determines device status based on sensor readings
- Thread-safe random number generation

**Device Status Values:**
| Status | Condition | Description |
|--------|-----------|-------------|
| `online` | Normal operation | Temperature ≤ 30°C and Humidity ≤ 70% |
| `warning` | Threshold exceeded | Temperature > 30°C or Humidity > 70% |
| `offline` | Not connected | Device is not connected (not currently used) |
| `maintenance` | Under maintenance | Device is being serviced (not currently used) |

**Sensor Data Generation:**
| Sensor | Mean | Standard Deviation | Description |
|--------|------|-------------------|-------------|
| Temperature | 25°C | ±5°C | Uses Gaussian distribution to generate realistic temperature values |
| Humidity | 50% | ±10% | Uses Gaussian distribution to generate realistic humidity values |

**Public Methods:**
| Method | Return Type | Description |
|--------|-------------|-------------|
| `getDeviceTemperature()` | int | Returns the current temperature reading |
| `getDeviceHumidity()` | int | Returns the current humidity reading |
| `getDeviceStatus()` | String | Returns the device status ("online", "warning", "offline", or "maintenance") |

## Configuration

### Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `IOTHUB_DEVICE_CONNECTION_STRING` | Yes | None | Azure IoT Hub connection string |
| `DEVICE_ID` | No | `javadevice001` | Device identifier |
| `MODEL_ID` | No | `dtmi:com:example:iotdevice` | Device model identifier |

```bash
# Required: IoT Hub connection string
export IOTHUB_DEVICE_CONNECTION_STRING="HostName=...;DeviceId=...;SharedAccessKey=..."

# Optional: Device identification (defaults provided)
export DEVICE_ID="your-device-id"
export MODEL_ID="dtmi:com:example:iotdevice;1"
```

### Remote Configuration via WebSocket

Configuration can be updated remotely by sending a `device.config.update` command:

```json
{
  "action": "device.config.update",
  "payload": {
    "device_id": "new-device-id",
    "IOTHUB_DEVICE_CONNECTION_STRING": "HostName=...;DeviceId=...;SharedAccessKey=...",
    "initialRetryTimeout": 60,
    "maxRetry": 5,
    "messageIntervalSeconds": 10
  }
}
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `com.microsoft.azure.sdk.iot:iot-device-client` | 2.1.5 | Azure IoT Hub SDK |
| `javax.websocket:javax.websocket-api` | 1.1 | WebSocket API |
| `org.glassfish.tyrus.bundles:tyrus-standalone-client` | 1.19 | WebSocket client implementation |
| `com.fasterxml.jackson.core:jackson-databind` | 2.15.2 | JSON processing |
| `org.glassfish.tyrus:tyrus-client` | 1.17 | Tyrus WebSocket client |
| `org.glassfish.tyrus:tyrus-container-grizzly-client` | 1.17 | Grizzly container for Tyrus |

## Building

### Prerequisites

- Java 21 or higher
- Maven 3.x

### Build Commands

```bash
# Build the application
mvn clean package

# This creates a fat JAR with all dependencies included:
# target/iot-device-java21-1.0-SNAPSHOT.jar
```

## Running

### Running Locally

```bash
# Run the application
java -jar target/iot-device-java21-1.0-SNAPSHOT.jar
```

### Running with Docker

```bash
# Build Docker image
docker build -t iot-client-java .

# Run container
docker run iot-client-java
```

### Docker Configuration

The Dockerfile uses:
- Base image: `eclipse-temurin:21-jre-jammy`
- Runs as unprivileged user (`appuser`)
- Configurable via `JAVA_OPTS` environment variable

## Network Requirements

| Port | Protocol | Direction | Purpose |
|------|----------|-----------|---------|
| 443 | WSS | Outbound | WebSocket connection to IoT service server |
| 8883 | MQTT | Outbound | Azure IoT Hub communication |

## CI/CD Pipeline

The repository includes a GitHub Actions workflow for continuous integration and deployment to Azure Container Registry.

**Workflow Features:**
- Automatically triggered on push/PR to main branch
- Builds Java application with Maven
- Creates and pushes Docker image to Azure Container Registry (ACR)
- Uses OIDC authentication for secure Azure access
- Implements dependency graph submission for security alerts

**Azure Resources Required:**
| Resource | Purpose |
|----------|---------|
| Azure Container Registry | Stores Docker images |
| Service Principal with OIDC | Authenticates GitHub Actions to Azure |
| Resource Group | Contains Azure resources |

**GitHub Secrets Required:**
| Secret | Description |
|--------|-------------|
| `AZURE_CLIENT_ID` | Azure service principal client ID |
| `AZURE_TENANT_ID` | Azure tenant ID |
| `AZURE_SUBSCRIPTION_ID` | Azure subscription ID |

**Docker Image Tags:**
The workflow generates the following tags:
- Git commit SHA (long format)
- Branch name (for branch pushes)
- PR reference (for pull requests)
- `latest` (for main branch only)

**Workflow File:**
`.github/workflows/maven.yml`

## Troubleshooting

### Common Issues

1. **WebSocket Connection Failed**
   - Check network connectivity to the IoT service server
   - Verify firewall allows outbound connections on port 443

2. **IoT Hub Connection Failed**
   - Verify the connection string is correct
   - Ensure firewall allows outbound connections on port 8883 (MQTT)
   - Check Azure IoT Hub service is available

3. **Retry Exhausted**
   - Error: `Failed to connect after 10 attempts. Giving up.`
   - Check network connectivity and IoT Hub availability
