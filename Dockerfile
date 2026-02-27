FROM 979211549557.dkr.ecr.eu-west-2.amazonaws.com/docker.io/library/ubuntu:20.04

ENV TZ=Europe/London
RUN ln -snf "/usr/share/zoneinfo/$TZ" /etc/localtime
RUN echo "$TZ" > /etc/timezone

RUN apt-get update && apt-get install -y wget gnupg2

RUN wget -O - https://apt.corretto.aws/corretto.key | gpg --dearmor -o /usr/share/keyrings/corretto-keyring.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/corretto-keyring.gpg] https://apt.corretto.aws stable main" | tee /etc/apt/sources.list.d/corretto.list

RUN DEBIAN_FRONTEND=noninteractive \
    && apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y ca-certificates-java \
    && apt-get install -y java-21-amazon-corretto-jdk \
    && rm -rf /var/lib/apt/lists/*

COPY target/scala-3.7.4/attachment-processor.jar /bin

ENTRYPOINT exec java $JAVA_OPTS -jar /bin/attachment-processor.jar