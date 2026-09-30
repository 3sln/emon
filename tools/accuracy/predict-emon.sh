#!/bin/sh
# PLACEHOLDER for the real emon driver (PDF/image -> Model JSON).   Protocol:
#   predict-emon.sh ID VARIANT INPUT GOLD   -> Model JSON on stdout
# The pipeline does not exist yet (P1.10), so this says so and exits 3, which
# `bb accuracy` reports as "not yet available" without failing (D078). When
# P1.10 lands, replace the body with the driver invocation on "$3" and nothing
# else changes: the scoreboard entry for predictor "emon" is created by
# `bb accuracy --update`, and only then does the ratchet bind it.
echo "emon: the PDF -> Model pipeline is not yet available (P1.10)" >&2
exit 3
