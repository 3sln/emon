//go:build !cgo || !(darwin || linux)

package adapterlib

import (
	"errors"

	"emon"
)

// Load in a build without cgo (or on a platform whose loader this host does not drive): it answers an error, so a program that names it need not be built twice.
func Load(path, config, packsDir string) (emon.Adapter, error) {
	return nil, errors.New("adapter-load-failed: this build cannot load adapter libraries (needs cgo on macOS or Linux)")
}
