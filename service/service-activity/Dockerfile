FROM openjdk:8-jdk-alpine
VOLUME /tmp
COPY ./target/server-activity.jar server-activity.jar
ENTRYPOINT ["java","-jar","/server-activity.jar", "&"]