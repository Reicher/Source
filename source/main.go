package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/grandcat/zeroconf"
)

const serviceType = "_sourceself._tcp"

func main() {
	listen := flag.String("listen", ":8080", "LAN HTTPS address for Self")
	setup := flag.String("setup", "127.0.0.1:8081", "loopback HTTP address for setup")
	data := flag.String("data", "data/pairing", "directory for the persistent Source identity (mount its parent on first start)")
	flag.Parse()

	setupHost, _, err := net.SplitHostPort(*setup)
	if err != nil || net.ParseIP(setupHost) == nil || !net.ParseIP(setupHost).IsLoopback() {
		log.Fatal("setup must bind to an explicit loopback IP address")
	}
	identity, err := loadIdentity(*data)
	if err != nil {
		log.Fatal(err)
	}
	lan, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatal(err)
	}
	local, err := net.Listen("tcp", *setup)
	if err != nil {
		log.Fatal(err)
	}
	port := lan.Addr().(*net.TCPAddr).Port
	mdns, err := zeroconf.Register("Source-"+identity.id, serviceType, "local.", port, []string{"id=" + identity.id}, nil)
	if err != nil {
		log.Fatal(err)
	}
	defer mdns.Shutdown()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	lanHandler, err := identity.newLanHandler(ctx)
	if err != nil {
		log.Fatal(err)
	}
	lanServer := &http.Server{Handler: lanHandler, ReadHeaderTimeout: 5 * time.Second, TLSConfig: identity.tlsConfig()}
	localServer := &http.Server{Handler: identity.setupHandler(local.Addr().String()), ReadHeaderTimeout: 5 * time.Second}
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = lanServer.Shutdown(shutdownCtx)
		_ = localServer.Shutdown(shutdownCtx)
	}()
	go func() {
		log.Printf("Source setup: http://%s", local.Addr())
		if err := localServer.Serve(local); !errors.Is(err, http.ErrServerClosed) {
			log.Printf("setup server: %v", err)
			stop()
		}
	}()
	log.Printf("Source HTTPS on %s, advertised via mDNS", lan.Addr())
	if err := lanServer.ServeTLS(lan, identity.certPath, identity.keyPath); !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}
