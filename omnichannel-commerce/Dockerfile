FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/omnichannel-commerce-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar","--spring.profiles.active=prod"]
