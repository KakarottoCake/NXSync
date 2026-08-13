package main

import (
	"embed"
	"io/fs"
	"log"
	"os"

	"github.com/nxsync/nxsync/desktop"
	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
)

// Set this for release builds:
// go build -ldflags "-X main.googleClientID=<installed-app-client-id>"
var googleClientID = "99491436094-o26b6pcetir1hdnkrm2fjgeuhnpojoqk.apps.googleusercontent.com"

//go:embed frontend/*
var assets embed.FS

func main() {
	clientID := googleClientID
	if clientID == "" {
		clientID = os.Getenv("NXSYNC_GOOGLE_CLIENT_ID")
	}
	app, err := desktop.New(clientID)
	if err != nil {
		log.Fatal(err)
	}
	webAssets, err := fs.Sub(assets, "frontend")
	if err != nil {
		log.Fatal(err)
	}
	if err := wails.Run(&options.App{
		Title:            "NXSync",
		Width:            420,
		Height:           300,
		MinWidth:         380,
		MinHeight:        260,
		DisableResize:    false,
		BackgroundColour: &options.RGBA{R: 13, G: 18, B: 26, A: 1},
		AssetServer:      &assetserver.Options{Assets: webAssets},
		OnStartup:        app.Startup,
		OnShutdown:       app.Shutdown,
		Bind:             []interface{}{app},
	}); err != nil {
		log.Print(err)
		os.Exit(1)
	}
}
