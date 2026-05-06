
DEVICE := $(shell ./scripts/vela-device)

.PHONY: build install deploy smoke harness harness-sync

build:
	./gradlew assembleDebug -x test

smoke:
	@./scripts/smoke-test.sh

# harness-sync: keep harness source files in sync with the app
# Run this after editing normalizer/stream client/models in the app
harness-sync:
	@echo "Syncing harness source from app..."
	@cp app/src/main/kotlin/com/vela/app/amplifierd/AmplifierdStreamClient.kt \
	     harness/src/main/kotlin/com/vela/app/amplifierd/AmplifierdStreamClient.kt
	@cp app/src/main/kotlin/com/vela/app/streaming/SessionSseNormalizer.kt \
	     harness/src/main/kotlin/com/vela/app/streaming/SessionSseNormalizer.kt
	@cp app/src/main/kotlin/com/vela/app/streaming/SessionState.kt \
	     harness/src/main/kotlin/com/vela/app/streaming/SessionState.kt
	@cp app/src/main/kotlin/com/vela/app/ui/sessiondetail/ContentBlock.kt \
	     harness/src/main/kotlin/com/vela/app/ui/sessiondetail/ContentBlock.kt
	@cp app/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionModels.kt \
	     harness/src/main/kotlin/com/vela/app/ui/sessiondetail/SessionModels.kt
	@echo "Done. Run 'make harness' to test."

# harness: sync + run a quick end-to-end chat to verify normalizer works
harness: harness-sync
	@cd harness && ./gradlew -q --console=plain run --args='"use bash to run: echo NORMALIZER_OK"'

install: build
	adb -s $(DEVICE) install -r app/build/outputs/apk/debug/app-debug.apk

# deploy runs smoke first — if server is broken, stop before installing
deploy: smoke build install
	adb -s $(DEVICE) shell am force-stop com.vela.app
	adb -s $(DEVICE) shell am start --user 0 -n com.vela.app/.MainActivity
