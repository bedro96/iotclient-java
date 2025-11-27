package com.example.iot;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.microsoft.azure.sdk.iot.device.DeviceClient;
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol;
import com.microsoft.azure.sdk.iot.device.Message;
// maven dependency 추가 필요: com.microsoft.azure.sdk.iot:iot-device-client:1.36.3
package com.example;

import javax.websocket.*;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


public class App{

    // Device identification
    private static final String DEVICE_ID = System.getenv().getOrDefault("DEVICE_ID", "javadevice001");
    private static final String MODEL_ID = System.getenv().getOrDefault("MODEL_ID", "dtmi:com:example:iotdevice");

    // 보안을 위해 환경변수로 받아 사용 (직접 문자열 하드코딩 지양)
    // 설정: export IOTHUB_DEVICE_CONNECTION_STRING="HostName=...;DeviceId=...;SharedAccessKey=..."
    private static final String IOTHUB_DEVICE_CONNECTION_STRING = System.getenv("IOTHUB_DEVICE_CONNECTION_STRING");

    // MQTT 권장 (방화벽 포트 8883 필요) [5](https://learn.microsoft.com/en-us/azure/iot/tutorial-send-telemetry-iot-hub)
    private static final IotHubClientProtocol PROTOCOL = IotHubClientProtocol.MQTT;
    
    // Retry configuration
    private static final int INITIAL_RETRY_DELAY_SECONDS = 30;
    private static final int MAX_RETRY_DELAY_SECONDS = 960; // Max ~16 minutes
    private static final int MAX_RETRIES = 10;
    
    // Scheduler for async retry operations
    private static final ScheduledExecutorService retryScheduler = Executors.newScheduledThreadPool(1);

    public static void main(String[] args) throws Exception {
        if (IOTHUB_DEVICE_CONNECTION_STRING == null || IOTHUB_DEVICE_CONNECTION_STRING.isBlank()) {
            System.err.println("환경변수 IOTHUB_DEVICE_CONNECTION_STRING이 설정되지 않았습니다.");
            System.err.println("예) export IOTHUB_DEVICE_CONNECTION_STRING=\"HostName=...;DeviceId=...;SharedAccessKey=...\"");
            System.exit(1);
        }

        DeviceClient client = new DeviceClient(IOTHUB_DEVICE_CONNECTION_STRING, PROTOCOL);

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { 
                retryScheduler.shutdown();
                client.close(); 
            } catch (Exception ignored) {}
        }));

        // Connect with retry logic with exponential backoff
        connectWithRetry(client);

        // 간단한 텔레메트리 5건 전송
        CountDownLatch latch = new CountDownLatch(5);
        for (int i = 0; i < 5; i++) {
            String payload = String.format(
                "{\"temp\": %d, \"ts\": \"%s\", \"deviceId\": \"%s\", \"modelId\": \"%s\"}",
                20 + i, Instant.now(), DEVICE_ID, MODEL_ID);
            Message msg = new Message(payload.getBytes(StandardCharsets.UTF_8));
            msg.setContentType("application/json");
            msg.setProperty("level", "info");
            msg.setProperty("deviceId", DEVICE_ID);
            msg.setProperty("modelId", MODEL_ID);
            
            sendMessageWithRetry(client, msg, latch);

            Thread.sleep(1000);
        }
        latch.await();
        System.out.println("Done. Closing.");
        retryScheduler.shutdown();
        client.close();
    }
    
    /**
     * Connect to IoT Hub with exponential backoff retry logic.
     * Starts with 30 seconds delay and doubles each retry up to max delay.
     */
    private static void connectWithRetry(DeviceClient client) throws Exception {
        int retryCount = 0;
        int currentDelay = INITIAL_RETRY_DELAY_SECONDS;
        
        while (retryCount < MAX_RETRIES) {
            try {
                System.out.println("Opening connection to IoT Hub ...");
                client.open(true);
                System.out.println("Connected.");
                return; // Success, exit the retry loop
            } catch (Exception e) {
                retryCount++;
                if (retryCount >= MAX_RETRIES) {
                    System.err.printf("Failed to connect after %d attempts. Giving up.%n", MAX_RETRIES);
                    throw e;
                }
                System.err.printf("Connection failed (attempt %d/%d): %s%n", retryCount, MAX_RETRIES, e.getMessage());
                System.out.printf("Retrying in %d seconds...%n", currentDelay);
                Thread.sleep(currentDelay * 1000L);
                // Exponential backoff: double the delay for next retry
                currentDelay = Math.min(currentDelay * 2, MAX_RETRY_DELAY_SECONDS);
            }
        }
    }
    
    /**
     * Send message with retry logic for network disruptions.
     * Uses exponential backoff starting at 30 seconds.
     */
    private static void sendMessageWithRetry(DeviceClient client, Message msg, CountDownLatch latch) {
        sendMessageWithRetryInternal(client, msg, latch, 0, INITIAL_RETRY_DELAY_SECONDS);
    }
    
    private static void sendMessageWithRetryInternal(DeviceClient client, Message msg, CountDownLatch latch, 
                                                      int currentRetry, int currentDelay) {
        client.sendEventAsync(msg, (sentMessage, clientException, callbackContext) -> {
            if (clientException == null) {
                System.out.printf("Message ack: SUCCESS%n");
                latch.countDown();
            } else {
                if (currentRetry < MAX_RETRIES) {
                    System.err.printf("Message send failed (attempt %d/%d): %s%n", 
                        currentRetry + 1, MAX_RETRIES, clientException.getMessage());
                    System.out.printf("Retrying in %d seconds...%n", currentDelay);
                    int nextDelay = Math.min(currentDelay * 2, MAX_RETRY_DELAY_SECONDS);
                    // Schedule retry asynchronously to avoid blocking callback thread
                    retryScheduler.schedule(
                        () -> sendMessageWithRetryInternal(client, msg, latch, currentRetry + 1, nextDelay),
                        currentDelay, TimeUnit.SECONDS);
                } else {
                    System.err.printf("Message ack: FAILED after %d retries - %s%n", 
                        MAX_RETRIES, clientException.getMessage());
                    latch.countDown();
                }
            }
        }, null);
    }
}


@ClientEndpoint
public class SimulatorWSClient {

// 서버 주소 및 deviceId (필요에 따라 동적으로 할당 가능)
private static final String SERVER_URI = "ws://localhost:5555/ws/";
private static final String DEVICE_ID = "device123"; // 실제 환경에서는 서버에서 받아올 수 있음

private Session session;
private static CountDownLatch latch = new CountDownLatch(1);
private static final ObjectMapper objectMapper = new ObjectMapper();

// 서버로부터 명령 수신
@OnMessage
public void onMessage(String message) {
    System.out.println("Received from server: " + message);

    try {
        JsonNode json = objectMapper.readTree(message);
        String action = json.has("action") ? json.get("action").asText() : "";
        // 필요한 명령 파라미터 파싱
        int initialRetryTimeout = json.has("initial_retry_timeout") ? json.get("initial_retry_timeout").asInt() : 30;
        int maxRetry = json.has("max_retry") ? json.get("max_retry").asInt() : 10;
        String iotHubConnectionString = json.has("iot_hub_connection_string") ? json.get("iot_hub_connection_string").asText() : "";

        // 명령 처리
        switch (action) {
            case "start":
            System.out.println("서비스 시작 명령 수신");
            // 실제 서비스 시작 로직 구현
            break;
        case "stop":
            System.out.println("서비스 중단 명령 수신");
            // 실제 서비스 중단 로직 구현
            break;
        default:
            System.out.println("알 수 없는 명령: " + action);
        }
        // 서버에 결과/상태 보고 (예시)
        sendStatus("processed:" + action);
        }   
        catch (Exception e) {
        e.printStackTrace();
    }
}

// 서버와 연결되었을 때
@OnOpen
public void onOpen(Session session) {
    System.out.println("Connected to server");
    this.session = session;
    // 서버에 초기 상태 보고
    sendStatus("connected");
}

// 서버와 연결이 종료되었을 때
@OnClose
    public void onClose(Session session, CloseReason reason) {
    System.out.println("Connection closed: " + reason);
    latch.countDown();
}

// 에러 발생 시
@OnError
public void onError(Session session, Throwable throwable) {
    System.err.println("Error: " + throwable.getMessage());
}

// 서버에 상태/결과 보고 (JSON 형식)
public void sendStatus(String status) {
    try {
        String json = String.format("{\"status\":\"%s\", \"deviceId\":\"%s\"}", status, DEVICE_ID);
        session.getAsyncRemote().sendText(json);
    } catch (Exception e) {
        e.printStackTrace();
    }
}

// 클라이언트 실행
public static void main(String[] args) {
    try {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        String uri = SERVER_URI + DEVICE_ID;

        container.connectToServer(IoTClient.class, URI.create(uri));
        latch.await(); // 연결 종료까지 대기
        } catch (Exception e) {
        e.printStackTrace();
        }
    }
}