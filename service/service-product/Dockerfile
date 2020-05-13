FROM openjdk:8-jdk-alpine
VOLUME /tmp
COPY ./target/server-product.jar server-product.jar
ENTRYPOINT ["java","-jar","/server-product.jar", "&"]