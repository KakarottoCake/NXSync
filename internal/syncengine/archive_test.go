package syncengine

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestBuildArchiveIsDeterministic(t *testing.T) {
	source := t.TempDir()
	if err := os.WriteFile(filepath.Join(source, "b"), []byte("two"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(source, "a"), []byte("one"), 0o600); err != nil {
		t.Fatal(err)
	}
	first, err := BuildArchive(source, filepath.Join(t.TempDir(), "first.zip"))
	if err != nil {
		t.Fatal(err)
	}
	second, err := BuildArchive(source, filepath.Join(t.TempDir(), "second.zip"))
	if err != nil {
		t.Fatal(err)
	}
	if first.SHA256 != second.SHA256 {
		t.Fatalf("same save produced different hashes: %s != %s", first.SHA256, second.SHA256)
	}
}

func TestExtractArchiveRejectsZipSlip(t *testing.T) {
	var data bytes.Buffer
	writer := zip.NewWriter(&data)
	entry, err := writer.Create("../outside")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := entry.Write([]byte("bad")); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	destination := t.TempDir()
	if err := ExtractArchive(bytes.NewReader(data.Bytes()), int64(data.Len()), destination); err == nil {
		t.Fatal("expected unsafe ZIP path to be rejected")
	}
}
