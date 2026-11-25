ARG JAVA_VERSION=17
FROM maven:3.9-eclipse-temurin-${JAVA_VERSION}

# Thiết lập user greytest chuyên biệt (UID 1000, GID 1000)
# Không cấp sudo, không cho phép nâng quyền
RUN if id -u ubuntu >/dev/null 2>&1; then \
        usermod -l greytest -d /home/greytest -m ubuntu && \
        groupmod -n greytest ubuntu; \
    else \
        groupadd -g 1000 greytest && \
        useradd -u 1000 -g greytest -m -s /bin/bash greytest; \
    fi && \
    gpasswd -d greytest sudo 2>/dev/null || true && \
    mkdir -p /home/greytest/.m2/repository /work && \
    chown -R 1000:1000 /home/greytest /work && \
    chmod 755 /home/greytest && \
    chmod 777 /work

ENV HOME=/home/greytest \
    MAVEN_CONFIG=/home/greytest/.m2

USER greytest
WORKDIR /work
