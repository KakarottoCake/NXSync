package edenconfig

import (
	"path/filepath"
	"strings"
	"testing"
)

func TestParseCustomNAND(t *testing.T) {
	env := Environment{
		GOOS:    "linux",
		HomeDir: "/home/link",
	}
	input := `
[Controls]
foo=bar

[Data Storage]
nand_directory=~/Eden Data/nand
`
	cfg, err := Parse(strings.NewReader(input), "/tmp/qt-config.ini", env)
	if err != nil {
		t.Fatal(err)
	}
	got, err := cfg.ResolveTitleSaveDirectory("0100f2c0115b6000")
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join("/home/link/Eden Data/nand", "user", "save",
		"0000000000000000", "0100F2C0115B6000")
	if got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}

func TestParseCustomSaveTemplate(t *testing.T) {
	env := Environment{GOOS: "linux", HomeDir: "/home/link"}
	input := "[Data Storage]\nsave_directory=/saves/{TITLE_ID}\n"
	cfg, err := Parse(strings.NewReader(input), "qt-config.ini", env)
	if err != nil {
		t.Fatal(err)
	}
	got, err := cfg.ResolveTitleSaveDirectory("0100000000000001")
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join("/saves", "0100000000000001")
	if got != want {
		t.Fatalf("got %q", got)
	}
}

func TestRejectsInvalidTitleID(t *testing.T) {
	cfg := Config{NANDDirectory: "/nand"}
	if _, err := cfg.ResolveTitleSaveDirectory("not-a-title"); err == nil {
		t.Fatal("expected invalid Title ID error")
	}
}

func TestPlatformConfigPaths(t *testing.T) {
	tests := []struct {
		env  Environment
		want string
	}{
		{Environment{GOOS: "windows", AppData: `C:\Users\Link\AppData\Roaming`},
			filepath.Join(`C:\Users\Link\AppData\Roaming`, "eden", "config", "qt-config.ini")},
		{Environment{GOOS: "darwin", HomeDir: "/Users/link"},
			filepath.Join("/Users/link", "Library", "Preferences", "eden", "qt-config.ini")},
		{Environment{GOOS: "linux", HomeDir: "/home/link"},
			filepath.Join("/home/link", ".config", "eden", "qt-config.ini")},
	}
	for _, test := range tests {
		got, err := ConfigPath(test.env)
		if err != nil {
			t.Fatal(err)
		}
		if got != test.want {
			t.Errorf("%s: got %q, want %q", test.env.GOOS, got, test.want)
		}
	}
}
