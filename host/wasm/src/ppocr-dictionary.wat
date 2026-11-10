;; Framing only. Tesseract's unchanged C++ IsValidWord performs the lookup.
;; Both imported memories stay separate; no data segments or shared allocators.
(module
  (import "link" "emon" (memory $emon 1))
  (import "link" "tess" (memory $tess 1))
  (import "link" "api" (global $api i32))
  (import "link" "malloc" (func $malloc (param i32) (result i32)))
  (import "link" "free" (func $free (param i32)))
  (import "link" "valid" (func $valid (param i32 i32) (result i32)))
  (func (export "valid_word") (param $p i32) (param $n i32) (result i32)
    (local $copy i32) (local $answer i32)
    local.get $n
    i32.const 1
    i32.add
    call $malloc
    local.tee $copy
    i32.eqz
    if (result i32)
      i32.const 0
    else
      local.get $copy
      local.get $p
      local.get $n
      memory.copy $tess $emon
      local.get $copy
      local.get $n
      i32.add
      i32.const 0
      i32.store8 $tess
      global.get $api
      local.get $copy
      call $valid
      local.set $answer
      local.get $copy
      call $free
      local.get $answer
    end))
