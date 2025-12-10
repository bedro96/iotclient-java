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

public class IotClient {

    // Device identification
    private String DEVICE_ID = System.getenv().getOrDefault("DEVICE_ID", "javadevice001");
    private boolean isReadytoRun = false;
    private static final String MODEL_ID = System.getenv().getOrDefault("MODEL_ID", "dtmi:iotdevice");

    // 보안을 위해 환경변수로 받아 사용 (직접 문자열 하드코딩 지양)
    // 설정: export
    // IOTHUB_DEVICE_CONNECTION_STRING="HostName=...;DeviceId=...;SharedAccessKey=..."
    private String IOTHUB_DEVICE_CONNECTION_STRING = null;

    // MQTT 권장 (방화벽 포트 8883 필요)
    // [5](https://learn.microsoft.com/en-us/azure/iot/tutorial-send-telemetry-iot-hub)
    private static final IotHubClientProtocol PROTOCOL = IotHubClientProtocol.MQTT;

    // Retry configuration
    private int INITIAL_RETRY_DELAY_SECONDS = 30;
    private int MAX_RETRY_DELAY_SECONDS = 960; // Max ~16 minutes
    private int MAX_RETRIES = 10;
    private int MESSAGE_INTERVAL_SECONDS = 5;

    // Scheduler for async retry operations
    private static final ScheduledExecutorService retryScheduler = Executors.newScheduledThreadPool(1);
    // worker management using executor + future so we can cancel the running task
    private java.util.concurrent.ExecutorService workerExecutor = null;
    private java.util.concurrent.Future<?> workerFuture = null;
    private volatile boolean workerRunning = false;
    private IotDeviceStatus iotdevicestatus;

    public void run(String[] args) throws Exception {
        // backward compatible wrapper
        runLoop();
    }

    private void runLoop() throws Exception {
        workerRunning = true;
        while (workerRunning) {
            if (!isReadytoRun) {
                Thread.sleep(1000);
                continue;
            }
            if (IOTHUB_DEVICE_CONNECTION_STRING == null || IOTHUB_DEVICE_CONNECTION_STRING.isBlank()) {
                System.err.println("환경변수 IOTHUB_DEVICE_CONNECTION_STRING이 설정되지 않았습니다.");
                System.err.println(
                        "예) export IOTHUB_DEVICE_CONNECTION_STRING=\"HostName=...;DeviceId=...;SharedAccessKey=...\"");
                Thread.sleep(1000);
                continue;
            }

            System.out.println("[IotClient] Creating DeviceClient instance...");
            DeviceClient client = new DeviceClient(IOTHUB_DEVICE_CONNECTION_STRING, PROTOCOL);
            System.out.println(
                    "[IotClient] DeviceClient created: " + client + " (hash=" + System.identityHashCode(client) + ")");

            // Graceful shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    retryScheduler.shutdown();
                    client.close();
                } catch (Exception ignored) {
                }
            }));

            // Connect with retry logic with exponential backoff
            connectWithRetry(client);
            iotdevicestatus = new IotDeviceStatus();
            System.out.println("[IotClient] DeviceStatus initialized: " + iotdevicestatus);

            // 간단한 텔레메트리 10건 전송
            CountDownLatch latch = new CountDownLatch(10);
            for (int i = 0; i < 10 && workerRunning; i++) {
                String payload = String.format(
                        "{\"deviceId\": \"%s\", \"Type\": \"Thermo-hygrometer\", \"modelId\": \"%s\", \"Status\": \"%s\", \"temp\": %d, \"Humidity\": %d, \"ts\": \"%s\"}",
                        DEVICE_ID, MODEL_ID, iotdevicestatus.getDeviceStatus(), iotdevicestatus.getDeviceTemperature(),
                        iotdevicestatus.getDeviceHumidity(), Instant.now());
                Message msg = new Message(payload.getBytes(StandardCharsets.UTF_8));
                msg.setContentType("application/json");
                msg.setProperty("level", "info");
                msg.setProperty("deviceId", DEVICE_ID);
                msg.setProperty("modelId", MODEL_ID);

                sendMessageWithRetry(client, msg, latch);

                Thread.sleep(MESSAGE_INTERVAL_SECONDS * 1000L);
            }
            try {
                latch.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            System.out.println("Done. Closing.");
            try {
                Thread.sleep(10000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            try {
                retryScheduler.shutdown();
            } catch (Exception ignored) {
            }
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
        System.out.println("IotClient stopped.");
    }

    public synchronized java.util.concurrent.Future<?> start() {
        if (workerExecutor == null || workerExecutor.isShutdown()) {
            workerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "IotClient-Worker");
                t.setDaemon(true);
                return t;
            });
        }
        if (workerFuture != null && !workerFuture.isDone())
            return workerFuture;
        workerFuture = workerExecutor.submit(() -> {
            try {
                runLoop();
            } catch (Exception e) {
                System.err.println("worker exception: " + e.getMessage());
                e.printStackTrace();
            }
        });
        return workerFuture;
    }

    public synchronized void stop() {
        workerRunning = false;
        if (workerFuture != null && !workerFuture.isDone()) {
            workerFuture.cancel(true);
        }
        if (workerExecutor != null) {
            try {
                workerExecutor.shutdownNow();
            } catch (Exception ignored) {
            }
        }
    }

    public static void main(String[] args) throws Exception {
        new IotClient().run(args);
    }

    /**
     * Connect to IoT Hub with exponential backoff retry logic.
     * Starts with 30 seconds delay and doubles each retry up to max delay.
     */
    private void connectWithRetry(DeviceClient client) throws Exception {
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
    private void sendMessageWithRetry(DeviceClient client, Message msg, CountDownLatch latch) {
        sendMessageWithRetryInternal(client, msg, latch, 0, INITIAL_RETRY_DELAY_SECONDS);
    }

    private void sendMessageWithRetryInternal(DeviceClient client, Message msg, CountDownLatch latch,
            int currentRetry, int currentDelay) {
        client.sendEventAsync(msg, (sentMessage, clientException, callbackContext) -> {
            if (clientException == null) {
                System.out.println("================================");
                System.out.printf("Message ack: SUCCESS%n");
                System.out.println("Payload: " + new String(sentMessage.getBytes(), StandardCharsets.UTF_8));
                System.out.println("================================");
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

    public void setDeviceString(String deviceId) {
        this.DEVICE_ID = deviceId;
    }

    public void setReadytoRun(boolean ready) {
        this.isReadytoRun = ready;
    }

    public void setInitialRetryDelaySeconds(int seconds) {
        this.INITIAL_RETRY_DELAY_SECONDS = seconds;
    }

    public void setMaxRetries(int maxRetries) {
        this.MAX_RETRIES = maxRetries;
    }

    public void setMaxRetryDelaySeconds(int seconds) {
        this.MAX_RETRY_DELAY_SECONDS = seconds;
    }

    public void setMessageIntervalSeconds(int seconds) {
        this.MESSAGE_INTERVAL_SECONDS = seconds;
    }

    public void setIothubConnectionString(String connectionString) {
        if (connectionString == null || connectionString.isBlank()) {
            System.err.println("Invalid connection string provided.");
            return;
        }
        this.IOTHUB_DEVICE_CONNECTION_STRING = connectionString;
        System.out.println("IoT Hub connection string updated successfully.");
    }
}