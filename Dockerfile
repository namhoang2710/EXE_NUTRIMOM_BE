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
# MaxRAMPercentage: instance nhỏ thì JVM mặc định ăn quá phần RAM được cấp rồi bị OOM-kill.
# user.timezone khớp giả định của AdminOperationsRepository (ZoneId.systemDefault()).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Djava.awt.headless=true -Duser.timezone=Asia/Ho_Chi_Minh"
EXPOSE 8080
# Render bơm biến PORT, còn application.yml chỉ đọc SERVER_PORT — truyền thẳng để khỏi phải
# set tay hai biến cho khớp nhau.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar --server.port=${PORT:-8080}"]
