VERSION=0.1.0

default: versioncheck

build-all: clean stage

clean:
	./gradlew clean

compile: build

build:
	./gradlew build -xtest

uberjar:
	./gradlew uberjar

uber: uberjar
	java -jar build/libs/kslides.jar

dist:
	./gradlew installDist

script: dist
	build/install/battlesnake-examples/bin/snake

stage:
	./gradlew stage

versioncheck:
	./gradlew dependencyUpdates

upgrade-wrapper:
	./gradlew wrapper --gradle-version=7.4 --distribution-type=bin