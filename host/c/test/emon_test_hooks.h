/* The test hooks of the C binding (P11.4, D542). NOT part of the installed API: this header is not shipped, and the symbol exists only in a
 * library built with the crate's `__test_hooks` feature (`kin/scripts/emon-bind-build`; `bb package` builds without it). */
#ifndef EMON_TEST_HOOKS_H
#define EMON_TEST_HOOKS_H

#include "emon.h"

/* Panics on purpose, to show the boundary holds: answers -EMON_ERR_INTERNAL and the message. */
int emon_test_panic(emon_error *err);

#endif
