FROM ubuntu:20.04

ENV TZ=Europe/London
RUN ln -snf "/usr/share/zoneinfo/$TZ" /etc/localtime
RUN echo "$TZ" > /etc/timezone

RUN DEBIAN_FRONTEND=noninteractive \
    && apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y ca-certificates-java \
    && apt-get install -y openjdk-17-jre-headless \
    && rm -rf /var/lib/apt/lists/*

COPY target/scala-2.13/attachment-processor.jar /bin

ENTRYPOINT exec java $JAVA_OPTS -jar /bin/attachment-processor.jar