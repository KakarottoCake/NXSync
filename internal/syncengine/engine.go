// Package syncengine packages Eden saves and synchronizes them with a remote.
package syncengine

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// RemoteObject is the cloud metadata used for conflict avoidance.
type RemoteObject struct {
	ID           string
	Name         string
	SHA256       string
	ModifiedTime time.Time
	Size         int64
}

// RemoteStore is implemented by the Google Drive client.
type RemoteStore interface {
	Find(ctx context.Context, name string) (*RemoteObject, error)
	Create(ctx context.Context, name string, archive Archive) (*RemoteObject, error)
	Update(ctx context.Context, objectID, name string, archive Archive) (*RemoteObject, error)
	Download(ctx context.Context, objectID, destination string) error
}

type ResultKind string

const (
	ResultCreated ResultKind = "created"
	ResultUpdated ResultKind = "updated"
	ResultSkipped ResultKind = "skipped"
	ResultPulled  ResultKind = "pulled"
)

type Result struct {
	Kind   ResultKind
	Object *RemoteObject
	Reason string
}

// Engine coordinates local archive creation and cloud conflict checks.
type Engine struct {
	Remote  RemoteStore
	TempDir string

	mu sync.Mutex // Serialize archives so a save cannot race itself.
}

// Push uploads only when content differs and the local save is newer.
func (e *Engine) Push(ctx context.Context, titleID, saveDirectory string) (Result, error) {
	if e.Remote == nil {
		return Result{}, errors.New("sync remote is not configured")
	}
	titleID = strings.ToUpper(titleID)
	if len(titleID) != 16 {
		return Result{}, fmt.Errorf("invalid Title ID %q", titleID)
	}
	e.mu.Lock()
	defer e.mu.Unlock()

	tempDir := e.TempDir
	if tempDir == "" {
		tempDir = os.TempDir()
	}
	temp, err := os.CreateTemp(tempDir, titleID+"-*.zip")
	if err != nil {
		return Result{}, fmt.Errorf("create temporary archive: %w", err)
	}
	archivePath := temp.Name()
	if err := temp.Close(); err != nil {
		return Result{}, err
	}
	defer os.Remove(archivePath)

	archive, err := BuildArchive(saveDirectory, archivePath)
	if err != nil {
		return Result{}, err
	}
	name := titleID + ".zip"
	remote, err := e.Remote.Find(ctx, name)
	if err != nil {
		return Result{}, fmt.Errorf("query remote save: %w", err)
	}
	if remote == nil {
		object, err := e.Remote.Create(ctx, name, archive)
		return Result{Kind: ResultCreated, Object: object}, err
	}
	if strings.EqualFold(remote.SHA256, archive.SHA256) {
		return Result{Kind: ResultSkipped, Object: remote, Reason: "content hash matches"}, nil
	}
	if !archive.ModifiedTime.After(remote.ModifiedTime) {
		return Result{
			Kind:   ResultSkipped,
			Object: remote,
			Reason: "remote save is newer or has the same timestamp",
		}, nil
	}
	object, err := e.Remote.Update(ctx, remote.ID, name, archive)
	return Result{Kind: ResultUpdated, Object: object}, err
}

// Pull downloads and safely extracts a remote save into a caller-provided
// staging directory. It does not overwrite a live emulator save directly.
func (e *Engine) Pull(ctx context.Context, titleID, stagingDirectory string) (Result, error) {
	if e.Remote == nil {
		return Result{}, errors.New("sync remote is not configured")
	}
	name := strings.ToUpper(titleID) + ".zip"
	remote, err := e.Remote.Find(ctx, name)
	if err != nil {
		return Result{}, err
	}
	if remote == nil {
		return Result{}, fmt.Errorf("no remote save named %q", name)
	}
	if err := os.MkdirAll(stagingDirectory, 0o700); err != nil {
		return Result{}, err
	}
	path := filepath.Join(stagingDirectory, name)
	if err := e.Remote.Download(ctx, remote.ID, path); err != nil {
		return Result{}, err
	}
	file, err := os.Open(path)
	if err != nil {
		return Result{}, err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return Result{}, err
	}
	if err := ExtractArchive(file, info.Size(), stagingDirectory); err != nil {
		return Result{}, err
	}
	_ = os.Remove(path)
	return Result{Kind: ResultPulled, Object: remote}, nil
}
