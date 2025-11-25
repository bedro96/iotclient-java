FROM eclipse-temurin:21-jre-jammy

# Create app directory
WORKDIR /app

# Build-time argument to point to the fat jar produced by Maven
ARG JAR_FILE=target/iot-device-java21-1.0-SNAPSHOT.jar

# Copy the fat jar into the image
COPY ${JAR_FILE} /app/app.jar

# Create an unprivileged user and switch to it
RUN groupadd --system appgroup \
  && useradd --system --gid appgroup --create-home --home-dir /home/appuser appuser \
  && chown -R appuser:appgroup /app

USER appuser

# Allow runtime override of Java options and the IoT hub connection string
ENV JAVA_OPTS=""
ENV IOTHUB_DEVICE_CONNECTION_STRING="HostName=s1toptest01.azure-devices.net;DeviceId=javadevice001;SharedAccessKey=pb4CJgdsz1mGnnvSy7LOt6qLx+0xU6jQCYjkThvWjlY="

# The application makes outbound connections (MQTT 8883) — no ports need to be exposed for basic operation
# EXPOSE 8883

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
