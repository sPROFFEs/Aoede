package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"html/template"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

type Config struct {
	ServerURL string `json:"server_url"`
}

var (
	version = "1.2.0"
)

func getConfigDir() string {
	configDir, err := os.UserConfigDir()
	if err != nil {
		home, _ := os.UserHomeDir()
		return filepath.Join(home, ".config", "navipod")
	}
	return filepath.Join(configDir, "navipod")
}

func getConfigFile() string {
	return filepath.Join(getConfigDir(), "desktop_config.json")
}

func loadConfig() (*Config, error) {
	cfgFile := getConfigFile()
	data, err := os.ReadFile(cfgFile)
	if err != nil {
		return &Config{}, err
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return &Config{}, err
	}
	return &cfg, nil
}

func saveConfig(cfg *Config) error {
	dir := getConfigDir()
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(getConfigFile(), data, 0644)
}

func openBrowser(url string) error {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		// Try app-mode launchers if available (Chrome, Chromium, Edge, Brave)
		browsers := []string{"google-chrome", "chromium", "chromium-browser", "microsoft-edge", "brave-browser", "xdg-open"}
		for _, b := range browsers {
			if path, err := exec.LookPath(b); err == nil {
				if b != "xdg-open" {
					cmd = exec.Command(path, fmt.Sprintf("--app=%s", url), "--name=Navipod", "--class=Navipod")
				} else {
					cmd = exec.Command(path, url)
				}
				break
			}
		}
		if cmd == nil {
			cmd = exec.Command("xdg-open", url)
		}
	}
	return cmd.Start()
}

const setupHTML = `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Navipod Desktop Setup</title>
    <style>
        :root {
            --primary: #1ed760;
            --background: #0f1011;
            --surface: #151718;
            --border: #3a3d40;
            --text-main: #f5f7f8;
            --text-sub: #a7adb2;
        }
        * { margin:0; padding:0; box-sizing:border-box; font-family:-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
        body { background: var(--background); color: var(--text-main); min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 20px; }
        .card { background: var(--surface); border: 1px solid var(--border); border-radius: 16px; padding: 36px; max-width: 440px; width: 100%; box-shadow: 0 16px 32px rgba(0,0,0,0.5); }
        .logo { font-size: 1.8rem; font-weight: 700; color: #fff; margin-bottom: 8px; display: flex; align-items: center; gap: 10px; }
        .logo span { color: var(--primary); }
        .sub { color: var(--text-sub); font-size: 0.9rem; margin-bottom: 24px; line-height: 1.4; }
        label { display: block; font-size: 0.8rem; text-transform: uppercase; color: var(--text-sub); font-weight: 600; margin-bottom: 8px; letter-spacing: 0.05em; }
        input { width: 100%; padding: 12px 14px; background: rgba(0,0,0,0.4); border: 1px solid var(--border); border-radius: 8px; color: #fff; font-size: 0.95rem; outline: none; margin-bottom: 20px; transition: border 0.2s; }
        input:focus { border-color: var(--primary); }
        button { width: 100%; padding: 13px; background: var(--primary); color: #000; font-weight: 700; border: none; border-radius: 999px; cursor: pointer; font-size: 0.95rem; transition: transform 0.1s, opacity 0.2s; }
        button:hover { opacity: 0.92; }
        button:active { transform: scale(0.98); }
    </style>
</head>
<body>
    <div class="card">
        <div class="logo">Navipod <span>Desktop</span></div>
        <div class="sub">Connect this desktop player to your private Navipod instance.</div>
        <form action="/save" method="POST">
            <label for="server">Navipod Server URL</label>
            <input type="url" id="server" name="server" placeholder="https://navipod.yourdomain.com" value="{{.ServerURL}}" required autofocus>
            <button type="submit">Connect to Server</button>
        </form>
    </div>
</body>
</html>`

func main() {
	resetFlag := flag.Bool("config", false, "Reconfigure Navipod server URL")
	flag.Parse()

	cfg, _ := loadConfig()

	// If no URL or --config flag passed, run local setup assistant
	if cfg.ServerURL == "" || *resetFlag {
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			fmt.Printf("Failed to start local setup listener: %v\n", err)
			os.Exit(1)
		}
		port := listener.Addr().(*net.TCPAddr).Port
		setupURL := fmt.Sprintf("http://127.0.0.1:%d", port)

		serverDone := make(chan bool)

		mux := http.NewServeMux()
		tmpl := template.Must(template.New("setup").Parse(setupHTML))

		mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
			tmpl.Execute(w, cfg)
		})

		mux.HandleFunc("/save", func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodPost {
				http.Redirect(w, r, "/", http.StatusSeeOther)
				return
			}
			serverInput := strings.TrimSpace(r.FormValue("server"))
			if !strings.HasPrefix(serverInput, "http://") && !strings.HasPrefix(serverInput, "https://") {
				serverInput = "https://" + serverInput
			}
			serverInput = strings.TrimRight(serverInput, "/")

			parsed, err := url.Parse(serverInput)
			if err != nil || parsed.Host == "" {
				http.Error(w, "Invalid server URL", http.StatusBadRequest)
				return
			}

			cfg.ServerURL = serverInput
			_ = saveConfig(cfg)

			http.Redirect(w, r, cfg.ServerURL, http.StatusSeeOther)
			go func() {
				time.Sleep(300 * time.Millisecond)
				serverDone <- true
			}()
		})

		server := &http.Server{Handler: mux}
		go server.Serve(listener)

		fmt.Printf("Navipod Desktop Setup running at: %s\n", setupURL)
		_ = openBrowser(setupURL)

		<-serverDone
		_ = listener.Close()
		return
	}

	fmt.Printf("Launching Navipod Desktop connected to: %s\n", cfg.ServerURL)
	if err := openBrowser(cfg.ServerURL); err != nil {
		fmt.Printf("Error opening browser: %v\n", err)
		os.Exit(1)
	}
}
