FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY target/backend-java-*.jar app.jar

RUN mkdir -p /app/data /app/logs

EXPOSE 8080

ENV JAVA_OPTS="-Xmx384m -Xms192m -XX:+UseSerialGC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
