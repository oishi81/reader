# myreader - 书源登录版
# 基于 hectorqin/reader，添加书源登录功能
FROM hectorqin/reader:latest

LABEL maintainer="oishi1981"
LABEL description="reader with book source login support"

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms128m -Xmx384m -XX:+UseG1GC"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/reader3/getSystemInfo || exit 1

CMD ["java", "-jar", "/app/bin/reader.jar"]