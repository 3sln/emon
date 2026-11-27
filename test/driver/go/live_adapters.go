//go:build adapters

package main

import (
	"emon"
	"emon/adapterlib"
)

// liveLoader is the cgo loader of adapter libraries (host/go/adapterlib), linked only under the `adapters` tag (`EMON_GO_LIVE=1 kin/scripts/emon-go-build`): a gate replays a transcript.
func liveLoader() emon.Loader { return adapterlib.Load }
