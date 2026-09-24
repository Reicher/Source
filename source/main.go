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
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/grandcat/zeroconf"
)

const serviceType = "_sourceself._tcp"

func privateLANAddress(value string) (net.IP, error) {
	host, portValue, err := net.SplitHostPort(value)
	if err != nil {
		return nil, errors.New("listen must be an explicit private IPv4 address and port")
	}
	ip := net.ParseIP(host)
	port, portErr := strconv.Atoi(portValue)
	if strings.Contains(host, ":") || ip == nil || ip.To4() == nil || !ip.IsPrivate() || portErr != nil || port < 1 || port > 65535 {
		return nil, errors.New("listen must be an explicit private IPv4 address and port")
	}
	return ip.To4(), nil
}

func interfaceForAddress(ip net.IP) (net.Interface, error) {
	interfaces, err := net.Interfaces()
	if err != nil {
		return net.Interface{}, err
	}
	for _, iface := range interfaces {
		addresses, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, address := range addresses {
			var candidate net.IP
			switch value := address.(type) {
			case *net.IPNet:
				candidate = value.IP
			case *net.IPAddr:
				candidate = value.IP
			}
			if candidate.Equal(ip) {
				return iface, nil
			}
		}
	}
	return net.Interface{}, errors.New("listen address is not assigned to a local interface")
}

func main() {
	listen := flag.String("listen", "", "explicit private IPv4 LAN HTTPS address for Self")
	setup := flag.String("setup", "127.0.0.1:8081", "loopback HTTP address for setup")
	data := flag.String("data", "data/pairing", "directory for the persistent Source identity (mount its parent on first start)")
	flag.Parse()

	lanIP, err := privateLANAddress(*listen)
	if err != nil {
		log.Fatal(err)
	}
	setupHost, _, err := net.SplitHostPort(*setup)
	if err != nil || net.ParseIP(setupHost) == nil || !net.ParseIP(setupHost).IsLoopback() {
		log.Fatal("setup must bind to an explicit loopback IP address")
	}
	lanInterface, err := interfaceForAddress(lanIP)
	if err != nil {
		log.Fatal(err)
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
	hostname, err := os.Hostname()
	if err != nil {
		log.Fatal(err)
	}
	mdns, err := zeroconf.RegisterProxy("Source-"+identity.id, serviceType, "local.", port,
		hostname, []string{lanIP.String()}, []string{"id=" + identity.id}, []net.Interface{lanInterface})
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
