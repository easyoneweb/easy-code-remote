GO      ?= go
BINARY  := easy-code-remote
MODDIR  := server

.PHONY: build test check vet fmt clean

build:
	cd $(MODDIR) && $(GO) build -o ../$(BINARY) ./cmd/easy-code-remote

test:
	cd $(MODDIR) && $(GO) test ./...

vet:
	cd $(MODDIR) && $(GO) vet ./...

fmt:
	cd $(MODDIR) && gofmt -l .

check: fmt vet test

clean:
	rm -f $(BINARY)