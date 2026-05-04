
DEVICE := $(shell ./scripts/vela-device)

.PHONY: build install deploy

build:
	./gradlew assembleDebug -x test

install: build
	adb -s $(DEVICE) install -r app/build/outputs/apk/debug/app-debug.apk

deploy: install
	adb -s $(DEVICE) shell am force-stop com.vela.app
	adb -s $(DEVICE) shell am start --user 0 -n com.vela.app/.MainActivity
