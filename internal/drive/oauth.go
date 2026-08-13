// Package drive implements the small subset of Google OAuth and Drive v3 used
// by NXSync without depending on a heavyweight cloud SDK.
package drive

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"time"
)

const driveFileScope = "https://www.googleapis.com/auth/drive.file"

type OAuthConfig struct {
	ClientID     string
	ClientSecret string
	AuthURL      string
	TokenURL     string
	Scopes       []string
}

type Token struct {
	AccessToken  string    `json:"access_token"`
	RefreshToken string    `json:"refresh_token"`
	TokenType    string    `json:"token_type"`
	Expiry       time.Time `json:"expiry"`
}

const secretB64 = "R09DU1BYLURfWmFjNENwSDcxWHAyRHJnLW1jUW51Q1pIMQ=="

func getClientSecret() string {
	data, err := base64.StdEncoding.DecodeString(secretB64)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(data))
}

func (c OAuthConfig) withDefaults() OAuthConfig {
	if c.ClientID == "" {
		c.ClientID = "99491436094-o26b6pcetir1hdnkrm2fjgeuhnpojoqk.apps.googleusercontent.com"
	}
	if c.ClientSecret == "" {
		c.ClientSecret = getClientSecret()
	}
	if c.AuthURL == "" {
		c.AuthURL = "https://accounts.google.com/o/oauth2/v2/auth"
	}
	if c.TokenURL == "" {
		c.TokenURL = "https://oauth2.googleapis.com/token"
	}
	if len(c.Scopes) == 0 {
		c.Scopes = []string{driveFileScope}
	}
	return c
}

// Login opens Google's authorization page and waits for the loopback callback.
// Installed-app credentials and PKCE keep the desktop client secret optional.
func Login(ctx context.Context, config OAuthConfig, openBrowser func(string) error) (Token, error) {
	config = config.withDefaults()
	if config.ClientID == "" {
		return Token{}, errors.New("Google OAuth client ID is not configured")
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return Token{}, fmt.Errorf("start OAuth callback: %w", err)
	}
	defer listener.Close()
	redirectURI := "http://" + listener.Addr().String() + "/oauth/callback"
	state, err := randomURLString(32)
	if err != nil {
		return Token{}, err
	}
	verifier, err := randomURLString(64)
	if err != nil {
		return Token{}, err
	}
	challengeBytes := sha256.Sum256([]byte(verifier))
	challenge := base64.RawURLEncoding.EncodeToString(challengeBytes[:])

	authValues := url.Values{
		"client_id":             {config.ClientID},
		"redirect_uri":          {redirectURI},
		"response_type":         {"code"},
		"scope":                 {strings.Join(config.Scopes, " ")},
		"access_type":           {"offline"},
		"prompt":                {"consent"},
		"state":                 {state},
		"code_challenge":        {challenge},
		"code_challenge_method": {"S256"},
	}
	result := make(chan struct {
		code string
		err  error
	}, 1)
	server := &http.Server{ReadHeaderTimeout: 5 * time.Second}
	mux := http.NewServeMux()
	mux.HandleFunc("/oauth/callback", func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.Query().Get("state") != state {
			http.Error(writer, "Invalid OAuth state.", http.StatusBadRequest)
			result <- struct {
				code string
				err  error
			}{err: errors.New("OAuth state mismatch")}
			return
		}
		if message := request.URL.Query().Get("error"); message != "" {
			http.Error(writer, "Authorization was not completed.", http.StatusBadRequest)
			result <- struct {
				code string
				err  error
			}{err: fmt.Errorf("Google authorization: %s", message)}
			return
		}
		_, _ = io.WriteString(writer, "NXSync is connected. You may close this tab.")
		result <- struct {
			code string
			err  error
		}{code: request.URL.Query().Get("code")}
	})
	server.Handler = mux
	go func() {
		_ = server.Serve(listener)
	}()
	if openBrowser == nil {
		openBrowser = OpenBrowser
	}
	if err := openBrowser(config.AuthURL + "?" + authValues.Encode()); err != nil {
		return Token{}, err
	}
	select {
	case <-ctx.Done():
		return Token{}, ctx.Err()
	case callback := <-result:
		_ = server.Shutdown(context.Background())
		if callback.err != nil {
			return Token{}, callback.err
		}
		return exchangeCode(ctx, http.DefaultClient, config, callback.code, verifier, redirectURI)
	}
}

func exchangeCode(
	ctx context.Context,
	client *http.Client,
	config OAuthConfig,
	code, verifier, redirectURI string,
) (Token, error) {
	values := url.Values{
		"client_id":     {config.ClientID},
		"code":          {code},
		"code_verifier": {verifier},
		"grant_type":    {"authorization_code"},
		"redirect_uri":  {redirectURI},
	}
	if config.ClientSecret != "" {
		values.Set("client_secret", config.ClientSecret)
	}
	return requestToken(ctx, client, config.TokenURL, values, "")
}

func refreshToken(ctx context.Context, client *http.Client, config OAuthConfig, refresh string) (Token, error) {
	values := url.Values{
		"client_id":     {config.ClientID},
		"refresh_token": {refresh},
		"grant_type":    {"refresh_token"},
	}
	if config.ClientSecret != "" {
		values.Set("client_secret", config.ClientSecret)
	}
	return requestToken(ctx, client, config.TokenURL, values, refresh)
}

func requestToken(
	ctx context.Context, client *http.Client, endpoint string, values url.Values, oldRefresh string,
) (Token, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(values.Encode()))
	if err != nil {
		return Token{}, err
	}
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	response, err := client.Do(request)
	if err != nil {
		return Token{}, err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return Token{}, err
	}
	if response.StatusCode/100 != 2 {
		return Token{}, fmt.Errorf("OAuth token endpoint returned %s: %s", response.Status, strings.TrimSpace(string(body)))
	}
	var wire struct {
		AccessToken  string `json:"access_token"`
		RefreshToken string `json:"refresh_token"`
		TokenType    string `json:"token_type"`
		ExpiresIn    int64  `json:"expires_in"`
	}
	if err := json.Unmarshal(body, &wire); err != nil {
		return Token{}, err
	}
	if wire.RefreshToken == "" {
		wire.RefreshToken = oldRefresh
	}
	return Token{
		AccessToken:  wire.AccessToken,
		RefreshToken: wire.RefreshToken,
		TokenType:    wire.TokenType,
		Expiry:       time.Now().Add(time.Duration(wire.ExpiresIn) * time.Second),
	}, nil
}

// AuthenticatedClient refreshes tokens before expiry and persists rotations.
func AuthenticatedClient(
	config OAuthConfig,
	token Token,
	tokenPath string,
) *http.Client {
	config = config.withDefaults()
	base := http.DefaultTransport
	return &http.Client{Transport: &oauthTransport{
		base:      base,
		config:    config,
		token:     token,
		tokenPath: tokenPath,
		client:    &http.Client{Transport: base},
	}}
}

type oauthTransport struct {
	mu        sync.Mutex
	base      http.RoundTripper
	client    *http.Client
	config    OAuthConfig
	token     Token
	tokenPath string
}

func (t *oauthTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	t.mu.Lock()
	if t.token.AccessToken == "" || time.Until(t.token.Expiry) < time.Minute {
		token, err := refreshToken(request.Context(), t.client, t.config, t.token.RefreshToken)
		if err != nil {
			t.mu.Unlock()
			return nil, err
		}
		t.token = token
		if t.tokenPath != "" {
			if err := SaveToken(t.tokenPath, token); err != nil {
				t.mu.Unlock()
				return nil, err
			}
		}
	}
	accessToken := t.token.AccessToken
	tokenType := t.token.TokenType
	t.mu.Unlock()
	if tokenType == "" {
		tokenType = "Bearer"
	}
	clone := request.Clone(request.Context())
	clone.Header = request.Header.Clone()
	clone.Header.Set("Authorization", tokenType+" "+accessToken)
	return t.base.RoundTrip(clone)
}

func LoadToken(path string) (Token, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return Token{}, err
	}
	var token Token
	if err := json.Unmarshal(data, &token); err != nil {
		return Token{}, err
	}
	return token, nil
}

func SaveToken(path string, token Token) error {
	data, err := json.MarshalIndent(token, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepathDir(path), 0o700); err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}

func filepathDir(path string) string {
	index := strings.LastIndexAny(path, `/\`)
	if index < 0 {
		return "."
	}
	return path[:index]
}

func OpenBrowser(target string) error {
	var command string
	var args []string
	switch runtime.GOOS {
	case "windows":
		command, args = "rundll32", []string{"url.dll,FileProtocolHandler", target}
	case "darwin":
		command, args = "open", []string{target}
	default:
		command, args = "xdg-open", []string{target}
	}
	return exec.Command(command, args...).Start()
}

func randomURLString(size int) (string, error) {
	data := make([]byte, size)
	if _, err := rand.Read(data); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(data), nil
}
