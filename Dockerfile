# ===========================================
# myreader - 书源登录集成版 (多架构)
# 支持 amd64 / arm64
# ===========================================

FROM oishi1981/reader:latest

# 环境变量
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms128m -Xmx384m -XX:+UseG1GC"

EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/reader3/getSystemInfo || exit 1

# 需要挂载的目录: /app/storage (书源、Cookie等持久化数据)
VOLUME ["/app/storage"]

CMD ["java", "-jar", "/app/bin/reader.jar"]