.PHONY: test desktop switch overlay

test:
	go test ./...

desktop:
	go build -o bin/nxsync-desktop ./cmd/nxsync-desktop

switch:
	$(MAKE) -C switch/sys-savesync

overlay:
	$(MAKE) -C switch/overlay

