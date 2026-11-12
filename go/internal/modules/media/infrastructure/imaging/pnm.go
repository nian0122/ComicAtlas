package imaging

import (
	"bufio"
	"errors"
	"os"
	"strconv"
	"strings"
)

const maxPNMHeaderBytes = 1 << 20

func validatePPM(path string, width, height int) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	reader := &pnmHeaderReader{reader: bufio.NewReaderSize(file, 4096)}
	magic, err := reader.token()
	if err != nil || magic != "P6" {
		return errors.New("invalid PPM header")
	}
	widthToken, err := reader.token()
	if err != nil {
		return errors.New("invalid PPM width")
	}
	heightToken, err := reader.token()
	if err != nil {
		return errors.New("invalid PPM height")
	}
	maxValue, err := reader.token()
	if err != nil || maxValue != "255" {
		return errors.New("invalid PPM component depth")
	}
	actualWidth, widthErr := strconv.Atoi(widthToken)
	actualHeight, heightErr := strconv.Atoi(heightToken)
	if widthErr != nil || heightErr != nil || actualWidth != width || actualHeight != height {
		return errors.New("unexpected PPM dimensions")
	}
	return nil
}

type pnmHeaderReader struct {
	reader *bufio.Reader
	read   int
}

func (header *pnmHeaderReader) byte() (byte, error) {
	if header.read >= maxPNMHeaderBytes {
		return 0, errors.New("PNM header exceeds limit")
	}
	value, err := header.reader.ReadByte()
	if err != nil {
		return 0, err
	}
	header.read++
	return value, nil
}

func (header *pnmHeaderReader) token() (string, error) {
	for {
		value, err := header.byte()
		if err != nil {
			return "", err
		}
		if isPNMWhitespace(value) {
			continue
		}
		if value == '#' {
			if err = header.comment(); err != nil {
				return "", err
			}
			continue
		}
		var token strings.Builder
		token.Grow(16)
		for {
			if token.Len() >= 32 {
				return "", errors.New("PNM header token exceeds limit")
			}
			if err = token.WriteByte(value); err != nil {
				return "", err
			}
			value, err = header.byte()
			if err != nil {
				return "", err
			}
			if isPNMWhitespace(value) {
				if value == '\r' {
					next, peekErr := header.reader.Peek(1)
					if peekErr == nil && len(next) == 1 && next[0] == '\n' {
						_, _ = header.byte()
					}
				}
				return token.String(), nil
			}
			if value == '#' {
				if err = header.comment(); err != nil {
					return "", err
				}
				return token.String(), nil
			}
		}
	}
}

func (header *pnmHeaderReader) comment() error {
	for {
		value, err := header.byte()
		if err != nil {
			return err
		}
		if value == '\n' || value == '\r' {
			return nil
		}
	}
}

func isPNMWhitespace(value byte) bool {
	return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\v' || value == '\f'
}
