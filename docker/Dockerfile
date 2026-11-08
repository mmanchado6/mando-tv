# Imagen de compilación de «Mando TV»: JDK + Android SDK + Gradle, con versiones fijas.
# Todo lo descargado se verifica con su SHA-256 oficial.
FROM eclipse-temurin:21-jdk-noble@sha256:114cabb73033ca4ce7ceec3deb62396bc1b716282bae051ed3c1d82e38fcab2d

ARG CMDLINE_TOOLS_VERSION=15859902
ARG CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ARG GRADLE_VERSION=9.6.0
ARG GRADLE_SHA256=bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01
ARG ANDROID_PLATFORM="platforms;android-37.0"
ARG ANDROID_BUILD_TOOLS="build-tools;36.0.0"

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_HOME=/opt/gradle
ENV PATH=${GRADLE_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/build-tools/36.0.0:${PATH}

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Herramientas de línea de comandos del Android SDK
RUN curl -fsSL -o /tmp/cmdline.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
 && echo "${CMDLINE_TOOLS_SHA256}  /tmp/cmdline.zip" | sha256sum -c - \
 && mkdir -p ${ANDROID_HOME}/cmdline-tools \
 && unzip -q /tmp/cmdline.zip -d ${ANDROID_HOME}/cmdline-tools \
 && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
 && rm /tmp/cmdline.zip

# Licencia del Android SDK (aceptada por el usuario al aprobar el plan) y paquetes necesarios
RUN yes | sdkmanager --licenses > /dev/null \
 && sdkmanager --install "${ANDROID_PLATFORM}" "${ANDROID_BUILD_TOOLS}" > /dev/null \
 && chmod -R a+rX ${ANDROID_HOME}

# Gradle
RUN curl -fsSL -o /tmp/gradle.zip \
      "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
 && echo "${GRADLE_SHA256}  /tmp/gradle.zip" | sha256sum -c - \
 && unzip -q /tmp/gradle.zip -d /opt \
 && mv /opt/gradle-${GRADLE_VERSION} ${GRADLE_HOME} \
 && rm /tmp/gradle.zip

# Caché persistente (volumen de Docker); escribible por cualquier usuario
RUN mkdir -p /cache && chmod 1777 /cache
ENV GRADLE_USER_HOME=/cache/gradle \
    ANDROID_USER_HOME=/cache/android \
    HOME=/cache/home

WORKDIR /proyecto
