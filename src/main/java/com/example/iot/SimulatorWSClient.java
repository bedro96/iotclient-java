package com.example.iot;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@ClientEndpoint
public class SimulatorWSClient {

// 서버 주소 및 deviceId (필요에 따라 동적으로 할당 가능)
// private static final String SERVER_URI = "ws://kukovm.koreacentral.cloudapp.azure.com:5555/ws/";
private static final String SERVER_URI = "wss://iot-service-server.wonderfulrock-1223eeed.koreacentral.azurecontainerapps.io/ws/";
// private String DEVICE_ID = "device123"; // 실제 환경에서는 서버에서 받아올 수 있음
private static final String DEVICE_UUID = get_UuidString();
private String IOTHUB_DEVICE_CONNECTION_STRING;
private String received_DEVICE_ID;
private int initialRetryTimeout;
private int maxRetry;
private enum MessageType {
    REQUEST("request"),
    RESPONSE("response"),
    EVENT("event"),
    ERROR("error");

    private final String value;

    MessageType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}

private boolean isReadytoSend = false;
private Session session;
private static CountDownLatch latch = new CountDownLatch(1);
private static final ObjectMapper objectMapper = new ObjectMapper();

// Static으로 변경하여 모든 인스턴스가 같은 iotClient를 공유
private static IotClient iotClient;
private static Thread iotClientThread;
// 서버로부터 명령 수신
@OnMessage
public void onMessage(String message) {
    System.out.println("=== @OnMessage triggered ===");
    System.out.println("This instance hashCode: " + this.hashCode());
    System.out.println("Received from server: " + message);

    try {
        JsonNode json = objectMapper.readTree(message);
        String action = json.has("action") ? json.get("action").asText() : "";
        System.out.println("Parsed action: '" + action + "'");
        System.out.println("iotClient static reference: " + (iotClient != null ? "exists" : "null"));

        // 명령 처리
        switch (action) {
            case "device.start":
                System.out.println("서비스 시작 명령 수신");
                isReadytoSend = true;
                synchronized (SimulatorWSClient.class) {
                    if (iotClient == null) {
                        System.out.println("iotClient가 null이므로 새로 생성합니다...");
                        iotClient = new IotClient();
                    }
                    try {
                        iotClient.start();
                        iotClient.setReadytoRun(true);
                        System.out.println("iotClient.start() 및 setReadytoRun(true) 호출 완료");
                    } catch (Exception e) {
                        System.err.println("iotClient 시작/설정 중 오류: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                // 실제 서비스 시작 로직 구현
                break;
        case "device.stop":
                System.out.println("서비스 중단 명령 수신");
                isReadytoSend = false;
                if (iotClient != null) {
                    iotClient.setReadytoRun(false);
                    System.out.println("iotClient.setReadytoRun(false) 호출 완료");
                } else {
                    System.err.println("경고: iotClient가 null입니다!");
                }
                // 실제 서비스 중단 로직 구현
                break;
        case "device.config.update":
                System.out.println("구성 업데이트 명령 수신");
                if (json.has("payload")) {
                    JsonNode payload = json.get("payload");
                    // payload에서 구성 파라미터 추출
                    if (payload.has("device_id")) {
                        received_DEVICE_ID = payload.get("device_id").asText();
                        if (iotClient != null) {
                            iotClient.setDeviceString(received_DEVICE_ID);
                        }
                        System.out.println("구성 업데이트 - device_id: " + received_DEVICE_ID);
                    }
                    if (payload.has("IOTHUB_DEVICE_CONNECTION_STRING")) {
                        IOTHUB_DEVICE_CONNECTION_STRING = payload.get("IOTHUB_DEVICE_CONNECTION_STRING").asText();
                        if (iotClient != null) {
                            iotClient.setIothubConnectionString(IOTHUB_DEVICE_CONNECTION_STRING);
                        }
                        System.out.println("구성 업데이트 - iot_hub_connection_string: " + IOTHUB_DEVICE_CONNECTION_STRING);
                    }
                    if (payload.has("initialRetryTimeout")) {
                        initialRetryTimeout = payload.get("initialRetryTimeout").asInt();
                        if (iotClient != null) iotClient.setInitialRetryDelaySeconds(initialRetryTimeout);
                        System.out.println("구성 업데이트 - initial_retry_timeout: " + initialRetryTimeout);
                    }
                    if (payload.has("maxRetry")) {
                        maxRetry = payload.get("maxRetry").asInt();
                        if (iotClient != null) iotClient.setMaxRetries(maxRetry);
                        System.out.println("구성 업데이트 - max_retry: " + maxRetry);
                    }
                    isReadytoSend = true;
                    // 추가 구성 파라미터 처리 가능
                }   
                // 구성 업데이트 로직 구현
                break;
            default:
                System.out.println("알 수 없는 명령: '" + action + "'");
                System.out.println("전체 JSON: " + json.toString());
        }
        
        // 서버에 결과/상태 보고 (예시)
        if (!action.isEmpty()) {
            sendMessage(MessageType.EVENT, "processed:" + action, "");
        }
    } catch (Exception e) {
        System.err.println("메시지 처리 중 오류 발생:");
        e.printStackTrace();
    }
}

// 서버와 연결되었을 때
@OnOpen
public void onOpen(Session session) {
    System.out.println("=== WebSocket Connected to server ===");
    System.out.println("Session ID: " + session.getId());
    System.out.println("Session isOpen: " + session.isOpen());
    System.out.println("This instance hashCode: " + this.hashCode());
    this.session = session;
    
    // 서버에 초기 상태 보고
    System.out.println("Sending initial messages...");
    sendMessage(MessageType.EVENT, "connected", "");
    sendMessage(MessageType.REQUEST, "device need device_id", "");
    
    // IotClient 인스턴스 생성 및 초기화 (한 번만)
    synchronized (SimulatorWSClient.class) {
        if (iotClient == null) {
            System.out.println("Initializing IotClient...");
            iotClient = new IotClient();
            System.out.println("IotClient instance created: " + (iotClient != null));
        } else {
            System.out.println("IotClient already initialized. Reusing existing instance.");
        }
    }
    // Start IotClient worker thread (non-blocking)
    try {
        iotClient.start();
        System.out.println("IotClient.start() invoked. Worker state: " + iotClient.getWorkerState());
    } catch (Exception e) {
        System.err.println("Failed to start IotClient: " + e.getMessage());
    }
}

// 서버와 연결이 종료되었을 때
@OnClose
public void onClose(Session session, CloseReason reason) {
    System.out.println("=== @OnClose triggered ===");
    System.out.println("Connection closed: " + reason);
    System.out.println("Close code: " + reason.getCloseCode());
    System.out.println("Reason phrase: " + reason.getReasonPhrase());
    try {
        sendMessage(MessageType.EVENT, "connection closed", "");
    } catch (Exception e) {
        System.err.println("Error sending close message: " + e.getMessage());
    }
    latch.countDown();
}

// 에러 발생 시
@OnError
public void onError(Session session, Throwable throwable) {
    System.err.println("=== @OnError triggered ===");
    System.err.println("Error: " + throwable.getMessage());
    throwable.printStackTrace();
    try {
        sendMessage(MessageType.ERROR, "error occurred", "");
    } catch (Exception e) {
        System.err.println("Error sending error message: " + e.getMessage());
    }
}

// 서버에 상태/결과 보고 (JSON 형식)
public void sendMessage(MessageType messageType, String status, String correlation_id) {
    try {
        String TIMESTAMP = get_Timestamp();
        //String json = String.format("{\"status\":\"%s\", \"deviceId\":\"%s\"}", status, DEVICE_ID);
        com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
        node.put("version", "1.0");
        node.put("type", messageType.toString());
        node.put("id", DEVICE_UUID);
        node.put("correlation_id", "");
        node.put("ts", TIMESTAMP);
        node.put("action", "");
        node.put("status", status);
        node.put("payload", objectMapper.createObjectNode().put("DEVICE_UUID", DEVICE_UUID));
        node.set("meta", objectMapper.createObjectNode().put("source", "simulator"));
        String json = node.toString();
        session.getAsyncRemote().sendText(json);
    } catch (Exception e) {
        e.printStackTrace();
    }
}

// 클라이언트 실행
public static void main(String[] args) {
    try {
        System.out.println("=== Starting WebSocket Client ===");
        System.out.println("Device UUID: " + DEVICE_UUID);
        System.out.println("Server URI: " + SERVER_URI);
        
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        String uri = SERVER_URI + DEVICE_UUID;
        System.out.println("Connecting to: " + uri);
        
        Session session = container.connectToServer(SimulatorWSClient.class, URI.create(uri));
        System.out.println("Connection established. Session ID: " + session.getId());
        System.out.println("Waiting for messages...");
        
        latch.await(); // 연결 종료까지 대기
        System.out.println("WebSocket client terminated.");
    } catch (Exception e) {
        System.err.println("Error in main: " + e.getMessage());
        e.printStackTrace();
    }
}

public static String get_UuidString() { return UUID.randomUUID().toString();}

public String get_Timestamp() {
    // IOS8601 형식의 타임스탬프 생성 및 반환
    java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ISO_INSTANT;
    return formatter.format(java.time.Instant.now());
}
// private MessageType getMessage_typEnum() {
//     return message_typEnum;
// }

// private void setMessage_typEnum(MessageType message_typEnum) {
//     this.message_typEnum = message_typEnum;
// }
}



