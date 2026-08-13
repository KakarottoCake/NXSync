package syncengine

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/fsnotify/fsnotify"
)

// Watcher recursively watches registered title save directories and debounces
// bursts caused by games writing multiple files in one transaction.
type Watcher struct {
	FS       *fsnotify.Watcher
	Debounce time.Duration
	OnChange func(titleID, saveDirectory string)

	mu      sync.Mutex
	roots   map[string]string
	parents map[string]struct{}
	timers  map[string]*time.Timer
}

func NewWatcher(onChange func(titleID, saveDirectory string)) (*Watcher, error) {
	fs, err := fsnotify.NewWatcher()
	if err != nil {
		return nil, err
	}
	return &Watcher{
		FS:       fs,
		Debounce: 2 * time.Second,
		OnChange: onChange,
		roots:    make(map[string]string),
		parents:  make(map[string]struct{}),
		timers:   make(map[string]*time.Timer),
	}, nil
}

// WatchParent discovers title directories created after NXSync starts.
func (w *Watcher) WatchParent(directory string) error {
	absolute, err := filepath.Abs(directory)
	if err != nil {
		return err
	}
	w.mu.Lock()
	w.parents[absolute] = struct{}{}
	w.mu.Unlock()
	return w.FS.Add(absolute)
}

func (w *Watcher) Add(titleID, saveDirectory string) error {
	absolute, err := filepath.Abs(saveDirectory)
	if err != nil {
		return err
	}
	w.mu.Lock()
	w.roots[titleID] = absolute
	w.mu.Unlock()
	return filepath.WalkDir(absolute, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() {
			return w.FS.Add(path)
		}
		return nil
	})
}

func (w *Watcher) Run(ctx context.Context) error {
	defer w.Close()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case err, ok := <-w.FS.Errors:
			if !ok {
				return nil
			}
			return fmt.Errorf("save watcher: %w", err)
		case event, ok := <-w.FS.Events:
			if !ok {
				return nil
			}
			if event.Op&fsnotify.Create != 0 {
				if info, err := os.Stat(event.Name); err == nil && info.IsDir() {
					_ = w.FS.Add(event.Name)
					parent, _ := filepath.Abs(filepath.Dir(event.Name))
					w.mu.Lock()
					_, isTitleParent := w.parents[parent]
					w.mu.Unlock()
					name := filepath.Base(event.Name)
					if isTitleParent && isTitleID(name) {
						if err := w.Add(name, event.Name); err == nil {
							w.schedule(event.Name)
						}
					}
				}
			}
			if event.Op&(fsnotify.Create|fsnotify.Write|fsnotify.Remove|fsnotify.Rename) != 0 {
				w.schedule(event.Name)
			}
		}
	}
}

func isTitleID(value string) bool {
	if len(value) != 16 {
		return false
	}
	for _, character := range value {
		if !((character >= '0' && character <= '9') ||
			(character >= 'a' && character <= 'f') ||
			(character >= 'A' && character <= 'F')) {
			return false
		}
	}
	return true
}

func (w *Watcher) schedule(path string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	for titleID, root := range w.roots {
		relative, err := filepath.Rel(root, path)
		if err != nil || relative == ".." ||
			(len(relative) > 3 && relative[:3] == ".."+string(filepath.Separator)) {
			continue
		}
		if timer := w.timers[titleID]; timer != nil {
			timer.Stop()
		}
		id, directory := titleID, root
		w.timers[titleID] = time.AfterFunc(w.Debounce, func() {
			if w.OnChange != nil {
				w.OnChange(id, directory)
			}
		})
		break
	}
}

func (w *Watcher) Close() error {
	w.mu.Lock()
	for _, timer := range w.timers {
		timer.Stop()
	}
	w.mu.Unlock()
	return w.FS.Close()
}
