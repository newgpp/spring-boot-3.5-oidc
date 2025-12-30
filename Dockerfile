# ========= 构建阶段 =========
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
# 替换 apk 源（加速 apk）
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories
# 设置 Maven 国内镜像
RUN mkdir -p /root/.m2 && \
    printf '<settings>\n\
  <mirrors>\n\
    <mirror>\n\
      <id>aliyun</id>\n\
      <mirrorOf>*</mirrorOf>\n\
      <name>Aliyun Maven</name>\n\
      <url>https://maven.aliyun.com/repository/public</url>\n\
    </mirror>\n\
  </mirrors>\n\
</settings>' > /root/.m2/settings.xml
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

# ========= 运行阶段 =========
FROM maven:3.9.9-eclipse-temurin-21-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]
