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
    // private static final String SERVER_URI =
    // "ws://kukovm.koreacentral.cloudapp.azure.com:5555/ws/";
    private static final String SERVER_URI = "wss://iot-service-server.wonderfulrock-1223eeed.koreacentral.azurecontainerapps.io/ws/";
    // private String DEVICE_ID = "device123"; // 실제 환경에서는 서버에서 받아올 수 있음
    private static final String DEVICE_UUID = get_UuidString();
    private String IOTHUB_DEVICE_CONNECTION_STRING;
    private String received_DEVICE_ID;
    private int initialRetryTimeout;
    private int maxRetry;
    private int messageIntervalSeconds;

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
    private static final IotClient iotClient = new IotClient();
    // handle to running worker so we can cancel it
    private static java.util.concurrent.Future<?> iotWorkerFuture = null;

    // 서버로부터 명령 수신
    @OnMessage
    public void onMessage(String message) {
        System.out.println("=== @OnMessage triggered ===");
        System.out.println("Received message: " + message);
        try {
            JsonNode json = objectMapper.readTree(message);
            String action = json.has("action") ? json.get("action").asText() : "";

            // 명령 처리
            switch (action) {
                case "device.start":
                    System.out.println("서비스 시작 명령 수신");
                    isReadytoSend = true;
                    iotClient.setReadytoRun(isReadytoSend);
                    // 시작: IotClient의 cancellable worker를 사용
                    try {
                        iotWorkerFuture = iotClient.start();
                        System.out.println("iotClient.start() 호출됨, future saved");
                    } catch (Exception e) {
                        System.err.println("Error starting IotClient: " + e.getMessage());
                        e.printStackTrace();
                    }
                    break;
                case "device.stop":
                    System.out.println("서비스 중단 명령 수신");
                    isReadytoSend = false;
                    iotClient.setReadytoRun(isReadytoSend);
                    // 중단: cancellable worker에게 중단 요청
                    try {
                        if (iotWorkerFuture != null && !iotWorkerFuture.isDone()) {
                            iotWorkerFuture.cancel(true);
                            System.out.println("iotWorkerFuture.cancel(true) 호출됨");
                        }
                        iotClient.stop();
                        System.out.println("iotClient.stop() 호출됨");
                    } catch (Exception e) {
                        System.err.println("Error stopping IotClient: " + e.getMessage());
                        e.printStackTrace();
                    }
                    break;
                case "device.restart":
                    System.out.println("서비스 재시작 명령 수신");
                    isReadytoSend = false;
                    iotClient.setReadytoRun(isReadytoSend);
                    // 재시작: stop -> 대기 -> start
                    try {
                        if (iotWorkerFuture != null && !iotWorkerFuture.isDone()) {
                            iotWorkerFuture.cancel(true);
                        }
                        iotClient.stop();
                        Thread.sleep(2000);
                        isReadytoSend = true;
                        iotClient.setReadytoRun(isReadytoSend);
                        iotWorkerFuture = iotClient.start();
                        System.out.println("iotClient restarted");
                    } catch (Exception e) {
                        System.err.println("Error restarting IotClient: " + e.getMessage());
                        e.printStackTrace();
                    }
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
                            System.out
                                    .println("구성 업데이트 - iot_hub_connection_string: " + IOTHUB_DEVICE_CONNECTION_STRING);
                        }
                        if (payload.has("initialRetryTimeout")) {
                            initialRetryTimeout = payload.get("initialRetryTimeout").asInt();
                            if (iotClient != null)
                                iotClient.setInitialRetryDelaySeconds(initialRetryTimeout);
                            System.out.println("구성 업데이트 - initial_retry_timeout: " + initialRetryTimeout);
                        }
                        if (payload.has("maxRetry")) {
                            maxRetry = payload.get("maxRetry").asInt();
                            if (iotClient != null)
                                iotClient.setMaxRetries(maxRetry);
                            System.out.println("구성 업데이트 - max_retry: " + maxRetry);
                        }
                        if (payload.has("messageIntervalSeconds")) {
                            messageIntervalSeconds = payload.get("messageIntervalSeconds").asInt();
                            if (iotClient != null)
                                iotClient.setMessageIntervalSeconds(messageIntervalSeconds);
                            System.out.println("구성 업데이트 - message_interval_seconds: " + messageIntervalSeconds);
                        }
                        isReadytoSend = true;
                        // 추가 구성 파라미터 처리 가능
                    }
                    // 구성 업데이트 로직 구현
                    break;
            }
            sendMessage(MessageType.EVENT, "processed:" + action, "");
            // 서버에 결과/상태 보고 (예시)
        } catch (Exception e) {
            System.err.println("메시지 처리 중 오류 발생:");
            e.printStackTrace();
        }
    }

    // 서버와 연결되었을 때
    @OnOpen
    public void onOpen(Session session) {
        // 서버에 초기 상태 보고
        System.out.println("Sending initial messages...");
        this.session = session;
        sendMessage(MessageType.EVENT, "connected", "");
        sendMessage(MessageType.REQUEST, "device need device_id", "");
        // Start IotClient worker (non-blocking) and save Future for cancellation
        // try {
        // iotWorkerFuture = iotClient.start();
        // System.out.println("iotClient.start() called from onOpen; future saved");
        // } catch (Exception e) {
        // System.err.println("Error starting IotClient: " + e.getMessage());
        // e.printStackTrace();
        // }
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
            // String json = String.format("{\"status\":\"%s\", \"deviceId\":\"%s\"}",
            // status, DEVICE_ID);
            com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
            node.put("version", "1.0");
            node.put("type", messageType.toString());
            node.put("id", DEVICE_UUID);
            node.put("correlation_id", "");
            node.put("ts", TIMESTAMP);
            node.put("action", "");
            node.put("status", status);
            node.set("payload", objectMapper.createObjectNode().put("DEVICE_UUID", DEVICE_UUID));
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

    public static String get_UuidString() {
        return UUID.randomUUID().toString();
    }

    public String get_Timestamp() {
        // IOS8601 형식의 타임스탬프 생성 및 반환
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ISO_INSTANT;
        return formatter.format(java.time.Instant.now());
    }

}
