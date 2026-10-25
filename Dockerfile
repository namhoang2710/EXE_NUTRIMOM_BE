# Render không có runtime Java nên service này chạy bằng Docker.
# JDK 21 chứ không phải <java.version>17</java.version> của pom: CI (.github/workflows/ci.yml)
# build và test trên Temurin 21, image phải khớp thứ đã được kiểm chứng.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Tách pom ra một layer riêng: sửa code mà không đụng dependency thì Docker dùng lại cache này.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
# Bỏ test ở đây là cố ý: test chạy trên H2 và cần TZ=Asia/Ho_Chi_Minh, đã có CI lo.
RUN mvn -B -ntp clean package -DskipTests

# jammy (glibc) chứ không alpine (musl): imageio-webp đụng AWT/ImageIO, musl hay sinh lỗi native lạ.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# user.timezone khớp giả định của AdminOperationsRepository (ZoneId.systemDefault()).
# Heap cố định thay vì MaxRAMPercentage: trần RAM của instance phải chứa cả metaspace, code cache
# và thread stack, nên để JVM tự chia theo phần trăm là chắc chắn vượt ở mức 512MB.
# TieredStopAtLevel=1 bỏ JIT tầng cao: khởi động nhanh hơn hẳn khi CPU bị bóp, đổi lại throughput
# lúc chạy ổn định thấp hơn. Lên instance >= 2GB thì bỏ toàn bộ phần giới hạn bộ nhớ ở đây.
ENV JAVA_OPTS="-Xmx190m -Xss512k -XX:MaxMetaspaceSize=200m -XX:ReservedCodeCacheSize=48m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djava.awt.headless=true -Duser.timezone=Asia/Ho_Chi_Minh -Dspring.jmx.enabled=false"
EXPOSE 8080
# Render bơm biến PORT, còn application.yml chỉ đọc SERVER_PORT — truyền thẳng để khỏi phải
# set tay hai biến cho khớp nhau.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar --server.port=${PORT:-8080}"]
