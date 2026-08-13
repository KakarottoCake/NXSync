package drive

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/nxsync/nxsync/internal/syncengine"
)

const (
	defaultAPIBase    = "https://www.googleapis.com/drive/v3"
	defaultUploadBase = "https://www.googleapis.com/upload/drive/v3"
)

// Client stores NXSync archives in one Drive folder. The drive.file OAuth
// scope limits access to files created or explicitly opened by NXSync.
type Client struct {
	HTTP       *http.Client
	FolderID   string
	APIBase    string
	UploadBase string
}

func (c *Client) defaults() {
	if c.HTTP == nil {
		c.HTTP = http.DefaultClient
	}
	if c.APIBase == "" {
		c.APIBase = defaultAPIBase
	}
	if c.UploadBase == "" {
		c.UploadBase = defaultUploadBase
	}
}

func (c *Client) Find(ctx context.Context, name string) (*syncengine.RemoteObject, error) {
	c.defaults()
	query := fmt.Sprintf("name = '%s' and trashed = false", escapeDriveQuery(name))
	if c.FolderID != "" {
		query += fmt.Sprintf(" and '%s' in parents", escapeDriveQuery(c.FolderID))
	}
	values := url.Values{
		"q":        {query},
		"spaces":   {"drive"},
		"pageSize": {"10"},
		"fields":   {"files(id,name,size,modifiedTime,appProperties)"},
	}
	var response struct {
		Files []driveFile `json:"files"`
	}
	if err := c.jsonRequest(ctx, http.MethodGet, c.APIBase+"/files?"+values.Encode(), nil, &response); err != nil {
		return nil, err
	}
	if len(response.Files) == 0 {
		return nil, nil
	}
	best := response.Files[0]
	for _, candidate := range response.Files[1:] {
		if candidate.sourceModified().After(best.sourceModified()) {
			best = candidate
		}
	}
	object := best.remoteObject()
	return &object, nil
}

func (c *Client) Create(
	ctx context.Context, name string, archive syncengine.Archive,
) (*syncengine.RemoteObject, error) {
	metadata := c.metadata(name, archive)
	return c.upload(ctx, http.MethodPost, c.UploadBase+"/files?uploadType=multipart", metadata, archive)
}

func (c *Client) Update(
	ctx context.Context, objectID, name string, archive syncengine.Archive,
) (*syncengine.RemoteObject, error) {
	metadata := c.metadata(name, archive)
	endpoint := c.UploadBase + "/files/" + url.PathEscape(objectID) + "?uploadType=multipart"
	return c.upload(ctx, http.MethodPatch, endpoint, metadata, archive)
}

func (c *Client) Download(ctx context.Context, objectID, destination string) error {
	c.defaults()
	request, err := http.NewRequestWithContext(
		ctx, http.MethodGet, c.APIBase+"/files/"+url.PathEscape(objectID)+"?alt=media", nil,
	)
	if err != nil {
		return err
	}
	response, err := c.HTTP.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode/100 != 2 {
		return responseError(response)
	}
	if err := os.MkdirAll(filepath.Dir(destination), 0o700); err != nil {
		return err
	}
	temp := destination + ".tmp"
	output, err := os.OpenFile(temp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	_, copyErr := io.Copy(output, response.Body)
	closeErr := output.Close()
	if copyErr != nil {
		_ = os.Remove(temp)
		return copyErr
	}
	if closeErr != nil {
		_ = os.Remove(temp)
		return closeErr
	}
	return os.Rename(temp, destination)
}

func (c *Client) metadata(name string, archive syncengine.Archive) map[string]any {
	metadata := map[string]any{
		"name":         name,
		"modifiedTime": archive.ModifiedTime.Format(time.RFC3339Nano),
		"appProperties": map[string]string{
			"nxsync_sha256":               archive.SHA256,
			"nxsync_source_modified":      archive.ModifiedTime.Format(time.RFC3339Nano),
			"nxsync_source_modified_unix": strconv.FormatInt(archive.ModifiedTime.Unix(), 10),
			"nxsync_title_id":             strings.TrimSuffix(name, ".zip"),
		},
	}
	if c.FolderID != "" {
		metadata["parents"] = []string{c.FolderID}
	}
	return metadata
}

func (c *Client) upload(
	ctx context.Context,
	method, endpoint string,
	metadata map[string]any,
	archive syncengine.Archive,
) (*syncengine.RemoteObject, error) {
	c.defaults()
	file, err := os.Open(archive.Path)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	jsonHeader := make(textproto.MIMEHeader)
	jsonHeader.Set("Content-Type", "application/json; charset=UTF-8")
	jsonPart, err := writer.CreatePart(jsonHeader)
	if err != nil {
		return nil, err
	}
	if err := json.NewEncoder(jsonPart).Encode(metadata); err != nil {
		return nil, err
	}
	zipHeader := make(textproto.MIMEHeader)
	zipHeader.Set("Content-Type", "application/zip")
	zipPart, err := writer.CreatePart(zipHeader)
	if err != nil {
		return nil, err
	}
	if _, err := io.Copy(zipPart, file); err != nil {
		return nil, err
	}
	if err := writer.Close(); err != nil {
		return nil, err
	}

	request, err := http.NewRequestWithContext(ctx, method, endpoint, &body)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Content-Type", "multipart/related; boundary="+writer.Boundary())
	var response driveFile
	if err := c.doJSON(request, &response); err != nil {
		return nil, err
	}
	object := response.remoteObject()
	return &object, nil
}

func (c *Client) jsonRequest(
	ctx context.Context, method, endpoint string, body io.Reader, target any,
) error {
	request, err := http.NewRequestWithContext(ctx, method, endpoint, body)
	if err != nil {
		return err
	}
	return c.doJSON(request, target)
}

func (c *Client) doJSON(request *http.Request, target any) error {
	response, err := c.HTTP.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode/100 != 2 {
		return responseError(response)
	}
	if err := json.NewDecoder(response.Body).Decode(target); err != nil {
		return fmt.Errorf("decode Drive response: %w", err)
	}
	return nil
}

type driveFile struct {
	ID            string            `json:"id"`
	Name          string            `json:"name"`
	Size          string            `json:"size"`
	ModifiedTime  time.Time         `json:"modifiedTime"`
	AppProperties map[string]string `json:"appProperties"`
}

func (f driveFile) sourceModified() time.Time {
	if value := f.AppProperties["nxsync_source_modified"]; value != "" {
		if parsed, err := time.Parse(time.RFC3339Nano, value); err == nil {
			return parsed
		}
	}
	if value := f.AppProperties["nxsync_source_modified_unix"]; value != "" {
		if parsed, err := strconv.ParseInt(value, 10, 64); err == nil {
			return time.Unix(parsed, 0).UTC()
		}
	}
	return f.ModifiedTime
}

func (f driveFile) remoteObject() syncengine.RemoteObject {
	size, _ := strconv.ParseInt(f.Size, 10, 64)
	return syncengine.RemoteObject{
		ID:           f.ID,
		Name:         f.Name,
		SHA256:       f.AppProperties["nxsync_sha256"],
		ModifiedTime: f.sourceModified(),
		Size:         size,
	}
}

func responseError(response *http.Response) error {
	data, _ := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	return fmt.Errorf("Google Drive returned %s: %s", response.Status, strings.TrimSpace(string(data)))
}

func escapeDriveQuery(value string) string {
	return strings.ReplaceAll(strings.ReplaceAll(value, `\`, `\\`), `'`, `\'`)
}
