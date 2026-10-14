//go:build emontest

package emon

// The test hooks of the Go host (P11.4, D1480). NOT part of the published module: `bb package` leaves this file out, and without the build
// tag `emontest` it is not compiled (kin/scripts/emon-go-build passes the tag for the gates' driver).

// SetNoRetarget sets the hidden test setting `noRetarget` (P7.4): leave every pixel unread by the page units, as the Engine was before the ink
// layers and the targeted passes.
func SetNoRetarget(o *Options, v bool) { o.noRetarget = v }
