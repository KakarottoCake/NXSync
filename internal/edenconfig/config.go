// Package edenconfig locates and parses Eden's qt-config.ini.
package edenconfig

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
)

const dataStorageSection = "data storage"

var titleIDPattern = regexp.MustCompile(`^[0-9a-fA-F]{16}$`)

// Environment makes path discovery deterministic and testable.
type Environment struct {
	GOOS      string
	HomeDir   string
	AppData   string
	XDGConfig string
	XDGData   string
}

// Config is the subset of Eden configuration needed by NXSync.
type Config struct {
	ConfigPath          string
	NANDDirectory       string
	CustomSaveDirectory string
}

// CurrentEnvironment reads the host paths used by Eden.
func CurrentEnvironment() (Environment, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return Environment{}, fmt.Errorf("find home directory: %w", err)
	}
	return Environment{
		GOOS:      runtime.GOOS,
		HomeDir:   home,
		AppData:   os.Getenv("APPDATA"),
		XDGConfig: os.Getenv("XDG_CONFIG_HOME"),
		XDGData:   os.Getenv("XDG_DATA_HOME"),
	}, nil
}

// ConfigPath returns Eden's platform-specific qt-config.ini location.
func ConfigPath(env Environment) (string, error) {
	switch env.GOOS {
	case "windows":
		if env.AppData == "" {
			return "", errors.New("APPDATA is not set")
		}
		return filepath.Join(env.AppData, "eden", "config", "qt-config.ini"), nil
	case "darwin":
		return filepath.Join(env.HomeDir, "Library", "Preferences", "eden", "qt-config.ini"), nil
	case "linux":
		base := env.XDGConfig
		if base == "" {
			base = filepath.Join(env.HomeDir, ".config")
		}
		return filepath.Join(base, "eden", "qt-config.ini"), nil
	default:
		return "", fmt.Errorf("unsupported desktop platform %q", env.GOOS)
	}
}

// DefaultNANDDirectory returns Eden's standard per-platform NAND directory.
func DefaultNANDDirectory(env Environment) (string, error) {
	switch env.GOOS {
	case "windows":
		if env.AppData == "" {
			return "", errors.New("APPDATA is not set")
		}
		return filepath.Join(env.AppData, "eden", "nand"), nil
	case "darwin":
		return filepath.Join(env.HomeDir, "Library", "Application Support", "eden", "nand"), nil
	case "linux":
		base := env.XDGData
		if base == "" {
			base = filepath.Join(env.HomeDir, ".local", "share")
		}
		return filepath.Join(base, "eden", "nand"), nil
	default:
		return "", fmt.Errorf("unsupported desktop platform %q", env.GOOS)
	}
}

// Load discovers and parses the current user's Eden configuration.
func Load() (Config, error) {
	env, err := CurrentEnvironment()
	if err != nil {
		return Config{}, err
	}
	path, err := ConfigPath(env)
	if err != nil {
		return Config{}, err
	}
	f, err := os.Open(path)
	if err != nil {
		return Config{}, fmt.Errorf("open Eden config %q: %w", path, err)
	}
	defer f.Close()
	return Parse(f, path, env)
}

// Parse reads only [Data Storage], tolerating the INI syntax emitted by Qt.
func Parse(r io.Reader, configPath string, env Environment) (Config, error) {
	nand, err := DefaultNANDDirectory(env)
	if err != nil {
		return Config{}, err
	}
	cfg := Config{ConfigPath: configPath, NANDDirectory: nand}
	section := ""
	values := make(map[string]string)
	scanner := bufio.NewScanner(r)
	for scanner.Scan() {
		line := strings.TrimSpace(strings.TrimPrefix(scanner.Text(), "\uFEFF"))
		if line == "" || strings.HasPrefix(line, ";") || strings.HasPrefix(line, "#") {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.ToLower(strings.TrimSpace(line[1 : len(line)-1]))
			continue
		}
		if section != dataStorageSection {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		values[normalizeKey(key)] = cleanQtValue(value)
	}
	if err := scanner.Err(); err != nil {
		return Config{}, fmt.Errorf("read Eden config: %w", err)
	}

	if value := firstNonEmpty(values, "nanddirectory", "nanddir"); value != "" {
		cfg.NANDDirectory = expandPath(value, env)
	}
	if value := firstNonEmpty(values,
		"savedirectory", "savedatadirectory", "usersavedirectory",
	); value != "" {
		cfg.CustomSaveDirectory = expandPath(value, env)
	}
	return cfg, nil
}

// ResolveTitleSaveDirectory returns the save folder for a 16-digit Title ID.
func (c Config) ResolveTitleSaveDirectory(titleID string) (string, error) {
	if !titleIDPattern.MatchString(titleID) {
		return "", fmt.Errorf("invalid Title ID %q: expected 16 hexadecimal characters", titleID)
	}
	titleID = strings.ToUpper(titleID)
	if c.CustomSaveDirectory != "" {
		if strings.Contains(strings.ToLower(c.CustomSaveDirectory), "{title_id}") {
			return replaceFold(c.CustomSaveDirectory, "{title_id}", titleID), nil
		}
		return filepath.Join(c.CustomSaveDirectory, titleID), nil
	}
	return filepath.Join(
		c.NANDDirectory,
		"user", "save", "0000000000000000", titleID,
	), nil
}

func normalizeKey(key string) string {
	replacer := strings.NewReplacer(" ", "", "_", "", "-", "", "\\", "", "/", "")
	return strings.ToLower(replacer.Replace(strings.TrimSpace(key)))
}

func cleanQtValue(value string) string {
	value = strings.TrimSpace(value)
	if len(value) >= 2 && value[0] == '"' && value[len(value)-1] == '"' {
		value = value[1 : len(value)-1]
	}
	// QSettings escapes backslashes and may prefix string values with @String().
	if strings.HasPrefix(value, "@String(") && strings.HasSuffix(value, ")") {
		value = value[len("@String(") : len(value)-1]
	}
	return strings.ReplaceAll(value, `\\`, `\`)
}

func expandPath(value string, env Environment) string {
	value = os.Expand(value, func(key string) string {
		switch strings.ToUpper(key) {
		case "HOME", "USERPROFILE":
			return env.HomeDir
		case "APPDATA":
			return env.AppData
		default:
			return ""
		}
	})
	if value == "~" {
		return env.HomeDir
	}
	if strings.HasPrefix(value, "~/") || strings.HasPrefix(value, `~\`) {
		return filepath.Join(env.HomeDir, value[2:])
	}
	return filepath.Clean(value)
}

func firstNonEmpty(values map[string]string, keys ...string) string {
	for _, key := range keys {
		if value := strings.TrimSpace(values[key]); value != "" {
			return value
		}
	}
	return ""
}

func replaceFold(s, old, replacement string) string {
	index := strings.Index(strings.ToLower(s), strings.ToLower(old))
	if index < 0 {
		return s
	}
	return s[:index] + replacement + s[index+len(old):]
}
