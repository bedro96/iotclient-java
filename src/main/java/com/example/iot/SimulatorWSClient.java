package com.example.iot;

import java.net.URI;
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
private static final String SERVER_URI = "wss://iot-service-server.wonderfulrock-1223eeed.koreacentral.azurecontainerapps.io/ws/";
private static final String DEVICE_ID = "device123"; // 실제 환경에서는 서버에서 받아올 수 있음
private String received_DEVICED_ID;


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

        container.connectToServer(SimulatorWSClient.class, URI.create(uri));
        latch.await(); // 연결 종료까지 대기
        } catch (Exception e) {
        e.printStackTrace();
        }
    }
}