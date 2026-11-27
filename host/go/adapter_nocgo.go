//go:build !cgo || !(darwin || linux)

package emon

// loadAdapter: a build without cgo (or on a platform whose loader this host does not drive) cannot load adapter libraries; `Engines.Add` still takes in-process adapters.
func loadAdapter(path, config, packsDir string) (Adapter, error) {
	return nil, errNoAdapterLoader
}
