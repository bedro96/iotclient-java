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

public class IotClient{

    // Device identification
    private String DEVICE_ID = System.getenv().getOrDefault("DEVICE_ID", "javadevice001");
    private boolean isReadytoRun = false;
    private static final String MODEL_ID = System.getenv().getOrDefault("MODEL_ID", "dtmi:com:example:iotdevice");

    // 보안을 위해 환경변수로 받아 사용 (직접 문자열 하드코딩 지양)
    // 설정: export IOTHUB_DEVICE_CONNECTION_STRING="HostName=...;DeviceId=...;SharedAccessKey=..."
    private String IOTHUB_DEVICE_CONNECTION_STRING = null;

    // MQTT 권장 (방화벽 포트 8883 필요) [5](https://learn.microsoft.com/en-us/azure/iot/tutorial-send-telemetry-iot-hub)
    private static final IotHubClientProtocol PROTOCOL = IotHubClientProtocol.MQTT;
    
    // DeviceClient instance - initialized when connection string is set
    private DeviceClient client = null;
    // Internal worker thread when started via start()
    private Thread workerThread = null;
    
    // Retry configuration
    private int INITIAL_RETRY_DELAY_SECONDS = 30;
    private int MAX_RETRY_DELAY_SECONDS = 960; // Max ~16 minutes
    private int MAX_RETRIES = 10;
    
    // Scheduler for async retry operations
    private static final ScheduledExecutorService retryScheduler = Executors.newScheduledThreadPool(1);
    // control flag for worker thread
    private volatile boolean workerRunning = false;

    public void main(String[] args) throws Exception {
        // keep backward-compatible entry point
        runLoop();
    }

    // Non-blocking start: spawn worker thread to run loop
    public synchronized void start() {
        if (workerRunning) {
            System.out.println("IotClient worker already running");
            return;
        }
        workerRunning = true;
        workerThread = new Thread(() -> {
            try {
                runLoop();
            } catch (Exception e) {
                System.err.println("IotClient worker exception: " + e.getMessage());
                e.printStackTrace();
            } finally {
                workerRunning = false;
            }
        }, "IotClient-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    // Stop the worker (best-effort)
    public synchronized void stop() {
        workerRunning = false;
        if (workerThread != null) {
            try {
                workerThread.interrupt();
            } catch (Exception ignored) {}
        }
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
            client = null;
        }
        retryScheduler.shutdownNow();
    }

    public String getWorkerState() {
        return workerThread != null ? workerThread.getState().toString() : "NOT_STARTED";
    }

    // The main loop extracted for use by both main() and worker thread
    private void runLoop() throws Exception {
        // Initialize connection string from environment if not already set
        if (IOTHUB_DEVICE_CONNECTION_STRING == null) {
            String envConnectionString = System.getenv("IOTHUB_DEVICE_CONNECTION_STRING");
            if (envConnectionString != null && !envConnectionString.isBlank()) {
                setIothubConnectionString(envConnectionString);
            }
        }

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                retryScheduler.shutdown();
                if (client != null) {
                    client.close();
                }
            } catch (Exception ignored) {}
        }));

        Instant lastActivityTime = Instant.now();

        while (workerRunning || Thread.currentThread().isInterrupted() == false) {
            // stop condition
            if (!workerRunning) break;

            // Wait until connection string is set and ready to run
            if (this.isReadytoRun && IOTHUB_DEVICE_CONNECTION_STRING != null && !IOTHUB_DEVICE_CONNECTION_STRING.isBlank()) {
                // Initialize DeviceClient if not already created
                if (client == null) {
                    System.out.println("Initializing IoT Hub Device Client...");
                    client = new DeviceClient(IOTHUB_DEVICE_CONNECTION_STRING, PROTOCOL);
                    // Connect with retry logic with exponential backoff
                    connectWithRetry(client);
                }

                // Update activity time
                lastActivityTime = Instant.now();

                // Send telemetry messages
                // 간단한 텔레메트리 5건 전송
                CountDownLatch latch = new CountDownLatch(5);
                for (int i = 0; i < 5 && workerRunning; i++) {
                    String payload = String.format(
                        "{\"temp\": %d, \"ts\": \"%s\", \"deviceId\": \"%s\", \"modelId\": \"%s\"}",
                        20 + i, Instant.now(), DEVICE_ID, MODEL_ID);
                    Message msg = new Message(payload.getBytes(StandardCharsets.UTF_8));
                    msg.setContentType("application/json");
                    msg.setProperty("level", "info");
                    msg.setProperty("deviceId", DEVICE_ID);
                    msg.setProperty("modelId", MODEL_ID);

                    sendMessageWithRetry(client, msg, latch);

                    try { Thread.sleep(10000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
                try { latch.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

                System.out.println("Batch of 5 messages sent. Continuing...");
            } else {
                // Wait for connection string and ready flag
                if (IOTHUB_DEVICE_CONNECTION_STRING == null || IOTHUB_DEVICE_CONNECTION_STRING.isBlank()) {
                    System.out.println("Waiting for IOTHUB_DEVICE_CONNECTION_STRING to be set...");
                } else if (!isReadytoRun) {
                    System.out.println("Waiting for isReadytoRun flag...");
                }

                try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

                // Check if idle for too long
                if (Instant.now().minusSeconds(MAX_RETRY_DELAY_SECONDS).isAfter(lastActivityTime)) {
                    System.out.println("Idle 상태로 MAX_RETRY_DELAY_SECONDS 경과하여 종료합니다.");
                    break;
                }
            }
        }
                System.out.println("Done. Closing.");
        
        // cleanup
        try { retryScheduler.shutdown(); } catch (Exception ignored) {}
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
            client = null;
        }
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
    public void setIothubConnectionString(String connectionString) {
        if (connectionString == null || connectionString.isBlank()) {
            System.err.println("Invalid connection string provided.");
            return;
        }
        
        // If client already exists, close it before re-initializing
        if (this.client != null) {
            try {
                System.out.println("Closing existing client before updating connection string...");
                this.client.close();
                this.client = null;
            } catch (Exception e) {
                System.err.println("Error closing existing client: " + e.getMessage());
            }
        }
        
        this.IOTHUB_DEVICE_CONNECTION_STRING = connectionString;
        System.out.println("IoT Hub connection string updated successfully.");
    }
}