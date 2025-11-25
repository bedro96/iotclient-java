# IoT Client Java

Maven-based IoT client for Azure IoT Hub.

## Features

- **Retry Logic with Exponential Backoff**: Automatically reconnects on network disruptions with exponential delay starting at 30 seconds, doubling each retry up to a maximum of 960 seconds (16 minutes), with up to 10 retry attempts.
- **SDK Version Info**: Each message includes SDK version information in both the message payload and properties.
- **Device Identification**: Device ID and Model ID are transmitted with each message for device tracking and management.

## Configuration

Set the following environment variables:

```bash
# Required: IoT Hub connection string
export IOTHUB_DEVICE_CONNECTION_STRING="HostName=...;DeviceId=...;SharedAccessKey=..."

# Optional: Device identification (defaults provided)
export DEVICE_ID="your-device-id"        # Default: java-iot-device
export MODEL_ID="dtmi:com:example:iotdevice;1"  # Default: dtmi:com:example:iotdevice;1
```

## Message Format

Each telemetry message includes:

```json
{
  "temp": 20,
  "ts": "2024-01-01T00:00:00Z",
  "sdkVersion": "1.0.0",
  "deviceId": "java-iot-device",
  "modelId": "dtmi:com:example:iotdevice;1"
}
```

Message properties also include:
- `level`: info
- `sdkVersion`: SDK version string
- `deviceId`: Device identifier
- `modelId`: Device model identifier

## Building

```bash
cd iot-device-java21
mvn clean package
```

## Running

```bash
java -jar target/iot-device-java21-1.0-SNAPSHOT.jar
```
