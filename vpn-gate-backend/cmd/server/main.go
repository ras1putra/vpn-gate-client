package main

import (
	"context"
	"encoding/json"
	"flag"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	httpSwagger "github.com/swaggo/http-swagger/v2"

	_ "vpn-gate-backend/docs"
	"vpn-gate-backend/internal/database"
	"vpn-gate-backend/internal/scraper"
	"vpn-gate-backend/internal/validator"
	"vpn-gate-backend/internal/vpncheck"
)

// @title           Zenith VPN Gate API
// @version         1.0
// @description     Backend API for Zenith VPN client — scrapes, validates, and serves VPN Gate server lists.
// @host
// @BasePath        /
// @produce         json
// @contact.name    Zenith VPN
// @license.name    MIT

var validServerTypes = map[string]bool{
	"RESIDENTIAL": true,
	"ACADEMIC":    true,
	"DATACENTER":  true,
}

func main() {
	port := flag.String("port", "8080", "port number to listen on")
	dbPath := flag.String("db", "vpn.db", "sqlite database file path")
	flag.Parse()

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	if err := database.InitDB(*dbPath); err != nil {
		slog.Error("fatal database error", "error", err)
		os.Exit(1)
	}

	// Initial scrape + validate on startup
	go func() {
		slog.Info("startup: performing initial scrape & sync")
		if err := scraper.ScrapeAndSync(); err != nil {
			slog.Error("startup: ScrapeAndSync failed", "error", err)
		}
		validator.ValidateAllServers()

		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		allServers, err := database.GetAllServers(ctx)
		if err == nil {
			vpncheck.CheckVpnDetection(ctx, allServers)
		}
		cancel()
		slog.Info("startup: initial scrape & sync completed")
	}()

	// Scrape scheduler (every 30 min)
	go func() {
		ticker := time.NewTicker(30 * time.Minute)
		defer ticker.Stop()
		for range ticker.C {
			jitter := time.Duration(time.Now().UnixNano()%30) * time.Second
			time.Sleep(jitter)

			slog.Info("scheduler: triggering scrape")
			if err := scraper.ScrapeAndSync(); err != nil {
				slog.Error("scheduler: ScrapeAndSync failed", "error", err)
			}
		}
	}()

	// Probe + stale cleanup (every 10 min)
	go func() {
		ticker := time.NewTicker(10 * time.Minute)
		defer ticker.Stop()
		for range ticker.C {
			slog.Info("scheduler: triggering validation and stale cleanup")
			validator.ValidateAllServers()

			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			removed, err := database.MarkStaleServersInactive(ctx, 4*time.Hour)
			cancel()
			if err != nil {
				slog.Error("scheduler: MarkStaleServersInactive failed", "error", err)
			} else if removed > 0 {
				slog.Info("scheduler: marked stale servers inactive", "count", removed)
			}
		}
	}()

	// VPN detection (every 10 min, non-blocking)
	go func() {
		ticker := time.NewTicker(10 * time.Minute)
		defer ticker.Stop()
		for range ticker.C {
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
			allServers, err := database.GetAllServers(ctx)
			if err == nil {
				vpncheck.CheckVpnDetection(ctx, allServers)
			}
			cancel()
		}
	}()

	mux := http.NewServeMux()
	mux.HandleFunc("/api/servers", securityMiddleware(handleGetActiveServers))
	mux.HandleFunc("/api/servers/all", securityMiddleware(handleGetAllServers))
	mux.HandleFunc("/api/servers/ip/", securityMiddleware(handleGetServerByIP))
	mux.HandleFunc("/api/health", securityMiddleware(handleHealth))
	mux.Handle("/docs/", httpSwagger.Handler(
		httpSwagger.URL("/docs/doc.json"),
		httpSwagger.DeepLinking(true),
		httpSwagger.DocExpansion("list"),
	))

	envPort := os.Getenv("PORT")
	if envPort != "" {
		*port = envPort
	}

	srv := &http.Server{
		Addr:              ":" + *port,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    1 << 20,
	}

	go func() {
		slog.Info("server starting", "port", *port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("server failed", "error", err)
			os.Exit(1)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	slog.Info("server shutting down gracefully")
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		slog.Error("server forced shutdown", "error", err)
	}
	slog.Info("server stopped")
}

func securityMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "strict-origin-when-cross-origin")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
		next(w, r)
	}
}

func writeJSON(w http.ResponseWriter, status int, data any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(data)
}

// handleGetActiveServers godoc
// @Summary      List active VPN servers
// @Description  Returns all currently active (online) VPN servers. Optionally filter by server classification type and/or country.
// @Tags         servers
// @Accept       json
// @Produce      json
// @Param        type     query    string  false  "Server classification filter"  Enums(RESIDENTIAL, ACADEMIC, DATACENTER)
// @Param        country  query    string  false  "Country short code filter (e.g. JP, US, DE)"  example("JP")
// @Success      200      {array}  database.VpnServer
// @Failure      400      {string} string  "Invalid server type"
// @Failure      405      {string} string  "Method Not Allowed"
// @Failure      500      {string} string  "Internal Server Error"
// @Router       /api/servers [get]
func handleGetActiveServers(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	typeParam := r.URL.Query().Get("type")
	if typeParam != "" && !validServerTypes[typeParam] {
		http.Error(w, "Invalid server type. Must be one of: RESIDENTIAL, ACADEMIC, DATACENTER", http.StatusBadRequest)
		return
	}

	countryParam := strings.ToUpper(strings.TrimSpace(r.URL.Query().Get("country")))

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	servers, err := database.GetActiveServers(ctx, typeParam, countryParam)
	if err != nil {
		slog.Error("GetActiveServers failed", "error", err)
		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusOK, stripConfig(servers))
}

// handleGetAllServers godoc
// @Summary      List all VPN servers
// @Description  Returns every known VPN server regardless of active status.
// @Tags         servers
// @Accept       json
// @Produce      json
// @Success      200  {array}   database.VpnServer
// @Failure      405  {string}  string  "Method Not Allowed"
// @Failure      500  {string}  string  "Internal Server Error"
// @Router       /api/servers/all [get]
func handleGetAllServers(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	servers, err := database.GetAllServers(ctx)
	if err != nil {
		slog.Error("GetAllServers failed", "error", err)
		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusOK, stripConfig(servers))
}

// handleGetServerByIP godoc
// @Summary      Get server by IP
// @Description  Returns full server details including OpenVPN config for connecting.
// @Tags         servers
// @Accept       json
// @Produce      json
// @Param        ip   path     string  true  "Server IP address"
// @Success      200  {object} database.VpnServer
// @Failure      404  {string} string  "Server not found"
// @Failure      500  {string} string  "Internal Server Error"
// @Router       /api/servers/ip/{ip} [get]
func handleGetServerByIP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	ip := strings.TrimPrefix(r.URL.Path, "/api/servers/ip/")
	if ip == "" {
		http.Error(w, "IP required", http.StatusBadRequest)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	server, err := database.GetServerByIP(ctx, ip)
	if err != nil {
		slog.Error("GetServerByIP failed", "error", err)
		http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		return
	}
	if server == nil {
		http.Error(w, "Server not found", http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, server)
}

func stripConfig(servers []database.VpnServer) []database.VpnServer {
	result := make([]database.VpnServer, len(servers))
	copy(result, servers)
	for i := range result {
		result[i].OpenVpnConfigBase64 = ""
	}
	return result
}

// handleHealth godoc
// @Summary      Health check
// @Description  Returns service health status including database connectivity.
// @Tags         system
// @Produce      json
// @Success      200  {object}  map[string]interface{}  "healthy"
// @Failure      503  {object}  map[string]interface{}  "degraded"
// @Router       /api/health [get]
func handleHealth(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()

	dbOk := true
	if err := database.DB.PingContext(ctx); err != nil {
		dbOk = false
		slog.Error("health: db ping failed", "error", err)
	}

	status := "healthy"
	httpStatus := http.StatusOK
	if !dbOk {
		status = "degraded"
		httpStatus = http.StatusServiceUnavailable
	}

	writeJSON(w, httpStatus, map[string]any{
		"status": status,
		"db":     dbOk,
		"time":   time.Now().Format(time.RFC3339),
	})
}
