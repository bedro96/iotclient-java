import com.microsoft.azure.sdk.iot.device.*;

public class App {
    public static void main(String[] args) throws Exception {
        String hostName = "<your-iot-hub>.azure-devices.net";
        String deviceId = "<your-device-id>";
        String sharedAccessKey = "<your-device-key>";

        // SAS Token Provider 생성 (1시간 유효)
        SimpleSasTokenProvider tokenProvider =
                new SimpleSasTokenProvider(hostName, deviceId, sharedAccessKey, 3600);

        // DeviceClient 생성
        DeviceClient client = new DeviceClient(
                hostName,
                deviceId,
                tokenProvider,
                IotHubClientProtocol.MQTT
        );

        client.open(true);

        // Test message
        Message msg = new Message("Hello from JRE 21 using SAS Token Provider!");
        client.sendEventAsync(msg, (responseStatus, callbackContext) -> {
            System.out.println("Send Status = " + responseStatus.name());
        }, null);

        Thread.sleep(3000);
        client.close();
    }
}
