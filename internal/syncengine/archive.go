package syncengine

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// Archive describes a prepared save payload.
type Archive struct {
	Path         string
	SHA256       string
	ModifiedTime time.Time
	Size         int64
}

// BuildArchive creates a deterministic ZIP and hashes it while writing. Files
// are sorted, paths use '/', and the enclosing save directory is not included.
func BuildArchive(sourceDir, destination string) (archive Archive, err error) {
	info, err := os.Stat(sourceDir)
	if err != nil {
		return Archive{}, fmt.Errorf("stat save directory: %w", err)
	}
	if !info.IsDir() {
		return Archive{}, fmt.Errorf("save path %q is not a directory", sourceDir)
	}

	var files []string
	latest := info.ModTime()
	err = filepath.WalkDir(sourceDir, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.Type()&os.ModeSymlink != 0 {
			return nil // Save archives must never escape through a symlink.
		}
		if entry.IsDir() {
			return nil
		}
		fileInfo, infoErr := entry.Info()
		if infoErr != nil {
			return infoErr
		}
		if fileInfo.ModTime().After(latest) {
			latest = fileInfo.ModTime()
		}
		files = append(files, path)
		return nil
	})
	if err != nil {
		return Archive{}, fmt.Errorf("enumerate save directory: %w", err)
	}
	sort.Strings(files)

	if err := os.MkdirAll(filepath.Dir(destination), 0o700); err != nil {
		return Archive{}, fmt.Errorf("create archive directory: %w", err)
	}
	output, err := os.OpenFile(destination, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return Archive{}, fmt.Errorf("create archive: %w", err)
	}
	defer func() {
		if closeErr := output.Close(); err == nil && closeErr != nil {
			err = closeErr
		}
	}()

	hasher := sha256.New()
	zipWriter := zip.NewWriter(io.MultiWriter(output, hasher))
	for _, path := range files {
		if err := addFile(zipWriter, sourceDir, path); err != nil {
			_ = zipWriter.Close()
			return Archive{}, err
		}
	}
	if err := zipWriter.Close(); err != nil {
		return Archive{}, fmt.Errorf("finalize archive: %w", err)
	}
	if err := output.Sync(); err != nil {
		return Archive{}, fmt.Errorf("flush archive: %w", err)
	}
	stat, err := output.Stat()
	if err != nil {
		return Archive{}, fmt.Errorf("stat archive: %w", err)
	}
	return Archive{
		Path:         destination,
		SHA256:       hex.EncodeToString(hasher.Sum(nil)),
		ModifiedTime: latest.UTC(),
		Size:         stat.Size(),
	}, nil
}

func addFile(writer *zip.Writer, root, path string) error {
	info, err := os.Stat(path)
	if err != nil {
		return err
	}
	relative, err := filepath.Rel(root, path)
	if err != nil {
		return err
	}
	header, err := zip.FileInfoHeader(info)
	if err != nil {
		return err
	}
	header.Name = filepath.ToSlash(relative)
	header.Method = zip.Deflate
	header.SetMode(info.Mode() & 0o777)
	target, err := writer.CreateHeader(header)
	if err != nil {
		return err
	}
	source, err := os.Open(path)
	if err != nil {
		return err
	}
	defer source.Close()
	if _, err := io.Copy(target, source); err != nil {
		return fmt.Errorf("archive %q: %w", path, err)
	}
	return nil
}

// ExtractArchive replaces files beneath destination without allowing ZIP-slip.
// Callers should extract to a staging directory and perform an atomic swap when
// the platform permits it.
func ExtractArchive(reader io.ReaderAt, size int64, destination string) error {
	zr, err := zip.NewReader(reader, size)
	if err != nil {
		return fmt.Errorf("open ZIP: %w", err)
	}
	cleanRoot, err := filepath.Abs(destination)
	if err != nil {
		return err
	}
	for _, file := range zr.File {
		target := filepath.Join(cleanRoot, filepath.FromSlash(file.Name))
		relative, err := filepath.Rel(cleanRoot, target)
		if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
			return fmt.Errorf("unsafe path in ZIP: %q", file.Name)
		}
		if file.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0o700); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o700); err != nil {
			return err
		}
		source, err := file.Open()
		if err != nil {
			return err
		}
		mode := file.Mode() & 0o777
		if mode == 0 {
			mode = 0o600
		}
		output, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode)
		if err != nil {
			source.Close()
			return err
		}
		_, copyErr := io.Copy(output, source)
		closeErr := output.Close()
		sourceErr := source.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
		if sourceErr != nil {
			return sourceErr
		}
	}
	return nil
}
