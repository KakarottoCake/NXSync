// Package desktop is the Wails-facing application service.
package desktop

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/nxsync/nxsync/internal/drive"
	"github.com/nxsync/nxsync/internal/edenconfig"
	"github.com/nxsync/nxsync/internal/syncengine"
)

type State struct {
	EdenDetected bool   `json:"edenDetected"`
	EdenPath     string `json:"edenPath"`
	Status       string `json:"status"`
	Detail       string `json:"detail"`
	Connected    bool   `json:"connected"`
	Busy         bool   `json:"busy"`
}

type App struct {
	clientID  string
	tokenPath string

	mu      sync.RWMutex
	state   State
	ctx     context.Context
	cancel  context.CancelFunc
	config  edenconfig.Config
	engine  *syncengine.Engine
	watcher *syncengine.Watcher
}

func New(clientID string) (*App, error) {
	configDir, err := os.UserConfigDir()
	if err != nil {
		return nil, err
	}
	return &App{
		clientID:  clientID,
		tokenPath: filepath.Join(configDir, "nxsync", "token.json"),
		state:     State{Status: "Idle"},
	}, nil
}

func (a *App) Startup(ctx context.Context) {
	a.ctx, a.cancel = context.WithCancel(ctx)
	cfg, err := edenconfig.Load()
	if err != nil {
		a.setState(func(state *State) {
			state.EdenDetected = false
			state.Detail = err.Error()
		})
	} else {
		a.config = cfg
		a.setState(func(state *State) {
			state.EdenDetected = true
			state.EdenPath = cfg.NANDDirectory
			state.Detail = "Eden save path detected"
		})
	}
	if token, err := drive.LoadToken(a.tokenPath); err == nil {
		a.connect(token)
	}
}

func (a *App) Shutdown(context.Context) {
	if a.cancel != nil {
		a.cancel()
	}
	if a.watcher != nil {
		_ = a.watcher.Close()
	}
}

func (a *App) State() State {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return a.state
}

// Login is invoked by the UI's single Google Drive button.
func (a *App) Login() error {
	if a.clientID == "" {
		return errors.New("this build has no Google OAuth client ID")
	}
	a.setState(func(state *State) {
		state.Busy = true
		state.Detail = "Waiting for Google authorization"
	})
	defer a.setState(func(state *State) { state.Busy = false })

	token, err := drive.Login(a.ctx, drive.OAuthConfig{ClientID: a.clientID}, nil)
	if err != nil {
		a.setOffline(err)
		return err
	}
	if err := drive.SaveToken(a.tokenPath, token); err != nil {
		return err
	}
	a.connect(token)
	return nil
}

// SyncNow supports a future tray action and is callable from Wails.
func (a *App) SyncNow(titleID string) error {
	if a.engine == nil {
		return errors.New("Google Drive is not connected")
	}
	directory, err := a.config.ResolveTitleSaveDirectory(titleID)
	if err != nil {
		return err
	}
	a.sync(titleID, directory)
	return nil
}

func (a *App) connect(token drive.Token) {
	httpClient := drive.AuthenticatedClient(
		drive.OAuthConfig{ClientID: a.clientID}, token, a.tokenPath,
	)
	a.engine = &syncengine.Engine{Remote: &drive.Client{HTTP: httpClient}}
	a.setState(func(state *State) {
		state.Connected = true
		state.Status = "Idle"
		state.Detail = "Google Drive connected"
	})
	if a.state.EdenDetected {
		a.startWatcher()
	}
}

func (a *App) startWatcher() {
	watcher, err := syncengine.NewWatcher(func(titleID, directory string) {
		a.sync(titleID, directory)
	})
	if err != nil {
		a.setOffline(err)
		return
	}
	a.watcher = watcher
	root := a.config.CustomSaveDirectory
	if root == "" {
		root = filepath.Join(a.config.NANDDirectory, "user", "save", "0000000000000000")
	} else if strings.Contains(strings.ToLower(root), "{title_id}") {
		root = filepath.Dir(root)
	}
	if err := watcher.WatchParent(root); err != nil {
		a.setState(func(state *State) {
			state.Detail = "Waiting for Eden to create its save directory"
		})
		return
	}
	entries, err := os.ReadDir(root)
	if err != nil {
		a.setState(func(state *State) {
			state.Detail = "Waiting for Eden to create a save"
		})
		entries = nil
	}
	count := 0
	for _, entry := range entries {
		if !entry.IsDir() || len(entry.Name()) != 16 {
			continue
		}
		if err := watcher.Add(strings.ToUpper(entry.Name()), filepath.Join(root, entry.Name())); err == nil {
			count++
		}
	}
	a.setState(func(state *State) {
		state.Detail = fmt.Sprintf("Watching %d Eden save(s)", count)
	})
	go func() {
		err := watcher.Run(a.ctx)
		if err != nil && !errors.Is(err, context.Canceled) {
			a.setOffline(err)
		}
	}()
}

func (a *App) sync(titleID, directory string) {
	a.setState(func(state *State) {
		state.Status = "Syncing"
		state.Detail = "Syncing " + titleID
	})
	ctx, cancel := context.WithTimeout(a.ctx, 5*time.Minute)
	defer cancel()
	result, err := a.engine.Push(ctx, titleID, directory)
	if err != nil {
		a.setOffline(err)
		return
	}
	a.setState(func(state *State) {
		state.Status = "Idle"
		state.Detail = fmt.Sprintf("%s: %s", titleID, result.Kind)
	})
}

func (a *App) setOffline(err error) {
	a.setState(func(state *State) {
		state.Status = "Offline"
		state.Detail = err.Error()
	})
}

func (a *App) setState(update func(*State)) {
	a.mu.Lock()
	defer a.mu.Unlock()
	update(&a.state)
}
