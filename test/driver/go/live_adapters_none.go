//go:build !adapters

package main

import "emon"

// liveLoader in a build WITHOUT the adapter loader: nil, and the registry says how to get one (`EMON_GO_LIVE=1 kin/scripts/emon-go-build`).
func liveLoader() emon.Loader { return nil }
