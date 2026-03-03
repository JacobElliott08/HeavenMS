# Docker support, thanks to xinyifly

FROM eclipse-temurin:21-jdk-alpine

RUN apk add --no-cache tini bash maven

WORKDIR /mnt
COPY ./ ./

RUN sed -i 's/\r$//' ./posix-compile.sh ./posix-launch.sh && bash ./posix-compile.sh

ADD https://github.com/ufoscout/docker-compose-wait/releases/download/2.6.0/wait /wait
RUN chmod +x /wait

EXPOSE 8484 7575 7576 7577
ENTRYPOINT ["tini", "--"]
CMD /wait && bash ./posix-launch.sh
