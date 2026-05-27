FROM openjdk:17-jdk-slim

MAINTAINER xiaofuge

ENV PARAMS=""
ENV TZ=PRC
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

ADD target/ai-agent-station-study-app.jar /ai-agent-station-study-app.jar

ENTRYPOINT ["sh","-c","java -jar $JAVA_OPTS /ai-agent-station-study-app.jar $PARAMS"]
