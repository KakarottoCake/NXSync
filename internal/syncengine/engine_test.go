package syncengine

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

type fakeRemote struct {
	object      *RemoteObject
	createCount int
	updateCount int
}

func (f *fakeRemote) Find(context.Context, string) (*RemoteObject, error) {
	return f.object, nil
}
func (f *fakeRemote) Create(_ context.Context, name string, archive Archive) (*RemoteObject, error) {
	f.createCount++
	return &RemoteObject{Name: name, SHA256: archive.SHA256}, nil
}
func (f *fakeRemote) Update(_ context.Context, id, name string, archive Archive) (*RemoteObject, error) {
	f.updateCount++
	return &RemoteObject{ID: id, Name: name, SHA256: archive.SHA256}, nil
}
func (f *fakeRemote) Download(context.Context, string, string) error { return nil }

func TestPushCreatesMissingRemote(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "main"), []byte("save"), 0o600); err != nil {
		t.Fatal(err)
	}
	remote := &fakeRemote{}
	engine := Engine{Remote: remote, TempDir: t.TempDir()}
	result, err := engine.Push(context.Background(), "0100000000000001", root)
	if err != nil {
		t.Fatal(err)
	}
	if result.Kind != ResultCreated || remote.createCount != 1 {
		t.Fatalf("unexpected result: %#v", result)
	}
}

func TestPushSkipsNewerRemote(t *testing.T) {
	root := t.TempDir()
	path := filepath.Join(root, "main")
	if err := os.WriteFile(path, []byte("local"), 0o600); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-time.Hour)
	if err := os.Chtimes(path, old, old); err != nil {
		t.Fatal(err)
	}
	remote := &fakeRemote{object: &RemoteObject{
		ID: "remote", SHA256: "different", ModifiedTime: time.Now(),
	}}
	engine := Engine{Remote: remote, TempDir: t.TempDir()}
	result, err := engine.Push(context.Background(), "0100000000000001", root)
	if err != nil {
		t.Fatal(err)
	}
	if result.Kind != ResultSkipped || remote.updateCount != 0 {
		t.Fatalf("unexpected result: %#v", result)
	}
}
