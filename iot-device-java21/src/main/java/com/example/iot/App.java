package com.example.iot;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;

import com.microsoft.azure.sdk.iot.device.DeviceClient;
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol;
import com.microsoft.azure.sdk.iot.device.Message;


public class App{

    // 보안을 위해 환경변수로 받아 사용 (직접 문자열 하드코딩 지양)
    // 설정: export IOTHUB_DEVICE_CONNECTION_STRING="HostName=...;DeviceId=...;SharedAccessKey=..."
    private static final String IOTHUB_DEVICE_CONNECTION_STRING = System.getenv("IOTHUB_DEVICE_CONNECTION_STRING");

    // MQTT 권장 (방화벽 포트 8883 필요) [5](https://learn.microsoft.com/en-us/azure/iot/tutorial-send-telemetry-iot-hub)
    private static final IotHubClientProtocol PROTOCOL = IotHubClientProtocol.MQTT;

    public static void main(String[] args) throws Exception {
        if (IOTHUB_DEVICE_CONNECTION_STRING == null || IOTHUB_DEVICE_CONNECTION_STRING.isBlank()) {
            System.err.println("환경변수 IOTHUB_DEVICE_CONNECTION_STRING이 설정되지 않았습니다.");
            System.err.println("예) export IOTHUB_DEVICE_CONNECTION_STRING=\"HostName=...;DeviceId=...;SharedAccessKey=...\"");
            System.exit(1);
        }

        DeviceClient client = new DeviceClient(IOTHUB_DEVICE_CONNECTION_STRING, PROTOCOL);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { client.close(); } catch (Exception ignored) {}
        }));

        System.out.println("Opening connection to IoT Hub ...");
        client.open(true);
        System.out.println("Connected.");
        // 간단한 텔레메트리 5건 전송
        CountDownLatch latch = new CountDownLatch(5);
        for (int i = 0; i < 5; i++) {
            String payload = String.format("{\"temp\": %d, \"ts\": \"%s\"}", 20 + i, Instant.now());
            Message msg = new Message(payload.getBytes(StandardCharsets.UTF_8));
            msg.setContentType("application/json");
            msg.setProperty("level", "info");
            client.sendEventAsync(msg, (sentMessage, clientException, callbackContext) -> {
                if (clientException == null) {
                    System.out.printf("Message ack: SUCCESS%n");
                } else {
                    System.out.printf("Message ack: FAILED - %s%n", clientException.getMessage());
                }
                latch.countDown();
            }, null);

            Thread.sleep(1000);
        }
        latch.await();
        System.out.println("Done. Closing.");
        client.close();
    }
}

/**
 * Hello world!
 */
// public class App {
//     public static void main(String[] args) {
//         System.out.println("Hello World!");
//         System.out.println("IoT Device Application Running...");
//     }
// }
