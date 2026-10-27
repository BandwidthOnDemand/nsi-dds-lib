# Build and test dds-lib with the fixed Maven 3.10.0 / Java 21 container.
# Targets install only into the mounted local Maven cache; they do not publish.

all:    build

.PHONY: build test clean
build:
	docker run -it --rm --name dds-build \
		-v "$(PWD)":/usr/src/mymaven \
		-v "$(HOME)/.m2":/root/.m2  \
		-w /usr/src/mymaven \
		maven:3.10.0-eclipse-temurin-21 mvn clean install -DskipTests=true

test:
	docker run -it --rm --name dds-build \
		-v "$(PWD)":/usr/src/mymaven \
		-v "$(HOME)/.m2":/root/.m2  \
		-w /usr/src/mymaven \
		-p 8801:8801 \
		-p 8802:8802 \
		maven:3.10.0-eclipse-temurin-21 mvn clean install

clean:
	docker run -it --rm --name dds-clean \
		-v "$(PWD)":/usr/src/mymaven \
		-v "$(HOME)/.m2":/root/.m2  \
		-w /usr/src/mymaven \
		maven:3.10.0-eclipse-temurin-21 mvn clean

docker:
