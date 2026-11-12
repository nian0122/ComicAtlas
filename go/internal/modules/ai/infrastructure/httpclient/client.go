package httpclient

import (
	aiports "comicatlas.local/backend/internal/modules/ai/application/ports"
	aidomain "comicatlas.local/backend/internal/modules/ai/domain"
	"context"
	"errors"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Client struct {
	endpoint  string
	timeout   time.Duration
	http      *http.Client
	transport *http.Transport
	permit    chan struct{}
}

var _ aiports.Backend = (*Client)(nil)

func New(endpoint string, timeout time.Duration) (*Client, error) {
	value, err := url.Parse(endpoint)
	if err != nil || value.Hostname() == "" || value.Opaque != "" || value.User != nil || value.RawQuery != "" || value.ForceQuery || value.Fragment != "" || value.RawPath != "" || value.Scheme != "http" && value.Scheme != "https" || timeout < time.Millisecond || timeout > 30*time.Minute {
		return nil, aidomain.ErrValidation
	}
	value.Path = strings.TrimRight(value.Path, "/") + "/v1/analyses"
	transport := &http.Transport{Proxy: nil, DisableCompression: true, MaxConnsPerHost: 1, MaxIdleConnsPerHost: 1, IdleConnTimeout: 30 * time.Second, TLSHandshakeTimeout: 10 * time.Second, MaxResponseHeaderBytes: 16 << 10}
	client := &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	return &Client{endpoint: value.String(), timeout: timeout, http: client, transport: transport, permit: make(chan struct{}, 1)}, nil
}

func (client *Client) Close() { client.transport.CloseIdleConnections() }

func (client *Client) Analyze(parent context.Context, input aidomain.Request, source aiports.SampleSource) (aidomain.Candidate, error) {
	var empty aidomain.Candidate
	if aidomain.ValidateRequest(input) != nil || source == nil {
		return empty, aidomain.ErrValidation
	}
	ctx, cancel := context.WithTimeout(parent, client.timeout)
	defer cancel()
	if err := ctx.Err(); err != nil {
		return empty, err
	}
	select {
	case client.permit <- struct{}{}:
		defer func() { <-client.permit }()
	default:
		return empty, aidomain.ErrBusy
	}
	manifest, err := encodeManifest(input)
	if err != nil {
		return empty, err
	}
	reader, writer := io.Pipe()
	form := multipart.NewWriter(writer)
	request, err := http.NewRequestWithContext(ctx, "POST", client.endpoint, reader)
	if err != nil {
		_ = reader.Close()
		_ = writer.Close()
		return empty, aidomain.ErrValidation
	}
	request.Header.Set("Content-Type", form.FormDataContentType())
	request.Header.Set("Accept", "application/json")
	stop := context.AfterFunc(ctx, func() { _ = reader.CloseWithError(ctx.Err()); _ = writer.CloseWithError(ctx.Err()) })
	defer stop()
	defer reader.Close()
	done := make(chan error, 1)
	go func() {
		err := writeRequest(ctx, form, manifest, input, source)
		if err == nil {
			err = form.Close()
		}
		_ = writer.CloseWithError(err)
		done <- err
	}()
	response, callErr := client.http.Do(request)
	if callErr != nil || response.StatusCode != http.StatusOK {
		contextErr := ctx.Err()
		cancel()
		_ = reader.Close()
		writeErr := <-done
		if response != nil {
			defer response.Body.Close()
		}
		if err := parent.Err(); err != nil {
			return empty, err
		}
		if contextErr != nil {
			return empty, contextErr
		}
		if errors.Is(writeErr, aidomain.ErrSource) {
			return empty, aidomain.ErrSource
		}
		if callErr != nil {
			if errors.Is(callErr, context.DeadlineExceeded) {
				return empty, context.DeadlineExceeded
			}
			return empty, aidomain.ErrUnavailable
		}
		if response.StatusCode == http.StatusTooManyRequests {
			return empty, aidomain.ErrBusy
		}
		if response.StatusCode == http.StatusGatewayTimeout {
			return empty, context.DeadlineExceeded
		}
		if response.StatusCode >= 500 {
			return empty, aidomain.ErrUnavailable
		}
		return empty, aidomain.ErrProtocol
	}
	defer response.Body.Close()
	if err = <-done; err != nil {
		if ctx.Err() != nil {
			return empty, ctx.Err()
		}
		if errors.Is(err, aidomain.ErrSource) {
			return empty, err
		}
		return empty, aidomain.ErrUnavailable
	}
	contentType, _, err := mime.ParseMediaType(response.Header.Get("Content-Type"))
	encoding := response.Header.Get("Content-Encoding")
	if err != nil || contentType != "application/json" || encoding != "" && encoding != "identity" || response.ContentLength > aidomain.MaxResponseBytes {
		return empty, aidomain.ErrProtocol
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, aidomain.MaxResponseBytes+1))
	if err != nil {
		if ctx.Err() != nil {
			return empty, ctx.Err()
		}
		return empty, aidomain.ErrProtocol
	}
	if len(data) > aidomain.MaxResponseBytes {
		return empty, aidomain.ErrProtocol
	}
	return decodeResponse(data, input)
}
