# Ambiente di build: JDK 17 + Android SDK. L'SDK Linux (aapt2, build-tools) esiste solo per x86-64,
# quindi su Mac Apple Silicon l'immagine gira in emulazione (--platform linux/amd64).
FROM eclipse-temurin:17-jdk-jammy

ARG CMDLINE_TOOLS=11076708
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle-cache
ENV PATH="${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools"

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
 && curl -fsSL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" \
 && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
 && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
 && rm /tmp/cmdline-tools.zip

RUN (yes | sdkmanager --licenses > /dev/null || true) \
 && sdkmanager "platform-tools" "platforms;android-35" "platforms;android-36" "build-tools;35.0.0" "build-tools;36.0.0"

COPY docker/build-in-container.sh /usr/local/bin/build-in-container
RUN chmod +x /usr/local/bin/build-in-container

WORKDIR /work
ENTRYPOINT ["build-in-container"]
CMD [":core:selfTest", ":app:assembleDebug"]
