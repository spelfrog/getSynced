# Android build image for getSynced. Build: scripts/build.sh (or: podman build -t getsynced-builder .)
FROM docker.io/eclipse-temurin:17-jdk
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
ARG CMDLINE_TOOLS=15859902
RUN apt-get update && apt-get install -y --no-install-recommends unzip wget git && rm -rf /var/lib/apt/lists/*
RUN mkdir -p $ANDROID_HOME/cmdline-tools && cd $ANDROID_HOME/cmdline-tools \
 && wget -q "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" -O t.zip \
 && unzip -q t.zip && mv cmdline-tools latest && rm t.zip
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
RUN yes | sdkmanager --licenses >/dev/null \
 && sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
WORKDIR /src
